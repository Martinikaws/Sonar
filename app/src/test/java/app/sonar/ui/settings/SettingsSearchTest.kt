package app.sonar.ui.settings

import app.sonar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val words = mapOf(
        1 to "Crossfade",
        2 to "Trim silence",
        3 to "Thème sombre",
        4 to "Pure black",
        5 to "A curve for each pair of headphones",
        7 to "Wi-Fi only",
        SettingsGroup.SOUND.title to "Sound settings",
        SettingsGroup.SOUND.summary to "Playback options",
    )
    private val text: (Int) -> String = { words[it].orEmpty() }

    private val crossfade = SettingsEntry("crossfade", 1, null, listOf(2), SettingsGroup.LISTENING)
    private val theme = SettingsEntry("theme", 3, null, listOf(4), SettingsGroup.APPEARANCE)
    private val equalizer = SettingsEntry(
        "equalizer", 6, 5, emptyList(), SettingsGroup.SOUND, SettingsAction.Open(SettingsPage.Equalizer),
    )
    private val wifi = SettingsEntry("wifi", 7, null, emptyList(), SettingsGroup.SOUND)
    private val all = listOf(crossfade, theme, equalizer, wifi)

    private fun ids(query: String) = matchSettings(all, query, text).map { it.id }

    @Test
    fun blankQueryFindsEverything() {
        assertEquals(listOf("crossfade", "theme", "equalizer", "wifi"), ids(""))
        assertEquals(listOf("crossfade", "theme", "equalizer", "wifi"), ids(" ... "))
    }

    @Test
    fun matchesTitleSubtitleAndKeywordsIgnoringCase() {
        assertEquals(listOf("crossfade"), ids("CROSS"))
        assertEquals(listOf("crossfade"), ids("silence"))
        assertEquals(listOf("equalizer"), ids("headphones"))
        assertEquals(listOf("theme"), ids("black"))
    }

    @Test
    fun ignoresAccentsAndNeedsEveryWord() {
        assertEquals(listOf("theme"), ids("theme"))
        assertEquals(listOf("theme"), ids("sombre pure"))
        assertEquals(emptyList<String>(), ids("sombre crossfade"))
    }

    @Test
    fun noMatchIsEmpty() {
        assertEquals(emptyList<String>(), ids("bluetooth"))
    }

    @Test
    fun punctuationSplitsWordsAndMatchesOnlyPrefixes() {
        assertEquals(listOf("wifi"), ids("Wi Fi"))
        assertEquals(listOf("wifi"), ids("Wi–Fi, on"))
        assertEquals(emptyList<String>(), ids("fade"))
    }

    @Test
    fun groupTitleMatchesWholeGroupOnItsOwn() {
        assertEquals(listOf("equalizer", "wifi"), ids("sound sett"))
        assertEquals(emptyList<String>(), ids("sound headphones"))
        assertEquals(emptyList<String>(), ids("playback options"))
    }

    @Test
    fun glassKeywordsFollowControlVisibility() {
        fun keywords(supported: Boolean, liquid: Boolean, ios27: Boolean, tinted: Boolean) =
            glassSearchKeywords(supported, liquid, ios27, tinted, emptyList())

        assertTrue(keywords(false, true, true, true).isEmpty())
        assertFalse(R.string.glass_dispersion in keywords(true, false, true, false))
        assertTrue(R.string.glass_dispersion in keywords(true, true, false, false))
        assertFalse(R.string.glass_frost in keywords(true, true, false, false))
        assertTrue(R.string.glass_frost in keywords(true, true, true, false))
        assertFalse(R.string.glass_tint_strength in keywords(true, true, true, false))
        assertTrue(R.string.glass_tint_strength in keywords(true, false, false, true))
    }

    @Test
    fun subpageMatchesLocalizedKeyword() {
        val found = matchSettings(SETTINGS_INDEX, "préamp") {
            if (it == R.string.eq_preamp) "Préamplification" else ""
        }
        assertEquals(listOf("equalizer"), found.map { it.id })
    }

    @Test
    fun indexIdsAreUniqueAndEveryGroupHasEntries() {
        val idList = SETTINGS_INDEX.map { it.id }
        assertEquals(idList.size, idList.toSet().size)
        assertEquals(SettingsGroup.entries.toSet(), SETTINGS_INDEX.map { it.group }.toSet())
    }

    @Test
    fun everyPageOpensFromExactlyOneEntry() {
        val opened = SETTINGS_INDEX.mapNotNull { (it.action as? SettingsAction.Open)?.page }
        assertEquals(SettingsPage.entries.toList(), opened.sorted())
    }
}
