package app.sonar.backend

import app.sonar.data.AlbumDto
import app.sonar.data.ArtistDto
import app.sonar.data.ImageDto
import app.sonar.data.OwnerDto
import app.sonar.data.PlaylistDto
import app.sonar.data.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeCatalogTest {

    @Test fun webOnlyNeedsFreeAndSignedIn() {
        assertTrue(FreeCatalog.webOnly(freeAccount = true, signedIn = true))
        assertFalse(FreeCatalog.webOnly(freeAccount = false, signedIn = true))
        assertFalse(FreeCatalog.webOnly(freeAccount = true, signedIn = false))
    }

    @Test fun playlistKeepsOwnerAndCover() {
        val p = FreeCatalog.playlist(
            PlaylistDto(
                id = "a", uri = "spotify:playlist:a", name = "Mix",
                images = listOf(ImageDto("https://i/1")),
                owner = OwnerDto(id = "u1", displayName = null),
            ),
        )
        assertEquals("u1", p.subtitle)
        assertEquals("https://i/1", p.artworkUrl)
    }

    @Test fun likedSongsIsRoutedAsCollection() {
        assertTrue(FreeCatalog.likedSongs("Liked", "c").uri.endsWith(":collection"))
    }

    @Test fun albumTracksGetTheAlbumAndDropUnplayable() {
        val album = AlbumDto(uri = "spotify:album:x", name = "Rec", images = listOf(ImageDto("https://i/a")))
        val tracks = FreeCatalog.albumTracks(
            album,
            listOf(
                TrackDto(uri = "spotify:track:1", name = "One", artists = listOf(ArtistDto(name = "A"))),
                TrackDto(uri = "spotify:track:2", name = "Gone", isPlayable = false),
                TrackDto(uri = "spotify:episode:3", name = "Ep"),
            ),
        )
        assertEquals(listOf("spotify:track:1"), tracks.map { it.uri })
        assertEquals("Rec", tracks[0].album)
        assertEquals("https://i/a", tracks[0].artworkUrl)
    }

    @Test fun failedReadFallsBackToEmpty() {
        assertEquals(emptyList<String>(), FreeCatalog.orElse(emptyList<String>()) { error("403") })
        assertEquals(listOf("x"), FreeCatalog.orElse(emptyList()) { listOf("x") })
    }
}
