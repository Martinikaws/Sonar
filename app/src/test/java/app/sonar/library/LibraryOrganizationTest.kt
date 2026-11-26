package app.sonar.library

import app.sonar.data.CatalogPlaylist
import app.sonar.data.CatalogTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOrganizationTest {
    @Test
    fun customOrderShowsNewPlaylistsFirstAndIgnoresAbsentUris() {
        val catalog = listOf("old-a", "new-a", "old-b", "new-b")
        val arranged = catalog.inCustomLibraryOrder(listOf("old-b", "absent", "old-a")) { it }
        assertEquals(listOf("new-a", "new-b", "old-b", "old-a"), arranged)
    }

    @Test
    fun reorderPreservesAbsentPositionsAndUsesLatestSavedOrder() {
        val initial = LibraryOrganization().reorder(null, listOf("a", "b", "c", "d"), "a", "d")
        val partial = listOf("b", "c", "a")
        val first = initial.reorder(null, partial, "a", "b")
        assertEquals(listOf("a", "b", "d", "c"), first.customOrder(null))
        val second = first.reorder(null, partial, "a", "b")
        assertEquals(listOf("b", "a", "d", "c"), second.customOrder(null))
        assertEquals(second, second.reorder(null, partial, "a", "d"))
    }

    @Test
    fun folderOrderIsIndependentAndDeletionReturnsPlaylistsToRoot() {
        val root = listOf("a", "b", "c")
        val initial = LibraryOrganization(folders = listOf(LibraryFolder("folder", "Original")))
            .reorder(null, root, "c", "a")
            .moveToFolder("a", "folder")
            .moveToFolder("b", "folder")
        assertFalse(initial.atLevel("a", null))
        assertTrue(initial.atLevel("a", "folder"))
        assertEquals(initial, initial.moveToFolder("c", "unknown"))
        assertEquals(initial, initial.reorder("folder", root, "a", "c"))

        val arranged = initial.reorder("folder", root, "b", "a").renameFolder("folder", " Renamed ")
        assertEquals(listOf("c"), arranged.customOrder(null))
        assertEquals(listOf("b", "a"), arranged.customOrder("folder"))
        assertEquals("Renamed", arranged.folders.single().name)

        val deleted = arranged.deleteFolder("folder")
        assertTrue(deleted.folders.isEmpty())
        assertTrue(deleted.atLevel("a", null))
        assertTrue(deleted.atLevel("b", null))
        assertTrue(deleted.customOrder("folder").isEmpty())
        assertEquals(listOf("c"), deleted.customOrder(null))
    }

    @Test
    fun fixedAndPinnedEntriesStayInRoot() {
        val entries = listOf(CatalogPlaylist("a", "A"), CatalogPlaylist("b", "B"))
        val organization = LibraryOrganization(folders = listOf(LibraryFolder("folder", "Folder")))
            .moveToFolder("a", "folder")
            .moveToFolder("b", "folder")
        assertEquals(listOf(entries[1]), entries.atLibraryLevel(organization, null, setOf("a")))
        assertEquals(listOf(entries[0]), entries.atLibraryLevel(organization, "folder", setOf("a")))
    }

    @Test
    fun creatorSortUsesPlaylistNameToBreakCreatorTies() {
        val playlists = listOf(
            CatalogPlaylist("1", "Zulu", subtitle = "alice"),
            CatalogPlaylist("2", "Beta", subtitle = "Charlie"),
            CatalogPlaylist("3", "Alpha", subtitle = "Alice"),
        )
        val sorted = playlists.inCreatorOrder { it.subtitle.orEmpty() }
        assertEquals(listOf("3", "1", "2"), sorted.map { it.uri })
    }

    @Test
    fun hiddenSongsAreScopedAndCanBeRestored() {
        val hidden = LibraryOrganization()
            .setHidden("playlist-a", "track", true)
            .setHidden("playlist-a", "track", true)
        assertEquals(setOf("track"), hidden.hiddenTracks["playlist-a"])
        assertTrue(hidden.hiddenTracks["playlist-b"].orEmpty().isEmpty())
        assertTrue(hidden.setHidden("playlist-a", "track", false).hiddenTracks.isEmpty())
    }

    @Test
    fun snapshotRoundTripsAndSupportsMissingFields() {
        val snapshot = LibraryOrganization(folders = listOf(LibraryFolder("folder", "Reading, studying")))
            .moveToFolder("spotify:playlist:a", "folder")
            .reorder(null, listOf("b", "c"), "b", "c")
            .setHidden("spotify:playlist:a", "spotify:track:b", true)
        val serializer = LibraryOrganization.serializer()
        assertEquals(snapshot, Json.decodeFromString(serializer, Json.encodeToString(serializer, snapshot)))
        assertEquals(LibraryOrganization(), Json.decodeFromString(serializer, "{}"))
    }

    @Test
    fun trackOrderSupportsDescendingAndHidingRetainsPlaybackIndexes() {
        val one = CatalogTrack("1", "Zulu", "A")
        val two = CatalogTrack("2", "Alpha", "B")
        val three = CatalogTrack("3", "Beta", "C")
        val tracks = listOf(one, two, three, two)
        assertEquals(PlaylistTrackSort.ORIGINAL, PlaylistTrackSort.fromPreference("unknown"))
        assertEquals(PlaylistTrackSort.TITLE, PlaylistTrackSort.fromPreference("TITLE"))
        assertEquals(listOf(two, three, two, one), tracks.visiblePlaylistTracks(PlaylistTrackSort.ORIGINAL, true))
        assertEquals(listOf(one, three), tracks.visiblePlaylistTracks(PlaylistTrackSort.TITLE, true, setOf("2")))
        assertEquals(listOf(three, one), tracks.visiblePlaylistTracks(PlaylistTrackSort.TITLE, false, setOf("2")))
        assertEquals(listOf(three), tracks.visiblePlaylistTracks(PlaylistTrackSort.ARTIST, false) { it.artist == "C" })
        assertEquals(listOf(1, 3), tracks.visiblePlaylistTrackIndexes(setOf("1", "3")))
    }
}
