package app.sonar.audiosource

import android.util.AtomicFile
import android.util.Log
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.text.Normalizer
import kotlin.math.abs

data class SpotifyTrackKey(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    val isrc: String? = null,
)

data class YtMatch(val videoId: String, val score: Double)

class YtAudioForSpotify internal constructor(
    cacheDir: File?,
    private val search: suspend (String) -> List<SongItem>?,
) {
    constructor(cacheDir: File) : this(cacheDir, { query ->
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            ?.items?.filterIsInstance<SongItem>()
    })

    private data class Entry(val match: YtMatch?, val expiresAt: Long)

    private val cache = cacheDir?.let { AtomicFile(File(it, "yt_audio_for_spotify-1.json")) }
    private val cacheLock = Mutex()
    private val entries by lazy { load() }
    private val suppressedVideos = mutableMapOf<String, MutableSet<String>>()
    // Bound lock storage while allowing searches for different tracks to run concurrently.
    private val searchLocks = Array(32) { Mutex() }

    suspend fun findAudio(track: SpotifyTrackKey): YtMatch? {
        if (track.id.isBlank() || track.title.isBlank() || track.durationMs <= 0 ||
            track.artists.none { it.isNotBlank() }
        ) return null

        val key = matcherCacheKey(track)
        return searchLock(track.id).withLock {
            val cached = withContext(Dispatchers.IO) {
                cacheLock.withLock {
                    entries[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }
                }
            }
            if (cached != null) return@withLock cached.match

            val query = (listOf(track.title) + track.artists).joinToString(" ")
            val result = withTimeoutOrNull(5_000L) { search(query) }
            currentCoroutineContext().ensureActive()
            // A failed search does not establish that the recording is missing.
            if (result == null) return@withLock null

            val candidates = result.asSequence()
                .filter { it.id.isNotBlank() && !it.isVideoSong && !it.isEpisode && it.uploadEntityId == null }
                .map { YtMatch(it.id, score(it, track)) }
                .filter { it.score >= MIN_MATCH_SCORE }
                .toList()

            withContext(Dispatchers.IO) {
                cacheLock.withLock {
                    val excluded = suppressedVideos[track.id].orEmpty()
                    val match = candidates.asSequence()
                        .filterNot { it.videoId in excluded }
                        .maxByOrNull { it.score }
                    val now = System.currentTimeMillis()
                    entries.values.removeAll { it.expiresAt <= now }
                    val ttl = if (match == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
                    entries[key] = Entry(match, now + ttl)
                    save()
                    match
                }
            }
        }
    }

    /** Excludes a failed video for this Spotify track for the lifetime of this matcher. */
    suspend fun suppress(videoId: String, spotifyId: String): Unit = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            if (!suppressedVideos.getOrPut(spotifyId) { mutableSetOf() }.add(videoId)) return@withLock
            clearEntries(spotifyId)
        }
    }

    /** Clears cached hits and misses for every metadata variant of this Spotify track. */
    suspend fun invalidate(spotifyId: String): Unit = searchLock(spotifyId).withLock {
        withContext(Dispatchers.IO) {
            cacheLock.withLock { clearEntries(spotifyId) }
        }
    }

    private fun searchLock(spotifyId: String) =
        searchLocks[Math.floorMod(spotifyId.hashCode(), searchLocks.size)]

    private fun clearEntries(spotifyId: String) {
        val prefix = cacheKeyPrefix(spotifyId)
        if (entries.keys.removeAll { it.startsWith(prefix) }) save()
    }

    private fun load(): MutableMap<String, Entry> {
        val loaded = mutableMapOf<String, Entry>()
        val cache = cache ?: return loaded
        try {
            val json = JSONObject(cache.readFully().toString(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            for (id in json.keys()) {
                if (!id.startsWith("$MATCHER_VERSION:")) continue
                val entry = json.optJSONObject(id) ?: continue
                val expiresAt = entry.optLong("expiresAt")
                if (expiresAt <= now) continue
                val videoId = entry.optString("videoId")
                val match = if (videoId.isEmpty()) null else YtMatch(videoId, entry.getDouble("score"))
                if (match != null && !(match.score >= MIN_MATCH_SCORE)) continue
                loaded[id] = Entry(match, expiresAt)
            }
        } catch (_: FileNotFoundException) {
            return loaded
        } catch (error: Exception) {
            Log.w(TAG, "YouTube audio match cache unreadable", error)
        }
        return loaded
    }

    private fun save() {
        val cache = cache ?: return
        try {
            val json = JSONObject()
            for ((id, entry) in entries) {
                val value = JSONObject().put("expiresAt", entry.expiresAt)
                entry.match?.let { value.put("videoId", it.videoId).put("score", it.score) }
                json.put(id, value)
            }
            val stream = cache.startWrite()
            try {
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                cache.finishWrite(stream)
            } catch (error: Exception) {
                cache.failWrite(stream)
                throw error
            }
        } catch (error: Exception) {
            Log.w(TAG, "YouTube audio match cache not saved", error)
        }
    }

    private companion object {
        const val TAG = "YtAudioForSpotify"
        const val NEGATIVE_TTL_MS = 60 * 60 * 1_000L
        const val POSITIVE_TTL_MS = 30 * 24 * NEGATIVE_TTL_MS
    }
}

internal const val MATCHER_VERSION = 2
private const val MIN_MATCH_SCORE = 0.85

private fun cacheKeyPrefix(spotifyId: String) = "$MATCHER_VERSION:${spotifyId.length}:$spotifyId:"

internal fun matcherCacheKey(track: SpotifyTrackKey): String {
    val artists = track.artists.map(::normalizedWords).filter { it.isNotEmpty() }.toSortedSet()
    val durationSeconds = track.durationMs / 1_000 + if (track.durationMs % 1_000 >= 500) 1 else 0
    val titleInputs = listOf(
        normalizedTitle(track.title),
        versionSignature(track.title).joinToString(","),
        durationSeconds.toString(),
    )
    return cacheKeyPrefix(track.id) + (titleInputs + artists).joinToString("|")
}

private val diacritics = Regex("\\p{M}+")
private val nonWords = Regex("[^\\p{L}\\p{N}]+")
private val creditOrRemaster = Regex(
    """\s*(?:[(\[]\s*|[-–—]\s*)?(?:feat\.?|ft\.?|featuring)(?=\s)[^()\[\]]*[)\]]?|\s*(?:[(\[]\s*|[-–—]\s*)?(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?\s*[)\]]?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val versionTags = listOf(
    Regex("""\blive\b"""),
    Regex("""\bcover\b"""),
    Regex("""\bkaraoke\b"""),
    Regex("""\b(?:sp[ea]d[\s-]*up|speed[\s-]*up|nightcore)\b"""),
    Regex("""\bslowed\b"""),
    Regex("""\bremix\b"""),
    Regex("""\binstrumental\b"""),
)

private fun normalizedWords(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
    .replace(diacritics, "")
    .replace(nonWords, " ")
    .trim()

private fun normalizedTitle(value: String) = normalizedWords(value.replace(creditOrRemaster, " "))

private fun versionSignature(title: String): List<Int> {
    val words = normalizedWords(title)
    return versionTags.indices.filter { versionTags[it].containsMatchIn(words) }
}

/** Returns zero for recordings that should not be considered for playback. */
fun score(candidate: SongItem, wanted: SpotifyTrackKey): Double {
    val duration = candidate.duration?.takeIf { it > 0 }?.times(1_000L) ?: return 0.0
    if (wanted.durationMs <= 0) return 0.0
    val difference = abs(wanted.durationMs - duration)
    if (difference > 8_000L) return 0.0

    val candidateTitle = normalizedTitle(candidate.title)
    val wantedTitle = normalizedTitle(wanted.title)
    if (candidateTitle.isEmpty() || wantedTitle.isEmpty()) return 0.0
    if (versionSignature(candidate.title) != versionSignature(wanted.title)) return 0.0

    val candidateArtists = candidate.artists.map { normalizedWords(it.name) }.filter { it.isNotEmpty() }.toSet()
    val wantedArtists = wanted.artists.map(::normalizedWords).filter { it.isNotEmpty() }.toSet()
    val sharedArtists = candidateArtists.intersect(wantedArtists).size
    if (sharedArtists == 0) return 0.0

    val titleSimilarity = if (candidateTitle == wantedTitle) {
        1.0
    } else {
        val candidateWords = candidateTitle.split(' ')
        val wantedWords = wantedTitle.split(' ')
        2.0 * orderedOverlap(candidateWords, wantedWords) / (candidateWords.size + wantedWords.size)
    }
    if (titleSimilarity < 0.85) return 0.0

    val artistOverlap = sharedArtists.toDouble() / wantedArtists.size
    val durationScore = if (difference <= 3_000L) 1.0 - difference / 15_000.0 else 0.0
    return 0.55 * titleSimilarity + 0.15 * artistOverlap + 0.30 * durationScore
}

private fun orderedOverlap(candidate: List<String>, wanted: List<String>): Int {
    // Longest common subsequence, keeping only the previous row of counts.
    val counts = IntArray(wanted.size + 1)
    for (word in candidate) {
        var previousDiagonal = 0
        for (index in wanted.indices) {
            val previousRow = counts[index + 1]
            counts[index + 1] = if (word == wanted[index]) {
                previousDiagonal + 1
            } else {
                maxOf(counts[index], previousRow)
            }
            previousDiagonal = previousRow
        }
    }
    return counts.last()
}
