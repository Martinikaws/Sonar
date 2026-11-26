package app.sonar.migration

import android.content.Context
import android.net.Uri
import android.util.Xml
import android.webkit.CookieManager
import app.sonar.SonarApplication
import app.sonar.data.PreferencesStore
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    )

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

    // Only add paths whose on-disk schema Sonar can read directly.
    private val compatibleFiles = emptySet<String>()

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

    private data class Preference(val apply: (PreferencesStore, Boolean) -> Unit, val value: Boolean)
    private data class CookieRecord(val url: String, val header: String, val profile: String?)
    private data class Cookie(val url: String, val value: String)
    private data class CookiePlan(val values: Set<Cookie>, val skippedPairs: Int, val skippedRecords: Int)

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
        val preferences = mutableListOf<Preference>()
        val files = mutableMapOf<String, ByteArray>()
        var preferencesSkipped = 0
        var preferenceFilesSkipped = 0
        var manifest: ByteArray? = null
        var cookies: CookiePlan? = null

        val inventory = try {
            AstralImportZip.read(zip, retain = { name ->
                name == "manifest.json" || name == "cookies.json" ||
                    name in settings || name.removePrefix("files/") in compatibleFiles
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
                    content != null -> files[name.removePrefix("files/")] = content
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
        val plan = cookies ?: throw InvalidAstralExport()
        currentCoroutineContext().ensureActive()
        val filesImported = files.count { (name, content) -> restoreFile(application.filesDir, name, content) }
        val accepted = withContext(Dispatchers.Main) {
            preferences.forEach { it.apply(application.preferences, it.value) }
            installCookies(plan.values)
        }
        return Result(
            filesImported, inventory.count { it.startsWith("files/") } - filesImported,
            preferences.size, preferencesSkipped, preferenceFilesSkipped,
            accepted, plan.skippedPairs + plan.values.size - accepted, plan.skippedRecords,
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

    private suspend fun installCookies(cookies: Set<Cookie>): Int {
        if (cookies.isEmpty()) return 0
        return try {
            val manager = CookieManager.getInstance()
            var accepted = 0
            try {
                for (cookie in cookies) {
                    val stored = suspendCancellableCoroutine { continuation ->
                        manager.setCookie(cookie.url, cookie.value) { continuation.resume(it == true) }
                    }
                    if (stored) accepted++
                }
            } finally {
                withContext(Dispatchers.IO) { manager.flush() }
            }
            accepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            throw IOException("WebView cookie storage unavailable")
        }
    }

    private fun restoreFile(root: File, name: String, content: ByteArray): Boolean {
        val target = File(root, name)
        val canonicalRoot = root.canonicalFile.toPath()
        val destination = target.canonicalFile.toPath()
        if (!destination.startsWith(canonicalRoot) ||
            destination != canonicalRoot.resolve(name) || Files.exists(target.toPath())
        ) return false
        val parent = target.parentFile ?: throw InvalidAstralExport()
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create import directory")
        }
        val staged = File.createTempFile("astral-import-", ".tmp", parent)
        return try {
            staged.writeBytes(content)
            Files.move(staged.toPath(), target.toPath())
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } finally {
            Files.deleteIfExists(staged.toPath())
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
