package app.sonar.flac

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.sonar.audiosource.SpotifyTrackKey
import app.sonar.data.CatalogTrack
import app.sonar.playback.OfflineMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class FlacField { CLIENT, SERVER, USER, PASSWORD, RECORDING, PARTNER_KEY }

internal class FlacSources(private val context: Context) {
    private val prefs = context.getSharedPreferences("sonar_flac", Context.MODE_PRIVATE)

    fun enabled(provider: FlacProvider): Boolean = prefs.getBoolean(provider.name, false)
    fun setEnabled(provider: FlacProvider, enabled: Boolean) {
        prefs.edit().putBoolean(provider.name, enabled).apply()
    }

    fun field(field: FlacField): String = prefs.getString(field.name, "").orEmpty()
    fun setField(field: FlacField, value: String) {
        prefs.edit().putString(field.name, value).apply()
    }

    fun available(): Boolean = FlacProvider.entries.any { ready(it, networkAllowed()) }

    private fun networkAllowed() = OfflineMode.reason.value != OfflineMode.Reason.MANUAL

    private fun ready(provider: FlacProvider, online: Boolean): Boolean = enabled(provider) && when (provider) {
        FlacProvider.JAMENDO -> online && validClient(field(FlacField.CLIENT))
        FlacProvider.NAVIDROME -> online && flacServer(field(FlacField.SERVER)) != null &&
            field(FlacField.USER).isNotBlank() && field(FlacField.PASSWORD).isNotEmpty()
        FlacProvider.ARCHIVE -> online && archiveId(field(FlacField.RECORDING)) != null
        FlacProvider.BANDCAMP -> files().isNotEmpty()
        FlacProvider.MASSIVE -> false
    }

