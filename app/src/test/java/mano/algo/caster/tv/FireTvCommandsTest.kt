package mano.algo.caster.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FireTvCommandsTest {
    // Written with named characters so the expectations are readable without counting backslashes.
    private val bs = '\\'
    private val q = '"'

    @Test
    fun dpadAndButtonsMapToTheServiceActions() {
        assertEquals("/v1/FireTV?action=dpad_up", FireTvCommands.forKey(TvKey.UP)?.path)
        assertEquals("/v1/FireTV?action=select", FireTvCommands.forKey(TvKey.OK)?.path)
        assertEquals("/v1/FireTV?action=home", FireTvCommands.forKey(TvKey.HOME)?.path)
        assertEquals("/v1/media?action=play", FireTvCommands.forKey(TvKey.PLAY_PAUSE)?.path)
        assertEquals("/v1/media?action=scan", FireTvCommands.forKey(TvKey.FAST_FORWARD)?.path)
    }

    @Test
    fun onlyDirectionsCanBeHeld() {
        val down = FireTvCommands.hold(TvKey.LEFT, down = true)
        val up = FireTvCommands.hold(TvKey.LEFT, down = false)
        assertEquals("/v1/FireTV?action=dpad_left", down?.path)
        assertEquals("{${q}keyActionType$q:${q}keyDown$q}", down?.body)
        assertEquals("{${q}keyActionType$q:${q}keyUp$q}", up?.body)
        assertNull(FireTvCommands.hold(TvKey.OK, down = true))
        assertNull(FireTvCommands.hold(TvKey.FAST_FORWARD, down = true))
    }

    @Test
    fun onlyHomeHasALongPress() {
        val down = FireTvCommands.longPress(TvKey.HOME, down = true)
        assertEquals("/v1/FireTV?action=home", down?.path)
        assertEquals("{${q}keyActionType$q:${q}keyDown$q}", down?.body)
        assertEquals("{${q}keyActionType$q:${q}keyUp$q}", FireTvCommands.longPress(TvKey.HOME, down = false)?.body)
        assertNull(FireTvCommands.longPress(TvKey.OK, down = true))
        assertEquals("input keyevent --longpress 3", TvCommands.longPress(TvKey.HOME))
    }

    @Test
    fun buttonsTheServiceLacksAreNotSent() {
        assertNull(FireTvCommands.forKey(TvKey.VOLUME_UP))
        assertNull(FireTvCommands.forKey(TvKey.ENTER))
        assertFalse(FireTvCommands.supports(TvKey.MUTE))
        assertTrue(FireTvCommands.supports(TvKey.BACK))
    }

    @Test
    fun jsonStringCannotBreakOutOfTheDocument() {
        assertEquals("$q" + "a$bs$q" + "b$q", FireTvCommands.jsonString("a${q}b"))
        assertEquals("${q}x$bs${bs}y$q", FireTvCommands.jsonString("x${bs}y"))
        assertEquals("${q}line${bs}nbreak$q", FireTvCommands.jsonString("line\nbreak"))
        assertEquals("$q${bs}u0001$q", FireTvCommands.jsonString("\u0001"))
        val body = FireTvCommands.text("hi ${q}there$q").body
        assertEquals("{${q}text$q:${q}hi $bs${q}there$bs$q$q}", body)
    }

    @Test
    fun pinMustBeFourDigits() {
        assertTrue(FireTvCommands.isPin("7064"))
        assertFalse(FireTvCommands.isPin("706"))
        assertFalse(FireTvCommands.isPin("70645"))
        assertFalse(FireTvCommands.isPin("70a4"))
        assertEquals("{${q}pin$q:${q}7064$q}", FireTvCommands.verifyPin("7064").body)
    }

    @Test
    fun tokenIsReadFromTheAnswerOnlyIfSafe() {
        assertEquals("Wj9PP8I", FireTvCommands.tokenFrom("{${q}description$q:${q}Wj9PP8I$q}"))
        assertEquals("abc-1.2_3", FireTvCommands.tokenFrom("{ ${q}description$q : ${q}abc-1.2_3$q }"))
        assertNull(FireTvCommands.tokenFrom("{${q}description$q:${q}bad token${bs}r${bs}nX-Evil: 1$q}"))
        assertNull(FireTvCommands.tokenFrom("{${q}error$q:${q}nope$q}"))
        assertNull(FireTvCommands.tokenFrom(""))
    }

    @Test
    fun onlyHomeNetworkAddressesAreAccepted() {
        assertNotNull(FireTvLink("192.168.0.50", null))
        assertTrue(runCatching { FireTvLink("8.8.8.8", null) }.isFailure)
    }
}
