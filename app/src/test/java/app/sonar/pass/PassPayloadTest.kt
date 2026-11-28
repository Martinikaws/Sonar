package app.sonar.pass

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
