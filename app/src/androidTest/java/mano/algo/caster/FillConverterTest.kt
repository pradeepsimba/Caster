package mano.algo.caster

import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import mano.algo.caster.cast.FillConverter
import mano.algo.caster.cast.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on a device: a real video is re-encoded to fill a 16:9 screen, and the copy is checked. */
@RunWith(AndroidJUnit4::class)
class FillConverterTest {
    private fun convert(picture: Picture): Triple<File?, String?, List<Float>> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_VIDEO")
        val id = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), null, null,
            "${MediaStore.Video.Media.SIZE} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        assumeTrue("the device has no video to convert", id != null)

        val done = CountDownLatch(1)
        var file: File? = null
        var error: String? = null
        val progress = ArrayList<Float>()
        Handler(Looper.getMainLooper()).post {
            FillConverter.start(context, id!!, picture, { progress += it }, { file = it; done.countDown() }, { error = it; done.countDown() })
        }
        assertTrue("the conversion took too long", done.await(240, TimeUnit.SECONDS))
        return Triple(file, error, progress)
    }

    private fun size(file: File): Triple<Int, Int, Long> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            return Triple(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
            )
        } finally {
            r.release()
        }
    }

    @Test
    fun fillMakesAFullHdCopy() {
        val (file, error, progress) = convert(Picture.Fill)
        assertNull(error, error)
        val (w, h, duration) = size(file!!)
        println("FILL result: ${w}x$h, ${duration} ms, ${file.length()} bytes, progress steps=${progress.size}")
        assertEquals(1920, w)
        assertEquals(1080, h)
        assertTrue(duration > 0)
        assertTrue(file.length() > 0)
    }

    @Test
    fun stretchMakesAFullHdCopyToo() {
        val (file, error, _) = convert(Picture.Stretch)
        assertNull(error, error)
        val (w, h, _) = size(file!!)
        println("STRETCH result: ${w}x$h, ${file.length()} bytes")
        assertEquals(1920, w)
        assertEquals(1080, h)
    }
}
