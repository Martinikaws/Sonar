package app.sonar.library.importlink

import app.sonar.data.CatalogTrack
import app.sonar.library.LocalPlaylists
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.pages.PlaylistPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportedPlaylistTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun parsesPlayableYouTubeRowWithoutPlaylistSetVideoId() {
        val renderer = Json { explicitNulls = false }.decodeFromString<MusicResponsiveListItemRenderer>(
            """
            {
              "playlistItemData": {"videoId": "dQw4w9WgXcQ"},
              "flexColumns": [
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Track"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Artist"}]}}}
              ],
              "thumbnail": {
                "musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://example.com/track.jpg"}]}}
              }
            }
            """.trimIndent(),
        )

        val song = checkNotNull(PlaylistPage.fromMusicResponsiveListItemRenderer(renderer))
        assertEquals("dQw4w9WgXcQ", song.id)
        assertNull(song.setVideoId)
    }

    @Test
    fun createsThePlaylistWithUniqueTracksInSourceOrder() {
        val first = CatalogTrack(uri = "spotify:track:first", name = "First", artist = "Artist")
        val second = CatalogTrack(uri = "spotify:track:second", name = "Second", artist = "Artist")
        val original = LocalPlaylists().create("existing", "Existing")
        val imported = original.create("imported", " Shared list ", listOf(second, first, second))
        assertEquals(original.lists.single(), imported.lists.first())
        assertEquals("Shared list", imported.lists.last().name)
        assertEquals(listOf(second, first), imported.lists.last().tracks)
    }

    @Test
    fun playlistEditsPreserveOtherListsAndIgnoreBlankNamesAndDuplicateTracks() {
        val track = CatalogTrack(uri = "spotify:track:first", name = "Caffè", artist = "Artist")
        val initial = LocalPlaylists().create("selected", "One").create("other", "Two", listOf(track))
        assertEquals(initial, initial.create("empty", " \t "))
        assertEquals(initial, initial.rename("selected", "\n"))
        assertEquals(initial, initial.add("missing", track))

        val edited = initial.rename("selected", " 東京 🎵 ").add("selected", track)
        assertEquals("東京 🎵", edited.lists.first().name)
        assertEquals(edited, edited.add("selected", track.copy(name = "Duplicate")))
        assertEquals(listOf(track), edited.lists.first().tracks)
        assertEquals(emptyList<CatalogTrack>(), edited.remove("selected", track.uri).lists.first().tracks)
        assertEquals(listOf(initial.lists.last()), edited.delete("selected").lists)
    }
}
