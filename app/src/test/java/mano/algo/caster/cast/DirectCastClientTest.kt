package mano.algo.caster.cast

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class DirectCastClientTest {
    @Test
    fun castMessageRoundTrip() {
        val original = CastMessageCodec.CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.receiver",
            payloadUtf8 = """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""",
        )
        val framed = CastMessageCodec.encode(original)
        val decoded = CastMessageCodec.readFrame(ByteArrayInputStream(framed))
        assertEquals(original, decoded)
    }

    @Test
    fun castMessageMultipleFrames() {
        val first = CastMessageCodec.CastMessage("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.connection", """{"type":"CONNECT"}""")
        val second = CastMessageCodec.CastMessage("sender-0", "web-1", "urn:x-cast:com.google.cast.media", """{"type":"PLAY","mediaSessionId":42}""")
        val combined = CastMessageCodec.encode(first) + CastMessageCodec.encode(second)
        val stream = ByteArrayInputStream(combined)
        assertEquals(first, CastMessageCodec.readFrame(stream))
        assertEquals(second, CastMessageCodec.readFrame(stream))
    }
}
