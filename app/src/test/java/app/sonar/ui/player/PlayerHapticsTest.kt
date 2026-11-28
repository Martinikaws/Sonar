package app.sonar.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHapticsTest {
    @Test
    fun scrubTicksOnStartAndWhenCrossingAnIntervalInEitherDirection() {
        assertTrue(crossesScrubTick(null, 0.20f))
        assertFalse(crossesScrubTick(0.20f, 0.24f))
        assertTrue(crossesScrubTick(0.24f, 0.25f))
        assertTrue(crossesScrubTick(0.25f, 0.24f))
        assertFalse(crossesScrubTick(1f, 1f))
    }
}
