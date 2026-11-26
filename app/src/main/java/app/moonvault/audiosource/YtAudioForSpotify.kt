package app.moonvault.audiosource

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

class YtAudioForSpotify(cacheDir: File) {
    private data class Entry(val match: YtMatch?, val expiresAt: Long)

    private val cache = AtomicFile(File(cacheDir, "yt_audio_for_spotify-1.json"))
    private val cacheLock = Mutex()
    private val entries by lazy { load() }
    // Bound lock storage while allowing searches for different tracks to run concurrently.
    private val searchLocks = Array(32) { Mutex() }

    suspend fun findAudio(track: SpotifyTrackKey): YtMatch? {
        if (track.id.isBlank() || track.title.isBlank() || track.durationMs <= 0 ||
            track.artists.none { it.isNotBlank() }
        ) return null

        val lock = searchLocks[Math.floorMod(track.id.hashCode(), searchLocks.size)]
        return lock.withLock {
            val cached = withContext(Dispatchers.IO) {
                cacheLock.withLock {
                    entries[track.id]?.takeIf { it.expiresAt > System.currentTimeMillis() }
                }
            }
            if (cached != null) return@withLock cached.match

            val query = (listOf(track.title) + track.artists).joinToString(" ")
            val result = withTimeoutOrNull(5_000L) {
                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            }
            currentCoroutineContext().ensureActive()
            // A failed search does not establish that the recording is missing.
            if (result == null) return@withLock null

            val match = result.items.asSequence()
                .filterIsInstance<SongItem>()
                .filter { it.id.isNotBlank() && !it.isVideoSong && !it.isEpisode && it.uploadEntityId == null }
                .map { YtMatch(it.id, score(it, track)) }
                .filter { it.score > 0 }
                .maxByOrNull { it.score }

            withContext(Dispatchers.IO) {
                cacheLock.withLock {
                    val now = System.currentTimeMillis()
                    entries.values.removeAll { it.expiresAt <= now }
                    val ttl = if (match == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
                    entries[track.id] = Entry(match, now + ttl)
                    save()
                }
            }
            match
        }
    }

    private fun load(): MutableMap<String, Entry> {
        val loaded = mutableMapOf<String, Entry>()
        try {
            val json = JSONObject(cache.readFully().toString(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            for (id in json.keys()) {
                val entry = json.optJSONObject(id) ?: continue
                val expiresAt = entry.optLong("expiresAt")
                if (expiresAt <= now) continue
                val videoId = entry.optString("videoId")
                val match = if (videoId.isEmpty()) null else YtMatch(videoId, entry.getDouble("score"))
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

/** Returns zero for recordings that should not be considered for playback. */
fun score(candidate: SongItem, wanted: SpotifyTrackKey): Double {
    val duration = candidate.duration?.takeIf { it > 0 }?.times(1_000L) ?: return 0.0
    if (wanted.durationMs <= 0) return 0.0
    val difference = abs(wanted.durationMs - duration)
    if (difference > 8_000L) return 0.0

    val candidateTitle = normalizedTitle(candidate.title)
    val wantedTitle = normalizedTitle(wanted.title)
    if (candidateTitle.isEmpty() || wantedTitle.isEmpty()) return 0.0
    val candidateVersion = normalizedWords(candidate.title)
    val wantedVersion = normalizedWords(wanted.title)
    if (versionTags.any { it.containsMatchIn(candidateVersion) && !it.containsMatchIn(wantedVersion) }) {
        return 0.0
    }

    val candidateArtists = candidate.artists.map { normalizedWords(it.name) }.filter { it.isNotEmpty() }.toSet()
    val wantedArtists = wanted.artists.map(::normalizedWords).filter { it.isNotEmpty() }.toSet()
    val sharedArtists = candidateArtists.intersect(wantedArtists).size
    if (sharedArtists == 0) return 0.0

    val titleSimilarity = if (candidateTitle == wantedTitle) {
        1.0
    } else {
        val candidateWords = candidateTitle.split(' ').toSet()
        val wantedWords = wantedTitle.split(' ').toSet()
        2.0 * candidateWords.intersect(wantedWords).size / (candidateWords.size + wantedWords.size)
    }
    if (titleSimilarity < 0.85) return 0.0

    val artistOverlap = sharedArtists.toDouble() / wantedArtists.size
    val durationScore = if (difference <= 3_000L) 1.0 - difference / 15_000.0 else 0.0
    return 0.55 * titleSimilarity + 0.15 * artistOverlap + 0.30 * durationScore
}
