package app.sonar.lyricsx

import app.sonar.backend.lyrics.LyricsQuery
import app.sonar.backend.lyrics.LyricsSource
import app.sonar.data.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

internal data class LyricsLookupKey(val uri: String, val title: String, val artist: String)

internal fun LyricsQuery.lookupKey() = LyricsLookupKey(uri, title.trim().lowercase(), artist.trim().lowercase())

internal enum class LyricsLookupOutcome { ANSWERED, NO_LYRICS, FAILED, TIMED_OUT }

internal data class LyricsAttempt(val outcome: LyricsLookupOutcome, val elapsedMs: Long)

internal class LyricsDiagnostics(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val history = MutableStateFlow<Map<LyricsLookupKey, Map<LyricsSource, LyricsAttempt>>>(emptyMap())
    val attempts = history.asStateFlow()

    @Synchronized
    private fun record(key: LyricsLookupKey, source: LyricsSource, attempt: LyricsAttempt) {
        val current = history.value
        val updated = current[key].orEmpty() + (source to attempt)
        history.value = ((current - key).toList().takeLast(15) + (key to updated)).toMap()
    }

    suspend fun observe(
        query: LyricsQuery,
        source: LyricsSource,
        timeoutMs: Long?,
        fetch: suspend () -> Lyrics?,
    ): Lyrics? {
        val start = nanoTime()
        val (lyrics, outcome) = try {
            if (timeoutMs == null) answer(fetch())
            else withTimeoutOrNull(timeoutMs) { answer(fetch()) }
                ?: (null to LyricsLookupOutcome.TIMED_OUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null to LyricsLookupOutcome.FAILED
        }
        record(query.lookupKey(), source, LyricsAttempt(outcome, (nanoTime() - start) / 1_000_000L))
        return lyrics
    }

    private fun answer(lyrics: Lyrics?): Pair<Lyrics?, LyricsLookupOutcome> =
        if (lyrics == null || lyrics.lines.isEmpty()) null to LyricsLookupOutcome.NO_LYRICS
        else lyrics to LyricsLookupOutcome.ANSWERED

    companion object {
        val session = LyricsDiagnostics()
    }
}
