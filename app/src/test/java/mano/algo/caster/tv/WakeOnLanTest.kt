package mano.algo.caster.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun aMacAddressIsReadInTheCommonWays() {
        assertEquals("aa:bb:cc:dd:ee:ff", WakeOnLan.normalize("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", WakeOnLan.normalize(" aa-bb-cc-dd-ee-ff "))
        assertEquals("aa:bb:cc:dd:ee:ff", WakeOnLan.normalize("aabbccddeeff"))
    }

    @Test
    fun somethingThatIsNotAMacAddressIsRefused() {
        assertNull(WakeOnLan.normalize(""))
        assertNull(WakeOnLan.normalize("aa:bb:cc:dd:ee"))
        assertNull(WakeOnLan.normalize("aa:bb:cc:dd:ee:gg"))
        assertNull(WakeOnLan.normalize("aa:bb-cc:dd:ee:ff")) // two kinds of separator
        assertNull(WakeOnLan.normalize("aabb:ccdd:eeff"))
        assertNull(WakeOnLan.normalize("00:00:00:00:00:00"))
        assertNull(WakeOnLan.normalize("ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun theMagicPacketIsSixFFThenTheMacSixteenTimes() {
        val packet = WakeOnLan.packet("01:02:03:04:05:06")
        assertEquals(102, packet.size)
        assertEquals(List(6) { 0xFF.toByte() }, packet.take(6))
        for (copy in 0 until 16) {
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), packet.slice(6 + copy * 6 until 12 + copy * 6))
        }
    }

    @Test
    fun theSubnetBroadcastIsWorkedOutFromTheNumbersOfAHomeAddress() {
        assertEquals("192.168.0.255", WakeOnLan.subnetBroadcast("192.168.0.50"))
        assertEquals("10.0.4.255", WakeOnLan.subnetBroadcast("10.0.4.7"))
        assertNull(WakeOnLan.subnetBroadcast("8.8.8.8")) // never something on the internet
        assertNull(WakeOnLan.subnetBroadcast("tv.local"))
    }
}
