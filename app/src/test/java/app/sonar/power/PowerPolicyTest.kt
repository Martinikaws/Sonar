package app.sonar.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {
    @Test fun batterySaverNeedsLowBattery() {
        assertTrue(shouldConserve(batterySaver = true, dataSaver = false, lowBattery = true, cellular = false))
        assertFalse(shouldConserve(batterySaver = true, dataSaver = false, lowBattery = false, cellular = true))
    }

    @Test fun dataSaverNeedsCellular() {
        assertTrue(shouldConserve(batterySaver = false, dataSaver = true, lowBattery = false, cellular = true))
        assertFalse(shouldConserve(batterySaver = false, dataSaver = true, lowBattery = true, cellular = false))
    }

    @Test fun disabledSaversIgnoreBothTriggers() {
        assertFalse(shouldConserve(batterySaver = false, dataSaver = false, lowBattery = true, cellular = true))
    }
}
