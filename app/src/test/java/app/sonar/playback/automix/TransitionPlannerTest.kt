package app.sonar.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TransitionPlannerTest {
    @Test
    fun alignsTwoBarsAtCompatibleTempos() {
        val timing = TransitionPlanner.plan(
            20_000, 30_000, 5_000, 19_000, 500,
            longArrayOf(15_000, 17_000, 19_000),
            longArrayOf(500, 2_600, 4_700), true,
        )
        assertEquals(TransitionTiming(15_000, 500, 4_000, 1.05f), timing)
    }

    @Test
    fun usesCueCenteredFadeWhenTemposDifferOrMatchingIsOff() {
        val outgoing = longArrayOf(15_000, 17_000, 19_000)
        val incoming = longArrayOf(500, 3_000, 5_500)
        val expected = TransitionTiming(15_000, 0, 5_000)
        assertEquals(expected, TransitionPlanner.plan(20_000, 30_000, 5_000, 19_000, 500, outgoing, incoming, true))
        assertEquals(expected, TransitionPlanner.plan(20_000, 30_000, 5_000, 19_000, 500, outgoing, outgoing, false))
    }

    @Test
    fun boundsTheOverlapAndKeepsGaplessWhenFadeIsOff() {
        val empty = longArrayOf()
        assertEquals(
            TransitionTiming(1_500, 0, 1_500),
            TransitionPlanner.plan(3_000, 1_500, 6_000, 3_000, 0, empty, empty, true),
        )
        assertNull(TransitionPlanner.plan(3_000, 1_500, 0, 3_000, 0, empty, empty, true))
        assertFalse(TransitionTiming(1, 0, 1_500, 1.05f).fits(3_000, 1_500))
        assertEquals(2_000L, TransitionPlanner.snap(2_900, longArrayOf(2_000, 3_000), 1L..2_900L))
    }
}
