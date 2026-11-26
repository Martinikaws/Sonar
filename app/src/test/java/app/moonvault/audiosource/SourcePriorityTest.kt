package app.moonvault.audiosource

import app.moonvault.audiosource.AudioSource.FLAC
import app.moonvault.audiosource.AudioSource.SPOTIFY
import app.moonvault.audiosource.AudioSource.YOUTUBE_MUSIC
import org.junit.Assert.assertEquals
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
}
