package mano.algo.caster.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class AndroidTvTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // --- The messages ------------------------------------------------------------------------------------------

    @Test
    fun pairingMessagesLookLikeTheOnesTheGoogleTvAppSends() {
        assertEquals("080210c8015213" + "0a09" + "61747672656d6f7465" + "1206" + "436173746572", AndroidTvCommands.pairingRequest("Caster").hex())
        assertEquals("080210c801" + "a20108" + "0a0408031006" + "1801", AndroidTvCommands.options().hex())
        assertEquals("080210c801" + "f20108" + "0a0408031006" + "1001", AndroidTvCommands.configuration().hex())
    }

    @Test
    fun remoteMessagesLookLikeTheOnesTheGoogleTvAppSends() {
        assertEquals("520408131003", AndroidTvCommands.key(TvKey.UP).hex())
        assertEquals("4a020807", AndroidTvCommands.pingReply(7).hex())
        assertEquals(AndroidTvCommands.RemoteEvent.Configure(611), AndroidTvCommands.parseRemote(AndroidTvCommands.configureReply(611)))
        assertEquals(AndroidTvCommands.RemoteEvent.Configure(611 and 7), AndroidTvCommands.parseRemote(AndroidTvCommands.configureReply(7)))
    }

    @Test
    fun repliesFromTheTvAreRecognised() {
        fun reply(field: Int) = Proto.concat(Proto.int(1, 2), Proto.int(2, 200), Proto.bytes(field, ByteArray(0)))
        assertEquals(AndroidTvCommands.PairingStep.RequestAck, AndroidTvCommands.parsePairing(reply(11)).step)
        assertEquals(AndroidTvCommands.PairingStep.Options, AndroidTvCommands.parsePairing(reply(20)).step)
        assertEquals(AndroidTvCommands.PairingStep.ConfigurationAck, AndroidTvCommands.parsePairing(reply(31)).step)
        assertEquals(AndroidTvCommands.PairingStep.SecretAck, AndroidTvCommands.parsePairing(reply(41)).step)
        assertEquals(200, AndroidTvCommands.parsePairing(reply(11)).status)

        assertEquals(AndroidTvCommands.RemoteEvent.Ping(7), AndroidTvCommands.parseRemote(Proto.bytes(8, Proto.int(1, 7))))
        assertEquals(AndroidTvCommands.RemoteEvent.Start(true), AndroidTvCommands.parseRemote(Proto.bytes(40, Proto.int(1, 1))))
        assertEquals(AndroidTvCommands.RemoteEvent.Start(false), AndroidTvCommands.parseRemote(Proto.bytes(40, ByteArray(0))))
        assertEquals(AndroidTvCommands.RemoteEvent.Other, AndroidTvCommands.parseRemote(Proto.bytes(50, Proto.int(1, 1))))
    }

    @Test
    fun brokenMessagesAreRejectedNotCrashed() {
        try {
            Proto.parse(byteArrayOf(0x0A, 0x7F)) // says 127 bytes follow, none do
            fail("expected an error")
        } catch (e: java.io.IOException) {
            // fine
        }
    }

    @Test
    fun codesAreSixHexCharacters() {
        assertTrue(AndroidTvCommands.isCode("3A7F2c"))
        assertFalse(AndroidTvCommands.isCode("3A7F2"))
        assertFalse(AndroidTvCommands.isCode("3A7F2G"))
        assertFalse(AndroidTvCommands.isCode("1234"))
    }

    @Test
    fun aNumberLosesItsSignByte() {
        assertEquals("010001", AndroidTvCommands.unsigned(BigInteger.valueOf(65537)).hex())
        assertEquals("ff", AndroidTvCommands.unsigned(BigInteger.valueOf(255)).hex())
        assertEquals("00", AndroidTvCommands.unsigned(BigInteger.ZERO).hex())
    }

    // --- Typing ------------------------------------------------------------------------------------------------

    @Test
    fun lettersAndDigitsHaveKeys() {
        assertEquals(29, AndroidTvKeys.codeFor('a'))
        assertEquals(29, AndroidTvKeys.codeFor('A'))
        assertEquals(54, AndroidTvKeys.codeFor('z'))
        assertEquals(7, AndroidTvKeys.codeFor('0'))
        assertEquals(16, AndroidTvKeys.codeFor('9'))
        assertEquals(62, AndroidTvKeys.codeFor(' '))
        assertNull(AndroidTvKeys.codeFor('é'))
        assertNull(AndroidTvKeys.codeFor('€'))
    }

    @Test
    fun editingSendsOnlyWhatChanged() {
        assertEquals(listOf(30), AndroidTvKeys.edit("", "b"))
        assertEquals(listOf(31), AndroidTvKeys.edit("b", "bc"))
        assertEquals(listOf(TvKey.DELETE), AndroidTvKeys.edit("bc", "b"))
        assertEquals(listOf(TvKey.DELETE, 32), AndroidTvKeys.edit("bc", "bd"))
        // A letter the TV never received is not deleted from the TV.
        assertEquals(emptyList<Int>(), AndroidTvKeys.edit("é", ""))
    }

    // --- The certificate ---------------------------------------------------------------------------------------

    @Test
    fun theCertificateIsValidAndSelfSigned() {
        val identity = AndroidTvIdentity.create()
        identity.certificate.checkValidity()
        identity.certificate.verify(identity.certificate.publicKey) // signed by its own key
        assertEquals("CN=Caster", identity.certificate.subjectX500Principal.name)
        assertEquals(2048, (identity.certificate.publicKey as RSAPublicKey).modulus.bitLength())
    }

    @Test
    fun theIdentityIsKeptAndComesBackTheSame() {
        val dir = File.createTempFile("caster", "").apply { delete(); mkdirs() }
        try {
            val first = AndroidTvIdentity.loadOrCreate(dir)
            val second = AndroidTvIdentity.loadOrCreate(dir)
            assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
            // A damaged file is replaced by a new identity instead of failing for ever.
            File(dir, "atv_cert").writeBytes(byteArrayOf(1, 2, 3))
            val third = AndroidTvIdentity.loadOrCreate(dir)
            third.certificate.verify(third.certificate.publicKey)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aKeyAndACertificateThatDontMatchAreReplaced() {
        val dir = File.createTempFile("caster", "").apply { delete(); mkdirs() }
        try {
            val first = AndroidTvIdentity.loadOrCreate(dir)
            // As after an interrupted save: the key of one identity next to the certificate of another.
            File(dir, "atv_cert").writeBytes(AndroidTvIdentity.create().certificate.encoded)
            val repaired = AndroidTvIdentity.loadOrCreate(dir)
            assertEquals(
                (repaired.privateKey as java.security.interfaces.RSAPrivateKey).modulus,
                (repaired.certificate.publicKey as RSAPublicKey).modulus,
            )
            assertFalse(first.certificate.encoded.contentEquals(repaired.certificate.encoded))
            // And what was saved now works the next time.
            assertArrayEquals(repaired.certificate.encoded, AndroidTvIdentity.loadOrCreate(dir).certificate.encoded)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Voice ----------------------------------------------------------------------------------------------------

    @Test
    fun voiceMessagesLookLikeTheOnesTheTvKnows() {
        assertEquals("f201020805", AndroidTvCommands.voiceBegin(5).hex())
        assertEquals("8202020805", AndroidTvCommands.voiceEnd(5).hex())
        assertEquals("fa0106080512020102", AndroidTvCommands.voicePayload(5, byteArrayOf(1, 2)).hex())
        assertEquals(AndroidTvCommands.RemoteEvent.VoiceBegin(9), AndroidTvCommands.parseRemote(Proto.bytes(30, Proto.int(1, 9))))
        assertTrue(AndroidTvCommands.FEATURES and 8 != 0) // the phone says it can do voice
        assertEquals(84, TvKey.SEARCH)
    }

    @Test
    fun soundIsCutIntoThePiecesTheTvTakes() {
        assertEquals(emptyList<Int>(), AndroidTvCommands.voiceChunks(ByteArray(0)).map { it.size })
        assertEquals(listOf(8192), AndroidTvCommands.voiceChunks(ByteArray(100)).map { it.size }) // padded with silence
        assertEquals(listOf(8192), AndroidTvCommands.voiceChunks(ByteArray(8192)).map { it.size })
        assertEquals(listOf(20480), AndroidTvCommands.voiceChunks(ByteArray(20480)).map { it.size })
        assertEquals(listOf(20480, 8192), AndroidTvCommands.voiceChunks(ByteArray(20580)).map { it.size }) // never bigger than 20 KB
        val padded = AndroidTvCommands.voiceChunks(ByteArray(3) { 7 }).single()
        assertEquals(listOf<Byte>(7, 7, 7, 0), padded.take(4))
    }

    @Test
    fun aVoiceSessionOpensStreamsAndEnds() {
        val phone = AndroidTvIdentity.create()
        val tv = AndroidTvIdentity.create()
        val tvPrint = AndroidTvCommands.fingerprint(tv.certificate.encoded)
        val seen = ArrayList<String>()
        val fake = FakeTv(tv) { socket ->
            socket.write(Proto.bytes(1, Proto.int(1, 619)))
            socket.read() // the phone's configure answer
            socket.write(Proto.bytes(2, Proto.int(1, 619)))
            socket.read()
            socket.write(Proto.bytes(40, Proto.int(1, 1))) // started
            seen += socket.read().hex() // the search key
            socket.write(Proto.bytes(30, Proto.int(1, 9))) // the TV opens voice session 9
            seen += socket.read().hex() // the phone's answer
            seen += socket.read().hex() // one piece of sound
            seen += socket.read().hex() // the end
        }
        val link = AndroidTvLink.open("127.0.0.1", phone, tvPrint, {}, fake.port)
        try {
            assertTrue(link.awaitReady(10_000))
            assertEquals(9, link.startVoice(5_000))
            link.sendVoice(9, ByteArray(100) { 1 })
            link.endVoice(9)
            fake.finish()
        } finally {
            link.close()
        }
        assertEquals("520408541003", seen[0])
        assertEquals(AndroidTvCommands.voiceBegin(9).hex(), seen[1])
        assertEquals(AndroidTvCommands.voicePayload(9, ByteArray(100) { 1 }.copyOf(8192)).hex(), seen[2])
        assertEquals(AndroidTvCommands.voiceEnd(9).hex(), seen[3])
    }

    @Test
    fun aTvWithoutVoiceGivesNoSession() {
        val phone = AndroidTvIdentity.create()
        val tv = AndroidTvIdentity.create()
        val fake = FakeTv(tv) { socket ->
            socket.write(Proto.bytes(1, Proto.int(1, 619)))
            socket.read()
            socket.write(Proto.bytes(2, Proto.int(1, 619)))
            socket.read()
            socket.write(Proto.bytes(40, Proto.int(1, 1)))
            socket.read() // the search key, and no voice session follows
            Thread.sleep(1_000)
        }
        val link = AndroidTvLink.open("127.0.0.1", phone, AndroidTvCommands.fingerprint(tv.certificate.encoded), {}, fake.port)
        try {
            assertTrue(link.awaitReady(10_000))
            assertNull(link.startVoice(300))
        } finally {
            link.close()
        }
    }

    // --- The secret ---------------------------------------------------------------------------------------------

    @Test
    fun theSecretNeedsTheRightCode() {
        val mine = AndroidTvIdentity.create().certificate.publicKey as RSAPublicKey
        val theirs = AndroidTvIdentity.create().certificate.publicKey as RSAPublicKey
        val right = rightCode(mine, theirs)
        val secret = AndroidTvCommands.secret(mine, theirs, right)
        assertNotNull(secret)
        assertEquals(32, secret!!.size)
        // Same last four characters, wrong first byte: caught on the phone.
        val wrong = "%02X".format((secret[0].toInt() and 0xFF) xor 1) + right.substring(2)
        assertNull(AndroidTvCommands.secret(mine, theirs, wrong))
        // Lower case is the same code.
        assertNotNull(AndroidTvCommands.secret(mine, theirs, right.lowercase()))
    }

    private fun rightCode(mine: RSAPublicKey, theirs: RSAPublicKey): String {
        for (first in 0..255) {
            val code = "%02X".format(first) + "ABCD"
            if (AndroidTvCommands.secret(mine, theirs, code) != null) return code
        }
        throw AssertionError("no code found")
    }

    // --- A pretend TV on this computer: the real TLS and the real messages ---------------------------------------

    private class FakeTv(val identity: AndroidTvIdentity, private val script: (SSLSocket) -> Unit) {
        val server: SSLServerSocket
        @Volatile var error: Throwable? = null
        val done = CountDownLatch(1)

        init {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("tv", identity.privateKey, "x".toCharArray(), arrayOf(identity.certificate))
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "x".toCharArray()) }
            val acceptAnyPhone = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf<TrustManager>(acceptAnyPhone), SecureRandom()) }
            server = (context.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true; soTimeout = 15_000 }
            thread(isDaemon = true) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 15_000
                        script(socket)
                    }
                } catch (e: Throwable) {
                    error = e
                } finally {
                    done.countDown()
                }
            }
        }

        val port get() = server.localPort

        fun finish() {
            assertTrue("the pretend TV took too long", done.await(15, TimeUnit.SECONDS))
            server.close()
            error?.let { throw AssertionError("the pretend TV failed: $it", it) }
        }
    }

    private fun SSLSocket.read() = AndroidTvFraming.read(inputStream)
    private fun SSLSocket.write(message: ByteArray) {
        outputStream.write(AndroidTvFraming.frame(message))
        outputStream.flush()
    }

    private fun tvReply(field: Int) = Proto.concat(Proto.int(1, 2), Proto.int(2, 200), Proto.bytes(field, ByteArray(0)))

    @Test
    fun pairingWorksStartToFinish() {
        val phone = AndroidTvIdentity.create()
        val tv = AndroidTvIdentity.create()
        val phoneKey = phone.certificate.publicKey as RSAPublicKey
        val tvKey = tv.certificate.publicKey as RSAPublicKey
        val code = rightCode(phoneKey, tvKey)
        val seen = ArrayList<String>()

        val fake = FakeTv(tv) { socket ->
            seen += socket.read().hex()
            socket.write(tvReply(11))
            seen += socket.read().hex()
            socket.write(tvReply(20))
            seen += socket.read().hex()
            socket.write(tvReply(31)) // the TV now shows its code
            val secretMessage = socket.read()
            // The phone must prove it saw the code: the secret is built from both public keys.
            val clientKey = (socket.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
            val expected = AndroidTvCommands.secret(clientKey, tvKey, code)!!
            assertArrayEquals(AndroidTvCommands.secretMessage(expected), secretMessage)
            socket.write(tvReply(41))
        }

        val pairing = AndroidTvPairing.begin("127.0.0.1", phone, "Caster", fake.port)
        assertEquals(AndroidTvCommands.fingerprint(tv.certificate.encoded), pairing.serverFingerprint)
        try {
            val wrong = "%02X".format(code.substring(0, 2).toInt(16) xor 1) + code.substring(2)
            pairing.finish(wrong)
            fail("a wrong code must not go through")
        } catch (e: AndroidTvBadCodeException) {
            // caught on the phone; the pairing is still usable
        }
        pairing.finish(code)
        fake.finish()

        assertEquals(AndroidTvCommands.pairingRequest("Caster").hex(), seen[0])
        assertEquals(AndroidTvCommands.options().hex(), seen[1])
        assertEquals(AndroidTvCommands.configuration().hex(), seen[2])
    }

    @Test
    fun aTvThatRefusesTheSecretEndsThePairing() {
        val phone = AndroidTvIdentity.create()
        val tv = AndroidTvIdentity.create()
        val code = rightCode(phone.certificate.publicKey as RSAPublicKey, tv.certificate.publicKey as RSAPublicKey)
        val fake = FakeTv(tv) { socket ->
            socket.read(); socket.write(tvReply(11))
            socket.read(); socket.write(tvReply(20))
            socket.read(); socket.write(tvReply(31))
            socket.read()
            socket.write(Proto.concat(Proto.int(1, 2), Proto.int(2, 402))) // bad secret
        }
        val pairing = AndroidTvPairing.begin("127.0.0.1", phone, "Caster", fake.port)
        try {
            pairing.finish(code)
            fail("expected the TV's refusal")
        } catch (e: AndroidTvRefusedException) {
            // fine
        }
        pairing.close()
        fake.finish()
    }

    @Test
    fun theRemoteAnswersTheTvAndSendsKeys() {
        val phone = AndroidTvIdentity.create()
        val tv = AndroidTvIdentity.create()
        val tvPrint = AndroidTvCommands.fingerprint(tv.certificate.encoded)
        val seen = ArrayList<String>()

        val fake = FakeTv(tv) { socket ->
            // What the TV sends first, like the real one.
            socket.write(Proto.bytes(1, Proto.int(1, 611)))
            seen += socket.read().hex() // the phone's configure answer
            socket.write(Proto.bytes(2, Proto.int(1, 611)))
            seen += socket.read().hex() // the phone's set-active answer
            socket.write(Proto.bytes(8, Proto.int(1, 7))) // ping
            seen += socket.read().hex()
            socket.write(Proto.bytes(40, Proto.int(1, 1))) // started: from now on the phone may send keys
            seen += socket.read().hex() // the key
            seen += socket.read().hex() // the held key, down
            seen += socket.read().hex() // and up
        }

        val closed = CountDownLatch(1)
        val link = AndroidTvLink.open("127.0.0.1", phone, tvPrint, { closed.countDown() }, fake.port)
        try {
            assertTrue("the TV never said it was ready", link.awaitReady(10_000))
            link.key(TvKey.UP)
            link.key(TvKey.HOME, AndroidTvCommands.START_LONG)
            link.key(TvKey.HOME, AndroidTvCommands.END_LONG)
            fake.finish()
        } finally {
            link.close()
        }

        assertEquals(AndroidTvCommands.configureReply(611).hex(), seen[0])
        assertEquals(AndroidTvCommands.setActiveReply(611 and AndroidTvCommands.FEATURES).hex(), seen[1])
        assertEquals("4a020807", seen[2])
        assertEquals("520408131003", seen[3])
        assertEquals("520408031001", seen[4])
        assertEquals("520408031002", seen[5])
    }

    @Test
    fun aDifferentTvThanTheOnePairedIsNotTrusted() {
        val phone = AndroidTvIdentity.create()
        val impostor = AndroidTvIdentity.create()
        val real = AndroidTvIdentity.create()
        val fake = FakeTv(impostor) { socket -> runCatching { socket.read() } }
        try {
            AndroidTvLink.open("127.0.0.1", phone, AndroidTvCommands.fingerprint(real.certificate.encoded), {}, fake.port).close()
            fail("a TV with another certificate must be refused")
        } catch (e: java.io.IOException) {
            // refused, as it should be
        }
        fake.server.close()
    }
}
