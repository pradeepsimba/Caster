package mano.algo.caster.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    @Test
    fun aPinIsFourToEightDigits() {
        assertTrue(PinLock.isValid("1234"))
        assertTrue(PinLock.isValid("12345678"))
        assertFalse(PinLock.isValid("123"))
        assertFalse(PinLock.isValid("123456789"))
        assertFalse(PinLock.isValid("12a4"))
        assertFalse(PinLock.isValid("١٢٣٤")) // other scripts' digits are not what the pad types
        assertFalse(PinLock.isValid(""))
    }

    @Test
    fun theSameSaltAndPinGiveTheSameHashAndAnythingElseDoesNot() {
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(PinLock.hash("1234", salt), PinLock.hash("1234", salt))
        assertFalse(PinLock.hash("1234", salt).contentEquals(PinLock.hash("1235", salt)))
        assertFalse(PinLock.hash("1234", salt).contentEquals(PinLock.hash("1234", ByteArray(16) { 9 })))
        assertEquals(32, PinLock.hash("1234", salt).size)
    }

    @Test
    fun theHashDoesNotContainThePin() {
        val hash = PinLock.hash("4821", ByteArray(16))
        assertNotEquals("4821", String(hash))
    }
}
