package mano.algo.caster.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun blocksKnownAdDomains() {
        assertTrue(AdBlocker.isAd("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlocker.isAd("https://securepubads.g.doubleclick.net/gampad/ads"))
        assertTrue(AdBlocker.isAd("https://serve.popads.net/popunder.js"))
        assertTrue(AdBlocker.isAd("http://adsterra.com/track/click"))
    }

    @Test
    fun allowsNormalMediaAndWebsites() {
        assertFalse(AdBlocker.isAd("https://example.com/video.mp4"))
        assertFalse(AdBlocker.isAd("https://archive.org/download/sample/movie.mkv"))
        assertFalse(AdBlocker.isAd("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"))
    }
}
