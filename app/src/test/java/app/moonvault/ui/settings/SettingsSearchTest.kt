package app.moonvault.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    private val words = mapOf(
        1 to "Crossfade",
        2 to "Trim silence",
        3 to "Thème sombre",
        4 to "Pure black",
        5 to "A curve for each pair of headphones",
    )
    private val text: (Int) -> String = { words[it].orEmpty() }

    private val crossfade = SettingsEntry("crossfade", 1, null, listOf(2), SettingsGroup.LISTENING)
    private val theme = SettingsEntry("theme", 3, null, listOf(4), SettingsGroup.APPEARANCE)
    private val equalizer = SettingsEntry(
        "equalizer", 6, 5, emptyList(), SettingsGroup.SOUND, SettingsAction.Open(SettingsPage.Equalizer),
    )
    private val all = listOf(crossfade, theme, equalizer)

    private fun ids(query: String) = matchSettings(all, query, text).map { it.id }

    @Test
    fun blankQueryFindsEverything() {
        assertEquals(listOf("crossfade", "theme", "equalizer"), ids(""))
        assertEquals(listOf("crossfade", "theme", "equalizer"), ids("   "))
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
