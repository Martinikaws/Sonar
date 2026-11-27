package app.sonar.audiosource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRefusalPolicyTest {
    @Test fun refusedWhileWaitingFallsBack() {
        assertTrue(shouldFallBackOnKeyRefusal(1, wantsToPlay = true, started = false))
        assertTrue(shouldFallBackOnKeyRefusal(2, wantsToPlay = true, started = false))
    }

    @Test fun otherwiseKeepsSpotify() {
        assertFalse(shouldFallBackOnKeyRefusal(0, wantsToPlay = true, started = false))
        assertFalse(shouldFallBackOnKeyRefusal(1, wantsToPlay = true, started = true))
        assertFalse(shouldFallBackOnKeyRefusal(1, wantsToPlay = false, started = false))
    }
}
