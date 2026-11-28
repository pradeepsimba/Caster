package mano.algo.caster.server

import mano.algo.caster.cast.CastController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNotificationTargetTest {
    @Test
    fun `prefers active cast target over web remote`() {
        val now = 100_000L
        val cast = CastController.State(
            device = "Living Room TV",
            title = "Movie.mp4",
            playing = true,
            kind = "v",
            muted = true,
        )
        val remotes = listOf(
            RemoteState("d1", "Browser", "Song.mp3", playing = true, positionSec = 1, durationSec = 100, reportedAt = now, kind = "a"),
        )
        val target = activeMediaTarget(cast, remotes, now)
        assertTrue(target is MediaNotificationTarget.Cast)
        assertEquals("Movie.mp4", target?.title)
        assertEquals("Living Room TV", target?.subtitle)
        assertEquals("v", target?.kind)
        assertEquals(true, target?.muted)
    }

    @Test
    fun `picks fresh web remote with its media kind and mute state`() {
        val now = 100_000L
        val remotes = listOf(
            RemoteState("d1", "Kitchen", "Photo.jpg", playing = false, positionSec = 0, durationSec = 0, reportedAt = now - 1_000L, volume = 0, kind = "i"),
        )
        val target = activeMediaTarget(CastController.State(), remotes, now)
        assertTrue(target is MediaNotificationTarget.Web)
        val web = target as MediaNotificationTarget.Web
        assertEquals("d1", web.deviceId)
        assertEquals("i", web.kind)
        assertEquals(true, web.muted)
    }

    @Test
    fun `ignores stale web remote`() {
        val now = 100_000L
        val remotes = listOf(
            RemoteState("d1", "Kitchen", "Song.mp3", playing = true, positionSec = 5, durationSec = 120, reportedAt = now - RemoteControl.FRESH_MS * 2 - 1, kind = "a"),
        )
        assertNull(activeMediaTarget(CastController.State(), remotes, now))
    }
}
