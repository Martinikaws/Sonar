package app.sonar.extensions

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class ExtensionStore(filesDir: File) {
    private val directory = File(filesDir, "extensions")

    data class Installed(val id: String, val manifest: ExtensionManifest?)

    fun installed(): List<Installed> {
        if (!directory.exists()) return emptyList()
        val files = checkNotNull(directory.listFiles()) { "Cannot list extensions" }
        return files.filter { it.name.endsWith(".zip") && !it.isDirectory }.map { file ->
            val id = file.name.dropLast(4)
            val manifest = try {
                load(id).manifest
            } catch (_: Exception) {
                null
            }
            Installed(id, manifest)
        }.sortedBy { it.manifest?.name ?: it.id }
    }

    fun load(id: String): ExtensionPackage = packageFile(id).inputStream().use {
        val digest = ByteArray(64)
        java.io.DataInputStream(it).readFully(digest)
        ExtensionPackage.read(it.readLimited(ExtensionPackage.MAX_ZIP), digest.decodeToString())
            .also { pkg -> require(pkg.manifest.id == id) { "Package ID does not match filename" } }
    }

    fun install(bytes: ByteArray, sha256: String) = synchronized(installLock) {
        val manifest = ExtensionPackage.read(bytes, sha256).manifest
        val target = packageFile(manifest.id)
        require(!target.exists() || manifest.versionCode > load(manifest.id).manifest.versionCode) {
            "An equal or newer version is already installed"
        }
        Files.createDirectories(directory.toPath())
        val staged = File.createTempFile("install-", ".tmp", directory)
        try {
            staged.outputStream().use {
                it.write(sha256.encodeToByteArray())
                it.write(bytes)
                it.fd.sync()
            }
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
    }

    private fun packageFile(id: String): File {
        require(ExtensionManifest.validId(id)) { "Invalid extension ID" }
        return File(directory, "$id.zip")
    }

    private companion object {
        val installLock = Any()
    }
}
