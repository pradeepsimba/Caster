package mano.algo.caster.server

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Streams any Android-decodable video (HEVC, VP9, AV1, MPEG-4, MKV, etc.) as a
 * progressive Fragmented MP4 (`video/mp4`, H.264 + AAC) over an [InputStream].
 *
 * Uses hardware-accelerated Surface-to-Surface transcoding when the video codec is
 * not H.264, or zero-copy passthrough when video is already H.264 / audio is already AAC.
 */
object VideoTranscoder {
    private const val TAG = "VideoTranscoder"
    private const val FRAGMENT_MIN_TICKS = 90_000L // ~1s fragments for fast startup
    private const val FRAGMENT_MAX_TICKS = 270_000L // ~3s max if keyframes are sparse

    private val activeSessions = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Starts a background transcode/remux session for [uri] starting at [startSec] seconds,
     * returning an [InputStream] of `video/mp4` (fMP4) bytes.
     *
     * Any previous transcode for the same [sessionKey] is cancelled immediately so rapid
     * seeking does not pile up hardware codecs.
     */
    fun openStream(
        context: Context,
        uri: Uri,
        startSec: Long,
        sessionKey: String,
        stillAllowed: () -> Boolean,
    ): InputStream {
        val cancelled = AtomicBoolean(false)
        activeSessions.put(sessionKey, cancelled)?.set(true)

        val pipe = FastBytePipe(maxBufferedBytes = 4 * 1024 * 1024, cancelled = cancelled)

        val worker = Thread({
            try {
                runTranscode(
                    context = context.applicationContext,
                    uri = uri,
                    startSec = startSec.coerceAtLeast(0L),
                    cancelled = cancelled,
                    stillAllowed = stillAllowed,
                    out = pipe.outputStream,
                )
            } catch (e: IOException) {
                Log.d(TAG, "Stream closed for $uri: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Transcode failed for $uri", e)
            } finally {
                activeSessions.remove(sessionKey, cancelled)
                pipe.closeWrite()
            }
        }, "caster-transcode")
        worker.isDaemon = true
        worker.start()

        return pipe.inputStream
    }

