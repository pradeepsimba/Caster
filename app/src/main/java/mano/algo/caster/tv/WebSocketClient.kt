package mano.algo.caster.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/** The TV was reached, but turned this phone down (it wasn't allowed, or the answer to "Allow?" was No or too late). */
class TvRefusedException(message: String) : IOException(message)

/** The TV asked its owner to allow this phone and got no answer in time. (Unlike a TV that is slow, trying another port won't help.) */
class OwnerWaitTimeout : SocketTimeoutException("The TV wasn't answered in time")

/** Base64 without the platform classes (they need a newer Android, or aren't there in a plain unit test). */
internal object Base64Lite {
    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            out.append(TABLE[b0 shr 2])
            out.append(TABLE[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.size) TABLE[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) TABLE[b2 and 63] else '=')
            i += 3
        }
        return out.toString()
    }

    fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))
}

/**
 * A small WebSocket client (RFC 6455), enough for the Samsung and LG TV remotes: text messages, and the pings that keep
 * a connection alive. Nothing else of the protocol is needed.
 */
internal class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    /** The fingerprint of the TV's certificate on a secure connection (what to remember about a paired TV), else null. */
    val serverFingerprint: String?,
) : Closeable {
    private val output = socket.getOutputStream()
    private val writeLock = Any()

    /** Set once the TV has answered a ping. A TV that never does isn't given up on for being quiet. */
    @Volatile private var answersPings = false

    /** Sends one text message. */
    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    private fun sendPing() = sendFrame(OP_PING, ByteArray(0))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or opcode) // a whole message in one frame
        val size = payload.size
        when {
            size < 126 -> frame.write(0x80 or size)
            size < 65_536 -> {
                frame.write(0x80 or 126)
                frame.write(size shr 8)
                frame.write(size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write(((size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes) // a client must mask what it sends
        frame.write(mask)
        for (i in payload.indices) frame.write(payload[i].toInt() xor mask[i % 4].toInt())
        synchronized(writeLock) {
            output.write(frame.toByteArray())
            output.flush()
        }
    }

    /** The next text message. Waits at most [timeoutMs] for the TV to send anything; throws [SocketTimeoutException] after that. */
    fun readText(timeoutMs: Int): String = nextText(timeoutMs, keepAlive = false)

    /**
     * The next text message, for a connection that sits idle between button presses: after [pingMs] of silence it pings the TV.
     * A TV that has shown it answers pings, and then stays silent for two such periods, is treated as gone. (One that never
     * answers a ping is kept: it can't be told apart from a quiet one, and a lost connection shows on the next button.)
     */
    fun readTextKeepingAlive(pingMs: Int): String = nextText(pingMs, keepAlive = true)

    private fun nextText(timeoutMs: Int, keepAlive: Boolean): String {
        socket.soTimeout = timeoutMs
        var quiet = 0
        val message = ByteArrayOutputStream()
        while (true) {
            val first = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                if (!keepAlive) throw e
                if (++quiet >= 2) {
                    if (answersPings) throw e
                    quiet = 1
                }
                sendPing()
                continue
            }
            if (first < 0) throw EOFException("The TV closed the connection")
            quiet = 0
            val fin = first and 0x80 != 0
            val opcode = first and 0x0F
            val second = readByte()
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = (readByte().toLong() shl 8) or readByte().toLong()
            else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length < 0 || length > MAX_MESSAGE) throw IOException("Message too long")
            val mask = if (second and 0x80 != 0) ByteArray(4) { readByte().toByte() } else null
            val payload = ByteArray(length.toInt())
            var done = 0
            while (done < payload.size) {
                val n = input.read(payload, done, payload.size - done)
                if (n < 0) throw EOFException("The TV closed the connection")
                done += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (opcode) {
                OP_CLOSE -> throw EOFException("The TV closed the connection")
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> answersPings = true
                OP_TEXT, OP_CONTINUATION -> {
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("Message too long")
                    if (fin) return message.toString(Charsets.UTF_8.name())
                }
                else -> Unit // binary messages (not used by these TVs)
            }
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("The TV closed the connection")
        return b
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 1 shl 20
        private val random = SecureRandom()

        /**
         * Opens a WebSocket to [host]:[port] at [path]. A [secure] connection accepts the TV's self-signed certificate, or
         * only the one with the [pinned] fingerprint. [onSocket] gets the connection as soon as it exists, so a Cancel can
         * close it while the TV is still being waited for. The caller makes sure [host] is a home-network address.
         */
        fun connect(
            host: String,
            port: Int,
            path: String,
            secure: Boolean,
            pinned: String?,
            onSocket: (Closeable) -> Unit = {},
            connectMs: Int = 4_000,
            handshakeMs: Int = 10_000,
        ): WebSocketClient {
            val socket: Socket = if (secure) {
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(PinnedTrust(pinned)), SecureRandom()) }
                context.socketFactory.createSocket()
            } else {
                Socket()
            }
            try {
                onSocket(socket)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), connectMs)
                socket.soTimeout = handshakeMs
                if (socket is SSLSocket) socket.startHandshake()
                val fingerprint = (socket as? SSLSocket)?.session?.peerCertificates?.firstOrNull()
                    ?.let { AndroidTvCommands.fingerprint(it.encoded) }

                val key = Base64Lite.encode(ByteArray(16).also(random::nextBytes))
                val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nOrigin: ${if (secure) "https" else "http"}://$host:$port\r\n\r\n"
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = BufferedInputStream(socket.getInputStream())
                val headers = readHeaders(input)
                val lines = headers.split("\r\n")
                if (!lines[0].contains(" 101")) throw IOException("The TV didn't accept the connection (${lines[0].trim()})")
                val accept = lines.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
                val expected = Base64Lite.encode(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)))
                if (accept != expected) throw IOException("The TV's answer wasn't a WebSocket handshake")
                return WebSocketClient(socket, input, fingerprint)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** The answer's header lines, up to the empty line that ends them. */
        private fun readHeaders(input: InputStream): String {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw EOFException("The TV closed the connection")
                text.append(b.toChar())
                if (text.length > 16_384) throw IOException("The TV's answer was too long")
            }
            return text.toString()
        }
    }
}
