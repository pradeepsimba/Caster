package mano.algo.caster.server

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class RemoteStreamTest {
    private var server: NanoHTTPD? = null

    @Before
    fun setup() {
        RemoteControl.clear()
        PairingManager.reset()
        PairingManager.clearKnown()
    }

    @After
    fun teardown() {
        server?.stop()
        server = null
        RemoteControl.clear()
        PairingManager.reset()
    }

    @Test
    fun sseStreamDeliversCommandsImmediatelyWithoutGzipBuffering() {
        val created = PairingManager.request("TV", "127.0.0.1", "tv1111") as RequestResult.Created
        PairingManager.approve()
        val token = (PairingManager.status(created.request.id) as PairStatus.Approved).token
        val (deviceId, _) = PairingManager.clientOf(token)!!

        val active = AtomicBoolean(true)
        val nano = object : NanoHTTPD("127.0.0.1", 0) {
            override fun useGzipWhenAccepted(r: Response): Boolean =
                r.mimeType?.startsWith("text/event-stream") != true && super.useGzipWhenAccepted(r)

            override fun serve(session: IHTTPSession): Response =
                newChunkedResponse(
                    Response.Status.OK,
                    "text/event-stream; charset=utf-8",
                    LocalMediaServer.RemoteEventStream(deviceId, token) { active.get() && PairingManager.isValid(token) },
                ).apply {
                    setGzipEncoding(false)
                    addHeader("Cache-Control", "no-cache, no-store")
                }
        }
        nano.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = nano

        val conn = URI("http://127.0.0.1:${nano.listeningPort}/api/remote/stream?t=$token")
            .toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        assertEquals(200, conn.responseCode)
        assertNotEquals("gzip", conn.contentEncoding)

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        assertEquals(": ok", reader.readLine())
        assertEquals("", reader.readLine())

        val startMs = System.currentTimeMillis()
        RemoteControl.send(deviceId, "nav:down")
        assertEquals("data: nav:down", reader.readLine())
        assertEquals("", reader.readLine())

        RemoteControl.send(deviceId, "toggle")
        assertEquals("data: toggle", reader.readLine())
        assertEquals("", reader.readLine())
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("Expected instant SSE delivery (<500ms), took ${elapsedMs}ms", elapsedMs < 500)

        active.set(false)
        RemoteControl.idle(deviceId)
        conn.disconnect()
    }
}
