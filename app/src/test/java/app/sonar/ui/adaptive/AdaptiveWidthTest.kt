package app.sonar.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveWidthTest {
    @Test fun classifiesWidthsAtBothBreakpoints() {
        assertEquals(AdaptiveWidth.Compact, adaptiveWidth(599))
        assertEquals(AdaptiveWidth.Medium, adaptiveWidth(600))
        assertEquals(AdaptiveWidth.Medium, adaptiveWidth(839))
        assertEquals(AdaptiveWidth.Expanded, adaptiveWidth(840))
    }
}
