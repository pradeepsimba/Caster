package mano.algo.caster.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class WebSocketTvsTest {
    // Set free when a test is over: a pretend TV that has to stay connected waits for it, instead of sleeping for a guessed time.
    private val release = CountDownLatch(1)

    @After
    fun releasePretendTvs() {
        release.countDown()
    }

    // --- A pretend TV: the server side of a WebSocket -----------------------------------------------------------

    private class Peer(private val socket: Socket) : Closeable {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        var path = ""

        init {
            socket.soTimeout = 15_000
        }

        fun handshake() {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) text.append(input.read().also { check(it >= 0) }.toChar())
            val lines = text.split("\r\n")
            path = lines[0].split(" ")[1]
            val key = lines.first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val accept = Base64Lite.encode(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            output.flush()
        }

        /** The next frame from the phone: its opcode and (unmasked) payload. */
        fun frame(): Pair<Int, ByteArray> {
            val first = input.read()
            check(first >= 0) { "the phone closed the connection" }
            val second = input.read()
            check(second and 0x80 != 0) { "the phone must mask what it sends" }
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
            else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            val mask = ByteArray(4) { input.read().toByte() }
            val payload = ByteArray(length.toInt())
            var done = 0
            while (done < payload.size) done += input.read(payload, done, payload.size - done)
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return (first and 0x0F) to payload
        }

        /** The next text message, skipping pongs. */
        fun read(): String {
            while (true) {
                val (opcode, payload) = frame()
                if (opcode == 1) return payload.toString(Charsets.UTF_8)
            }
        }

        fun send(text: String) = write(1, text.toByteArray())

        fun ping() = write(9, ByteArray(0))

        fun pong() = write(10, ByteArray(0))

        private fun write(opcode: Int, payload: ByteArray) {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size < 65_536 -> {
                    out.write(126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(127)
                    for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            out.write(payload)
            output.write(out.toByteArray())
            output.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class FakeTv(private val script: (Peer) -> Unit) : Closeable {
        val server = ServerSocket(0)
        val port get() = server.localPort
        @Volatile var error: Throwable? = null
        private val done = CountDownLatch(1)

        init {
            server.soTimeout = 15_000
            thread(isDaemon = true) {
                try {
                    Peer(server.accept()).use { peer ->
                        peer.handshake()
                        script(peer)
                    }
                } catch (e: Throwable) {
                    error = e
                } finally {
                    done.countDown()
                }
            }
        }

        fun finish() {
            assertTrue("the pretend TV took too long", done.await(15, TimeUnit.SECONDS))
            error?.let { throw AssertionError("the pretend TV failed: $it", it) }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun plain(port: Int) = listOf(Endpoint(port, false))

    // --- The WebSocket client --------------------------------------------------------------------------------------

    @Test
    fun base64MatchesTheStandard() {
        assertEquals("", Base64Lite.encode(""))
        assertEquals("YQ==", Base64Lite.encode("a"))
        assertEquals("YWI=", Base64Lite.encode("ab"))
        assertEquals("YWJj", Base64Lite.encode("abc"))
        assertEquals("Q2FzdGVy", Base64Lite.encode("Caster"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(ByteArray(300) { it.toByte() }), Base64Lite.encode(ByteArray(300) { it.toByte() }))
    }

    @Test
    fun messagesGoBothWaysIncludingLongOnes() {
        val long = "x".repeat(70_000)
        val fake = FakeTv { peer ->
            peer.send("hello")
            assertEquals("hi", peer.read())
            peer.send(long)
            assertEquals(long, peer.read())
        }
        val ws = WebSocketClient.connect("127.0.0.1", fake.port, "/x", secure = false, pinned = null)
        try {
            assertEquals("hello", ws.readText(5_000))
            ws.sendText("hi")
            assertEquals(long, ws.readText(5_000))
            ws.sendText(long)
            fake.finish()
        } finally {
            ws.close()
            fake.close()
        }
    }

    @Test
    fun aPingFromTheTvIsAnswered() {
        var sawPong = false
        val fake = FakeTv { peer ->
            peer.ping()
            peer.send("after the ping")
            val (opcode, _) = peer.frame()
            sawPong = opcode == 0xA
        }
        val ws = WebSocketClient.connect("127.0.0.1", fake.port, "/", secure = false, pinned = null)
        try {
            assertEquals("after the ping", ws.readText(5_000))
            fake.finish()
            assertTrue(sawPong)
        } finally {
            ws.close()
            fake.close()
        }
    }

    @Test
    fun aTvThatAnswersPingsButThenGoesQuietIsGivenUp() {
        var pinged = false
        val fake = FakeTv { peer ->
            val (opcode, _) = peer.frame()
            pinged = opcode == 9
            peer.pong() // it answers the first ping ...
            peer.frame() // ... and the next one
            release.await(20, TimeUnit.SECONDS) // ... and then nothing, until the test is over
        }
        val ws = WebSocketClient.connect("127.0.0.1", fake.port, "/", secure = false, pinned = null)
        try {
            try {
                ws.readTextKeepingAlive(150)
                fail("a silent TV must be given up on")
            } catch (e: SocketTimeoutException) {
                // after two quiet periods
            }
            assertTrue(pinged)
        } finally {
            ws.close()
            fake.close()
        }
    }

    @Test
    fun aTvThatNeverAnswersPingsIsNotGivenUpOn() {
        val fake = FakeTv { peer ->
            peer.frame() // pings come, no pong goes back
            peer.frame()
            peer.frame()
            peer.send("still here")
        }
        val ws = WebSocketClient.connect("127.0.0.1", fake.port, "/", secure = false, pinned = null)
        try {
            assertEquals("still here", ws.readTextKeepingAlive(100)) // well past two quiet periods
        } finally {
            ws.close()
            fake.close()
        }
    }

    @Test
    fun aServerThatIsNotAWebSocketIsRejected() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(1024))
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                }
            }
        }
        try {
            WebSocketClient.connect("127.0.0.1", server.localPort, "/", secure = false, pinned = null)
            fail("expected a refusal")
        } catch (e: IOException) {
            // fine
        } finally {
            server.close()
        }
    }

    @Test
    fun aSecureConnectionRemembersAndChecksTheTvsCertificate() {
        val tv = AndroidTvIdentity.create()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("tv", tv.privateKey, "x".toCharArray(), arrayOf(tv.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "x".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, SecureRandom()) }
        val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.soTimeout = 15_000
        thread(isDaemon = true) {
            repeat(2) {
                runCatching { Peer(server.accept()).use { it.handshake(); release.await(5, TimeUnit.SECONDS) } }
            }
        }
        try {
            val print = AndroidTvCommands.fingerprint(tv.certificate.encoded)
            // First contact: any certificate is accepted, and its fingerprint is handed back to be remembered.
            WebSocketClient.connect("127.0.0.1", server.localPort, "/", secure = true, pinned = null).use {
                assertEquals(print, it.serverFingerprint)
            }
            // Later: only that certificate.
            try {
                WebSocketClient.connect("127.0.0.1", server.localPort, "/", secure = true, pinned = "00".repeat(32))
                fail("another certificate must be refused")
            } catch (e: IOException) {
                assertTrue(isPinMismatch(e))
            }
        } finally {
            server.close()
        }
    }

    // --- Samsung ------------------------------------------------------------------------------------------------------

    @Test
    fun samsungMessagesLookLikeTheOnesTheTvKnows() {
        assertEquals("/api/v2/channels/samsung.remote.control?name=Q2FzdGVy", SamsungTvCommands.path("Caster", null))
        assertEquals("/api/v2/channels/samsung.remote.control?name=Q2FzdGVy&token=12345678", SamsungTvCommands.path("Caster", "12345678"))
        assertTrue(SamsungTvCommands.path("a?", null).contains("name=YT8%3D")) // '=' in the name is escaped
        assertEquals(
            """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_UP","Option":"false","TypeOfRemote":"SendRemoteKey"}}""",
            SamsungTvCommands.key("KEY_UP"),
        )
        assertTrue(SamsungTvCommands.key("KEY_HOME", "Press").contains(""""Cmd":"Press""""))
        assertTrue(SamsungTvCommands.text("hi").contains(""""Cmd":"aGk=","DataOfCmd":"base64","TypeOfRemote":"SendInputString""""))
        assertTrue(SamsungTvCommands.openBrowser("http://192.168.0.5:8080/").contains(""""appId":"org.tizen.browser""""))
        assertEquals("ms.channel.connect", SamsungTvCommands.event("""{"event":"ms.channel.connect","data":{"token":"4242"}}"""))
        assertEquals("4242", SamsungTvCommands.token("""{"event":"ms.channel.connect","data":{"token":"4242"}}"""))
        assertNull(SamsungTvCommands.token("""{"data":{"token":"bad token!"}}"""))
        assertEquals("KEY_ENTER", SamsungTvCommands.keyName(TvKey.OK))
        assertEquals("KEY_RETURN", SamsungTvCommands.keyName(TvKey.BACK))
        assertNull(SamsungTvCommands.keyName(TvKey.DELETE))
    }

    @Test
    fun samsungAllowsThePhoneAndTakesKeys() {
        val fake = FakeTv { peer ->
            peer.send("""{"event":"ed.edenTV.update","data":{}}""")
            peer.send("""{"event":"ms.channel.connect","data":{"token":"4242","clients":[]}}""")
            assertEquals(SamsungTvCommands.key("KEY_UP"), peer.read())
            assertEquals(SamsungTvCommands.key("KEY_PAUSE"), peer.read()) // play/pause: pause first
            assertEquals(SamsungTvCommands.key("KEY_PLAY"), peer.read())
            assertEquals(SamsungTvCommands.textReceived(), peer.read()) // once, before the first text
            assertEquals(SamsungTvCommands.text("ab"), peer.read())
            assertEquals(SamsungTvCommands.text("abc"), peer.read())
        }
        val opened = SamsungTvLink.open("127.0.0.1", "Caster", null, null, plain(fake.port), {}, {})
        try {
            assertTrue(fake.error == null)
            // The token the TV hands out is kept (a plain connection has no certificate to remember).
            assertEquals("4242", opened.token)
            assertNull(opened.fingerprint)
            val link = opened.link
            assertTrue(link.supports(TvKey.PLAY_PAUSE))
            assertFalse(link.supports(TvKey.DELETE))
            link.press(TvKey.UP)
            link.press(TvKey.PLAY_PAUSE)
            link.press(TvKey.PLAY_PAUSE)
            link.typed("", "ab")
            link.typed("ab", "abc")
            fake.finish()
        } finally {
            opened.link.close()
            fake.close()
        }
    }

    @Test
    fun samsungSaysNoWhenTheOwnerDoes() {
        val fake = FakeTv { peer -> peer.send("""{"event":"ms.channel.unauthorized"}""") }
        try {
            SamsungTvLink.open("127.0.0.1", "Caster", null, null, plain(fake.port), {}, {})
            fail("expected a refusal")
        } catch (e: TvRefusedException) {
            // fine
        } finally {
            fake.close()
        }
    }

    @Test
    fun samsungTriesThePlainPortWhenTheSecureOneIsClosed() {
        val closed = ServerSocket(0).use { it.localPort } // nothing listens there any more
        val fake = FakeTv { peer -> peer.send("""{"event":"ms.channel.connect","data":{}}""") }
        val opened = SamsungTvLink.open("127.0.0.1", "Caster", null, null, listOf(Endpoint(closed, true), Endpoint(fake.port, false)), {}, {})
        assertTrue(opened.link.supports(TvKey.OK)) // the plain port is the one that answered
        assertNull(opened.fingerprint) // and a plain connection has no certificate to remember
        opened.link.close()
        fake.close()
    }

    @Test
    fun samsungTriesThePlainPortWhenTheSecureOneHangsUp() {
        val hangsUp = FakeTv { } // takes the connection and drops it
        val works = FakeTv { peer -> peer.send("""{"event":"ms.channel.connect","data":{}}""") }
        val opened = SamsungTvLink.open("127.0.0.1", "Caster", null, null, listOf(Endpoint(hangsUp.port, false), Endpoint(works.port, false)), {}, {})
        assertTrue(opened.link.supports(TvKey.OK)) // the second port is the one that answered
        opened.link.close()
        hangsUp.close()
        works.close()
    }

    @Test
    fun aWaitForTheOwnerThatRunsOutDoesNotTryAnotherPort() {
        assertFalse(canTryNextPort(OwnerWaitTimeout()))
        assertFalse(canTryNextPort(TvRefusedException("no")))
        assertTrue(canTryNextPort(java.io.EOFException()))
        assertTrue(canTryNextPort(SocketTimeoutException()))
    }

    // --- LG -----------------------------------------------------------------------------------------------------------

    @Test
    fun lgMessagesLookLikeTheOnesTheTvKnows() {
        assertEquals("""{"id":"hello","type":"hello","payload":{}}""", LgTvCommands.hello())
        val signed = LgTvCommands.register(null, signed = true)
        assertTrue(signed.contains(""""signatures"""") && signed.contains(""""pairingType":"PROMPT""""))
        assertFalse(signed.contains("client-key"))
        val unsigned = LgTvCommands.register("k3y", signed = false)
        assertFalse(unsigned.contains("signatures"))
        assertFalse(unsigned.contains(""""signed""""))
        assertTrue(unsigned.contains(""""client-key":"k3y","forcePairing":false"""))
        assertTrue(unsigned.contains("CONTROL_INPUT_TEXT")) // the unsigned one carries the signed one's permissions too
        assertEquals("type:button\nname:UP\n\n", LgTvCommands.button("UP"))
        assertEquals("""{"id":7,"type":"request","uri":"ssap://audio/volumeUp","payload":{}}""", LgTvCommands.request(7, "audio/volumeUp"))
        assertEquals("registered", LgTvCommands.type("""{"type":"registered","id":"register_0","payload":{"client-key":"abc"}}"""))
        assertEquals("abc", LgTvCommands.clientKey("""{"type":"registered","payload":{"client-key":"abc"}}"""))
        assertTrue(LgTvCommands.isPrompt("""{"type":"response","payload":{"pairingType":"PROMPT","returnValue":true}}"""))
        assertEquals("ws://192.168.0.9:3000/resources/a/b.sock", LgTvCommands.socketPath("""{"payload":{"socketPath":"ws:\/\/192.168.0.9:3000\/resources\/a\/b.sock"}}"""))
        assertEquals("FASTFORWARD", LgTvCommands.buttonName(TvKey.FAST_FORWARD))
        assertNull(LgTvCommands.buttonName(TvKey.PLAY_PAUSE))
    }

    @Test
    fun lgRegistersAndTakesButtonsAndText() {
        val buttons = ArrayList<String>()
        val buttonsRead = CountDownLatch(3)
        val input = FakeTv { peer ->
            repeat(3) {
                buttons += peer.read()
                buttonsRead.countDown()
            }
            release.await(20, TimeUnit.SECONDS) // a TV that hangs up would end the whole link, so stay until the test is done
        }
        val main = FakeTv { peer ->
            assertEquals(LgTvCommands.hello(), peer.read())
            peer.send("""{"type":"hello","id":"hello","payload":{"protocolVersion":1}}""")
            assertEquals(LgTvCommands.systemInfo(), peer.read())
            peer.send("""{"type":"response","id":"get_sys_info","payload":{}}""")
            assertEquals(LgTvCommands.register(null, signed = true), peer.read())
            peer.send("""{"type":"error","id":"register_0","error":"403 refused"}""") // a newer TV: the signed sample isn't accepted
            assertEquals(LgTvCommands.register(null, signed = false), peer.read())
            peer.send("""{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}""")
            peer.send("""{"type":"registered","id":"register_0","payload":{"client-key":"abc123"}}""")
            assertEquals(LgTvCommands.request(0, LgTvCommands.INPUT_SOCKET), peer.read())
            peer.send("""{"type":"response","id":0,"payload":{"returnValue":true,"socketPath":"ws:\/\/192.168.0.9:${input.port}\/resources\/x\/netinput.pointer.sock"}}""")
            // typed("ab", "ac"): one letter deleted, one typed
            assertEquals(LgTvCommands.request(1, "com.webos.service.ime/deleteCharacters", """{"count":1}"""), peer.read())
            assertEquals(LgTvCommands.request(2, "com.webos.service.ime/insertText", """{"text":"c","replace":0}"""), peer.read())
            assertEquals(LgTvCommands.request(3, "system.launcher/open", """{"target":"http://x/"}"""), peer.read())
        }
        val opened = LgTvLink.open("127.0.0.1", null, null, plain(main.port), {}, {})
        try {
            assertEquals("abc123", opened.clientKey)
            val link = opened.link
            link.press(TvKey.UP)
            link.press(TvKey.PLAY_PAUSE)
            link.press(TvKey.PLAY_PAUSE)
            link.typed("ab", "ac")
            link.openUrl("http://x/")
            main.finish()
            assertTrue("the buttons never reached the TV", buttonsRead.await(10, TimeUnit.SECONDS))
        } finally {
            opened.link.close()
            main.close()
            input.close()
        }
        assertEquals(listOf("type:button\nname:UP\n\n", "type:button\nname:PAUSE\n\n", "type:button\nname:PLAY\n\n"), buttons)
    }

    @Test
    fun lgTriesThePlainPortWhenTheSecureOneHangsUpAfterConnecting() {
        val hangsUp = FakeTv { peer -> peer.read() } // answers the handshake, then drops the connection on the first message
        val input = FakeTv { release.await(20, TimeUnit.SECONDS) }
        val works = FakeTv { peer ->
            peer.read(); peer.send("""{"type":"hello","payload":{}}""")
            peer.read(); peer.send("""{"type":"response","payload":{}}""")
            peer.read(); peer.send("""{"type":"registered","id":"register_0","payload":{"client-key":"k"}}""")
            peer.read(); peer.send("""{"type":"response","id":0,"payload":{"socketPath":"ws://192.168.0.9:${input.port}/p"}}""")
            release.await(20, TimeUnit.SECONDS)
        }
        val opened = LgTvLink.open("127.0.0.1", null, null, listOf(Endpoint(hangsUp.port, false), Endpoint(works.port, false)), {}, {})
        assertEquals("k", opened.clientKey)
        opened.link.close()
        hangsUp.close()
        works.close()
        input.close()
    }

    @Test
    fun aPairedLgTvIsNeverTalkedToWithoutTls() {
        val fake = FakeTv { peer -> peer.read() }
        try {
            LgTvLink.open("127.0.0.1", "secret-key", "00".repeat(32), plain(fake.port), {}, {})
            fail("the saved key must not go out over a plain connection")
        } catch (e: IOException) {
            // refused before anything was sent
        } finally {
            fake.close()
        }
    }

    @Test
    fun lgSaysNoWhenTheOwnerDoes() {
        val main = FakeTv { peer ->
            peer.read(); peer.send("""{"type":"hello","payload":{}}""")
            peer.read(); peer.send("""{"type":"response","payload":{}}""")
            peer.read(); peer.send("""{"type":"response","payload":{"pairingType":"PROMPT"}}""")
            peer.send("""{"type":"error","id":"register_0","error":"403 User rejected"}""")
        }
        try {
            LgTvLink.open("127.0.0.1", null, null, plain(main.port), {}, {})
            fail("expected a refusal")
        } catch (e: TvRefusedException) {
            // fine
        } finally {
            main.close()
        }
    }

    @Test
    fun anLgKeyThatIsAlreadyKnownConnectsWithoutAsking() {
        val input = FakeTv { peer -> peer.read() }
        val main = FakeTv { peer ->
            peer.read(); peer.send("""{"type":"hello","payload":{}}""")
            peer.read(); peer.send("""{"type":"response","payload":{}}""")
            assertTrue(peer.read().contains(""""client-key":"saved""""))
            peer.send("""{"type":"registered","id":"register_0","payload":{}}""") // no new key: the saved one stays
            peer.read()
            peer.send("""{"type":"response","id":0,"payload":{"socketPath":"ws://192.168.0.9:${input.port}/p"}}""")
        }
        val opened = LgTvLink.open("127.0.0.1", "saved", null, plain(main.port), {}, {})
        try {
            assertEquals("saved", opened.clientKey)
            assertNotNull(opened.link)
            main.finish()
        } finally {
            opened.link.close()
            main.close()
            input.close()
        }
    }

    @Test
    fun tvChannelGuideNumpadAndColorKeysMapCorrectly() {
        assertEquals("KEY_CHUP", SamsungTvCommands.keyName(TvKey.CHANNEL_UP))
        assertEquals("KEY_CHDOWN", SamsungTvCommands.keyName(TvKey.CHANNEL_DOWN))
        assertEquals("KEY_GUIDE", SamsungTvCommands.keyName(TvKey.GUIDE))
        assertEquals("KEY_SOURCE", SamsungTvCommands.keyName(TvKey.TV_INPUT))
        assertEquals("KEY_5", SamsungTvCommands.keyName(TvKey.DIGIT_5))
        assertEquals("KEY_RED", SamsungTvCommands.keyName(TvKey.PROG_RED))

        assertEquals("CHANNELUP", LgTvCommands.buttonName(TvKey.CHANNEL_UP))
        assertEquals("CHANNELDOWN", LgTvCommands.buttonName(TvKey.CHANNEL_DOWN))
        assertEquals("GUIDE", LgTvCommands.buttonName(TvKey.GUIDE))
        assertEquals("7", LgTvCommands.buttonName(TvKey.DIGIT_7))
        assertEquals("BLUE", LgTvCommands.buttonName(TvKey.PROG_BLUE))
    }

    @Test
    fun lgParsesExternalInputsLaunchPointsAndForegroundApp() {
        val inputsJson = """
            {"type":"response","id":1,"payload":{"returnValue":true,"devices":[
                {"id":"HDMI_1","label":"Airtel Set-Top Box","connected":true,"appId":"com.webos.app.hdmi1"},
                {"id":"HDMI_2","label":"HDMI 2","connected":false,"appId":"com.webos.app.hdmi2"}
            ]}}
        """.trimIndent()
        val inputs = LgTvCommands.parseExternalInputs(inputsJson)
        assertEquals(2, inputs.size)
        assertEquals("HDMI_1", inputs[0].id)
        assertEquals("Airtel Set-Top Box", inputs[0].label)
        assertEquals(TvInput.InputKind.STB, inputs[0].kind)
        assertTrue(inputs[0].connected)
        assertEquals(TvInput.InputKind.HDMI, inputs[1].kind)
        assertFalse(inputs[1].connected)

        val appsJson = """
            {"type":"response","id":2,"payload":{"returnValue":true,"launchPoints":[
                {"id":"youtube.leanback.v4","title":"YouTube","systemApp":false},
                {"id":"com.webos.app.internal","title":"com.webos.app.internal","systemApp":true}
            ]}}
        """.trimIndent()
        val apps = LgTvCommands.parseLaunchPoints(appsJson)
        assertEquals(1, apps.size)
        assertEquals("youtube.leanback.v4", apps[0].id)
        assertEquals("YouTube", apps[0].title)

        val fgJson = """{"type":"response","id":3,"payload":{"appId":"com.webos.app.hdmi1","returnValue":true}}"""
        assertEquals("com.webos.app.hdmi1", LgTvCommands.parseForegroundAppId(fgJson))
    }

    @Test
    fun lgSendsChannelUpDownAndDigitsOverInputSocket() {
        val sentInput = mutableListOf<String>()
        val input = FakeTv { peer ->
            sentInput += peer.read()
            sentInput += peer.read()
            sentInput += peer.read()
        }
        val main = FakeTv { peer ->
            peer.read(); peer.send("""{"type":"hello","payload":{}}""")
            peer.read(); peer.send("""{"type":"response","payload":{}}""")
            peer.read(); peer.send("""{"type":"registered","id":"register_0","payload":{}}""")
            peer.read(); peer.send("""{"type":"response","id":0,"payload":{"socketPath":"ws://127.0.0.1:${input.port}/p"}}""")
            release.await(15, TimeUnit.SECONDS)
        }
        val opened = LgTvLink.open("127.0.0.1", "saved", null, plain(main.port), {}, {})
        try {
            opened.link.press(TvKey.CHANNEL_UP)
            opened.link.press(TvKey.CHANNEL_DOWN)
            opened.link.press(TvKey.DIGIT_5)
            input.finish()
            assertTrue(sentInput.any { it.contains("name:CHANNELUP") })
            assertTrue(sentInput.any { it.contains("name:CHANNELDOWN") })
            assertTrue(sentInput.any { it.contains("name:5") })
        } finally {
            opened.link.close()
            main.close()
            input.close()
        }
    }
}
