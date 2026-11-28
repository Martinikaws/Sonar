package app.sonar.extensions

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

internal class ExtensionStore(filesDir: File) {
    private val directory = File(filesDir, "extensions")

    data class Installed(
        val id: String,
        val manifest: ExtensionManifest?,
        val origin: String? = null,
        val enabled: Boolean = manifest != null,
    )

    fun installed(): List<Installed> = synchronized(installLock) {
        if (!directory.exists()) return@synchronized emptyList()
        val files = checkNotNull(directory.listFiles()) { "Cannot list extensions" }
        files.filter { it.name.endsWith(".zip") && !it.isDirectory }.map { file ->
            val id = file.name.dropLast(4)
            try {
                val (pkg, origin) = read(id)
                Installed(id, pkg.manifest, origin, !disabledMarker(id).exists())
            } catch (_: Exception) {
                Installed(id, null)
            }
        }.sortedBy { it.manifest?.name ?: it.id }
    }

    fun load(id: String): ExtensionPackage = read(id).first

    fun setEnabled(id: String, enabled: Boolean): Unit = synchronized(installLock) {
        require(packageFile(id).isFile) { "Extension is not installed" }
        val marker = disabledMarker(id).toPath()
        if (enabled) {
            Files.deleteIfExists(marker)
        } else {
            marker.toFile().createNewFile()
        }
        Unit
    }

    fun status(id: String, locale: Locale): String? {
        val pkg = synchronized(installLock) {
            if (disabledMarker(id).exists()) return null
            load(id)
        }
        return ExtensionRuntime(pkg).status(locale)
    }

    private fun read(id: String): Pair<ExtensionPackage, String?> = packageFile(id).inputStream().buffered().use {
        val input = DataInputStream(it)
        it.mark(1)
        // The marker cannot be the first character of a legacy SHA-256 header.
        val origin = if (input.readUnsignedByte() == ORIGIN_MARKER) input.readUTF() else {
            it.reset()
            null
        }
        val digest = ByteArray(64)
        input.readFully(digest)
        val pkg = ExtensionPackage.read(input.readLimited(ExtensionPackage.MAX_ZIP), digest.decodeToString())
        require(pkg.manifest.id == id) { "Package ID does not match filename" }
        pkg to origin
    }

    fun install(bytes: ByteArray, sha256: String): ExtensionManifest =
        install(bytes, sha256, null, null, false)

    fun installFromCatalog(
        bytes: ByteArray,
        entry: CatalogExtension,
        origin: String,
        update: Boolean,
    ): ExtensionManifest = install(bytes, entry.sha256, entry, origin, update)

    private fun install(
        bytes: ByteArray,
        sha256: String,
        entry: CatalogExtension?,
        origin: String?,
        update: Boolean,
    ) = synchronized(installLock) {
        val manifest = ExtensionPackage.read(bytes, sha256).manifest
        val target = packageFile(manifest.id)
        val current = if (target.exists()) read(manifest.id) else null
        if (entry != null) {
            require(manifest.id == entry.id && manifest.versionCode == entry.versionCode &&
                manifest.version == entry.version && manifest.author == entry.author && manifest.apiVersion == entry.apiVersion
            ) { "Package manifest does not match catalog" }
            require(if (update) current != null && current.second == origin else current == null) {
                "Installed extension changed"
            }
        }
        require(current == null || manifest.versionCode > current.first.manifest.versionCode) {
            "An equal or newer version is already installed"
        }
        Files.createDirectories(directory.toPath())
        val staged = File.createTempFile("install-", ".tmp", directory)
        try {
            staged.outputStream().use {
                if (origin != null) {
                    val output = DataOutputStream(it)
                    output.writeByte(ORIGIN_MARKER)
                    output.writeUTF(origin)
                }
                it.write(sha256.encodeToByteArray())
                it.write(bytes)
                it.fd.sync()
            }
            if (current == null) Files.deleteIfExists(disabledMarker(manifest.id).toPath())
            Files.move(staged.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            staged.delete()
        }
        manifest
    }

    fun uninstall(id: String): Unit = synchronized(installLock) {
        val filename = "$id.zip"
        val target = File(directory, filename)
        require(File(filename).name == filename && target.parentFile == directory) { "Invalid package filename" }
        Files.deleteIfExists(target.toPath())
        if (ExtensionManifest.validId(id)) Files.deleteIfExists(disabledMarker(id).toPath())
        Unit
    }

    private fun disabledMarker(id: String): File {
        require(ExtensionManifest.validId(id)) { "Invalid extension ID" }
        return File(directory, "$id.disabled")
    }

    private fun packageFile(id: String): File {
        require(ExtensionManifest.validId(id)) { "Invalid extension ID" }
        return File(directory, "$id.zip")
    }

    private companion object {
        const val ORIGIN_MARKER = '!'.code
        val installLock = Any()
    }
}
