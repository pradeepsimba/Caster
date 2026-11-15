package mano.algo.caster.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCommandsTest {
    @Test
    fun keyIsAPlainKeyevent() {
        assertEquals("input keyevent 19", TvCommands.key(TvKey.UP))
    }

    @Test
    fun typingALetterSendsJustThatLetter() {
        assertEquals(listOf("input text 'a'"), TvCommands.edit("", "a"))
        assertEquals(listOf("input text 'c'"), TvCommands.edit("ab", "abc"))
        assertEquals(listOf("input text '%s'"), TvCommands.edit("ab", "ab "))
    }

    @Test
    fun backspaceSendsDeleteKeys() {
        assertEquals(listOf("input keyevent 67"), TvCommands.edit("abc", "ab"))
        assertEquals(listOf("input keyevent 67 67 67"), TvCommands.edit("abc", ""))
    }

    @Test
    fun aReplacedEndingDeletesThenTypes() {
        // The keyboard's autocorrect swapped "teh" for "the".
        assertEquals(listOf("input keyevent 67 67", "input text 'he'"), TvCommands.edit("teh", "the"))
    }

    @Test
    fun nothingChangedSendsNothing() {
        assertEquals(emptyList<String>(), TvCommands.edit("same", "same"))
    }

    @Test
    fun deletingIsCappedSoOneCommandStaysSmall() {
        assertEquals("input keyevent " + List(200) { 67 }.joinToString(" "), TvCommands.keys(TvKey.DELETE, 5000))
    }

    @Test
    fun textWritesSpacesTheWayInputExpects() {
        assertEquals("input text 'hello%sworld'", TvCommands.text("hello world"))
    }

    @Test
    fun textCannotBreakOutOfItsQuotes() {
        // A quote, a semicolon and a command substitution must stay inside one quoted argument.
        assertEquals("input text 'a'\\''b;rm%s-rf%s/\$(id)'", TvCommands.text("a'b;rm -rf /\$(id)"))
    }

    @Test
    fun textDropsWhatInputCannotType() {
        assertEquals("input text 'abc'", TvCommands.text("aéb\u0000c%"))
    }

    @Test
    fun openUrlQuotesTheAddress() {
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'http://192.168.0.5:8080/'",
            TvCommands.openUrl("http://192.168.0.5:8080/"),
        )
        assertEquals("am start -a android.intent.action.VIEW -d 'x'\\''y'", TvCommands.openUrl("x'y"))
    }

    @Test
    fun onlyHomeNetworkAddressesAreAccepted() {
        assertTrue(TvCommands.isLocalIpv4("192.168.0.50"))
        assertTrue(TvCommands.isLocalIpv4("10.0.0.7"))
        assertTrue(TvCommands.isLocalIpv4("172.16.4.1"))
        assertTrue(TvCommands.isLocalIpv4("169.254.1.1"))
        assertFalse(TvCommands.isLocalIpv4("8.8.8.8"))
        assertFalse(TvCommands.isLocalIpv4("172.32.0.1"))
        assertFalse(TvCommands.isLocalIpv4("127.0.0.1"))
    }

    @Test
    fun targetUsesTheDefaultPortUnlessOneIsGiven() {
        assertEquals(TvCommands.Target("192.168.0.50", 5555), TvCommands.parseTarget(" 192.168.0.50 "))
        assertEquals(TvCommands.Target("10.0.2.2", 5559), TvCommands.parseTarget("10.0.2.2:5559"))
        assertEquals(null, TvCommands.parseTarget("8.8.8.8:5555"))
        assertEquals(null, TvCommands.parseTarget("192.168.0.50:0"))
        assertEquals(null, TvCommands.parseTarget("192.168.0.50:70000"))
        assertEquals(null, TvCommands.parseTarget("192.168.0.50:5555;reboot"))
    }

    @Test
    fun malformedAddressesAreRejected() {
        assertFalse(TvCommands.isLocalIpv4(""))
        assertFalse(TvCommands.isLocalIpv4("tv.local"))
        assertFalse(TvCommands.isLocalIpv4("192.168.0"))
        assertFalse(TvCommands.isLocalIpv4("192.168.0.300"))
        assertFalse(TvCommands.isLocalIpv4("192.168.0.5; reboot"))
        assertFalse(TvCommands.isLocalIpv4("http://192.168.0.5"))
    }
}
