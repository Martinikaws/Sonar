package app.sonar.library

import app.sonar.data.movePinnedPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedPlaylistOrderTest {
    @Test
    fun movesVisiblePinsInBothDirectionsWhileKeepingAbsentPinsInPlace() {
        val original = listOf("a", "absent", "b", "c")
        val visible = setOf("a", "b", "c")
        val moved = original.movePinnedPlaylist(visible, "a", "c")
        assertEquals(listOf("b", "absent", "c", "a"), moved)
        assertEquals(original, moved.movePinnedPlaylist(visible, "a", "b"))
        assertEquals(original, original.movePinnedPlaylist(visible, "a", "absent"))
        assertEquals(original, original.movePinnedPlaylist(visible, "removed", "a"))
    }
}
