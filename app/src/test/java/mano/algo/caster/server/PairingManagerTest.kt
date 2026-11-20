package mano.algo.caster.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class PairingManagerTest {
    @Before
    fun clean() {
        PairingManager.reset()
    }

    @Test
    fun theSameScreenAskingAgainTakesThePlaceOfItsEarlierRequest() {
        val first = PairingManager.request("Chrome", "192.168.0.7") as RequestResult.Created
        val second = PairingManager.request("Chrome", "192.168.0.7") as RequestResult.Created
        assertNotEquals(first.request.id, second.request.id)
        assertEquals(PairStatus.Unknown, PairingManager.status(first.request.id)) // the old one is gone
        assertEquals(PairStatus.Pending, PairingManager.status(second.request.id))
    }

    @Test
    fun anotherScreenIsTurnedAwayWhileOneIsWaiting() {
        PairingManager.request("Chrome", "192.168.0.7")
        assertEquals(RequestResult.Busy, PairingManager.request("Firefox", "192.168.0.8"))
    }

    @Test
    fun aRequestWithoutAnAddressNeverReplacesAnother() {
        PairingManager.request("Chrome")
        assertEquals(RequestResult.Busy, PairingManager.request("Chrome"))
    }

    @Test
    fun codesAreFourPlainDigits() {
        val created = PairingManager.request("Chrome", "10.0.0.2") as RequestResult.Created
        assertEquals(true, Regex("[0-9]{4}").matches(created.request.code))
    }
}
