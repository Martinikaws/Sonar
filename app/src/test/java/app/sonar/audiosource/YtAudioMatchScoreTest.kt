package app.sonar.audiosource

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun studioResultsAreRejectedForExplicitVersionRequests() {
        val title = "When the Midnight Signal Calls Us Home"
        for (version in listOf("Live", "Remix")) {
            val track = wanted.copy(title = "$title $version")
            assertEquals(0.0, score(song(title = title), track), 0.0)
            assertEquals(1.0, score(song(title = "$title [$version]"), track), 0.0)
        }
        assertEquals(0.0, score(song(title = title), wanted.copy(title = "$title feat. South Star - Live")), 0.0)
    }

    @Test
    fun editionMarkersMustAgreeInBothDirections() {
        val track = wanted.copy(title = "When the Signal Calls")
        for (marker in listOf("Clean", "Explicit", "Remix", "Live", "Edit", "Instrumental", "Radio Edit")) {
            val alternate = track.copy(title = track.title + " ($marker)")
            assertEquals(marker, 0.0, score(song(title = alternate.title), track), 0.0)
            assertEquals(marker, 0.0, score(song(title = track.title), alternate), 0.0)
        }
    }

    @Test
    fun reorderedTitleTokensLoseCredit() {
        val track = wanted.copy(title = "When the Midnight Signal Calls Us Home")
        val reordered = score(song(title = "When the Signal Midnight Calls Us Home"), track)
        assertTrue(reordered > 0.0 && reordered < 1.0)
        assertEquals(0.0, score(song(title = "Signal Midnight"), wanted), 0.0)
    }

    @Test
    fun cacheKeyUsesVersionAndNormalizedMetadata() {
        val track = wanted.copy(artists = listOf("North Star", "South Star"))
        val key = matcherCacheKey(track)
        assertTrue(key.startsWith("$MATCHER_VERSION:"))
        assertEquals(key, matcherCacheKey(track.copy(
            title = "MÍDNIGHT SIGNAL [2011 Remaster]",
            artists = listOf(" south star ", "NORTH-STAR", "North Star"),
            durationMs = 180_499,
        )))
        assertNotEquals(key, matcherCacheKey(track.copy(title = "Signal Midnight")))
        assertNotEquals(key, matcherCacheKey(track.copy(title = "Midnight Signal feat. South Star - Live")))
        assertNotEquals(key, matcherCacheKey(track.copy(artists = listOf("North Star"))))
        assertNotEquals(key, matcherCacheKey(track.copy(durationMs = 180_500)))
    }

    @Test
    fun lowScoreIsCachedAsAMissAndMetadataChangeGetsAFreshLookup() = runBlocking {
        var searches = 0
        val matcher = YtAudioForSpotify(null) {
            searches++
            listOf(song(duration = 188))
        }

        assertTrue(score(song(duration = 188), wanted) > 0.0)
        assertNull(matcher.findAudio(wanted))
        assertNull(matcher.findAudio(wanted))
        assertEquals(1, searches)
        assertEquals("youtube-id", matcher.findAudio(wanted.copy(durationMs = 188_000))?.videoId)
        assertNull(matcher.findAudio(wanted.copy(title = "Signal Midnight", durationMs = 188_000)))
        assertEquals(3, searches)
    }

    @Test
    fun suppressionEvictsTheFailureAndCachesTheNextEligibleVideo() = runBlocking {
        var searches = 0
        val matcher = YtAudioForSpotify(null) {
            searches++
            listOf(song(), song(duration = 182, id = "backup"), song(duration = 188, id = "weak"))
        }
        val variant = wanted.copy(durationMs = 179_000)
        val other = wanted.copy(id = "other")

        assertEquals("youtube-id", matcher.findAudio(wanted)?.videoId)
        assertEquals("youtube-id", matcher.findAudio(variant)?.videoId)
        assertEquals("youtube-id", matcher.findAudio(other)?.videoId)
        matcher.suppress("youtube-id", wanted.id)
        assertEquals("backup", matcher.findAudio(wanted)?.videoId)
        assertEquals("backup", matcher.findAudio(wanted)?.videoId)
        assertEquals("backup", matcher.findAudio(variant)?.videoId)
        assertEquals("youtube-id", matcher.findAudio(other)?.videoId)
        assertEquals(5, searches)

        matcher.invalidate(wanted.id)
        assertEquals("backup", matcher.findAudio(wanted)?.videoId)
        assertEquals("backup", matcher.findAudio(variant)?.videoId)
        assertEquals(7, searches)
        matcher.suppress("backup", wanted.id)
        assertNull(matcher.findAudio(wanted))
        matcher.invalidate(wanted.id)
        assertNull(matcher.findAudio(wanted))
        assertEquals(9, searches)
    }

    @Test
    fun artistMismatchIsRejected() {
        assertEquals(0.0, score(song(artist = "South Star"), wanted), 0.0)
    }

    private fun song(
        title: String = "Midnight Signal",
        artist: String = "North Star",
        duration: Int = 180,
        id: String = "youtube-id",
    ) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(artist, null)),
        duration = duration,
        thumbnail = "",
    )
}