    private fun runTranscode(
        context: Context,
        uri: Uri,
        startSec: Long,
        cancelled: AtomicBoolean,
        stillAllowed: () -> Boolean,
        out: OutputStream,
    ) {
        val extractor = MediaExtractor()
        var videoDecoder: MediaCodec? = null
        var videoEncoder: MediaCodec? = null
        var encoderSurface: Surface? = null
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            var audioTrack = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/")) {
                    // Prefer AAC track if multiple audio tracks exist (e.g. DD+5.1 & AAC)
                    if (audioTrack < 0 || mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        audioTrack = i
                        audioFormat = fmt
                    }
                }
            }

            if (videoTrack < 0 || videoFormat == null) {
                throw IOException("No video track found")
            }

            extractor.selectTrack(videoTrack)
            if (audioTrack >= 0) extractor.selectTrack(audioTrack)

            val startUs = startSec * 1_000_000L
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val (outWidth, outHeight) = fitDimensions(srcWidth, srcHeight)
            val videoPassthrough = videoMime == MediaFormat.MIMETYPE_VIDEO_AVC

            var avcSps: List<ByteArray> = emptyList()
            var avcPps: List<ByteArray> = emptyList()
            if (videoPassthrough) {
                val (sps, pps) = FragmentedMp4Writer.extractSpsPps(
                    videoFormat.getByteBuffer("csd-0"),
                    videoFormat.getByteBuffer("csd-1"),
                )
                avcSps = sps
                avcPps = pps
            } else {
                val fps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(15, 60)
                } else 30
                val bitrate = ((outWidth.toLong() * outHeight) * 4L).coerceIn(1_500_000L, 6_000_000L).toInt()
                val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }
                }
                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                encoderSurface = videoEncoder.createInputSurface()
                videoEncoder.start()

                videoDecoder = MediaCodec.createDecoderByType(videoMime).apply {
                    configure(videoFormat, encoderSurface, null, 0)
                    start()
                }
            }

            // Setup audio
            var hasAudio = audioTrack >= 0 && audioFormat != null
            var audioPassthrough = false
            var audioSampleRate = 44100
            var audioChannels = 2
            var decodedAudioChannels = 2
            var audioSpecConfig = ByteArray(0)

            if (hasAudio && audioFormat != null) {
                val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                audioSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44100
                val srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 2
                decodedAudioChannels = srcChannels
                audioChannels = srcChannels.coerceIn(1, 2)

                if (audioMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    val csd0 = audioFormat.getByteBuffer("csd-0")
                    if (csd0 != null && csd0.remaining() >= 2) {
                        audioPassthrough = true
                        audioChannels = srcChannels
                        audioSpecConfig = ByteArray(csd0.remaining()).also { csd0.duplicate().get(it) }
                    }
                }

                if (!audioPassthrough) {
                    try {
                        audioDecoder = MediaCodec.createDecoderByType(audioMime).apply {
                            configure(audioFormat, null, null, 0)
                            start()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio decode unavailable for $audioMime; continuing video-only", e)
                        runCatching { audioDecoder?.release() }
                        audioDecoder = null
                        hasAudio = false
                    }
                }
            }

            fun ensureAudioEncoder(): Boolean {
                if (audioEncoder != null) return true
                if (!hasAudio || audioPassthrough) return false
                return try {
                    val aEncFmt = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        audioSampleRate,
                        audioChannels,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    }
                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        configure(aEncFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Audio encode unavailable; continuing video-only", e)
                    runCatching { audioEncoder?.release() }
                    runCatching { audioDecoder?.stop(); audioDecoder?.release() }
                    audioEncoder = null
                    audioDecoder = null
                    hasAudio = false
                    false
                }
            }

            var writer: FragmentedMp4Writer? = null
            val pendingVideo = ArrayList<FragmentedMp4Writer.VideoSample>()
            val pendingAudio = ArrayList<FragmentedMp4Writer.AudioSample>()
            var pendingVideoTicks = 0L

            var extractorDone = false
            var videoDecoderDone = videoPassthrough
            var videoEncoderDone = videoPassthrough
            var audioDecoderDone = !hasAudio || audioPassthrough
            var audioEncoderDone = !hasAudio || audioPassthrough

            // Timestamp tracking (zero-drift cumulative ticks)
            var firstVideoPtsUs = -1L
            var firstAudioPtsUs = -1L
            var totalVideoTicks = 0L
            var totalAudioTicks = 0L
            data class PcmChunk(val data: ByteArray, val ptsUs: Long)
            val pcmQueue = ArrayDeque<PcmChunk>()
            var pcmOffset = 0
            var audioEosPending = false

            val readBuf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            fun flushFragmentIfReady(force: Boolean, nextIsSync: Boolean = false) {
                val readyHeader = avcSps.isNotEmpty() && avcPps.isNotEmpty() && (!hasAudio || audioSpecConfig.isNotEmpty())
                if (!readyHeader) return
                if (writer == null) {
                    val vGapUs = if (hasAudio && firstVideoPtsUs > firstAudioPtsUs && firstAudioPtsUs >= 0) {
                        (firstVideoPtsUs - firstAudioPtsUs).coerceAtMost(2_000_000L)
                    } else 0L
                    val aGapUs = if (hasAudio && firstAudioPtsUs > firstVideoPtsUs && firstVideoPtsUs >= 0) {
                        (firstAudioPtsUs - firstVideoPtsUs).coerceAtMost(2_000_000L)
                    } else 0L
                    val vOff = vGapUs * FragmentedMp4Writer.VIDEO_TIMESCALE / 1_000_000L
                    val aOff = aGapUs * audioSampleRate / 1_000_000L
                    writer = FragmentedMp4Writer(
                        out = out,
                        width = if (videoPassthrough) srcWidth else outWidth,
                        height = if (videoPassthrough) srcHeight else outHeight,
                        avcSps = avcSps,
                        avcPps = avcPps,
                        hasAudio = hasAudio,
                        audioSampleRate = audioSampleRate,
                        audioChannelCount = audioChannels,
                        audioSpecificConfig = audioSpecConfig,
                        initialVideoDecodeTime = vOff,
                        initialAudioDecodeTime = aOff,
                    ).also { it.writeHeader() }
                }
                val shouldFlush = force ||
                    (pendingVideoTicks >= FRAGMENT_MIN_TICKS && nextIsSync) ||
                    pendingVideoTicks >= FRAGMENT_MAX_TICKS
                if (shouldFlush && (pendingVideo.isNotEmpty() || pendingAudio.isNotEmpty())) {
                    writer!!.writeFragment(pendingVideo.toList(), pendingAudio.toList())
                    pendingVideo.clear()
                    pendingAudio.clear()
                    pendingVideoTicks = 0L
                }
            }

            fun pushVideoSample(avcc: ByteArray, ptsUs: Long, isSync: Boolean) {
                if (firstVideoPtsUs < 0) firstVideoPtsUs = ptsUs
                val targetTicks = ((ptsUs - firstVideoPtsUs) * FragmentedMp4Writer.VIDEO_TIMESCALE / 1_000_000L)
                    .coerceAtLeast(totalVideoTicks + 1500L)
                val durTicks = if (pendingVideo.isEmpty() && totalVideoTicks == 0L) {
                    3000L // ~30fps initial frame estimate; subsequent frames self-correct against ptsUs
                } else {
                    (targetTicks - totalVideoTicks).coerceIn(1000L, 15_000L)
                }
                // Flush previous fragment right before a sync frame so each fragment starts with an IDR frame
                if (isSync && pendingVideoTicks >= FRAGMENT_MIN_TICKS) {
                    flushFragmentIfReady(force = true)
                }
                pendingVideo.add(FragmentedMp4Writer.VideoSample(avcc, durTicks, isSync))
                pendingVideoTicks += durTicks
                totalVideoTicks += durTicks
            }

            fun pushAudioSample(raw: ByteArray, ptsUs: Long) {
                val payload = if (audioPassthrough) stripAdtsIfPresent(raw) else raw
                if (payload.isEmpty()) return
                if (firstAudioPtsUs < 0) firstAudioPtsUs = ptsUs
                val durTicks = if (audioPassthrough && isSbrAac(audioSpecConfig)) 2048L else 1024L
                pendingAudio.add(FragmentedMp4Writer.AudioSample(payload, durTicks))
                totalAudioTicks += durTicks
            }

            while (!cancelled.get() && !Thread.currentThread().isInterrupted && stillAllowed()) {
                val allDone = extractorDone && videoDecoderDone && videoEncoderDone && audioDecoderDone && audioEncoderDone
                if (allDone) break

                var progress = false

                // 1. Feed extractor into decoders (or passthrough lists) - drain up to 8 samples per loop
                for (step in 0 until 8) {
                    if (extractorDone) break
                    val track = extractor.sampleTrackIndex
                    if (track < 0) {
                        var eosSent = true
                        if (!videoPassthrough && videoDecoder != null) {
                            val idx = videoDecoder.dequeueInputBuffer(0)
                            if (idx >= 0) {
                                videoDecoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                progress = true
                            } else {
                                eosSent = false
                            }
                        } else {
                            videoDecoderDone = true
                            videoEncoderDone = true
                        }
                        val aDec = audioDecoder
                        if (hasAudio && !audioPassthrough && aDec != null) {
                            val idx = aDec.dequeueInputBuffer(0)
                            if (idx >= 0) {
                                aDec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                progress = true
                            } else {
                                eosSent = false
                            }
                        } else {
                            audioDecoderDone = true
                            audioEncoderDone = true
                        }
                        if (eosSent) extractorDone = true
                        break
                    } else if (track == videoTrack) {
                        val pts = extractor.sampleTime.coerceAtLeast(0L)
                        if (videoPassthrough) {
                            readBuf.clear()
                            val size = extractor.readSampleData(readBuf, 0)
                            if (size > 0) {
                                val raw = ByteArray(size)
                                readBuf.get(raw)
                                val avcc = FragmentedMp4Writer.annexBToAvcc(raw)
                                val isSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                pushVideoSample(avcc, pts, isSync)
                            }
                            extractor.advance()
                            progress = true
                        } else if (videoDecoder != null) {
                            val idx = videoDecoder.dequeueInputBuffer(0)
                            if (idx >= 0) {
                                val buf = videoDecoder.getInputBuffer(idx)!!
                                val size = extractor.readSampleData(buf, 0)
                                if (size >= 0) {
                                    videoDecoder.queueInputBuffer(idx, 0, size, pts, extractor.sampleFlags)
                                }
                                extractor.advance()
                                progress = true
                            } else {
                                break
                            }
                        }
                    } else if (hasAudio && track == audioTrack) {
                        val pts = extractor.sampleTime.coerceAtLeast(0L)
                        if (audioPassthrough) {
                            readBuf.clear()
                            val size = extractor.readSampleData(readBuf, 0)
                            if (size > 0) {
                                val raw = ByteArray(size)
                                readBuf.get(raw)
                                pushAudioSample(raw, pts)
                            }
                            extractor.advance()
                            progress = true
                        } else {
                            val aDec = audioDecoder
                            if (aDec != null) {
                                val idx = aDec.dequeueInputBuffer(0)
                                if (idx >= 0) {
                                    val buf = aDec.getInputBuffer(idx)!!
                                    val size = extractor.readSampleData(buf, 0)
                                    if (size >= 0) {
                                        aDec.queueInputBuffer(idx, 0, size, pts, extractor.sampleFlags)
                                    }
                                    extractor.advance()
                                    progress = true
                                } else {
                                    break
                                }
                            }
                        }
                    } else {
                        extractor.advance()
                        progress = true
                    }
                }

                // 2. Drain video decoder -> encoder surface
                if (!videoPassthrough && videoDecoder != null && !videoDecoderDone) {
                    while (true) {
                        val outIdx = videoDecoder.dequeueOutputBuffer(info, 0)
                        if (outIdx >= 0) {
                            val doRender = info.size > 0
                            videoDecoder.releaseOutputBuffer(outIdx, doRender)
                            progress = true
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                videoDecoderDone = true
                                videoEncoder?.signalEndOfInputStream()
                                break
                            }
                        } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            progress = true
                        } else {
                            break
                        }
                    }
                }

                // 3. Drain video encoder -> pendingVideo
                if (!videoPassthrough && videoEncoder != null && !videoEncoderDone) {
                    while (true) {
                        val outIdx = videoEncoder.dequeueOutputBuffer(info, 0)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val fmt = videoEncoder.outputFormat
                            val (sps, pps) = FragmentedMp4Writer.extractSpsPps(
                                fmt.getByteBuffer("csd-0"),
                                fmt.getByteBuffer("csd-1"),
                            )
                            avcSps = sps
                            avcPps = pps
                            progress = true
                        } else if (outIdx >= 0) {
                            val buf = videoEncoder.getOutputBuffer(outIdx)!!
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val raw = ByteArray(info.size)
                                buf.get(raw)
                                val avcc = FragmentedMp4Writer.annexBToAvcc(raw)
                                val isSync = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                pushVideoSample(avcc, info.presentationTimeUs, isSync)
                            }
                            videoEncoder.releaseOutputBuffer(outIdx, false)
                            progress = true
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                videoEncoderDone = true
                                break
                            }
                        } else {
                            break
                        }
                    }
                }

                // 4. Drain audio decoder -> pcmQueue -> audio encoder (with 5.1->stereo downmix if needed)
                val dec = audioDecoder
                if (hasAudio && !audioPassthrough && dec != null) {
                    if (!audioDecoderDone) {
                        while (pcmQueue.size < 16) {
                            val outIdx = dec.dequeueOutputBuffer(info, 0)
                            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val fmt = dec.outputFormat
                                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    audioSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }
                                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    decodedAudioChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    audioChannels = decodedAudioChannels.coerceIn(1, 2)
                                }
                                ensureAudioEncoder()
                                progress = true
                            } else if (outIdx >= 0) {
                                val decBuf = dec.getOutputBuffer(outIdx)!!
                                if (info.size > 0 && ensureAudioEncoder()) {
                                    decBuf.position(info.offset)
                                    decBuf.limit(info.offset + info.size)
                                    val rawPcm = ByteArray(info.size)
                                    decBuf.get(rawPcm)
                                    val pcm = if (decodedAudioChannels > audioChannels) {
                                        downmixPcm16(rawPcm, decodedAudioChannels, audioChannels)
                                    } else {
                                        rawPcm
                                    }
                                    pcmQueue.addLast(PcmChunk(pcm, info.presentationTimeUs.coerceAtLeast(0L)))
                                }
                                dec.releaseOutputBuffer(outIdx, false)
                                progress = true
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    audioDecoderDone = true
                                    audioEosPending = true
                                    if (audioEncoder == null) audioEncoderDone = true
                                    break
                                }
                            } else {
                                break
                            }
                        }
                    }

                    // Feed pcmQueue into audioEncoder without dropping any bytes
                    val enc = audioEncoder
                    while (enc != null && (pcmQueue.isNotEmpty() || audioEosPending)) {
                        val inIdx = enc.dequeueInputBuffer(0)
                        if (inIdx < 0) break
                        if (pcmQueue.isNotEmpty()) {
                            val encBuf = enc.getInputBuffer(inIdx)!!
                            encBuf.clear()
                            val head = pcmQueue.first()
                            val avail = head.data.size - pcmOffset
                            val toCopy = minOf(avail, encBuf.remaining())
                            val bytesPerSec = (audioSampleRate.toLong() * 2L * audioChannels.toLong()).coerceAtLeast(1L)
                            val chunkPtsUs = head.ptsUs + (pcmOffset.toLong() * 1_000_000L) / bytesPerSec
                            encBuf.put(head.data, pcmOffset, toCopy)
                            pcmOffset += toCopy
                            if (pcmOffset >= head.data.size) {
                                pcmQueue.removeFirst()
                                pcmOffset = 0
                            }
                            enc.queueInputBuffer(inIdx, 0, toCopy, chunkPtsUs, 0)
                            progress = true
                        } else if (audioEosPending) {
                            audioEosPending = false
                            enc.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            progress = true
                            break
                        }
                    }
                }

                // 5. Drain audio encoder -> pendingAudio
                val encOut = audioEncoder
                if (hasAudio && !audioPassthrough && encOut != null && !audioEncoderDone) {
                    while (true) {
                        val outIdx = encOut.dequeueOutputBuffer(info, 0)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val csd0 = encOut.outputFormat.getByteBuffer("csd-0")
                            if (csd0 != null && csd0.remaining() >= 2) {
                                audioSpecConfig = ByteArray(csd0.remaining()).also { csd0.duplicate().get(it) }
                            }
                            progress = true
                        } else if (outIdx >= 0) {
                            val buf = encOut.getOutputBuffer(outIdx)!!
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val raw = ByteArray(info.size)
                                buf.get(raw)
                                pushAudioSample(raw, info.presentationTimeUs)
                            }
                            encOut.releaseOutputBuffer(outIdx, false)
                            progress = true
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                audioEncoderDone = true
                                break
                            }
                        } else {
                            break
                        }
                    }
                }

                // Fallback AudioSpecificConfig if audioEncoder hasn't emitted csd-0 yet
                if (hasAudio && audioSpecConfig.isEmpty() && pendingVideo.size > 60) {
                    audioSpecConfig = defaultAacConfig(audioSampleRate, audioChannels)
                }

                flushFragmentIfReady(force = false)

                if (!progress) {
                    Thread.sleep(2)
                }
            }

            flushFragmentIfReady(force = true)
        } finally {
            runCatching { videoDecoder?.stop() }
            runCatching { videoDecoder?.release() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { encoderSurface?.release() }
            runCatching { audioDecoder?.stop() }
            runCatching { audioDecoder?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downmixes interleaved 16-bit LE PCM from [srcCh] (e.g. 5.1) to [dstCh] (1 or 2). */
    private fun downmixPcm16(pcm: ByteArray, srcCh: Int, dstCh: Int): ByteArray {
        val sampleCount = pcm.size / (2 * srcCh)
        if (sampleCount <= 0) return ByteArray(0)
        val inBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(sampleCount * 2 * dstCh)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val fl = inBuf.getShort().toInt()
            val fr = if (srcCh >= 2) inBuf.getShort().toInt() else fl
            val c = if (srcCh >= 3) inBuf.getShort().toInt() else 0
            for (ch in 3 until srcCh) inBuf.getShort()
            if (dstCh == 1) {
                val mono = ((fl + fr) / 2 + c / 2).coerceIn(-32768, 32767).toShort()
                outBuf.putShort(mono)
            } else {
                val l = (fl + (c * 7) / 10).coerceIn(-32768, 32767).toShort()
                val r = (fr + (c * 7) / 10).coerceIn(-32768, 32767).toShort()
                outBuf.putShort(l)
                outBuf.putShort(r)
            }
        }
        return out
    }

    private fun stripAdtsIfPresent(packet: ByteArray): ByteArray {
        if (packet.size > 7 && (packet[0].toInt() and 0xFF) == 0xFF && (packet[1].toInt() and 0xF0) == 0xF0) {
            val headerLen = if ((packet[1].toInt() and 0x01) == 0) 9 else 7
            if (packet.size > headerLen) return packet.copyOfRange(headerLen, packet.size)
        }
        return packet
    }

    private fun isSbrAac(asc: ByteArray): Boolean {
        if (asc.isEmpty()) return false
        val objType = (asc[0].toInt() ushr 3) and 0x1F
        return objType == 5 || objType == 29
    }

    private fun defaultAacConfig(sampleRate: Int, channels: Int): ByteArray {
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
        val b0 = ((2 shl 3) or (freqIdx shr 1)).toByte() // AAC-LC (2)
        val b1 = (((freqIdx and 1) shl 7) or ((channels and 0x0F) shl 3)).toByte()
        return byteArrayOf(b0, b1)
    }

    /** Caps resolution to 1920x1080 preserving aspect ratio and rounding to even numbers. */
    private fun fitDimensions(w: Int, h: Int): Pair<Int, Int> {
        val maxW = 1920
        val maxH = 1080
        var ow = w.coerceAtLeast(16)
        var oh = h.coerceAtLeast(16)
        if (ow > maxW || oh > maxH) {
            val scale = minOf(maxW.toFloat() / ow, maxH.toFloat() / oh)
            ow = (ow * scale).toInt()
            oh = (oh * scale).toInt()
        }
        return (ow and 1.inv()) to (oh and 1.inv())
    }

    /**
     * Lock-free-style bounded byte queue connecting the transcode thread to NanoHTTPD
     * without `PipedInputStream`'s 1-second polling stalls.
     */
    private class FastBytePipe(
        private val maxBufferedBytes: Int,
        private val cancelled: AtomicBoolean,
    ) {
        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val notFull = lock.newCondition()
        private val chunks = ArrayDeque<ByteArray>()
        private var headOffset = 0
        private var bufferedBytes = 0
        private var writeClosed = false
        private var readClosed = false

        val outputStream: OutputStream = object : OutputStream() {
            private val buf = ByteArrayOutputStream(64 * 1024)

            override fun write(b: Int) {
                buf.write(b)
                if (buf.size() >= 64 * 1024) flush()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                buf.write(b, off, len)
                if (buf.size() >= 64 * 1024) flush()
            }

            override fun flush() {
                if (buf.size() == 0) return
                val data = buf.toByteArray()
                buf.reset()
                enqueue(data)
            }

            override fun close() {
                runCatching { flush() }
                closeWrite()
            }
        }

        val inputStream: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                lock.withLock {
                    while (chunks.isEmpty() && !writeClosed && !readClosed && !cancelled.get()) {
                        notEmpty.await()
                    }
                    if (readClosed || cancelled.get()) return -1
                    if (chunks.isEmpty()) return -1
                    val head = chunks.first()
                    val avail = head.size - headOffset
                    val n = minOf(len, avail)
                    System.arraycopy(head, headOffset, b, off, n)
                    headOffset += n
                    bufferedBytes -= n
                    if (headOffset >= head.size) {
                        chunks.removeFirst()
                        headOffset = 0
                    }
                    notFull.signalAll()
                    return n
                }
            }

            override fun available(): Int = lock.withLock { bufferedBytes }

            override fun close() {
                cancelled.set(true)
                lock.withLock {
                    readClosed = true
                    chunks.clear()
                    bufferedBytes = 0
                    notFull.signalAll()
                    notEmpty.signalAll()
                }
            }
        }

        private fun enqueue(data: ByteArray) {
            lock.withLock {
                while (bufferedBytes >= maxBufferedBytes && !readClosed && !cancelled.get()) {
                    notFull.await()
                }
                if (readClosed || cancelled.get()) throw IOException("Reader closed")
                chunks.addLast(data)
                bufferedBytes += data.size
                notEmpty.signalAll()
            }
        }

        fun closeWrite() {
            lock.withLock {
                writeClosed = true
                notEmpty.signalAll()
            }
        }
    }
}
