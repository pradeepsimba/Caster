package mano.algo.caster.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Writes a progressive Fragmented MP4 (ISO BMFF) stream (`ftyp` + `moov` + `moof`/`mdat` fragments)
 * containing H.264 (AVC) video and optional AAC audio.
 *
 * The resulting stream is valid `video/mp4` and plays natively in `<video>` elements across
 * Chrome, Firefox, Edge, Safari, and Smart TV browsers without client-side MSE libraries.
 */
class FragmentedMp4Writer(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val avcSps: List<ByteArray>,
    private val avcPps: List<ByteArray>,
    private val hasAudio: Boolean = false,
    private val audioSampleRate: Int = 44100,
    private val audioChannelCount: Int = 2,
    private val audioSpecificConfig: ByteArray = ByteArray(0),
    initialVideoDecodeTime: Long = 0L,
    initialAudioDecodeTime: Long = 0L,
) {
    data class VideoSample(
        val avccData: ByteArray,
        val durationTicks: Long,
        val isSync: Boolean,
    )

    data class AudioSample(
        val data: ByteArray,
        val durationTicks: Long,
    )

    private var seqNum = 1
    private var videoBaseDecodeTime = initialVideoDecodeTime
    private var audioBaseDecodeTime = initialAudioDecodeTime
    private var headerWritten = false

    fun writeHeader() {
        if (headerWritten) return
        headerWritten = true
        out.write(buildFtyp())
        out.write(buildMoov())
        out.flush()
    }

    /**
     * Writes one `moof` + `mdat` fragment for the given video and/or audio samples.
     */
    fun writeFragment(videoSamples: List<VideoSample>, audioSamples: List<AudioSample>) {
        if (videoSamples.isEmpty() && audioSamples.isEmpty()) return
        if (!headerWritten) writeHeader()

        val videoBytes = videoSamples.sumOf { it.avccData.size }
        val audioBytes = audioSamples.sumOf { it.data.size }
        val totalMdatPayload = videoBytes + audioBytes

        // Two-pass moof build: first with placeholder offsets (0) to measure exact moofSize,
        // then with exact data_offset = moofSize + 8 (for mdat header).
        val dummyMoof = buildMoof(videoSamples, audioSamples, 0, 0)
        val moofSize = dummyMoof.size
        val videoDataOffset = moofSize + 8
        val audioDataOffset = moofSize + 8 + videoBytes
        val moof = buildMoof(videoSamples, audioSamples, videoDataOffset, audioDataOffset)

        out.write(moof)

        // Write mdat header + payloads
        val mdatHeader = ByteArrayOutputStream(8)
        DataOutputStream(mdatHeader).apply {
            writeInt(8 + totalMdatPayload)
            writeBytes("mdat")
        }
        out.write(mdatHeader.toByteArray())
        for (s in videoSamples) out.write(s.avccData)
        for (s in audioSamples) out.write(s.data)
        out.flush()

        seqNum++
        for (s in videoSamples) videoBaseDecodeTime += s.durationTicks
        for (s in audioSamples) audioBaseDecodeTime += s.durationTicks
    }

    private fun buildFtyp(): ByteArray = box("ftyp") { d ->
        d.writeBytes("isom")
        d.writeInt(512)
        d.writeBytes("isom")
        d.writeBytes("iso6")
        d.writeBytes("mp41")
    }

    private fun buildMoov(): ByteArray = box("moov") { d ->
        d.write(buildMvhd())
        d.write(buildVideoTrak())
        if (hasAudio) d.write(buildAudioTrak())
        d.write(buildMvex())
    }

    private fun buildMvhd(): ByteArray = fullBox("mvhd", 0, 0) { d ->
        d.writeInt(0) // creation_time
        d.writeInt(0) // modification_time
        d.writeInt(1000) // timescale
        d.writeInt(0) // duration (unknown/fragmented)
        d.writeInt(0x00010000) // rate 1.0
        d.writeShort(0x0100) // volume 1.0
        d.write(ByteArray(10)) // reserved
        writeIdentityMatrix(d)
        d.write(ByteArray(24)) // pre_defined
        d.writeInt(if (hasAudio) 3 else 2) // next_track_ID
    }

    private fun buildVideoTrak(): ByteArray = box("trak") { d ->
        d.write(fullBox("tkhd", 0, 3) { t ->
            t.writeInt(0) // creation_time
            t.writeInt(0) // modification_time
            t.writeInt(1) // track_ID
            t.writeInt(0) // reserved
            t.writeInt(0) // duration
            t.write(ByteArray(8)) // reserved
            t.writeShort(0) // layer
            t.writeShort(0) // alternate_group
            t.writeShort(0) // volume
            t.writeShort(0) // reserved
            writeIdentityMatrix(t)
            t.writeInt(width shl 16)
            t.writeInt(height shl 16)
        })
        d.write(box("mdia") { m ->
            m.write(fullBox("mdhd", 0, 0) { md ->
                md.writeInt(0)
                md.writeInt(0)
                md.writeInt(VIDEO_TIMESCALE)
                md.writeInt(0)
                md.writeShort(0x55c4) // und
                md.writeShort(0)
            })
            m.write(fullBox("hdlr", 0, 0) { h ->
                h.writeInt(0)
                h.writeBytes("vide")
                h.write(ByteArray(12))
                h.writeBytes("VideoHandler")
                h.writeByte(0)
            })
            m.write(box("minf") { mf ->
                mf.write(fullBox("vmhd", 0, 1) { v ->
                    v.writeShort(0)
                    v.writeShort(0)
                    v.writeShort(0)
                    v.writeShort(0)
                })
                mf.write(buildDinf())
                mf.write(box("stbl") { st ->
                    st.write(buildVideoStsd())
                    st.write(emptyFullBox("stts"))
                    st.write(emptyFullBox("stsc"))
                    st.write(emptyStsz())
                    st.write(emptyFullBox("stco"))
                })
            })
        })
    }

    private fun buildVideoStsd(): ByteArray = fullBox("stsd", 0, 0) { d ->
        d.writeInt(1) // entry_count
        d.write(box("avc1") { a ->
            a.write(ByteArray(6)) // reserved
            a.writeShort(1) // data_reference_index
            a.write(ByteArray(16)) // pre_defined + reserved
            a.writeShort(width)
            a.writeShort(height)
            a.writeInt(0x00480000) // horizresolution 72 dpi
            a.writeInt(0x00480000) // vertresolution 72 dpi
            a.writeInt(0) // reserved
            a.writeShort(1) // frame_count
            a.write(ByteArray(32)) // compressorname
            a.writeShort(0x0018) // depth
            a.writeShort(-1) // pre_defined
            a.write(buildAvcC())
        })
    }

    private fun buildAvcC(): ByteArray = box("avcC") { d ->
        val sps0 = avcSps.firstOrNull()
        val profile = if (sps0 != null && sps0.size > 1) sps0[1].toInt() and 0xFF else 0x42
        val compat = if (sps0 != null && sps0.size > 2) sps0[2].toInt() and 0xFF else 0xC0
        val level = if (sps0 != null && sps0.size > 3) sps0[3].toInt() and 0xFF else 0x1E
        d.writeByte(1) // configurationVersion
        d.writeByte(profile)
        d.writeByte(compat)
        d.writeByte(level)
        d.writeByte(0xFF) // lengthSizeMinusOne = 3 (4 bytes)
        d.writeByte(0xE0 or (avcSps.size and 0x1F))
        for (sps in avcSps) {
            d.writeShort(sps.size)
            d.write(sps)
        }
        d.writeByte(avcPps.size and 0xFF)
        for (pps in avcPps) {
            d.writeShort(pps.size)
            d.write(pps)
        }
    }

    private fun buildAudioTrak(): ByteArray = box("trak") { d ->
        d.write(fullBox("tkhd", 0, 3) { t ->
            t.writeInt(0)
            t.writeInt(0)
            t.writeInt(2) // track_ID
            t.writeInt(0)
            t.writeInt(0)
            t.write(ByteArray(8))
            t.writeShort(0)
            t.writeShort(0)
            t.writeShort(0x0100) // volume 1.0
            t.writeShort(0)
            writeIdentityMatrix(t)
            t.writeInt(0)
            t.writeInt(0)
        })
        d.write(box("mdia") { m ->
            m.write(fullBox("mdhd", 0, 0) { md ->
                md.writeInt(0)
                md.writeInt(0)
                md.writeInt(audioSampleRate)
                md.writeInt(0)
                md.writeShort(0x55c4)
                md.writeShort(0)
            })
            m.write(fullBox("hdlr", 0, 0) { h ->
                h.writeInt(0)
                h.writeBytes("soun")
                h.write(ByteArray(12))
                h.writeBytes("SoundHandler")
                h.writeByte(0)
            })
            m.write(box("minf") { mf ->
                mf.write(fullBox("smhd", 0, 0) { s ->
                    s.writeShort(0)
                    s.writeShort(0)
                })
                mf.write(buildDinf())
                mf.write(box("stbl") { st ->
                    st.write(buildAudioStsd())
                    st.write(emptyFullBox("stts"))
                    st.write(emptyFullBox("stsc"))
                    st.write(emptyStsz())
                    st.write(emptyFullBox("stco"))
                })
            })
        })
    }

    private fun buildAudioStsd(): ByteArray = fullBox("stsd", 0, 0) { d ->
        d.writeInt(1)
        d.write(box("mp4a") { a ->
            a.write(ByteArray(6))
            a.writeShort(1) // data_reference_index
            a.write(ByteArray(8)) // reserved
            a.writeShort(audioChannelCount)
            a.writeShort(16) // sampleSize
            a.writeShort(0) // pre_defined
            a.writeShort(0) // reserved
            a.writeInt(audioSampleRate shl 16)
            a.write(buildEsds())
        })
    }

    private fun buildEsds(): ByteArray = fullBox("esds", 0, 0) { d ->
        val asc = if (audioSpecificConfig.isNotEmpty()) audioSpecificConfig else byteArrayOf(0x12, 0x10)
        val decConfigLen = 13 + 2 + asc.size
        val esDescLen = 3 + 2 + decConfigLen + 3
        // ES_Descriptor
        d.writeByte(0x03)
        d.writeByte(esDescLen)
        d.writeShort(2) // ES_ID
        d.writeByte(0) // flags
        // DecoderConfigDescriptor
        d.writeByte(0x04)
        d.writeByte(decConfigLen)
        d.writeByte(0x40) // AAC
        d.writeByte(0x15) // AudioStream
        d.writeByte(0); d.writeShort(0) // bufferSizeDB
        d.writeInt(192000) // maxBitrate
        d.writeInt(128000) // avgBitrate
        // DecoderSpecificInfo
        d.writeByte(0x05)
        d.writeByte(asc.size)
        d.write(asc)
        // SLConfigDescriptor
        d.writeByte(0x06)
        d.writeByte(1)
        d.writeByte(0x02)
    }

    private fun buildMvex(): ByteArray = box("mvex") { d ->
        d.write(buildTrex(1))
        if (hasAudio) d.write(buildTrex(2))
    }

    private fun buildTrex(trackId: Int): ByteArray = fullBox("trex", 0, 0) { d ->
        d.writeInt(trackId)
        d.writeInt(1) // default_sample_description_index
        d.writeInt(0)
        d.writeInt(0)
        d.writeInt(0)
    }

    private fun buildMoof(
        videoSamples: List<VideoSample>,
        audioSamples: List<AudioSample>,
        videoDataOffset: Int,
        audioDataOffset: Int,
    ): ByteArray = box("moof") { d ->
        d.write(fullBox("mfhd", 0, 0) { m ->
            m.writeInt(seqNum)
        })
        if (videoSamples.isNotEmpty()) {
            d.write(box("traf") { tf ->
                tf.write(fullBox("tfhd", 0, 0x020000) { th -> // default-base-is-moof
                    th.writeInt(1)
                })
                tf.write(fullBox("tfdt", 1, 0) { td ->
                    td.writeLong(videoBaseDecodeTime)
                })
                tf.write(fullBox("trun", 0, 0x000701) { tr ->
                    tr.writeInt(videoSamples.size)
                    tr.writeInt(videoDataOffset)
                    for (s in videoSamples) {
                        tr.writeInt(s.durationTicks.toInt())
                        tr.writeInt(s.avccData.size)
                        tr.writeInt(if (s.isSync) 0x02000000 else 0x01010000)
                    }
                })
            })
        }
        if (hasAudio && audioSamples.isNotEmpty()) {
            d.write(box("traf") { tf ->
                tf.write(fullBox("tfhd", 0, 0x020000) { th ->
                    th.writeInt(2)
                })
                tf.write(fullBox("tfdt", 1, 0) { td ->
                    td.writeLong(audioBaseDecodeTime)
                })
                tf.write(fullBox("trun", 0, 0x000301) { tr ->
                    tr.writeInt(audioSamples.size)
                    tr.writeInt(audioDataOffset)
                    for (s in audioSamples) {
                        tr.writeInt(s.durationTicks.toInt())
                        tr.writeInt(s.data.size)
                    }
                })
            })
        }
    }

    private fun buildDinf(): ByteArray = box("dinf") { d ->
        d.write(fullBox("dref", 0, 0) { r ->
            r.writeInt(1)
            r.write(fullBox("url ", 0, 1) {})
        })
    }

    private fun emptyFullBox(type: String): ByteArray = fullBox(type, 0, 0) { d ->
        d.writeInt(0)
    }

    private fun emptyStsz(): ByteArray = fullBox("stsz", 0, 0) { d ->
        d.writeInt(0)
        d.writeInt(0)
    }

    private fun writeIdentityMatrix(d: DataOutputStream) {
        d.writeInt(0x00010000); d.writeInt(0); d.writeInt(0)
        d.writeInt(0); d.writeInt(0x00010000); d.writeInt(0)
        d.writeInt(0); d.writeInt(0); d.writeInt(0x40000000)
    }

    private inline fun box(type: String, fill: (DataOutputStream) -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        fill(DataOutputStream(body))
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(8 + payload.size)
        DataOutputStream(out).apply {
            writeInt(8 + payload.size)
            writeBytes(type)
            write(payload)
        }
        return out.toByteArray()
    }

    private inline fun fullBox(type: String, version: Int, flags: Int, fill: (DataOutputStream) -> Unit): ByteArray {
        return box(type) { d ->
            d.writeInt(((version and 0xFF) shl 24) or (flags and 0xFFFFFF))
            fill(d)
        }
    }

    companion object {
        const val VIDEO_TIMESCALE = 90000

        /** Splits an Annex-B byte stream (with 00 00 01 or 00 00 00 01 start codes) into raw NAL units. */
        fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
            val nals = ArrayList<ByteArray>()
            var i = 0
            var nalStart = -1
            while (i < data.size) {
                val scLen = startCodeLen(data, i)
                if (scLen > 0) {
                    if (nalStart >= 0 && i > nalStart) {
                        nals.add(data.copyOfRange(nalStart, i))
                    }
                    i += scLen
                    nalStart = i
                } else {
                    i++
                }
            }
            if (nalStart >= 0 && data.size > nalStart) {
                nals.add(data.copyOfRange(nalStart, data.size))
            }
            return nals
        }

        /** Converts Annex-B NALUs (or already-AVCC if no start code) into 4-byte length-prefixed AVCC bytes. */
        fun annexBToAvcc(data: ByteArray): ByteArray {
            val nals = splitAnnexBNals(data)
            if (nals.isEmpty()) return data
            val out = ByteArrayOutputStream(data.size + nals.size * 2)
            val dos = DataOutputStream(out)
            for (nal in nals) {
                if (nal.isEmpty()) continue
                dos.writeInt(nal.size)
                dos.write(nal)
            }
            return out.toByteArray()
        }

        /** Extracts SPS (type 7) and PPS (type 8) from one or two Annex-B CSD buffers. */
        fun extractSpsPps(csd0: ByteBuffer?, csd1: ByteBuffer?): Pair<List<ByteArray>, List<ByteArray>> {
            val sps = ArrayList<ByteArray>()
            val pps = ArrayList<ByteArray>()
            fun scan(buf: ByteBuffer?) {
                if (buf == null) return
                val dup = buf.duplicate()
                val bytes = ByteArray(dup.remaining())
                dup.get(bytes)
                val nals = splitAnnexBNals(bytes)
                val items = if (nals.isNotEmpty()) nals else listOf(bytes)
                for (nal in items) {
                    if (nal.isEmpty()) continue
                    when (nal[0].toInt() and 0x1F) {
                        7 -> sps.add(nal)
                        8 -> pps.add(nal)
                    }
                }
            }
            scan(csd0)
            scan(csd1)
            return sps to pps
        }

        private fun startCodeLen(data: ByteArray, pos: Int): Int {
            if (pos + 2 < data.size && data[pos] == 0.toByte() && data[pos + 1] == 0.toByte()) {
                if (data[pos + 2] == 1.toByte()) return 3
                if (pos + 3 < data.size && data[pos + 2] == 0.toByte() && data[pos + 3] == 1.toByte()) return 4
            }
            return 0
        }
    }
}
