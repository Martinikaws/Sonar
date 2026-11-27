package app.sonar.library

import app.sonar.data.CatalogArtist
import app.sonar.data.CatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenContentTest {
    private fun track(uri: String, artistUri: String? = null, vararg credited: String) =
        CatalogTrack(uri = uri, name = uri, artist = "a", artistUri = artistUri, artists = credited.map { CatalogArtist("n", it) })

    @Test fun hiddenSongAndBannedArtistAreFiltered() {
        val h = HiddenContent().hideSong(track("t1")).banArtist("ar1", "One")
        val list = listOf(track("t1"), track("t2", "ar1"), track("t3", "ar9", "ar2", "ar1"), track("t4", "ar9"))
        assertEquals(listOf("t4"), h.filter(list).map { it.uri })
    }

    @Test fun tappedTrackIsKept() {
        val h = HiddenContent().hideSong(track("t1"))
        assertEquals(listOf("t1", "t2"), h.filter(listOf(track("t1"), track("t2")), keep = "t1").map { it.uri })
    }

    @Test fun unhideRestores() {
        val h = HiddenContent().hideSong(track("t1")).banArtist("ar1", "One").showSong("t1").unbanArtist("ar1")
        assertTrue(h.isEmpty)
    }

    @Test fun artistWithoutUriCannotBeBanned() {
        assertTrue(HiddenContent().banArtist(null, "x").artists.isEmpty())
    }

    @Test fun localPlaylistEdits() {
        val p = LocalPlaylists().create("p", " Mix ").add("p", track("t1")).add("p", track("t1")).add("p", track("t2"))
        assertEquals("Mix", p.lists.single().name)
        assertEquals(listOf("t1", "t2"), p.lists.single().tracks.map { it.uri })
        assertEquals(listOf("t2"), p.remove("p", "t1").lists.single().tracks.map { it.uri })
        assertTrue(p.delete("p").lists.isEmpty())
        assertTrue(LocalPlaylists().create("p", " ").lists.isEmpty())
    }
}
