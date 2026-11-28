package app.sonar.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionPackageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun manifestReadsAstralDefaultsAndIgnoresUnsupportedMetadata() {
        val manifest = ExtensionManifest.parse(
            """{"id":"io.github.example","name":"Example","version":"1.0","author":"Dev","actions":[]}""",
        )
        assertEquals("io.github.example", manifest.id)
        assertEquals("main.js", manifest.main)
        assertEquals(1, manifest.apiVersion)
        assertEquals(1, manifest.versionCode)
    }

    @Test
    fun manifestRejectsUnsafePathsAndUnsupportedApiVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionManifest.parse(manifest.replace("io.github.example", "../device"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionManifest.parse(manifest.replace("}", ""","main":"../main.js"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionManifest.parse(manifest.replace("}", ""","apiVersion":2}"""))
        }
    }

    @Test
    fun sha256MatchesKnownDigestAndRejectsChanges() {
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(ExtensionPackage.verifySha256("abc".encodeToByteArray(), expected))
        assertTrue(ExtensionPackage.verifySha256("abc".encodeToByteArray(), expected.uppercase(Locale.ROOT)))
        assertFalse(ExtensionPackage.verifySha256("abd".encodeToByteArray(), expected))
        assertFalse(ExtensionPackage.verifySha256("abc".encodeToByteArray(), expected.dropLast(1)))
    }

    @Test
    fun packageRequiresMatchingDigestAndReadsEntryPoint() {
        val script = "myspoti.provide('status', function () { return { live: true }; });"
        val bytes = zipOf("manifest.json" to manifest, "main.js" to script)
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionPackage.read(bytes, "0".repeat(64))
        }
        val pkg = ExtensionPackage.read(bytes, digest(bytes))
        assertEquals(script, pkg.script)
        assertEquals("""{"live":true}""", ExtensionRuntime(pkg).status(Locale.ENGLISH))
    }

    @Test
    fun packageAcceptsDefaultAndCustomIconsAndLimitsBothSizes() {
        val customIconManifest = manifest.replace("}", ""","icon":"custom.png"}""")
        fun packageWithIcons(defaultIcon: String, customIcon: String) = zipOf(
            "manifest.json" to customIconManifest,
            "main.js" to "",
            "custom.png" to customIcon,
            "icon.png" to defaultIcon,
        )
        val valid = packageWithIcons("default", "custom")
        assertEquals("custom.png", ExtensionPackage.read(valid, digest(valid)).manifest.icon)
        val oversized = "x".repeat(256 * 1024 + 1)
        listOf(packageWithIcons(oversized, "custom"), packageWithIcons("default", oversized)).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) {
                ExtensionPackage.read(bytes, digest(bytes))
            }
        }
    }

    @Test
    fun unreadablePackagesCanBeRemovedWithoutHidingHealthyPackages() {
        val store = ExtensionStore(temp.root)
        val bytes = zipOf("manifest.json" to manifest, "main.js" to "")
        val healthy = store.install(bytes, digest(bytes))
        val damaged = File(temp.root, "extensions/io.github.damaged.zip").apply { writeText("damaged") }
        val unreadable = ExtensionStore.Installed(damaged.nameWithoutExtension, null)
        val readable = ExtensionStore.Installed(healthy.id, healthy)

        assertEquals(setOf(readable, unreadable), store.installed().toSet())
        store.uninstall(unreadable.id)
        assertFalse(damaged.exists())
        assertEquals(listOf(readable), store.installed())
    }

    @Test
    fun catalogInstallPersistsOriginAndRejectsUnexpectedPackageAndUpdate() {
        val store = ExtensionStore(temp.root)
        val first = zipOf("manifest.json" to manifest, "main.js" to "")
        val preview = CatalogExtension.from(ExtensionManifest.parse(manifest))
        val entry = preview.copy(sha256 = digest(first))
        assertThrows(IllegalArgumentException::class.java) {
            store.installFromCatalog(first, entry.copy(sha256 = "0".repeat(64)), "origin", update = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.installFromCatalog(first, entry.copy(versionCode = 2), "origin", update = false)
        }
        store.installFromCatalog(first, entry, "origin", update = false)
        val reopened = ExtensionStore(temp.root)
        assertEquals("origin", reopened.installed().single().origin)
        assertEquals(1, reopened.load(entry.id).manifest.versionCode)
        val nextManifest = manifest.dropLast(1) + ""","versionCode":2}"""
        val next = zipOf("manifest.json" to nextManifest, "main.js" to "")
        val update = entry.copy(versionCode = 2, sha256 = digest(next))
        assertThrows(IllegalArgumentException::class.java) {
            reopened.installFromCatalog(next, update, "other", update = true)
        }
        assertEquals(1, reopened.load(entry.id).manifest.versionCode)
        reopened.installFromCatalog(next, update, "origin", update = true)
        assertEquals(2, reopened.load(entry.id).manifest.versionCode)
        reopened.uninstall(entry.id)
        assertThrows(IllegalArgumentException::class.java) {
            reopened.installFromCatalog(next, update, "origin", update = true)
        }
    }

    @Test
    fun disabledExtensionStaysDisabledAcrossUpdateAndSkipsStatusHook() {
        val script = "myspoti.provide('status', function () { return { live: true }; });"
        val store = ExtensionStore(temp.root)
        val first = zipOf("manifest.json" to manifest, "main.js" to script)
        val id = store.install(first, digest(first)).id
        assertEquals("""{"live":true}""", store.status(id, Locale.ENGLISH))
        store.setEnabled(id, false)

        val reopened = ExtensionStore(temp.root)
        val nextManifest = manifest.dropLast(1) + ""","versionCode":2}"""
        val next = zipOf("manifest.json" to nextManifest, "main.js" to script)
        reopened.install(next, digest(next))
        assertFalse(reopened.installed().single().enabled)
        assertNull(reopened.status(id, Locale.ENGLISH))
        reopened.setEnabled(id, true)
        assertEquals("""{"live":true}""", reopened.status(id, Locale.ENGLISH))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val manifest = """{"id":"io.github.example","name":"Example","version":"1.0","author":"Dev"}"""
    }
}
