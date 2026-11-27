package app.sonar.audiosource

import app.sonar.audiosource.AudioSource.FLAC
import app.sonar.audiosource.AudioSource.SPOTIFY
import app.sonar.audiosource.AudioSource.YOUTUBE_MUSIC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePriorityTest {
    @Test
    fun defaultsEnableEverySourceInPriorityOrder() {
        val priority = SourcePriority()
        assertEquals(listOf(SPOTIFY, FLAC, YOUTUBE_MUSIC), priority.order)
        assertEquals(emptySet<AudioSource>(), priority.disabled)
        assertEquals(priority.order, priority.effective())
    }

    @Test
    fun moveUsesFinalIndexAndClampsOutOfBounds() {
        val original = SourcePriority()
        val moved = original.move(SPOTIFY, 99)
        assertEquals(listOf(FLAC, YOUTUBE_MUSIC, SPOTIFY), moved.order)
        assertEquals(listOf(YOUTUBE_MUSIC, FLAC, SPOTIFY), moved.move(YOUTUBE_MUSIC, -1).order)
        assertEquals(listOf(SPOTIFY, FLAC, YOUTUBE_MUSIC), original.order)
    }

    @Test
    fun disablingAndReEnablingPreservesOrder() {
        val priority = SourcePriority().move(YOUTUBE_MUSIC, 0)
        val disabled = priority.setEnabled(FLAC, false)
        assertEquals(priority.order, disabled.order)
        assertEquals(listOf(YOUTUBE_MUSIC, SPOTIFY), disabled.effective())
        assertEquals(priority, disabled.setEnabled(FLAC, true))
    }

    @Test
    fun allDisabledFallsBackToSpotifyWithoutChangingPreferences() {
        val priority = SourcePriority(disabled = AudioSource.entries.toSet())
        assertEquals(listOf(SPOTIFY), priority.effective())
        assertEquals(priority, SourcePriority.fromJson(priority.toJson()))
    }

    @Test
    fun jsonRoundTripPreservesOrderAndDisabledSources() {
        val priority = SourcePriority().move(YOUTUBE_MUSIC, 0).setEnabled(SPOTIFY, false)
        assertEquals(priority, SourcePriority.fromJson(priority.toJson()))
    }

    @Test
    fun corruptOrMissingJsonUsesDefaults() {
        for (json in listOf(null, "", "{", "[]", "{}")) {
            assertEquals(SourcePriority(), SourcePriority.fromJson(json))
        }
    }

    @Test
    fun jsonKeepsFirstKnownEntriesAndAppendsMissingSources() {
        val priority = SourcePriority.fromJson(
            """{"order":["YOUTUBE_MUSIC","UNKNOWN","YOUTUBE_MUSIC",17],"disabled":["FLAC","FLAC","UNKNOWN",null]}""",
        )
        assertEquals(listOf(YOUTUBE_MUSIC, SPOTIFY, FLAC), priority.order)
        assertEquals(setOf(FLAC), priority.disabled)
        assertEquals(SourcePriority(), SourcePriority.fromJson("""{"order":true,"disabled":{}}"""))
    }

    @Test
    fun fallbacksSkipTriedDisabledAndUnreadyFlac() {
        val priority = SourcePriority()
        assertEquals(listOf(YOUTUBE_MUSIC), priority.fallbacks(setOf(SPOTIFY), flacReady = false))
        assertEquals(listOf(FLAC, YOUTUBE_MUSIC), priority.fallbacks(setOf(SPOTIFY), flacReady = true))
        assertEquals(
            emptyList<AudioSource>(),
            priority.setEnabled(YOUTUBE_MUSIC, false).fallbacks(setOf(SPOTIFY), flacReady = false),
        )
        assertEquals(listOf(SPOTIFY), priority.move(YOUTUBE_MUSIC, 0).fallbacks(setOf(YOUTUBE_MUSIC), false))
    }

    @Test
    fun trackTriesEachSourceOnceAndSkipsFlac() {
        val priority = SourcePriority()
        assertEquals(SPOTIFY, priority.nextTrackSource(emptySet(), youtubeAllowed = true))
        assertNull(priority.nextTrackSource(setOf(SPOTIFY), youtubeAllowed = true, spotifyPending = true))
        assertEquals(YOUTUBE_MUSIC, priority.nextTrackSource(setOf(SPOTIFY), youtubeAllowed = true))
        assertNull(priority.nextTrackSource(setOf(SPOTIFY, YOUTUBE_MUSIC), youtubeAllowed = true))
    }

    @Test
    fun configuredFlacRespectsOrderAndDoesNotDependOnYoutube() {
        val priority = SourcePriority()
        assertEquals(SPOTIFY, priority.nextTrackSource(emptySet(), youtubeAllowed = false, flacReady = true))
        assertEquals(
            TrackSourceAction.LOOK_UP_FLAC,
            priority.trackSourceAction(setOf(SPOTIFY), spotifyUnavailable = true, lookupPending = false,
                recoverWithWeb = false, youtubeAllowed = false, flacReady = true),
        )
        assertEquals(FLAC, priority.move(FLAC, 0).nextTrackSource(emptySet(), youtubeAllowed = true, flacReady = true))
        assertEquals(YOUTUBE_MUSIC, priority.nextTrackSource(setOf(SPOTIFY, FLAC), youtubeAllowed = true, flacReady = true))
        assertNull(priority.setEnabled(FLAC, false)
            .nextTrackSource(setOf(SPOTIFY), youtubeAllowed = false, flacReady = true))
    }

    @Test
    fun youtubeFirstCanFallThroughToSpotifyAndNextTrackStartsFresh() {
        val priority = SourcePriority().move(YOUTUBE_MUSIC, 0)
        assertEquals(YOUTUBE_MUSIC, priority.nextTrackSource(emptySet(), youtubeAllowed = true))
        assertEquals(SPOTIFY, priority.nextTrackSource(setOf(YOUTUBE_MUSIC), youtubeAllowed = true))
        assertNull(priority.nextTrackSource(setOf(YOUTUBE_MUSIC, SPOTIFY), youtubeAllowed = true))
        assertEquals(YOUTUBE_MUSIC, priority.nextTrackSource(emptySet(), youtubeAllowed = true))
    }

    @Test
    fun trackSkipsYoutubeWhenUnavailableOrDisabled() {
        val priority = SourcePriority().move(YOUTUBE_MUSIC, 0)
        assertEquals(SPOTIFY, priority.nextTrackSource(emptySet(), youtubeAllowed = false))
        assertNull(priority.nextTrackSource(setOf(SPOTIFY), youtubeAllowed = false))
        assertNull(
            priority.setEnabled(YOUTUBE_MUSIC, false)
                .nextTrackSource(setOf(SPOTIFY), youtubeAllowed = true),
        )
    }

    @Test
    fun callbacksWaitForTheYoutubeLookupAndLeaveSpotifyAvailableAfterAMiss() {
        val priority = SourcePriority(order = listOf(YOUTUBE_MUSIC, SPOTIFY, FLAC))
        val duringLookup = setOf(YOUTUBE_MUSIC)
        assertEquals(
            TrackSourceAction.WAIT,
            priority.trackSourceAction(duringLookup, spotifyUnavailable = false, lookupPending = true, recoverWithWeb = false),
        )
        assertEquals(
            TrackSourceAction.PLAY_SPOTIFY,
            priority.trackSourceAction(duringLookup, spotifyUnavailable = false, lookupPending = false, recoverWithWeb = false),
        )
        assertEquals(
            TrackSourceAction.LOOK_UP_YOUTUBE,
            priority.trackSourceAction(duringLookup - YOUTUBE_MUSIC, spotifyUnavailable = false, lookupPending = false, recoverWithWeb = false),
        )
    }

    @Test
    fun anUnavailableSpotifyTrackExhaustsItsSourcesOrRecoversWithWeb() {
        val priority = SourcePriority()
        val triedBoth = setOf(SPOTIFY, YOUTUBE_MUSIC)
        assertEquals(
            TrackSourceAction.WAIT,
            priority.trackSourceAction(triedBoth, spotifyUnavailable = true, lookupPending = true, recoverWithWeb = true),
        )
        assertEquals(
            TrackSourceAction.EXHAUSTED,
            priority.trackSourceAction(triedBoth, spotifyUnavailable = true, lookupPending = false, recoverWithWeb = false),
        )
        assertEquals(
            TrackSourceAction.RECOVER_WITH_WEB,
            priority.trackSourceAction(triedBoth, spotifyUnavailable = true, lookupPending = false, recoverWithWeb = true),
        )
        assertEquals(
            TrackSourceAction.WAIT,
            priority.trackSourceAction(triedBoth, spotifyUnavailable = false, lookupPending = false, recoverWithWeb = false),
        )
    }
}
