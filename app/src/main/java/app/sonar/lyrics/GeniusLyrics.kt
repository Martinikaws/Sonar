package app.sonar.lyrics

import androidx.core.text.HtmlCompat
import app.sonar.backend.lyrics.primaryArtist
import app.sonar.backend.lyrics.songTitle
import app.sonar.data.LyricLine
import app.sonar.data.Lyrics
import app.sonar.data.LyricsCredit
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

internal object GeniusLyrics {
    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    // TODO: Confirm Genius permits displaying lyrics from these pages before release.
    private val search = "https://genius.com/api/search/song?per_page=10".toHttpUrl()
    private val accents = Regex("""\p{Mn}+""")
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")
    private val artistAside = Regex("""\s*\([^)]*\)""")
    private val div = Regex("""</?div\b[^>]*>""", RegexOption.IGNORE_CASE)

    suspend fun lyrics(title: String, artist: String): Lyrics? = withContext(Dispatchers.IO) {
        val cleanTitle = title.songTitle()
        val lead = artist.primaryArtist()
        if (cleanTitle.isBlank() || lead.isBlank()) return@withContext null
        val url = search.newBuilder().addQueryParameter("q", "$cleanTitle $lead").build()
        val response = get(url) ?: return@withContext null
        for (page in songPages(response, cleanTitle, artist)) {
            ensureActive()
            val html = try {
                get(page, skipHttpErrors = true)
            } catch (_: IOException) {
                ensureActive()
                null
            } ?: continue
            val lines = lyricLines(html)
            if (lines.isNotEmpty()) {
                return@withContext Lyrics(
                    lines, synced = false,
                    credit = LyricsCredit(provider = "Genius", providerUrl = page.toString()),
                )
            }
        }
        null
    }

    private fun get(url: HttpUrl, skipHttpErrors: Boolean = false): String? {
        val request = Request.Builder().url(url).header("User-Agent", "SonarMusicApp/1.0").build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 404 || (skipHttpErrors && !response.isSuccessful)) return@use null
            check(response.isSuccessful) { "Genius HTTP ${response.code}" }
            response.body?.string()
        }
    }

    internal fun songPages(response: String, title: String, artist: String): List<HttpUrl> {
        val key = title.songTitle().matchKey()
        val artistName = artist.replace(artistAside, "")
        val lead = artistName.primaryArtist().matchKey()
        val fullArtist = artistName.matchKey()
        if (key.isEmpty() || lead.isEmpty()) return emptyList()
        val sections = try {
            JSONObject(response).optJSONObject("response")?.optJSONArray("sections")
        } catch (_: JSONException) {
            null
        } ?: return emptyList()
        return buildList {
            for (i in 0 until sections.length()) {
                val hits = sections.optJSONObject(i)?.optJSONArray("hits") ?: continue
                for (j in 0 until hits.length()) {
                    val result = hits.optJSONObject(j)?.optJSONObject("result") ?: continue
                    if (result.optString("title").songTitle().matchKey() != key) continue
                    val primary = result.optJSONObject("primary_artist")?.optString("name").orEmpty()
                    val resultLead = primary.ifBlank {
                        result.optString("artist_names").replace(artistAside, "").primaryArtist()
                    }.replace(artistAside, "").matchKey()
                    if (resultLead != lead && resultLead != fullArtist) continue
                    val page = result.optString("url").toHttpUrlOrNull() ?: continue
                    if (page.isHttps && page.host == "genius.com" && page.port == 443 &&
                        page.username.isEmpty() && page.password.isEmpty() &&
                        page.pathSegments.singleOrNull()?.endsWith("-lyrics", ignoreCase = true) == true
                    ) add(page)
                }
            }
        }.distinct()
    }

    private fun String.matchKey(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD).replace(accents, "")
            .lowercase().replace(punctuation, "")

    internal fun lyricFragments(html: String): List<String> = buildList {
        var depth = 0
        var excludedAt = 0
        var start = 0
        var fragment = StringBuilder()
        for (tag in div.findAll(html)) {
            if (depth == 0) {
                if (tag.value.startsWith("</") || !tag.value.contains("data-lyrics-container=\"true\"")) continue
                depth = 1
                start = tag.range.last + 1
            } else if (tag.value.startsWith("</")) {
                if (depth == excludedAt) {
                    excludedAt = 0
                    start = tag.range.last + 1
                }
                depth--
                if (depth == 0) {
                    fragment.append(html, start, tag.range.first)
                    add(fragment.toString())
                    fragment = StringBuilder()
                }
            } else {
                depth++
                if (excludedAt == 0 && tag.value.contains("data-exclude-from-selection=\"true\"")) {
                    fragment.append(html, start, tag.range.first)
                    excludedAt = depth
                }
            }
        }
    }

    private fun lyricLines(html: String): List<LyricLine> =
        lyricFragments(html).flatMap { fragment ->
            HtmlCompat.fromHtml(fragment, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                .lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !(it.startsWith('[') && it.endsWith(']')) }
                .map { LyricLine(startTimeMs = null, text = it) }
                .toList()
        }
}
