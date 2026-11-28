package app.sonar.backend.youtube

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

/**
 * Streams for the songs YouTube will only play to someone signed in.
 *
 * Streams are normally asked for without an account, which keeps the
 * listener's account out of the playback requests; and for nearly every song
 * that is all it takes. A few, explicit records above all, answer "please sign
 * in" to any client that has no account, and played nothing: the player sat at
 * --:-- with no word of why. Those are asked for again here as the signed-in
 * account, through the client of YouTube's TV app, which takes the account's
 * cookie and needs no proof-of-origin token.
 *
 * What it answers is enciphered as on the website: the signature, and the
 * parameter YouTube throttles a stream by, are both undone by running the
 * site's own player script; see PlayerChallenges.
 */
object SignedInStreams {

    /** What to ask for: the sound, the picture alone, or both in one file. */
    enum class Kind { AUDIO, VIDEO, MUXED }

    /** Thrown when a song needs an account and none is signed in; see YouTubePlayerFactory. */
    class SignInRequired(videoId: String) : java.io.IOException("$videoId plays only when signed in")

    /**
     * Thrown when a song needs an account, one is signed in, and its stream
     * still could not be had: YouTube refused every client, or the address
     * could not be deciphered. Said as such, rather than as a sign-in to do.
     */
    class Refused(videoId: String, cause: Throwable) :
        java.io.IOException("$videoId could not be played signed in: ${cause.message}", cause)

    internal data class Stream(val url: String, val kbps: Int?)

    private val urls = ConcurrentHashMap<String, Pair<Long, Stream>>()
    private val bestAudio = ConcurrentHashMap<String, Pair<Long, Stream?>>()

    /**
     * Whether [error], from asking without an account, is YouTube asking for
     * one: an age check, a song it serves to accounts alone, or its "confirm
     * you're not a bot", which it puts in front of every song for a network it
     * has stopped trusting.
     *
     * That last one is not a ContentNotAvailableException, so it was not taken
     * for a request for an account: a listener signed in, on such a network,
     * had every song fail and none tried as the account, which YouTube would
     * have played.
     */
    fun needsAccount(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AgeRestrictedContentException ||
                it is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException ||
                (it is ContentNotAvailableException && it.message.orEmpty().contains("LOGIN_REQUIRED"))
        }

    /**
     * Forgets the addresses kept, after one failed to play: kept, it would be
     * handed out again for the hour, and the song could not be tried afresh.
     */
    fun forget() {
        urls.clear()
        bestAudio.clear()
    }

    /** The URL of [kind] for [videoId], as the signed-in account; blocking. */
    fun url(context: android.content.Context, videoId: String, kind: Kind, lowRate: Boolean): String =
        stream(context, videoId, kind, lowRate, preferAac = true).url

