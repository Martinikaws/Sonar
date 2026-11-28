package app.sonar.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AutoBrowseNodesTest {
    @Test
    fun rootAndLibraryExposeStableRoutes() {
        assertEquals(
            listOf("sq/library", "sq/playlists", "sq/radio", "sq/recent"),
            AutoBrowseNodes.children(AutoBrowseNodes.ROOT),
        )
        assertEquals(
            listOf("sq/playlists", "sq/downloads"),
            AutoBrowseNodes.children(AutoBrowseNodes.LIBRARY),
        )
        assertEquals(emptyList<String>(), AutoBrowseNodes.children("unknown"))
    }

    @Test
    fun offlineShelvesLeadToDownloadsAndPermitDirectRecentLookup() {
        val downloads = listOf(AutoBrowseNodes.DOWNLOADS)
        assertEquals(downloads, AutoBrowseNodes.children(AutoBrowseNodes.ROOT, offline = true))
        assertEquals(downloads, AutoBrowseNodes.children(AutoBrowseNodes.LIBRARY, offline = true))
        assertTrue(AutoBrowseNodes.isOfflineNode(AutoBrowseNodes.RECENT))
        assertFalse(AutoBrowseNodes.isOfflineNode(AutoBrowseNodes.PLAYLISTS))
        assertFalse(AutoBrowseNodes.isOfflineNode(AutoBrowseNodes.RADIO))
        assertFalse(AutoBrowseNodes.isOfflineNode("spotify:playlist:example"))
    }

    @Test
    fun trackIdPreservesItsQueueEvenWithSlashesInSearch() {
        val parent = "sq/q/SPOTIFY/AC/DC"
        val uri = "spotify:track:example"
        assertEquals(parent to uri, AutoBrowseNodes.splitTrackId(AutoBrowseNodes.trackId(parent, uri)))
        assertNull(AutoBrowseNodes.splitTrackId(uri))
        assertNull(AutoBrowseNodes.splitTrackId("sq/t/sq/recent/"))
    }

    @Test
    fun pagingHandlesShortFinalPagesAndLargeLegacyPageSizes() {
        val items = listOf("one", "two", "three")
        assertEquals(items, AutoBrowseNodes.page(items, 0, Int.MAX_VALUE))
        assertEquals(listOf("three"), AutoBrowseNodes.page(items, 1, 2))
        assertEquals(emptyList<String>(), AutoBrowseNodes.page(items, 2, Int.MAX_VALUE))
        assertEquals(emptyList<String>(), AutoBrowseNodes.page(items, -1, 2))
    }
}
