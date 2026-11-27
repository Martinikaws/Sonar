package app.sonar.flac

import app.sonar.audiosource.SpotifyTrackKey
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.text.Normalizer
import kotlin.math.abs

internal enum class FlacProvider { JAMENDO, NAVIDROME, ARCHIVE, BANDCAMP, MASSIVE }

internal data class FlacTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val address: String,
)

internal fun flacHttps(value: String): HttpUrl? {
    val input = value.trim()
    if (!input.startsWith("https://", ignoreCase = true) ||
        input.any { it.isWhitespace() || it.isISOControl() || it == '\\' }
    ) return null
    val authority = runCatching { URI(input).rawAuthority }.getOrNull()
    if (authority.isNullOrEmpty() || '@' in authority) return null
    return input.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    }
}

internal fun flacServer(value: String): HttpUrl? = flacHttps(value)?.takeIf { it.query == null }

internal fun archiveId(value: String): String? {
    val input = value.trim()
    val id = if ("://" in input) {
        val url = flacHttps(input)?.takeIf { it.host in listOf("archive.org", "www.archive.org") }
            ?: return null
        url.pathSegments.dropLastWhile(String::isEmpty).takeIf { it.size == 2 && it[0] == "details" }
            ?.get(1) ?: return null
    } else input
    return id.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) }
}

internal fun HttpUrl.onDomain(domain: String): Boolean =
    port == 443 && (host == domain || host.endsWith(".$domain"))

private fun matchText(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFC)
    .trim().replace(Regex("\\s+"), " ")

/** Keep edition labels intact and reject ambiguous results from the same catalogue. */
internal fun matchFlac(key: SpotifyTrackKey, album: String, candidates: List<FlacTrack>): FlacTrack? {
    val title = matchText(key.title)
    val release = matchText(album)
    val credits = key.artists.map(::matchText).filter(String::isNotEmpty)
    if (title.isEmpty() || release.isEmpty() || credits.isEmpty() || key.durationMs <= 0) return null
    val artist = credits.joinToString(", ")
    return candidates.filter { candidate ->
        matchText(candidate.title) == title && matchText(candidate.album) == release &&
            matchText(candidate.artist) == artist && candidate.durationMs > 0 &&
            abs(candidate.durationMs - key.durationMs) <= 2_000
    }.distinctBy(FlacTrack::address).singleOrNull()
}

internal fun parseJamendo(body: JSONObject): List<FlacTrack> {
    if (body.optJSONObject("headers")?.optString("status") != "success") throw IOException("Jamendo request failed")
    return body.optJSONArray("results").objects().mapNotNull { result ->
        val url = flacHttps(result.optString("audio"))?.takeIf {
            it.onDomain("jamendo.com") && (it.queryParameter("format") == "flac" || it.encodedPath.endsWith(".flac", true))
        } ?: return@mapNotNull null
        result.track("name", "artist_name", "album_name", "duration", url.toString())
    }
}

internal fun parseNavidrome(body: JSONObject): List<FlacTrack> {
    val response = body.optJSONObject("subsonic-response")
    if (response?.optString("status") != "ok") throw IOException("Navidrome request failed")
    return response.optJSONObject("searchResult3")?.optJSONArray("song").objects().mapNotNull { result ->
        if (!result.optString("suffix").equals("flac", true) && result.optString("contentType") != "audio/flac") {
            return@mapNotNull null
        }
        val id = result.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        result.track("title", "artist", "album", "duration", id)
    }
}

internal fun parseArchive(id: String, body: JSONObject): List<FlacTrack> {
    val metadata = body.optJSONObject("metadata") ?: return emptyList()
    if (body.restricted("is_dark") || metadata.restricted("access-restricted-item") ||
        metadata.restricted("no-download") || metadata.optString("mediatype") != "etree"
    ) return emptyList()
    val artist = metadata.optJSONArray("creator")?.optString(0) ?: metadata.optString("creator")
    return body.optJSONArray("files").objects().mapNotNull { result ->
        val filename = result.optString("name")
        val parts = filename.split('/')
        if (!filename.endsWith(".flac", true) || '\\' in filename ||
            parts.any { it.isEmpty() || it == "." || it == ".." } || result.restricted("private")
        ) return@mapNotNull null
        val address = "https://archive.org/download/".toHttpUrl().newBuilder()
            .addPathSegment(id).apply { parts.forEach { addPathSegment(it) } }.build()
        FlacTrack(
            result.optString("title").ifBlank { parts.last().dropLast(5) },
            artist, metadata.optString("title"), result.duration("length"), address.toString(),
        )
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

private fun JSONObject.restricted(key: String) = optString(key).lowercase() in setOf("true", "1", "yes")

private fun JSONObject.duration(key: String): Long =
    optDouble(key).takeIf { it.isFinite() && it > 0 && it <= Long.MAX_VALUE / 1000.0 }
        ?.times(1000)?.toLong() ?: 0L

private fun JSONObject.track(title: String, artist: String, album: String, length: String, address: String): FlacTrack? =
    optString(title).takeIf(String::isNotBlank)?.let { FlacTrack(it, optString(artist), optString(album), duration(length), address) }