    internal fun stream(
        context: android.content.Context,
        videoId: String,
        kind: Kind,
        lowRate: Boolean,
        preferAac: Boolean = false,
        bestAvailable: Boolean = false,
    ): Stream {
        val account = YouTube.cookie ?: throw SignInRequired(videoId)
        val key = "$videoId|$kind|$lowRate|$preferAac|$bestAvailable|${account.hashCode()}|${YouTube.pageId}"
        val now = android.os.SystemClock.elapsedRealtime()
        urls[key]?.takeIf { now - it.first < URL_TTL_MS }?.let { return it.second }
        val script = PlayerChallenges.script(context)
        val response = player(videoId, script.signatureTimestamp)
        val issuedAt = android.os.SystemClock.elapsedRealtime()
        val formats = response.streamingData
        val audioOrder = compareByDescending<PlayerResponse.StreamingData.Format> {
            preferAac && it.mimeType.startsWith("audio/mp4")
        }.then(compareBy(YouTubeAudioQuality.order(lowRate)) { it.quality() })
        fun resolve(format: PlayerResponse.StreamingData.Format): Stream {
            val url = try {
                urlOf(context, script, format)
            } catch (failure: Exception) {
                // A failed challenge may leave the engine in an unusable state.
                PlayerChallenges.reset()
                throw failure
            }
            awaitReady(videoId, url, issuedAt, requireSuccess = kind == Kind.AUDIO)
            return Stream(url, format.quality().kbps.takeIf { kind == Kind.AUDIO && it > 0 })
        }
        val noStream = "no ${kind.name.lowercase()} stream for $videoId signed in"
        val (chosen, stream) = if (kind == Kind.AUDIO) {
            var lastFailure: Exception? = null
            YouTubeAudioQuality.firstPlayable(
                formats?.adaptiveFormats.orEmpty().filter { it.isAudio && it.isOriginal },
                isOriginal = { it.isOriginal },
                order = audioOrder,
            ) { format ->
                val upgrade = if (bestAvailable) tryUpgradeAudio(context, videoId, format.quality()) else null
                try {
                    format to (upgrade ?: resolve(format))
                } catch (failure: Exception) {
                    if (failure is InterruptedException || failure is kotlinx.coroutines.CancellationException) throw failure
                    lastFailure = failure
                    null
                }
            } ?: throw java.io.IOException(noStream, lastFailure)
        } else {
            val format = when (kind) {
                Kind.VIDEO -> formats?.adaptiveFormats.orEmpty()
                    .filter { !it.isAudio && (it.height ?: 0) in 1..MAX_VIDEO_HEIGHT }
                    .sortedWith(
                        compareByDescending<PlayerResponse.StreamingData.Format> { it.height ?: 0 }
                            .thenByDescending { it.mimeType.startsWith("video/mp4") },
                    )
                    .firstOrNull()
                Kind.MUXED -> formats?.formats.orEmpty().maxByOrNull { it.height ?: 0 }
                Kind.AUDIO -> error(noStream)
            } ?: error(noStream)
            format to resolve(format)
        }
        urls[key] = now to stream
        if (urls.size > 64) urls.entries.minByOrNull { it.value.first }?.let { urls.remove(it.key) }
        android.util.Log.i(
            TAG,
            "$videoId resolved signed-in ${kind.name.lowercase()} (base client $workingName, base itag ${chosen.itag})",
        )
        return stream
    }

    internal fun tryUpgradeAudio(
        context: android.content.Context,
        videoId: String,
        current: YouTubeAudioQuality,
    ): Stream? = runCatching { upgradeAudio(context, videoId, current) }.getOrNull()

    private fun upgradeAudio(
        context: android.content.Context,
        videoId: String,
        current: YouTubeAudioQuality,
    ): Stream? {
        val account = YouTube.cookie ?: return null
        if (current.itag == 251) return null
        val key = "$videoId|$current|${account.hashCode()}|${YouTube.pageId}"
        val now = android.os.SystemClock.elapsedRealtime()
        bestAudio[key]?.takeIf { now - it.first < URL_TTL_MS }?.let { return it.second }
        val order = YouTubeAudioQuality.order(lowRate = false)
        val script = PlayerChallenges.script(context)
        val response = runBlocking {
            YouTube.player(videoId, client = YouTubeClient.WEB_REMIX, signatureTimestamp = script.signatureTimestamp)
        }.getOrThrow()
        val stream = response.streamingData?.adaptiveFormats.orEmpty()
            .filter { it.isAudio && it.isOriginal && order.compare(it.quality(), current) < 0 }
            .sortedWith(compareBy(order) { it.quality() })
            .firstNotNullOfOrNull { format ->
                runCatching {
                    val url = urlOf(context, script, format)
                    if (probe(url) in 200..299) Stream(url, format.quality().kbps.takeIf { it > 0 })
                    else null
                }.getOrNull()
            }
        bestAudio[key] = now to stream
        if (bestAudio.size > 64) bestAudio.entries.minByOrNull { it.value.first }?.let { bestAudio.remove(it.key) }
        return stream
    }

    private fun PlayerResponse.StreamingData.Format.quality() =
        YouTubeAudioQuality(itag, (averageBitrate?.takeIf { it > 0 } ?: bitrate) / 1000)

