package app.moonvault.audiosource

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtAudioMatchScoreTest {
    private val wanted = SpotifyTrackKey("spotify-id", "Midnight Signal", listOf("North Star"), 180_000)

    @Test
    fun goodMatchPrefersCloseDuration() {
        val exact = score(song(), wanted)
        val close = score(song(duration = 183), wanted)
        val loose = score(song(duration = 188), wanted)

        assertEquals(1.0, exact, 0.0001)
        assertTrue(exact > close && close > loose && loose > 0.0)
    }

    @Test
    fun remasterSuffixAndFeaturedCreditAreIgnored() {
        val candidate = song(title = "Midnight Signal (feat. South Star) - 2011 Remaster")
        assertEquals(score(song(), wanted), score(candidate, wanted), 0.0)
    }

    @Test
    fun wrongDurationIsRejected() {
        assertEquals(0.0, score(song(duration = 189), wanted), 0.0)
    }

    @Test
    fun alternateVersionsAreRejected() {
        val track = wanted.copy(title = "When the Midnight Signal Calls Us Home")
        assertTrue(score(song(title = "${track.title} [Album]"), track) > 0.0)
        for (suffix in listOf("Live", "Cover", "Karaoke", "Sped-up")) {
            assertEquals(0.0, score(song(title = "${track.title} [$suffix]"), track), 0.0)
        }
    }

    @Test
    fun artistMismatchIsRejected() {
        assertEquals(0.0, score(song(artist = "South Star"), wanted), 0.0)
    }

    private fun song(
        title: String = "Midnight Signal",
        artist: String = "North Star",
        duration: Int = 180,
    ) = SongItem(
        id = "youtube-id",
        title = title,
        artists = listOf(Artist(artist, null)),
        duration = duration,
        thumbnail = "",
    )
}
