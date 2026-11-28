package app.sonar.migration

import android.content.Context
import android.net.Uri
import android.util.Xml
import android.webkit.CookieManager
import app.sonar.SonarApplication
import app.sonar.data.ListeningEvent
import app.sonar.data.LocalLibrary
import app.sonar.data.PreferencesStore
import app.sonar.library.LocalPlaylist
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

internal object AstralImport {
    data class Result(
        val filesImported: Int,
        val filesSkipped: Int,
        val preferencesImported: Int,
        val preferencesSkipped: Int,
        val preferenceFilesSkipped: Int,
        val cookiesAccepted: Int,
        val cookiesSkipped: Int,
        val cookieRecordsSkipped: Int,
        val cookiesSkippedForExistingSession: Int,
        val historyNeedsAccount: Boolean,
    ) : Serializable

    private val mutex = Mutex()
    private val settings = mapOf<String, Map<String, (PreferencesStore, Boolean) -> Unit>>(
        "prefs/settings_prefs.xml" to mapOf(
            "system_font" to PreferencesStore::setSystemFont,
            "canvas_background" to PreferencesStore::setCanvasEnabled,
            "autoplay_similar" to PreferencesStore::setAutoplayInfinite,
            "hide_dj" to { store, hidden -> store.setSpotifyDj(!hidden) },
        ),
        "prefs/sponsorblock.xml" to mapOf("on" to PreferencesStore::setSponsorBlock),
        "prefs/playback_service.xml" to mapOf("keep_playing_on_close" to PreferencesStore::setKeepPlayingOnClose),
    )

    private val spotifyUrls = setOf(
        "https://open.spotify.com/", "https://accounts.spotify.com/",
        "https://open.spotify.com/login", "https://accounts.spotify.com/login",
    )
    private val youtubeUrls = setOf(
        "https://music.youtube.com", "https://www.youtube.com", "https://youtube.com",
        "https://accounts.google.com", "https://www.google.com", "https://google.com",
    )
    private val cookieName = Regex("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+")
    private val cookieAttributes = setOf(
        "domain", "path", "expires", "max-age", "secure", "httponly", "samesite", "partitioned",
    )

    internal data class Preference(val apply: (PreferencesStore, Boolean) -> Unit, val value: Boolean)
    private data class CookieRecord(val url: String, val header: String, val profile: String?)
    internal data class Cookie(val url: String, val value: String)
    private data class CookieInstall(val accepted: Int, val skippedForSession: Int)
    internal data class CookiePlan(val values: Set<Cookie>, val skippedPairs: Int, val skippedRecords: Int)
    internal data class Archive(
        val files: Int,
        val preferences: List<Preference>,
        val preferencesSkipped: Int,
        val preferenceFilesSkipped: Int,
        val cookies: CookiePlan,
        val playlists: List<LocalPlaylist>?,
        val history: List<ListeningEvent>,
        val historyFiles: Int,
        val hasHistory: Boolean,
    )

