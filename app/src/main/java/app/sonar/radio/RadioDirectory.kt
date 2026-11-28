package app.sonar.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The radio-browser.info directory: a free community index of public streams, no account or key.
 * It has no single server, so a few of its mirrors are tried in turn and the one that answered
 * is tried first next time.
 */
object RadioDirectory {
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )
    @Volatile private var preferred = 0
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).followRedirects(false).build()
    }

    /** Most-voted first; broken streams left out. An empty [query] with a [tag] browses the tag. */
    suspend fun search(query: String = "", tag: String = "", limit: Int = 40): RadioResults {
        // Twice as many are asked for as are shown: the unencrypted ones are dropped after.
        val params = buildString {
            append("hidebroken=true&order=votes&reverse=true&limit=${limit * 2}")
            if (query.isNotBlank()) append("&name=${enc(query.trim())}")
            if (tag.isNotBlank()) append("&tag=${enc(tag)}")
        }
        return RadioParsing.stations(get("/json/stations/search?$params"), limit)
    }

    suspend fun tags(limit: Int = 16): List<String> =
        RadioParsing.tags(get("/json/tags?order=stationcount&reverse=true&limit=$limit"))

    /** Counts a play toward the station's popularity; best effort, so a failure is ignored. */
    suspend fun reportClick(uuid: String) {
        runCatching { get("/json/url/${enc(uuid)}") }
    }

    /** The body of [path] from the first mirror that answers; IOException when none does. */
    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        var failure: Exception? = null
        for (step in mirrors.indices) {
            val index = (preferred + step) % mirrors.size
            val request = Request.Builder().url(mirrors[index] + path)
                .header("User-Agent", "Sonar (live radio)")
                .header("Accept", "application/json")
                .build()
            try {
                http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body.string()
                    preferred = index
                    return@withContext body
                }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw IOException("radio-browser.info did not answer", failure)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** An answer that is not the expected JSON (a captive portal page, say) is a failed ask like an unreachable one. */
internal fun isDirectoryFailure(e: Exception) = e is IOException || e is SerializationException
