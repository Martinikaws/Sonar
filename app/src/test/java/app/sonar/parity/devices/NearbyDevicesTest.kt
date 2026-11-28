package app.sonar.parity.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyDevicesTest {
    @Test
    fun validatesTheLanInviteBeforeItCanReachJamJoin() {
        assertEquals("AbCd1234", nearbyJamToken("AbCd1234"))
        assertNull(nearbyJamToken("AbCd1234/leave"))
        assertNull(nearbyJamToken("AbCd1234?device=other"))
        assertNull(nearbyJamToken("a".repeat(129)))
        assertNull(nearbyJamToken("short"))
        assertEquals("Living room", nearbyJamName("  Living\u202E room\n "))
    }

    @Test
    fun matchesCastByConnectIdOrUnambiguousCastTypedName() {
        val group = CastReceiver("group-id", "Living Room", group = true)
        val other = group.copy(id = "other-id")
        val names = listOf(group.name)
        assertEquals(group, matchingCastReceiver(castConnectId(group.name), "Display", "speaker", listOf(group), names))
        assertEquals(group, matchingCastReceiver("different-id", group.name, "CastAudio", listOf(group), names))
        assertNull(matchingCastReceiver("different-id", group.name, "speaker", listOf(group), names))
        assertNull(matchingCastReceiver("different-id", group.name, "CastAudio", listOf(group, other), names))
        assertNull(matchingCastReceiver("different-id", group.name, "CastAudio", listOf(group), names + names))
    }
}
