package mano.algo.caster.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class PairingManagerTest {
    @Before
    fun clean() {
        PairingManager.reset()
        PairingManager.clearKnown()
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
    fun anApprovedScreenStaysListedAfterItIsGone() {
        PairingManager.clearKnown()
        PairingManager.request("Living room TV", "10.0.0.2")
        assertEquals(true, PairingManager.approve())
        assertEquals(listOf("Living room TV"), PairingManager.known.value.map { it.label })
        PairingManager.reset() // sharing stopped: the connection is gone, the entry stays
        assertEquals(emptyList<PairedDevice>(), PairingManager.devices.value)
        assertEquals(listOf("Living room TV"), PairingManager.known.value.map { it.label })
        // The same screen approved again is one entry, not two; another one goes in front.
        PairingManager.request("Living room TV", "10.0.0.2"); PairingManager.approve()
        PairingManager.reset()
        PairingManager.request("Laptop", "10.0.0.3"); PairingManager.approve()
        assertEquals(listOf("Laptop", "Living room TV"), PairingManager.known.value.map { it.label })
        PairingManager.clearKnown()
        assertEquals(emptyList<KnownDevice>(), PairingManager.known.value)
    }

    @Test
    fun twoScreensWithTheSameNameStayTwoScreens() {
        PairingManager.clearKnown()
        PairingManager.request("Fire TV", "10.0.0.2", "aaaa1111bbbb"); PairingManager.approve()
        PairingManager.reset()
        PairingManager.request("Fire TV", "10.0.0.3", "cccc2222dddd"); PairingManager.approve()
        assertEquals(listOf("cccc2222dddd", "aaaa1111bbbb"), PairingManager.known.value.map { it.clientId })
        assertEquals(listOf("Fire TV", "Fire TV"), PairingManager.known.value.map { it.label })
        // The first one coming back is the same screen, not a third.
        PairingManager.reset()
        PairingManager.request("Fire TV", "10.0.0.2", "aaaa1111bbbb"); PairingManager.approve()
        assertEquals(listOf("aaaa1111bbbb", "cccc2222dddd"), PairingManager.known.value.map { it.clientId })
        PairingManager.clearKnown()
    }

    @Test
    fun theSameScreenApprovedAgainHasOneAccessOnly() {
        PairingManager.request("Fire TV", "10.0.0.2", "eeee3333ffff")
        PairingManager.approve()
        PairingManager.request("Fire TV", "10.0.0.2", "eeee3333ffff")
        PairingManager.approve()
        assertEquals(1, PairingManager.devices.value.count { it.clientId == "eeee3333ffff" })
        PairingManager.clearKnown()
    }

    @Test
    fun codesAreFourPlainDigits() {
        val created = PairingManager.request("Chrome", "10.0.0.2") as RequestResult.Created
        assertEquals(true, Regex("[0-9]{4}").matches(created.request.code))
    }

    private fun approveWithToken(label: String, clientId: String, durationMs: Long = 0L): String {
        val created = PairingManager.request(label, "10.0.0.9", clientId) as RequestResult.Created
        assertEquals(true, PairingManager.approve(durationMs))
        return (PairingManager.status(created.request.id) as PairStatus.Approved).token
    }

    @Test
    fun aLimitedScreenSeesOnlyItsOwnVideosAndTheLimitIsKept() {
        PairingManager.clearKnown()
        val token = approveWithToken("Kids TV", "kids1111kids")
        assertEquals(true, PairingManager.allows(token, 42L)) // no limit yet
        PairingManager.setLimit("kids1111kids", setOf(1L, 2L))
        assertEquals(true, PairingManager.allows(token, 1L))
        assertEquals(false, PairingManager.allows(token, 42L))
        assertEquals(true, PairingManager.devices.value.single().limited)
        // Approved again later: the limit is still there.
        PairingManager.reset()
        val again = approveWithToken("Kids TV", "kids1111kids")
        assertEquals(false, PairingManager.allows(again, 42L))
        PairingManager.setLimit("kids1111kids", null)
        assertEquals(true, PairingManager.allows(again, 42L))
        assertEquals(false, PairingManager.allows("not-a-token", 1L))
        assertEquals(false, PairingManager.allows(null, 1L))
    }

    @Test
    fun anEmptyLimitMeansNoVideosAtAll() {
        PairingManager.clearKnown()
        val token = approveWithToken("Guest", "guest2222guest")
        PairingManager.setLimit("guest2222guest", emptySet())
        assertEquals(false, PairingManager.allows(token, 1L))
    }

    @Test
    fun theLimitIsWrittenAndReadBackTheSameWay() {
        assertEquals("", PairingManager.formatLimit(null))
        assertEquals("none", PairingManager.formatLimit(emptySet()))
        assertEquals(null, PairingManager.parseLimit(""))
        assertEquals(emptySet<Long>(), PairingManager.parseLimit("none"))
        assertEquals(setOf(3L, 9L), PairingManager.parseLimit(PairingManager.formatLimit(setOf(3L, 9L))))
    }

    @Test
    fun aScreenWithTimeRunsOutAndIsSignedOut() {
        PairingManager.clearKnown()
        val timed = approveWithToken("Guest laptop", "laptop3333lap", durationMs = 500L)
        assertEquals(true, PairingManager.isValid(timed))
        assertEquals(true, PairingManager.devices.value.single().expiresAt > 0L)
        Thread.sleep(700)
        assertEquals(false, PairingManager.isValid(timed))
        assertEquals(emptyList<PairedDevice>(), PairingManager.devices.value)
    }

    @Test
    fun aScreenApprovedWithoutTimeNeverRunsOut() {
        PairingManager.clearKnown()
        val token = approveWithToken("Living room", "living4444liv")
        assertEquals(0L, PairingManager.devices.value.single().expiresAt)
        PairingManager.expireIfDue()
        assertEquals(true, PairingManager.isValid(token))
    }

    @Test
    fun aLimitedScreenIsNeverDroppedToMakeRoomInTheList() {
        val limited = KnownDevice("kids1111kids", "Kids TV", 1L, setOf(1L))
        val others = (1..15).map { KnownDevice("other%02d".format(it) + "xxxxxx", "TV $it", 100L + it) }
        val kept = PairingManager.trimKnown(others + limited)
        assertEquals(true, kept.any { it.clientId == "kids1111kids" })
        assertEquals(10, kept.count { it.limit == null }) // the plain ones are still cut to ten
    }

    @Test
    fun aLimitedScreenIsNotGivenPhotosOrMusic() {
        PairingManager.clearKnown()
        val token = approveWithToken("Kids TV", "kids5555kids")
        assertEquals(false, PairingManager.isLimited(token))
        PairingManager.setLimit("kids5555kids", setOf(1L))
        assertEquals(true, PairingManager.isLimited(token))
        assertEquals(false, PairingManager.isLimited(null))
    }

    @Test
    fun aScreenThatComesBackWithANewIdButTheNameOfALimitedScreenIsLimited() {
        PairingManager.clearKnown()
        approveWithToken("Fire TV (Silk)", "idold1111idold")
        PairingManager.setLimit("idold1111idold", setOf(1L))
        PairingManager.reset()
        val again = approveWithToken("Fire TV (Silk)", "idnew2222idnew") // its browser data was cleared
        assertEquals(false, PairingManager.allows(again, 42L))
        assertEquals(setOf(1L), PairingManager.limitOf("idnew2222idnew"))
        PairingManager.reset()
        val other = approveWithToken("Laptop", "other3333other")
        assertEquals(true, PairingManager.allows(other, 42L)) // another name: not limited
    }

    @Test
    fun revokingByTokenDisconnectsTheScreen() {
        PairingManager.clearKnown()
        val token = approveWithToken("Web Browser", "browser1234")
        assertEquals(true, PairingManager.isValid(token))
        assertEquals(1, PairingManager.devices.value.size)
        PairingManager.revokeByToken(token)
        assertEquals(false, PairingManager.isValid(token))
        assertEquals(0, PairingManager.devices.value.size)
    }
}