    /** Takes ownership of the passphrase, including when opening the document fails. */
    suspend fun importDocument(context: Context, uri: Uri, passphrase: CharArray): Result {
        try {
            return mutex.withLock {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        SonarImportCrypto.decrypt(it, passphrase)
                    } ?: throw IOException("Cannot open export")
                    try {
                        restore(context.applicationContext as SonarApplication, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun restore(application: SonarApplication, zip: ByteArray): Result {
        val owner = application.listeningStore.account?.takeIf { application.spotifySignedIn }
        val archive = readArchive(zip, owner)
        currentCoroutineContext().ensureActive()
        var filesImported = 0
        archive.playlists?.let { lists ->
            val store = application.localPlaylists
            val deviceTracks = if (lists.any { it.tracks.isNotEmpty() } && LocalLibrary.granted(application)) {
                LocalLibrary.tracks(application)
            } else emptyList()
            store.mergeImported(lists, deviceTracks)
            filesImported++
        }
        val restoredHistory = owner != null && archive.historyFiles > 0 &&
            application.listeningStore.importMeasured(archive.history, owner)
        if (restoredHistory) {
            filesImported += archive.historyFiles
        }
        val plan = archive.cookies
        val (accepted, skippedForSession) = withContext(Dispatchers.Main) {
            val installed = installCookies(application, plan.values)
            archive.preferences.forEach { it.apply(application.preferences, it.value) }
            installed
        }
        return Result(
            filesImported, archive.files - filesImported,
            archive.preferences.size, archive.preferencesSkipped, archive.preferenceFilesSkipped,
            accepted, plan.skippedPairs + plan.values.size - accepted, plan.skippedRecords, skippedForSession,
            archive.hasHistory && !restoredHistory,
        )
    }

    internal fun readArchive(zip: ByteArray, owner: String?, zone: ZoneId = ZoneId.systemDefault()): Archive {
        val preferences = mutableListOf<Preference>()
        val files = mutableMapOf<String, ByteArray>()
        val key = owner?.let(AstralDataConverters::ownerKey)
        val parked = key?.let(AstralDataConverters::parkedHistory)
        var preferencesSkipped = 0
        var preferenceFilesSkipped = 0
        var manifest: ByteArray? = null
        var cookies: CookiePlan? = null

        val inventory = try {
            AstralImportZip.read(zip, retain = { name ->
                name == "manifest.json" || name == "cookies.json" ||
                    name in settings || name == AstralDataConverters.PLAYLISTS ||
                    (key != null && (name == parked || name == AstralDataConverters.HISTORY ||
                        name == AstralDataConverters.HISTORY_OWNER))
            }) { name, content ->
                when {
                    name == "manifest.json" -> manifest = content
                    name == "cookies.json" -> cookies = parseCookies(content!!)
                    name in settings -> {
                        val before = preferences.size
                        preferencesSkipped += readPreferences(content!!, settings.getValue(name), preferences)
                        if (preferences.size == before) preferenceFilesSkipped++
                    }
                    name.startsWith("prefs/") -> preferenceFilesSkipped++
                    content != null -> files[name] = content
                    else -> Unit
                }
            }.also { names ->
                verifyManifest(manifest ?: throw InvalidAstralExport(), names - "manifest.json")
            }
        } catch (_: JSONException) {
            throw InvalidAstralExport()
        } catch (_: XmlPullParserException) {
            throw InvalidAstralExport()
        } catch (_: ZipException) {
            throw InvalidAstralExport()
        } catch (_: EOFException) {
            throw InvalidAstralExport()
        } catch (_: IllegalArgumentException) {
            throw InvalidAstralExport()
        }
        val activeOwner = files[AstralDataConverters.HISTORY_OWNER]?.toString(Charsets.UTF_8)?.trim()
        val historyFiles = listOfNotNull(
            files[AstralDataConverters.HISTORY].takeIf { key != null && key == activeOwner },
            parked?.let(files::get),
        )
        return Archive(
            inventory.count { it.startsWith("files/") }, preferences, preferencesSkipped, preferenceFilesSkipped,
            cookies ?: throw InvalidAstralExport(),
            files[AstralDataConverters.PLAYLISTS]?.let {
                try {
                    AstralDataConverters.playlists(it)
                } catch (_: JSONException) {
                    null
                }
            },
            historyFiles.flatMap { AstralDataConverters.history(it, zone) }.distinctBy { it.id },
            historyFiles.size,
            inventory.any { it == AstralDataConverters.HISTORY || it.startsWith("files/listen-history/") },
        )
    }

    private fun verifyManifest(bytes: ByteArray, inventory: Set<String>) {
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = manifest.get("formatVersion")
        if (version != 1 || (manifest.get("appId") as? String).isNullOrBlank() ||
            manifest.get("appVersion") !is String || "cookies.json" !in inventory
        ) throw InvalidAstralExport()
        try {
            val createdAt = manifest.get("createdAt") as? String ?: throw InvalidAstralExport()
            if (!createdAt.endsWith("Z")) throw InvalidAstralExport()
            Instant.parse(createdAt)
        } catch (_: java.time.format.DateTimeParseException) {
            throw InvalidAstralExport()
        }
        val entries = manifest.getJSONArray("entries")
        if (entries.length() != inventory.size) throw InvalidAstralExport()
        val listed = (0 until entries.length()).mapTo(mutableSetOf()) {
            entries.get(it) as? String ?: throw InvalidAstralExport()
        }
        if (listed != inventory) throw InvalidAstralExport()
    }

    private fun readPreferences(
        bytes: ByteArray,
        mapping: Map<String, (PreferencesStore, Boolean) -> Unit>,
        output: MutableList<Preference>,
    ): Int {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(bytes.inputStream(), null)
        var foundMap = false
        var skipped = 0
        val seen = mutableSetOf<String>()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.DOCDECL -> throw InvalidAstralExport()
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.depth) {
                    1 -> {
                        if (parser.name != "map" || foundMap) throw InvalidAstralExport()
                        foundMap = true
                    }
                    2 -> {
                        val name = parser.getAttributeValue(null, "name")
                        if (name != null && !seen.add(name)) throw InvalidAstralExport()
                        val apply = name?.let(mapping::get)
                        val value = parser.getAttributeValue(null, "value")?.toBooleanStrictOrNull()
                        if (apply != null && parser.name == "boolean" && value != null) {
                            output += Preference(apply, value)
                        } else {
                            skipped++
                        }
                    }
                }
            }
        }
        if (!foundMap) throw InvalidAstralExport()
        return skipped
    }

    private fun parseCookies(bytes: ByteArray): CookiePlan {
        val records = JSONArray(bytes.toString(Charsets.UTF_8))
        if (records.length() > 1024) throw AstralExportTooLarge()
        val parsed = List(records.length()) { index ->
            val record = records.getJSONObject(index)
            CookieRecord(
                record.get("url") as? String ?: throw InvalidAstralExport(),
                record.get("cookie") as? String ?: throw InvalidAstralExport(),
                if (record.has("profile")) {
                    record.get("profile") as? String ?: throw InvalidAstralExport()
                } else null,
            )
        }
        // Sonar uses the default jar. Prefer Astral's dedicated YouTube profile
        // when present and avoid merging accounts from different source jars.
        val youtubeProfile = "ytmusic".takeIf { profile ->
            parsed.any { it.profile == profile && it.url in youtubeUrls && it.header.isNotBlank() }
        }
        val values = linkedSetOf<Cookie>()
        var skippedPairs = 0
        var skippedRecords = 0
        for (record in parsed.sortedBy { it.url.endsWith("/login") }) {
            val allowed = (record.url in spotifyUrls && record.profile == null) ||
                (record.url in youtubeUrls && record.profile == youtubeProfile)
            if (!allowed) {
                skippedRecords++
                continue
            }
            val path = if (record.url.endsWith("/login")) "/login" else "/"
            for (part in record.header.splitToSequence(';')) {
                val pair = part.trim()
                val name = pair.substringBefore('=')
                if ('=' !in pair || !cookieName.matches(name) ||
                    name.lowercase() in cookieAttributes ||
                    pair.length > 4096 || pair.any { it.code !in 0x21..0x7e }
                ) {
                    skippedPairs++
                } else if (path == "/login" &&
                    Cookie(record.url.removeSuffix("login"), "$pair; Path=/; Secure") in values
                ) {
                    // A root cookie can already be visible at /login.
                    skippedPairs++
                } else if (!values.add(Cookie(record.url, "$pair; Path=$path; Secure"))) {
                    skippedPairs++
                }
                if (values.size + skippedPairs > 8192) throw AstralExportTooLarge()
            }
        }
        return CookiePlan(values, skippedPairs, skippedRecords)
    }

    private suspend fun installCookies(application: SonarApplication, cookies: Set<Cookie>): CookieInstall {
        if (cookies.isEmpty()) return CookieInstall(0, 0)
        return try {
            val manager = CookieManager.getInstance()
            // Check all related origins before replaying any cookie for that service.
            val occupiedUrls = listOf(spotifyUrls, youtubeUrls)
                .filter { urls -> urls.any { !manager.getCookie(it).isNullOrBlank() } }
                .flatten().toMutableSet()
            if (application.spotifySignedIn || application.webApi.isReady || application.spotifySdkAccount.tokens.isLoggedIn) {
                occupiedUrls.addAll(spotifyUrls)
            }
            if (application.youtubeAccount.isSignedIn) occupiedUrls.addAll(youtubeUrls)
            val (preserved, pending) = cookies.partition { it.url in occupiedUrls }
            if (pending.isEmpty()) return CookieInstall(0, preserved.size)
            var accepted = 0
            try {
                for (cookie in pending) {
                    currentCoroutineContext().ensureActive()
                    val stored = suspendCancellableCoroutine { continuation ->
                        manager.setCookie(cookie.url, cookie.value) { continuation.resume(it == true) }
                    }
                    if (stored) accepted++
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { manager.flush() }
            }
            CookieInstall(accepted, preserved.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            throw IOException("WebView cookie storage unavailable")
        }
    }

}

/** ZIP validation is independent of Android so malformed archives can be tested on the JVM. */
internal object AstralImportZip {
    private const val MAX_ENTRIES = 16_384
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024

    fun read(
        bytes: ByteArray,
        retain: (String) -> Boolean,
        entryRead: (String, ByteArray?) -> Unit,
    ): Set<String> {
        if (bytes.size > SonarImportCrypto.MAX_ZIP_BYTES) throw AstralExportTooLarge()
        val seen = linkedSetOf<String>()
        var expanded = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !validName(name) || !seen.add(name)) throw InvalidAstralExport()
                if (seen.size > MAX_ENTRIES) throw AstralExportTooLarge()
                val metadata = !name.startsWith("files/")
                val limit = if (metadata) MAX_METADATA_BYTES else SonarImportCrypto.MAX_ZIP_BYTES
                if (entry.size > limit) throw AstralExportTooLarge()
                val content = if (retain(name)) ByteArrayOutputStream() else null
                var size = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count
                    expanded += count
                    if (size > limit || expanded > MAX_EXPANDED_BYTES) throw AstralExportTooLarge()
                    content?.write(buffer, 0, count)
                }
                entryRead(name, content?.toByteArray())
            }
        }
        verifyDirectory(bytes, seen)
        return seen
    }

    private fun verifyDirectory(bytes: ByteArray, names: Set<String>) {
        // Astral writes a single-disk ZIP. Its bounded size and entry count
        // allow the regular end record, and ZIP64 entry sizes remain unnecessary.
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun short(offset: Int) = data.getShort(offset).toInt() and 0xffff
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 0xffff)).firstOrNull {
            data.getInt(it) == 0x06054b50 && it + 22 + short(it + 20) == bytes.size
        } ?: throw InvalidAstralExport()
        if (short(end + 4) != 0 || short(end + 6) != 0 ||
            short(end + 8) != names.size || short(end + 10) != names.size
        ) throw InvalidAstralExport()
        val start = data.getInt(end + 16)
        if (start !in 0..end || data.getInt(end + 12) != end - start) throw InvalidAstralExport()
        var offset = start
        val centralNames = mutableSetOf<String>()
        repeat(names.size) {
            if (offset > end - 46 || data.getInt(offset) != 0x02014b50 || short(offset + 34) != 0) {
                throw InvalidAstralExport()
            }
            val nameLength = short(offset + 28)
            val nameStart = offset + 46
            val next = nameStart + nameLength + short(offset + 30) + short(offset + 32)
            if (next > end) throw InvalidAstralExport()
            val local = data.getInt(offset + 42)
            if (local !in 0..(start - 30) || data.getInt(local) != 0x04034b50 ||
                short(local + 26) != nameLength || local + 30 + nameLength > start ||
                (0 until nameLength).any { bytes[nameStart + it] != bytes[local + 30 + it] }
            ) throw InvalidAstralExport()
            val name = String(bytes, nameStart, nameLength, Charsets.UTF_8)
            if (!centralNames.add(name)) throw InvalidAstralExport()
            offset = next
        }
        if (offset != end || centralNames != names) throw InvalidAstralExport()
    }

    private fun validName(name: String): Boolean {
        if (name.length > 4096 || name.any { it == '\\' || it == ':' || it.code < 32 }) return false
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return name == "manifest.json" || name == "cookies.json" ||
            (parts.first() == "files" && parts.size >= 2) ||
            (parts.first() == "prefs" && parts.size == 2 && parts.last().endsWith(".xml"))
    }
}
