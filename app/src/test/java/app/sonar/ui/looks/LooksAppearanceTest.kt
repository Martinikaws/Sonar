package app.sonar.ui.looks

import androidx.compose.ui.graphics.Color
import app.sonar.data.PreferencesStore.Looks
import app.sonar.data.PreferencesStore.LooksOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LooksAppearanceTest {
    @Test fun parsesOpaqueRgbWithOptionalHash() {
        assertEquals(0xFF7C5CE6.toInt(), looksAccentArgb(" #7c5ce6 "))
        assertEquals(0xFF000000.toInt(), looksAccentArgb("000000"))
    }

    @Test fun rejectsMalformedOrAlphaColors() {
        for (value in listOf("", "abc", "#11223344", "+12345", "1_2345", "##123456", "gg1234")) {
            assertNull(value, looksAccentArgb(value))
        }
    }

    @Test fun coverModeUsesArtworkAndFallsBackWhenItIsUnavailable() {
        val looks = Looks(setOf(LooksOption.CoverColors), "7c5ce6")
        assertEquals(Color.Cyan, resolveLooksSeed(looks, Color.Cyan))
        assertNull(resolveLooksSeed(looks, null))
    }

    @Test fun manualModeUsesThePickedColorOrThemeDefault() {
        assertEquals(Color(0xFF7C5CE6), resolveLooksSeed(Looks(emptySet(), "7c5ce6"), Color.Cyan))
        assertNull(resolveLooksSeed(Looks(emptySet()), Color.Cyan))
    }
}
