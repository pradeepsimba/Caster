package mano.algo.caster.tv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The messages of the Android TV / Google TV remote service (the one the Google TV app talks to). The TV listens on
 * port 6467 for pairing (it then shows a 6-character code) and on port 6466 for the remote itself. Both are TLS with
 * a client certificate, and the messages are protocol buffers, a few of which are built by hand here.
 */
object AndroidTvCommands {
    const val PAIR_PORT = 6467
    const val REMOTE_PORT = 6466
    const val STATUS_OK = 200

    /** What this phone offers: ping, keys, voice (the phone's microphone), power, volume and app links (no on-screen keyboard). */
    const val FEATURES = 1 or 2 or 8 or 32 or 64 or 512

    /** How a key is pressed: a normal press, or down and up separately (a long press). */
    const val SHORT = 3
    const val START_LONG = 1
    const val END_LONG = 2

    private const val PROTOCOL_VERSION = 2L
    private const val ENCODING_HEX = 3L
    private const val CODE_LENGTH = 6L
    private const val ROLE_INPUT = 1L

    // --- Pairing (the TV shows a code, the phone proves it saw it) -------------------------------------------

    private fun outer(payload: ByteArray, status: Long = STATUS_OK.toLong()) =
        Proto.concat(Proto.int(1, PROTOCOL_VERSION), Proto.int(2, status), payload)

    fun pairingRequest(clientName: String): ByteArray =
        outer(Proto.bytes(10, Proto.concat(Proto.string(1, "atvremote"), Proto.string(2, clientName))))

    private fun encoding() = Proto.concat(Proto.int(1, ENCODING_HEX), Proto.int(2, CODE_LENGTH))

    /** "I can type a 6-character hexadecimal code." */
    fun options(): ByteArray = outer(Proto.bytes(20, Proto.concat(Proto.bytes(1, encoding()), Proto.int(3, ROLE_INPUT))))

    fun configuration(): ByteArray = outer(Proto.bytes(30, Proto.concat(Proto.bytes(1, encoding()), Proto.int(2, ROLE_INPUT))))

    fun secretMessage(secret: ByteArray): ByteArray = outer(Proto.bytes(40, Proto.bytes(1, secret)))

    enum class PairingStep { RequestAck, Options, ConfigurationAck, SecretAck, Other }

    class PairingReply(val status: Int, val step: PairingStep)

    fun parsePairing(message: ByteArray): PairingReply {
        var status = 0
        var step = PairingStep.Other
        for (field in Proto.parse(message)) {
            when (field.number) {
                2 -> status = field.value.toInt()
                11 -> step = PairingStep.RequestAck
                20 -> step = PairingStep.Options
                31 -> step = PairingStep.ConfigurationAck
                41 -> step = PairingStep.SecretAck
            }
        }
        return PairingReply(status, step)
    }

    /** The code the TV shows: 6 hexadecimal characters. */
    fun isCode(code: String) = Regex("[0-9A-Fa-f]{6}").matches(code)

