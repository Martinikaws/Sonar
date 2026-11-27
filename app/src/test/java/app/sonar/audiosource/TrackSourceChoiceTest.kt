package app.sonar.audiosource

import app.sonar.audiosource.AudioSource.FLAC
import app.sonar.audiosource.AudioSource.SPOTIFY
import app.sonar.audiosource.AudioSource.YOUTUBE_MUSIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSourceChoiceTest {
    @Test
    fun freeUsesYouTubeRegardlessOfSwitchOrSourceOrder() {
        val orders = listOf(listOf(SPOTIFY, YOUTUBE_MUSIC), listOf(YOUTUBE_MUSIC, SPOTIFY), listOf(SPOTIFY))
        for (order in orders) {
            assertEquals(YOUTUBE_MUSIC, chooseTrackSource(order, freeAccount = true, youtubeFallback = false))
            assertEquals(YOUTUBE_MUSIC, chooseTrackSource(order, freeAccount = true, youtubeFallback = true))
        }
    }

    @Test
    fun premiumUsesTheFirstEnabledTrackSourceAndHonorsTheSwitch() {
        val youtubeFirst = listOf(FLAC, YOUTUBE_MUSIC, SPOTIFY)
        val spotifyFirst = listOf(SPOTIFY, YOUTUBE_MUSIC, FLAC)
        assertEquals(YOUTUBE_MUSIC, chooseTrackSource(youtubeFirst, freeAccount = false, youtubeFallback = true))
        assertEquals(SPOTIFY, chooseTrackSource(spotifyFirst, freeAccount = false, youtubeFallback = true))
        assertEquals(SPOTIFY, chooseTrackSource(youtubeFirst, freeAccount = false, youtubeFallback = false))
        assertEquals(SPOTIFY, chooseTrackSource(spotifyFirst, freeAccount = false, youtubeFallback = false))
        assertEquals(SPOTIFY, chooseTrackSource(listOf(SPOTIFY), freeAccount = false, youtubeFallback = true))
        assertNull(chooseTrackSource(listOf(YOUTUBE_MUSIC), freeAccount = false, youtubeFallback = false))
    }

    @Test
    fun manualOfflineSkipsYouTubeForBothAccounts() {
        assertNull(chooseTrackSource(listOf(SPOTIFY), true, true, onlinePlaybackAllowed = false))
        assertEquals(SPOTIFY, chooseTrackSource(listOf(YOUTUBE_MUSIC, SPOTIFY), false, true, onlinePlaybackAllowed = false))
    }
}
