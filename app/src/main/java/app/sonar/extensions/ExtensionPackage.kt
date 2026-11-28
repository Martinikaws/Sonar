package app.sonar.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal class ExtensionPackage private constructor(
    val manifest: ExtensionManifest,
    val script: String,
) {
    companion object {
        const val MAX_ZIP = 2 * 1024 * 1024
        private const val MAX_SCRIPT = 1024 * 1024
        private const val MAX_ICON = 256 * 1024
        private val metadata = setOf("manifest.json", "LICENSE", "LICENSE.txt", "README.md")
        private val digestFormat = Regex("[a-fA-F0-9]{64}")

        fun validDigest(digest: String): Boolean = digestFormat.matches(digest)

        fun verifySha256(bytes: ByteArray, expected: String): Boolean =
            validDigest(expected) && MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)

        fun read(bytes: ByteArray, expectedSha256: String): ExtensionPackage {
            require(bytes.size <= MAX_ZIP && verifySha256(bytes, expectedSha256)) {
                "Package size or SHA-256 check failed"
            }
            val files = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var remaining = MAX_ZIP
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(files.size < 30 && name !in files && !entry.isDirectory &&
                        name.none { it == '/' || it == '\\' || it == ':' } && ".." !in name
                    ) { "Invalid package entry" }
                    val content = zip.readLimited(minOf(remaining, MAX_SCRIPT))
                    files[name] = content
                    remaining -= content.size
                }
            }
            val manifest = ExtensionManifest.parse(
                files.getValue("manifest.json").decodeToString(throwOnInvalidSequence = true),
            )
            val icon = manifest.icon ?: "icon.png"
            require(manifest.icon == null || icon in files) { "Missing package icon" }
            val icons = setOf("icon.png", icon)
            require(icons.all { (files[it]?.size ?: 0) <= MAX_ICON }) { "Package icon is too large" }
            require(files.keys.all { it in metadata || it in icons || it == manifest.main }) {
                "Unexpected package entry"
            }
            return ExtensionPackage(
                manifest,
                files.getValue(manifest.main).decodeToString(throwOnInvalidSequence = true),
            )
        }
    }
}

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(count <= limit - output.size()) { "Input exceeds size limit" }
        output.write(buffer, 0, count)
    }
}