    /**
     * The proof that this phone saw the TV's code: a SHA-256 over both public keys and the last 4 characters of the code.
     * The first 2 characters of the code are the first byte of that hash, so a mistyped code is caught here, before
     * anything is sent. Returns null for a code that doesn't match.
     */
    fun secret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
        require(isCode(code))
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(unsigned(client.modulus))
        hash.update(unsigned(client.publicExponent))
        hash.update(unsigned(server.modulus))
        hash.update(unsigned(server.publicExponent))
        hash.update(hex(code.substring(2)))
        val digest = hash.digest()
        return if ((digest[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) digest else null
    }

    /** A number as the shortest big-endian bytes (BigInteger adds a sign byte in front when the top bit is set). */
    internal fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A certificate's fingerprint: what is remembered about a paired TV, to know it is the same one next time. */
    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

    // --- The remote itself -----------------------------------------------------------------------------------

    /** The answer to the TV's first message: which features are used (what both sides have) and who this is. */
    fun configureReply(tvFeatures: Int): ByteArray {
        val device = Proto.concat(Proto.int(3, 1), Proto.string(4, "1"), Proto.string(5, "atvremote"), Proto.string(6, "1.0.0"))
        return Proto.bytes(1, Proto.concat(Proto.int(1, (FEATURES and tvFeatures).toLong()), Proto.bytes(2, device)))
    }

    fun setActiveReply(features: Int): ByteArray = Proto.bytes(2, Proto.int(1, features.toLong()))

    fun pingReply(value: Long): ByteArray = Proto.bytes(9, Proto.int(1, value))

    fun key(code: Int, direction: Int = SHORT): ByteArray =
        Proto.bytes(10, Proto.concat(Proto.int(1, code.toLong()), Proto.int(2, direction.toLong())))

    /** Opens a link (a web address, or an app's own link) on the TV. */
    fun appLink(url: String): ByteArray = Proto.bytes(90, Proto.string(1, url))

    // --- Voice: the search key makes the TV open a session; the phone then streams its microphone into it ---------------

    /** The phone's answer to the TV opening a voice session. */
    fun voiceBegin(sessionId: Int): ByteArray = Proto.bytes(30, Proto.int(1, sessionId.toLong()))

    fun voicePayload(sessionId: Int, samples: ByteArray): ByteArray =
        Proto.bytes(31, Proto.concat(Proto.int(1, sessionId.toLong()), Proto.bytes(2, samples)))

    fun voiceEnd(sessionId: Int): ByteArray = Proto.bytes(32, Proto.int(1, sessionId.toLong()))

    private const val VOICE_MIN_BYTES = 8 * 1024
    private const val VOICE_MAX_BYTES = 20 * 1024

    /**
     * Sound (16-bit, mono, 8000 samples a second) cut into the pieces the TV takes: at least 8 KB (a shorter last piece is
     * filled up with silence) and at most 20 KB (the TV closes the connection on a bigger one).
     */
    fun voiceChunks(samples: ByteArray): List<ByteArray> {
        val pieces = ArrayList<ByteArray>()
        var start = 0
        while (start < samples.size) {
            val piece = samples.copyOfRange(start, minOf(samples.size, start + VOICE_MAX_BYTES))
            pieces += if (piece.size < VOICE_MIN_BYTES) piece.copyOf(VOICE_MIN_BYTES) else piece
            start += VOICE_MAX_BYTES
        }
        return pieces
    }

    sealed interface RemoteEvent {
        data class VoiceBegin(val sessionId: Int) : RemoteEvent
        data class Configure(val features: Int) : RemoteEvent
        data class SetActive(val features: Int) : RemoteEvent
        data class Start(val on: Boolean) : RemoteEvent
        data class Ping(val value: Long) : RemoteEvent
        data object Other : RemoteEvent
    }

    fun parseRemote(message: ByteArray): RemoteEvent {
        for (field in Proto.parse(message)) {
            val inner = if (field.wire == 2) Proto.parse(field.bytes) else emptyList()
            fun number(n: Int) = inner.firstOrNull { it.number == n }?.value ?: 0L
            when (field.number) {
                1 -> return RemoteEvent.Configure(number(1).toInt())
                2 -> return RemoteEvent.SetActive(number(1).toInt())
                8 -> return RemoteEvent.Ping(number(1))
                30 -> return RemoteEvent.VoiceBegin(number(1).toInt())
                40 -> return RemoteEvent.Start(number(1) != 0L)
            }
        }
        return RemoteEvent.Other
    }
}

/** Letters and keys typed on the phone, as the key codes of an ordinary keyboard. */
object AndroidTvKeys {
    /** The key that types [c], or null for a character a key press can't make (accents, emoji). Capitals come out small. */
    fun codeFor(c: Char): Int? {
        val l = c.lowercaseChar()
        return when (l) {
            in 'a'..'z' -> 29 + (l - 'a')
            in '0'..'9' -> 7 + (l - '0')
            ' ' -> 62
            '.' -> 56
            ',' -> 55
            '-' -> 69
            '=' -> 70
            '/' -> 76
            ';' -> 74
            '\'' -> 75
            '@' -> 77
            '+' -> 81
            '*' -> 17
            '#' -> 18
            else -> null
        }
    }

    /** The key presses that turn the text on the phone from [old] into [new] on the TV: delete the changed end, type the new one. */
    fun edit(old: String, new: String): List<Int> {
        var same = 0
        while (same < old.length && same < new.length && old[same] == new[same]) same++
        // Only letters that were really typed on the TV are deleted there: the others never arrived.
        val removed = old.substring(same).count { codeFor(it) != null }
        return List(removed) { TvKey.DELETE } + new.substring(same).mapNotNull(::codeFor)
    }
}

/** The proof the TV asked for was wrong (a typo in the code): nothing was sent, so the same pairing can be tried again. */
class AndroidTvBadCodeException : Exception("The code doesn't match")

/** The TV ended the pairing (wrong code, or it timed out). */
class AndroidTvRefusedException(status: Int) : IOException("The TV ended the pairing ($status)")

/** Reading and writing the length-prefixed messages. */
internal object AndroidTvFraming {
    private const val MAX_MESSAGE = 1 shl 20

