package app.sonar.library.importlink

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistLinkTest {
    @Test
    fun acceptsSpotifyPlaylistShares() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val expected = PlaylistLink.Spotify(id)
        assertEquals(expected, parsePlaylistLink(" https://open.spotify.com/playlist/$id?si=123 "))
        assertEquals(expected, parsePlaylistLink("https://open.spotify.com/intl-it/playlist/$id/"))
    }

    @Test
    fun acceptsYouTubePlaylistShares() {
        val id = "PL_test-123"
        val expected = PlaylistLink.YouTube(id)
        assertEquals(expected, parsePlaylistLink("https://music.youtube.com/playlist?list=$id&si=123"))
        assertEquals(expected, parsePlaylistLink("https://www.youtube.com/watch?v=video&list=$id"))
    }

    @Test
    fun distinguishesUnsupportedHostsFromInvalidPlaylistLinks() {
        listOf(
            "https://tidal.com/browse/playlist/example",
            "https://music.apple.com/it/playlist/example",
            "https://open.spotify.com.example/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "https://example.com/?url=https://music.youtube.com/playlist?list=PL123",
        ).forEach { assertEquals(it, PlaylistLink.Unsupported, parsePlaylistLink(it)) }

        listOf(
            "",
            "not a url",
            "https://open.spotify.com/track/37i9dQZF1DXcBWIGoYBM5M",
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M/extra",
            "https://music.youtube.com/playlist?list=",
            "https://music.youtube.com/playlist?list=PL1&list=PL2",
            "https://music.youtube.com/playlist?other=1#list=PL123",
            "https://example.com@music.youtube.com/playlist?list=PL123",
        ).forEach { assertEquals(it, PlaylistLink.Invalid, parsePlaylistLink(it)) }
    }
}