    /**
     * The first signed-in client that will play it. The TV app's comes first,
     * asked as YouTube's own TV app asks: of www.youtube.com, as a named TV. Its streams need no proof-of-origin token, which the website's
     * clients do: their addresses answer 403 without one. Asked through
     * music.youtube.com, or without a make and model, the TV client
     * answers "the page needs to be reloaded" instead, which is why the
     * library's own TV client is only a fallback. The rest are tried in turn
     * after it.
     */
    private fun player(videoId: String, timestamp: Int): PlayerResponse {
        val refusals = mutableListOf<String>()
        val tv = runCatching { tvPlayer(videoId, timestamp) }
            .onFailure { refusals += "TV: ${it.javaClass.simpleName}" }
            .getOrNull()
        val tvStatus = tv?.playabilityStatus
        if (tv != null && tvStatus?.status == "OK" && tv.streamingData != null) {
            workingName = "TVHTML5 (Samsung)"
            return tv
        }
        if (tv != null) refusals += "TV: ${tvStatus?.status} ${tvStatus?.reason.orEmpty()}"
        for (client in CLIENTS) {
            val response = runBlocking {
                YouTube.player(videoId, client = client, signatureTimestamp = timestamp)
            }.getOrNull()
            val status = response?.playabilityStatus
            if (response != null && status?.status == "OK" && response.streamingData != null) {
                workingName = client.clientName
                if (refusals.isNotEmpty()) android.util.Log.i(TAG, "$videoId refused first: ${refusals.joinToString(" / ")}")
                return response
            }
            refusals += "${client.clientName}: ${status?.status} ${status?.reason.orEmpty()}"
        }
        android.util.Log.w(TAG, "$videoId refused signed in: ${refusals.joinToString(" / ")}")
        error("signed in, $videoId is unplayable: ${refusals.joinToString(" / ")}")
    }

    /**
     * Waits for [url] to open. An address the TV client has just been given
     * answers 403 for its first three or four seconds, then plays; handed to
     * the player at once, it failed the song. Asked for its first two bytes
     * until it answers otherwise, for as long as [READY_WAIT_MS] after it was
     * issued; one that still refuses then is refused for good.
     */
    internal fun isAudioUrlPlayable(url: String): Boolean =
        runCatching { probe(url) in 200..299 }.getOrDefault(false)

    private fun probe(url: String): Int {
        require(isYouTubePlaybackUrl(url)) { "Unexpected YouTube media address" }
        val request = Request.Builder().url(url).header("Range", "bytes=0-1").build()
        return http.newCall(request).execute().use { it.code }
    }

    private fun awaitReady(videoId: String, url: String, issuedAt: Long, requireSuccess: Boolean) {
        while (true) {
            val code = runCatching { probe(url) }.getOrElse {
                if (requireSuccess) throw it
                return
            }
            if (requireSuccess && code != 403 && code !in 200..299) error("$videoId stream returned HTTP $code")
            if (code != 403) return
            val waited = android.os.SystemClock.elapsedRealtime() - issuedAt
            if (waited > READY_WAIT_MS) error("$videoId still refused after $waited ms")
            Thread.sleep(READY_POLL_MS)
        }
    }

