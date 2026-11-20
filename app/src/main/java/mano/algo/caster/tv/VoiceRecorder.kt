package mano.algo.caster.tv

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException

/** Records the phone's microphone as the TV's voice assistant wants it: 16-bit, mono, 8000 samples a second. */
internal object VoiceRecorder {
    private const val SAMPLE_RATE = 8_000
    private const val CHUNK_BYTES = 8_192 // about half a second of sound

    /**
     * Hands [onChunk] the sound, piece by piece, for as long as [keepGoing] says so. The caller has checked that the
     * microphone may be used. The last piece may be shorter.
     */
    @SuppressLint("MissingPermission")
    fun record(keepGoing: () -> Boolean, onChunk: (ByteArray) -> Unit) {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("This phone's microphone can't be used.")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, CHUNK_BYTES * 2),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("This phone's microphone is busy or not allowed.")
        }
        try {
            recorder.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            while (keepGoing()) {
                var filled = 0
                while (filled < buffer.size && keepGoing()) {
                    val n = recorder.read(buffer, filled, buffer.size - filled)
                    if (n < 0) throw IOException("The microphone stopped (error $n).")
                    filled += n
                }
                if (filled > 0) onChunk(buffer.copyOf(filled))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
