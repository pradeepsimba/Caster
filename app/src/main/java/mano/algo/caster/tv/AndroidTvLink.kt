package mano.algo.caster.tv

import android.annotation.SuppressLint
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS connection to an Android TV. The TV's certificate is self-signed, so it can't be checked the usual way. While
 * pairing it is accepted (the code on the TV screen is what proves it is the right TV: it is mixed from both certificates).
 * Afterwards only a TV with the same certificate as at pairing is accepted. The address must be a home-network one; the
 * caller checks that.
 */
internal object AndroidTvTls {
    private const val CONNECT_MS = 4_000
    private val PASSWORD = "caster".toCharArray()

    /** Connects to [host]:[port] with this phone's certificate. [pinned] is the fingerprint the TV must have, or null while pairing. */
    fun open(host: String, port: Int, identity: AndroidTvIdentity, pinned: String?, readTimeoutMs: Int): SSLSocket {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("caster", identity.privateKey, PASSWORD, arrayOf(identity.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf<TrustManager>(PinnedTrust(pinned)), SecureRandom()) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            socket.soTimeout = readTimeoutMs
            socket.startHandshake()
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    // The TV's certificate is self-signed; see the note above for why it is accepted, and when it is checked.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class PinnedTrust(private val pinned: String?) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (pinned != null && AndroidTvCommands.fingerprint(chain[0].encoded) != pinned) {
                throw CertificateException("This isn't the TV that was paired")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/**
 * Pairing with an Android TV. [begin] makes the TV show a code; [finish] sends the code typed on the phone. A paired TV
 * is remembered by its certificate's fingerprint ([serverFingerprint]).
 */
class AndroidTvPairing private constructor(
    val host: String,
    val identity: AndroidTvIdentity,
    private val socket: SSLSocket,
    private val server: X509Certificate,
) : Closeable {
    val serverFingerprint: String get() = AndroidTvCommands.fingerprint(server.encoded)

    /**
     * Sends the code. Throws [AndroidTvBadCodeException] for a code that can't be right (nothing was sent, so it can be
     * tried again), or an [IOException] when the TV ended the pairing.
     */
    fun finish(code: String) {
        val own = identity.certificate.publicKey as? RSAPublicKey
        val theirs = server.publicKey as? RSAPublicKey
        if (own == null || theirs == null) throw IOException("The TV uses a kind of key Caster doesn't know")
        val secret = AndroidTvCommands.secret(own, theirs, code) ?: throw AndroidTvBadCodeException()
        write(socket, AndroidTvCommands.secretMessage(secret))
        val reply = AndroidTvCommands.parsePairing(AndroidTvFraming.read(socket.inputStream))
        if (reply.status != AndroidTvCommands.STATUS_OK || reply.step != AndroidTvCommands.PairingStep.SecretAck) {
            throw AndroidTvRefusedException(reply.status)
        }
        close()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val READ_MS = 10_000

        /** Opens the pairing; when this returns, the TV is showing its code. */
        fun begin(host: String, identity: AndroidTvIdentity, clientName: String, port: Int = AndroidTvCommands.PAIR_PORT): AndroidTvPairing {
            val socket = AndroidTvTls.open(host, port, identity, null, READ_MS)
            try {
                val server = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw IOException("The TV sent no certificate")
                write(socket, AndroidTvCommands.pairingRequest(clientName))
                while (true) {
                    val reply = AndroidTvCommands.parsePairing(AndroidTvFraming.read(socket.inputStream))
                    if (reply.status != AndroidTvCommands.STATUS_OK) throw AndroidTvRefusedException(reply.status)
                    when (reply.step) {
                        AndroidTvCommands.PairingStep.RequestAck -> write(socket, AndroidTvCommands.options())
                        AndroidTvCommands.PairingStep.Options -> write(socket, AndroidTvCommands.configuration())
                        AndroidTvCommands.PairingStep.ConfigurationAck -> return AndroidTvPairing(host, identity, socket, server)
                        else -> throw IOException("The TV answered something unexpected")
                    }
                }
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }

        private fun write(socket: SSLSocket, message: ByteArray) {
            socket.outputStream.write(AndroidTvFraming.frame(message))
            socket.outputStream.flush()
        }
    }
}

/**
 * A paired Android TV, ready for keys. A thread reads what the TV sends: it answers the TV's start-up messages and its
 * pings (the TV drops a phone that doesn't), and tells [onClosed] when the connection ends.
 */
class AndroidTvLink private constructor(
    private val socket: SSLSocket,
    private val onClosed: (Throwable) -> Unit,
) : Closeable {
    private val started = CountDownLatch(1)
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var features = AndroidTvCommands.FEATURES

    /** Why the connection ended, if it did. */
    @Volatile var failure: Throwable? = null
        private set

    /** Waits until the TV says it is ready for keys. False when it didn't in time, or the connection ended first. */
    fun awaitReady(timeoutMs: Long): Boolean {
        started.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ready
    }

    fun key(code: Int, direction: Int = AndroidTvCommands.SHORT) = send(AndroidTvCommands.key(code, direction))

    fun openLink(url: String) = send(AndroidTvCommands.appLink(url))

    private fun send(message: ByteArray) {
        val out = socket.outputStream
        synchronized(out) { // the reader thread answers pings while a button is being sent
            out.write(AndroidTvFraming.frame(message))
            out.flush()
        }
    }

    private fun read() {
        try {
            val input = socket.inputStream
            while (true) {
                when (val event = AndroidTvCommands.parseRemote(AndroidTvFraming.read(input))) {
                    is AndroidTvCommands.RemoteEvent.Configure -> {
                        features = AndroidTvCommands.FEATURES and event.features
                        send(AndroidTvCommands.configureReply(event.features))
                    }
                    is AndroidTvCommands.RemoteEvent.SetActive -> send(AndroidTvCommands.setActiveReply(features))
                    is AndroidTvCommands.RemoteEvent.Start -> {
                        ready = true
                        started.countDown()
                    }
                    is AndroidTvCommands.RemoteEvent.Ping -> send(AndroidTvCommands.pingReply(event.value))
                    else -> Unit
                }
            }
        } catch (e: Throwable) {
            failure = e
            started.countDown()
            if (!closed) {
                closed = true
                runCatching { socket.close() }
                onClosed(e)
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        // The TV pings every few seconds; a phone that hears nothing for this long has lost it.
        private const val READ_MS = 30_000

        /** Connects with this phone's certificate; [pinned] is the fingerprint of the TV it was paired with. */
        fun open(
            host: String,
            identity: AndroidTvIdentity,
            pinned: String,
            onClosed: (Throwable) -> Unit,
            port: Int = AndroidTvCommands.REMOTE_PORT,
        ): AndroidTvLink {
            val socket = AndroidTvTls.open(host, port, identity, pinned, READ_MS)
            val link = AndroidTvLink(socket, onClosed)
            Thread(link::read, "caster-atv-reader").apply { isDaemon = true }.start()
            return link
        }
    }
}