    /** The player answer of YouTube's TV app, signed in; see [player]. */
    private fun tvPlayer(videoId: String, timestamp: Int): PlayerResponse {
        val locale = YouTube.locale
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "TVHTML5")
                        .put("clientVersion", TV_VERSION)
                        .put("userAgent", TV_USER_AGENT)
                        .put("deviceMake", "Samsung")
                        .put("deviceModel", "UKS9800")
                        .put("osName", "Tizen")
                        .put("osVersion", "2.4.0")
                        .put("hl", locale.hl)
                        .put("gl", locale.gl)
                        .apply { YouTube.visitorData?.let { put("visitorData", it) } },
                ),
            )
            .put("videoId", videoId)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("signatureTimestamp", timestamp).put("html5Preference", "HTML5_PREF_WANTS"),
                ),
            )
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val cookie = YouTube.cookie ?: throw SignInRequired(videoId)
        val request = Request.Builder()
            .url("$WWW/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", TV_USER_AGENT)
            .header("Origin", WWW)
            .header("X-Origin", WWW)
            .header("X-Youtube-Client-Name", "7")
            .header("X-Youtube-Client-Version", TV_VERSION)
            .header("Cookie", cookie)
            .header("X-Goog-AuthUser", "0")
            .apply {
                authorization(cookie)?.let { header("Authorization", it) }
                YouTube.visitorData?.let { header("X-Goog-Visitor-Id", it) }
                YouTube.pageId?.takeIf { it.isNotBlank() }?.let { header("X-Goog-PageId", it) }
            }
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: error("empty answer")
        }
        return json.decodeFromString(PlayerResponse.serializer(), text)
    }

    /**
     * The hashes that prove the cookie's holder made the request, as the
     * website sends them: one for each of the account's three API cookies
     * the cookie carries, each over the time, the cookie and the origin.
     */
    private fun authorization(cookie: String): String? {
        val cookies = cookie.split(';').associate { it.trim().substringBefore('=') to it.trim().substringAfter('=', "") }
        val now = System.currentTimeMillis() / 1000
        fun hash(scheme: String, value: String?) = value?.takeIf { it.isNotEmpty() }?.let {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest("$now $it $WWW".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            "$scheme ${now}_$digest"
        }
        return listOfNotNull(
            hash("SAPISIDHASH", cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            hash("SAPISID1PHASH", cookies["__Secure-1PAPISID"]),
            hash("SAPISID3PHASH", cookies["__Secure-3PAPISID"]),
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** The name of the client that last played something, for the log. */
    @Volatile
    private var workingName: String? = null

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val CLIENTS = listOf(
        YouTubeClient.TVHTML5,
        YouTubeClient.MOBILE,
        // The website's clients: their streams need a proof-of-origin token
        // this does not make, save for Premium accounts. Last, for those.
        YouTubeClient.WEB_CREATOR,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.WEB_ACCOUNTS,
    )

    private const val WWW = "https://www.youtube.com"
    // A Samsung TV's, as yt-dlp's tv_samsung client: since August 2026 a TV
    // client that does not say what TV it is answers "the page needs to be
    // reloaded" to an account.
    private const val TV_VERSION = "5.20260707"
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    /**
     * A format's address with its signature and its throttling parameter
     * undone, both by YouTube's own player script; see PlayerChallenges.
     */
    private fun urlOf(
        context: android.content.Context,
        script: PlayerChallenges.Script,
        format: PlayerResponse.StreamingData.Format,
    ): String {
        var url: String
        var signature: String? = null
        var signatureParameter = "signature"
        val cipher = format.signatureCipher ?: format.cipher
        val direct = format.url
        if (direct != null) {
            url = direct
        } else {
            cipher ?: error("no address for itag ${format.itag}")
            val params = cipher.split('&').associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            url = params["url"] ?: error("no url in the cipher")
            signature = params["s"]
            params["sp"]?.let { signatureParameter = it }
        }
        require(isYouTubePlaybackUrl(url)) { "Unexpected YouTube media address" }
        val throttle = android.net.Uri.parse(url).getQueryParameter("n")
        val (nAnswers, sigAnswers) = PlayerChallenges.solve(
            context,
            script,
            listOfNotNull(throttle),
            listOfNotNull(signature),
        )
        if (signature != null) {
            val solved = sigAnswers[signature] ?: error("signature unsolved")
            url += "&$signatureParameter=${URLEncoder.encode(solved, "UTF-8")}"
        }
        if (throttle != null) {
            val solved = nAnswers[throttle] ?: error("throttling parameter unsolved")
            url = url.replace(THROTTLE, "$1n=${URLEncoder.encode(solved, "UTF-8")}")
        }
        return url
    }

    private val THROTTLE = Regex("""([?&])n=[^&]*""")

    /** As long as the anonymous answers are kept; the addresses last for hours. */
    private const val URL_TTL_MS = 60 * 60 * 1000L

    private const val MAX_VIDEO_HEIGHT = 1080

    private const val READY_WAIT_MS = 10_000L
    private const val READY_POLL_MS = 500L

    private const val TAG = "SonarYTSignedIn"
}

internal fun isYouTubePlaybackUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
        (parsed.host == "googlevideo.com" || parsed.host.endsWith(".googlevideo.com"))
}