    fun frame(message: ByteArray): ByteArray = Proto.varint(message.size.toLong()) + message

    /** The next message. Throws [EOFException] when the TV closed the connection. */
    fun read(input: InputStream): ByteArray {
        var length = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("The TV closed the connection")
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("Bad message length")
        }
        if (length > MAX_MESSAGE) throw IOException("Message too long")
        val data = ByteArray(length.toInt())
        var done = 0
        while (done < data.size) {
            val n = input.read(data, done, data.size - done)
            if (n < 0) throw EOFException("The TV closed the connection")
            done += n
        }
        return data
    }
}

/** Just enough protocol buffers for the messages above: numbers and byte strings. */
internal object Proto {
    class Field(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray)

    private val EMPTY = ByteArray(0)

    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)

    fun bytes(field: Int, data: ByteArray): ByteArray = tag(field, 2) + varint(data.size.toLong()) + data

    fun string(field: Int, text: String): ByteArray = bytes(field, text.toByteArray(Charsets.UTF_8))

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    fun parse(data: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var pos = 0
        fun varintAt(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size || shift > 63) throw IOException("Bad message")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < data.size) {
            val key = varintAt()
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (wire) {
                0 -> fields += Field(number, wire, varintAt(), EMPTY)
                1, 5 -> {
                    val size = if (wire == 1) 8 else 4
                    if (pos + size > data.size) throw IOException("Bad message")
                    pos += size
                }
                2 -> {
                    val size = varintAt()
                    if (size < 0 || pos + size > data.size) throw IOException("Bad message")
                    fields += Field(number, wire, 0, data.copyOfRange(pos, pos + size.toInt()))
                    pos += size.toInt()
                }
                else -> throw IOException("Unsupported message part")
            }
        }
        return fields
    }
}

/**
 * This phone's identity for the Android TV remote service: a self-made certificate. It is created once, kept private to the
 * app (left out of backups), and the TV remembers it when it is paired, like an ADB key.
 */
class AndroidTvIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val KEY_FILE = "atv_key"
        private const val CERT_FILE = "atv_cert"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private val lock = Any()

        /**
         * The saved identity from [dir], or a new one (saved there) when there is none, it can't be read, or the key and the
         * certificate don't belong together (a save that was interrupted). One at a time, so two quick connects can't each make one.
         */
        fun loadOrCreate(dir: File): AndroidTvIdentity = synchronized(lock) {
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                    val sameKey = (key as? RSAPrivateKey)?.modulus == (cert.publicKey as? RSAPublicKey)?.modulus
                    if (sameKey) return@synchronized AndroidTvIdentity(key, cert)
                }
            }
            val made = create()
            certFile.delete() // never leave an old certificate next to a new key
            keyFile.writeBytes(made.privateKey.encoded)
            certFile.writeBytes(made.certificate.encoded)
            made
        }

        /** A new RSA key with a self-signed certificate (valid for 10 years), written out by hand in DER. */
        fun create(): AndroidTvIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val signatureAlgorithm = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), der(0x05, ByteArray(0)))
            val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)), der(0x0C, "Caster".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = der(0x30, utcTime(now - DAY_MS), utcTime(now + 3650 * DAY_MS))
            val serial = der(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray())
            val tbs = der(
                0x30,
                der(0xA0, der(0x02, byteArrayOf(2))), // version 3
                serial,
                signatureAlgorithm,
                name,
                validity,
                name,
                pair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(tbs)
                sign()
            }
            val certificate = der(0x30, tbs, signatureAlgorithm, der(0x03, byteArrayOf(0) + signature))
            val parsed = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
            return AndroidTvIdentity(pair.private, parsed)
        }

        private fun utcTime(millis: Long): ByteArray {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return der(0x17, format.format(Date(millis)).toByteArray())
        }

        /** One DER element: its tag, its length, its content. */
        private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
            val content = Proto.concat(*parts)
            val length = content.size
            val header = when {
                length < 0x80 -> byteArrayOf(tag.toByte(), length.toByte())
                length < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
                else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), length.toByte())
            }
            return header + content
        }
    }
}
