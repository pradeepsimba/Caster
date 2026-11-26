package mano.algo.caster.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class FragmentedMp4WriterTest {

    @Test
    fun splitAnnexBNals_handlesThreeAndFourByteStartCodes() {
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E,
            0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(),
        )
        val nals = FragmentedMp4Writer.splitAnnexBNals(stream)
        assertEquals(2, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 0x42, 0x00, 0x1E), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()), nals[1])
    }

    @Test
    fun annexBToAvcc_prefixesFourByteLength() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        val avcc = FragmentedMp4Writer.annexBToAvcc(annexB)
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0x65, 0x88.toByte(), 0x84.toByte()), avcc)
    }

    @Test
    fun extractSpsPps_extractsFromCombinedCsd0() {
        val combined = ByteBuffer.wrap(
            byteArrayOf(
                0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28,
                0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C,
            ),
        )
        val (sps, pps) = FragmentedMp4Writer.extractSpsPps(combined, null)
        assertEquals(1, sps.size)
        assertEquals(1, pps.size)
        assertEquals(0x67, sps[0][0].toInt() and 0xFF)
        assertEquals(0x68, pps[0][0].toInt() and 0xFF)
    }

    @Test
    fun writeHeaderAndFragment_producesValidTopLevelBoxes() {
        val out = ByteArrayOutputStream()
        val sps = listOf(byteArrayOf(0x67, 0x64, 0x00, 0x28))
        val pps = listOf(byteArrayOf(0x68, 0xEE.toByte(), 0x3C))
        val writer = FragmentedMp4Writer(
            out = out,
            width = 1280,
            height = 720,
            avcSps = sps,
            avcPps = pps,
            hasAudio = true,
            audioSampleRate = 48000,
            audioChannelCount = 2,
            audioSpecificConfig = byteArrayOf(0x11, 0x90.toByte()),
        )
        writer.writeHeader()
        writer.writeFragment(
            videoSamples = listOf(
                FragmentedMp4Writer.VideoSample(byteArrayOf(0, 0, 0, 2, 0x65, 0x01), 3000L, true),
            ),
            audioSamples = listOf(
                FragmentedMp4Writer.AudioSample(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), 1024L),
            ),
        )

        val bytes = out.toByteArray()
        val boxes = parseTopLevelBoxes(bytes)
        assertEquals(listOf("ftyp", "moov", "moof", "mdat"), boxes.map { it.first })
        assertTrue(boxes.sumOf { it.second } == bytes.size)
    }

    private fun parseTopLevelBoxes(data: ByteArray): List<Pair<String, Int>> {
        val result = ArrayList<Pair<String, Int>>()
        val buf = ByteBuffer.wrap(data)
        while (buf.remaining() >= 8) {
            val size = buf.getInt()
            val typeBytes = ByteArray(4)
            buf.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            result.add(type to size)
            buf.position(buf.position() + size - 8)
        }
        return result
    }
}
