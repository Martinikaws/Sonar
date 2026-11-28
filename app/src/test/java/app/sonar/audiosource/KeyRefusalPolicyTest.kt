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

    @Test fun refusedAccountSkipsSpotifyAfterOneRealAttempt() {
        assertTrue(shouldSkipRefusedSpotify(accountRefused = true, webPlayerConfigured = false, refusedThisRun = true))
        // The first song of a run still asks Spotify, which is how a lifted refusal is noticed.
        assertFalse(shouldSkipRefusedSpotify(accountRefused = true, webPlayerConfigured = false, refusedThisRun = false))
    }

    @Test fun premiumOrWebPlayerKeepsSpotify() {
        assertFalse(shouldSkipRefusedSpotify(accountRefused = false, webPlayerConfigured = false, refusedThisRun = true))
        assertFalse(shouldSkipRefusedSpotify(accountRefused = true, webPlayerConfigured = true, refusedThisRun = true))
    }
}