    suspend fun match(key: SpotifyTrackKey, album: String): String? = withContext(Dispatchers.IO) {
        if (album.isBlank() || key.title.isBlank() || key.artists.isEmpty() || key.durationMs <= 0) return@withContext null
        for (provider in FlacProvider.entries) {
            currentCoroutineContext().ensureActive()
            if (!ready(provider, networkAllowed())) continue
            val found = try {
                matchFlac(key, album, lookup(provider, key.title))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (found != null && ready(provider, networkAllowed())) return@withContext register(provider, found.address)
        }
        null
    }

    suspend fun search(provider: FlacProvider, query: String): List<CatalogTrack> = withContext(Dispatchers.IO) {
        check(ready(provider, networkAllowed()))
        lookup(provider, query).map { track ->
            CatalogTrack(register(provider, track.address), track.title, track.artist, album = track.album, durationMs = track.durationMs)
        }
    }

    private suspend fun lookup(provider: FlacProvider, query: String): List<FlacTrack> = when (provider) {
        FlacProvider.JAMENDO -> {
            val clientId = field(FlacField.CLIENT).trim()
            check(validClient(clientId))
            val url = "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId).addQueryParameter("format", "json")
                .addQueryParameter("audioformat", "flac").addQueryParameter("limit", "40")
                .addQueryParameter("type", "single albumtrack").addQueryParameter("search", query).build()
            parseJamendo(json(provider, url))
        }
        FlacProvider.NAVIDROME -> {
            val server = checkNotNull(flacServer(field(FlacField.SERVER)))
            val user = field(FlacField.USER).trim()
            val password = field(FlacField.PASSWORD)
            check(user.isNotEmpty() && password.isNotEmpty())
            fun endpoint(name: String): HttpUrl.Builder {
                val salt = UUID.randomUUID().toString().replace("-", "")
                // Subsonic token authentication requires MD5(password + salt).
                val token = MessageDigest.getInstance("MD5").digest((password + salt).encodeToByteArray())
                    .joinToString("") { "%02x".format(it) }
                return server.newBuilder().apply {
                    if (server.encodedPath.endsWith('/')) removePathSegment(server.pathSize - 1)
                }.addPathSegment("rest").addPathSegment("$name.view")
                    .addQueryParameter("u", user).addQueryParameter("t", token).addQueryParameter("s", salt)
                    .addQueryParameter("v", "1.16.1").addQueryParameter("c", "Sonar").addQueryParameter("f", "json")
            }
            val url = endpoint("search3").addQueryParameter("query", query)
                .addQueryParameter("artistCount", "0").addQueryParameter("albumCount", "0")
                .addQueryParameter("songCount", "40").build()
            parseNavidrome(json(provider, url)).map {
                it.copy(address = endpoint("stream").addQueryParameter("id", it.address)
                    .addQueryParameter("format", "raw").build().toString())
            }
        }
        FlacProvider.ARCHIVE -> {
            val id = checkNotNull(archiveId(field(FlacField.RECORDING)))
            val url = "https://archive.org/metadata/".toHttpUrl().newBuilder().addPathSegment(id).build()
            parseArchive(id, json(provider, url))
        }
        FlacProvider.BANDCAMP -> files().mapNotNull {
            currentCoroutineContext().ensureActive()
            inspect(it, persist = false)
        }.filter {
            query.isBlank() || it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
        FlacProvider.MASSIVE -> emptyList()
    }

    suspend fun importFiles(selected: List<Uri>): Int = withContext(Dispatchers.IO) {
        val accepted = selected.distinct().filter { inspect(it, persist = true) != null }
        synchronized(importLock) {
            val all = files() + accepted
            prefs.edit().putStringSet("files", all.map(Uri::toString).toSet()).apply()
        }
        accepted.size
    }

    private fun files(): List<Uri> = prefs.getStringSet("files", emptySet()).orEmpty().sorted().map(Uri::parse)

    private fun inspect(uri: Uri, persist: Boolean): FlacTrack? {
        if (uri.scheme != "content") return null
        return runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.takeIf { it.endsWith(".flac", true) } ?: return null
            val signature = resolver.openInputStream(uri)?.buffered()?.use { source ->
                byteArrayOf(source.read().toByte(), source.read().toByte(), source.read().toByte(), source.read().toByte())
            }
            if (signature?.contentEquals("fLaC".encodeToByteArray()) != true) return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val track = FlacTrack(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().ifBlank { name.dropLast(5) },
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    uri.toString(),
                )
                if (persist) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                track
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun json(provider: FlacProvider, url: HttpUrl): JSONObject =
        request(provider, url, metadata = true).use { response ->
            val source = (response.body ?: throw IOException("Empty FLAC response")).source()
            if (source.request(2L * 1024 * 1024 + 1)) throw IOException("FLAC source response too large")
            JSONObject(source.readUtf8())
        }

    companion object {
        private val importLock = Any()
        const val SCHEME = "sonar-flac"
        fun owns(uri: String) = uri.startsWith("$SCHEME:")

        class Stream(val provider: FlacProvider, val address: String)
        private val streams = object : LinkedHashMap<String, Stream>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stream>?) = size > 1_000
        }

        private fun register(provider: FlacProvider, address: String): String {
            val uri = "$SCHEME:${UUID.randomUUID()}"
            synchronized(streams) { streams[uri] = Stream(provider, address) }
            return uri
        }

        fun stream(uri: String): Stream? = synchronized(streams) { streams[uri] }

        fun validClient(value: String): Boolean = value.trim().matches(Regex("[A-Za-z0-9_-]{4,100}"))

        private val transport = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        private val metadataTransport = transport.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

        fun request(provider: FlacProvider, initial: HttpUrl, metadata: Boolean, range: String? = null): Response {
            var url = initial
            repeat(5) {
                val allowed = url.isHttps && url.fragment == null && url.username.isEmpty() && url.password.isEmpty() && when (provider) {
                    FlacProvider.JAMENDO -> url.onDomain("jamendo.com")
                    FlacProvider.ARCHIVE -> url.onDomain("archive.org")
                    FlacProvider.NAVIDROME -> url == initial
                    else -> false
                }
                if (!allowed || OfflineMode.reason.value == OfflineMode.Reason.MANUAL) throw IOException("FLAC source unavailable")
                val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
                    if (range != null) header("Range", range)
                }.build()
                val response = try {
                    (if (metadata) metadataTransport else transport).newCall(request).execute()
                } catch (_: IOException) {
                    throw IOException("Could not connect to FLAC source")
                }
                if (response.isSuccessful) return response
                val redirect = response.use {
                    if (provider != FlacProvider.NAVIDROME && !metadata && it.code in listOf(301, 302, 303, 307, 308)) {
                        it.header("Location")?.let(url::resolve)
                    } else null
                } ?: throw IOException("FLAC request failed")
                url = redirect
            }
            throw IOException("Too many FLAC redirects")
        }
    }
}
