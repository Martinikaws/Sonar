package app.sonar.lyricsx

import app.sonar.backend.lyrics.LyricsQuery
import app.sonar.backend.lyrics.LyricsSource
import app.sonar.data.LyricLine
import app.sonar.data.Lyrics
import app.sonar.data.LyricsCredit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class LyricsToolsTest {
    private val lyrics = Lyrics(
        listOf(LyricLine(1000L, " First "), LyricLine(2000L, ""), LyricLine(3000L, "Third")),
        synced = true,
    )
    private val query = LyricsQuery("track:1", "Title", "Artist", 8000L)

    @Test
    fun offsetsAreStoredByUriBoundedAndResettable() {
        val persisted = mutableMapOf<String, Long>()
        fun open() = TrackLyricsOffsets({ persisted[it] ?: 0L }) { uri, offset ->
            if (offset == 0L) persisted.remove(uri) else persisted[uri] = offset
        }
        val store = open()
        val limit = TrackLyricsOffsets.LIMIT_MS
        assertEquals(limit, store.set("track:1", Long.MAX_VALUE))
        assertEquals(-limit, store.set("track:2", Long.MIN_VALUE))
        assertEquals(limit, open()["track:1"])
        assertEquals(-limit, open()["track:2"])
        assertEquals(0L, open()["track:3"])
        store.set("track:1", 0L)
        assertFalse("track:1" in persisted)
        assertEquals(0L, open()["track:1"])
    }

    @Test
    fun tapCalibrationComposesWithOutputDelayAndReplacesAnyPriorOffset() {
        val offset = calibrationOffset(1350L, 150L, 1000L)
        assertEquals(200L, offset)
        assertEquals(1000L, lyricsPosition(1350L, 150L, offset))
        assertEquals(1350L, lyricSeekPosition(1000L, 150L, offset))
        assertEquals(-350L, lyricsPosition(0L, 150L, offset))
        assertEquals(0L, lyricsPosition(0L, 150L, 0L))
        assertEquals(0L, lyricSeekPosition(0L, 150L, -200L))
        assertEquals(-200L, calibrationOffset(950L, 150L, 1000L))
    }

    @Test
    fun calibrationOffersSixNearestTimedNonblankLinesInTimeOrder() {
        val timed = Lyrics(
            (1..9).map { LyricLine(it * 1000L, "Line $it") } +
                listOf(LyricLine(null, "Untimed"), LyricLine(7500L, "")),
            synced = true,
        )
        assertEquals((3..8).toList(), calibrationLines(timed, 7500L))
        assertEquals(emptyList<Int>(), calibrationLines(timed.copy(synced = false), 7500L))
    }

    @Test
    fun currentLineUsesThePlaybackClockAndOmitsInstrumentalGaps() {
        assertNull(currentLyricLine(lyrics, 999L))
        assertEquals(0, currentLyricLine(lyrics, 1000L))
        assertNull(currentLyricLine(lyrics, 2500L))
        assertEquals(2, currentLyricLine(lyrics, 3000L))
        assertNull(currentLyricLine(lyrics.copy(synced = false), 3000L))
    }

    @Test
    fun sharingKeepsSongOrderAndProviderCredit() {
        val credited = lyrics.copy(credit = LyricsCredit("Provider", maker = "Maker"))
        assertEquals("Third", lyricsShareText(lyrics, setOf(2)))
        assertEquals("First\nThird\n\nProvider\nMaker", lyricsShareText(credited, setOf(99, 2, 1, 0)))
        assertEquals("", lyricsShareText(credited, setOf(1)))
    }

    @Test
    fun diagnosticsRecordSourceOutcomeAndElapsedTime() = runBlocking {
        var now = 100_000_000L
        val diagnostics = LyricsDiagnostics { now }
        val answer = diagnostics.observe(query, LyricsSource.LRCLIB, null) {
            now += 12_900_000L
            lyrics
        }
        assertSame(lyrics, answer)
        assertNull(diagnostics.observe(query, LyricsSource.SPOTIFY, null) { error("Failed") })
        assertNull(diagnostics.observe(query, LyricsSource.KUGOU, null) { lyrics.copy(lines = emptyList()) })
        assertNull(diagnostics.observe(query, LyricsSource.NETEASE, 5L) { awaitCancellation() })
        val attempts = diagnostics.attempts.value.getValue(query.lookupKey())
        assertEquals(LyricsAttempt(LyricsLookupOutcome.ANSWERED, 12L), attempts[LyricsSource.LRCLIB])
        assertEquals(LyricsLookupOutcome.FAILED, attempts[LyricsSource.SPOTIFY]?.outcome)
        assertEquals(LyricsLookupOutcome.NO_LYRICS, attempts[LyricsSource.KUGOU]?.outcome)
        assertEquals(LyricsLookupOutcome.TIMED_OUT, attempts[LyricsSource.NETEASE]?.outcome)
    }

    @Test
    fun cancellationDoesNotRecordFailureAndHistoryStaysBounded() = runBlocking {
        val diagnostics = LyricsDiagnostics { 0L }
        val source = LyricsSource.LRCLIB
        try {
            diagnostics.observe(query, source, null) { throw CancellationException("Track changed") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(query.lookupKey() in diagnostics.attempts.value)
        }
        repeat(17) { index ->
            diagnostics.observe(query.copy(uri = "track:$index"), source, null) { null }
        }
        assertEquals(16, diagnostics.attempts.value.size)
        assertFalse(query.copy(uri = "track:0").lookupKey() in diagnostics.attempts.value)
        assertEquals(query.lookupKey(), query.copy(title = " title ", artist = "ARTIST").lookupKey())
    }
}
