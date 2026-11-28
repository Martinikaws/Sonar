package app.sonar.pass

import java.net.Inet4Address
import java.net.InetAddress
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PassPayloadTest {
    private val passphrase = "testCode1234"
    private val payload = PassPayload("SAPISID=test-cookie", "visitor", "channel", "Caffè", "page")

    @Test
    fun sessionRoundTrip() {
        val frame = payload.encode(passphrase)
        val decoded = PassPayload.decode(frame, passphrase)
        assertEquals(payload.cookie, decoded.cookie)
        assertEquals(payload.visitorData, decoded.visitorData)
        assertEquals(payload.dataSyncId, decoded.dataSyncId)
        assertEquals(payload.name, decoded.name)
        assertEquals(payload.pageId, decoded.pageId)
        assertFalse(frame.decodeToString().contains(payload.cookie))
    }

    @Test
    fun wrongPassphraseAndTamperingAreRejected() {
        val frame = payload.encode(passphrase)
        assertThrows(AEADBadTagException::class.java) {
            PassPayload.decode(frame, "wrongCode123")
        }
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) {
            PassPayload.decode(frame, passphrase)
        }
    }

    @Test
    fun rejectsEmptyAndOversizedFramesAndCountsUtf8BytesWhenEncoding() {
        for (size in listOf(0, PASS_MAX_FRAME + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                PassPayload.decode(ByteArray(size), passphrase)
            }
        }
        val tooLarge = PassPayload("fixture=1", "", "", "è".repeat(PASS_MAX_FRAME / 2), null)
        assertThrows(IllegalArgumentException::class.java) { tooLarge.encode(passphrase) }
    }

    @Test
    fun rejectsEncryptedSessionsWithInvalidCookies() {
        for (cookie in listOf("", "   ", "SAPISID=test\r\ncookie", "SAPISID=test\u0000cookie")) {
            val frame = PassPayload(cookie, "", "", "", null).encode(passphrase)
            assertThrows(IllegalArgumentException::class.java) {
                PassPayload.decode(frame, passphrase)
            }
        }
    }

    @Test
    fun invitationLinksRequireAnUnpaddedTokenWithinTheLengthLimit() {
        val maxToken = "a".repeat(512 - PASS_LINK_PREFIX.length)
        assertTrue(PassInvite.accepts(PASS_LINK_PREFIX + maxToken))
        for (token in listOf("", "abc=", "abc?x", "abc#x", "caffè", maxToken + "a")) {
            assertFalse(PassInvite.accepts(PASS_LINK_PREFIX + token))
        }
    }

    @Test
    fun invitationAddressesRequireFourAsciiOctets() {
        assertEquals("192.168.1.255", PassTransport.parseAddress("192.168.1.255").hostAddress)
        for (host in listOf("", "localhost", "192.168.1", "192.168.1.256", "192.168.1.-1", "192.168.1.１", "::1")) {
            assertThrows(IllegalArgumentException::class.java) { PassTransport.parseAddress(host) }
        }
    }

    @Test
    fun invitationPeerMustBePrivateIpv4WithinTheLocalPrefix() {
        val local = PassTransport.parseAddress("192.168.1.130") as Inet4Address
        assertTrue(PassTransport.sameSubnet(local, PassTransport.parseAddress("192.168.1.254"), 25))
        assertFalse(PassTransport.sameSubnet(local, PassTransport.parseAddress("192.168.1.127"), 25))
        assertTrue(PassTransport.sameSubnet(local, local, 32))
        assertFalse(PassTransport.sameSubnet(local, PassTransport.parseAddress("203.0.113.1"), 1))
        assertFalse(PassTransport.sameSubnet(local, InetAddress.getByAddress(ByteArray(16)), 24))
    }

    @Test
    fun invitationRoundTrip() {
        val invite = PassInvite("192.168.1.7", 12345, encodeBase64(ByteArray(PASS_KEY_BYTES)))
        val link = invite.encode(passphrase)
        assertTrue(PassInvite.accepts(link))
        assertFalse(PassInvite.accepts(link + "/extra"))
        assertFalse(link.contains(invite.host))
        assertFalse(link.contains(invite.key))
        val decoded = PassInvite.decode(link, passphrase)
        assertEquals(invite.host, decoded.host)
        assertEquals(invite.port, decoded.port)
        assertEquals(invite.key, decoded.key)
    }
}
