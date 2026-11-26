package app.sonar.migration

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AstralImportTest {
    @Test
    fun roundTripWithAstralExporter() {
        val original = zip("files/listen_history.jsonl")
        val passphrase = "  café🎵 pass  ".toCharArray()
        val encrypted = encryptLikeAstral(original, passphrase)

        val decrypted = SonarImportCrypto.decrypt(encrypted.inputStream(), passphrase)

        assertArrayEquals(original, decrypted)
        assertEquals(setOf("files/listen_history.jsonl"), AstralImportZip.read(decrypted, { false }) { _, _ -> })
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun wrongPassphraseFailsAuthentication() {
        val encrypted = encryptLikeAstral(zip("cookies.json"), "right password".toCharArray())
        val wrong = "wrong password".toCharArray()

        assertThrows(AEADBadTagException::class.java) {
            SonarImportCrypto.decrypt(encrypted.inputStream(), wrong)
        }
        assertTrue(wrong.all { it == '\u0000' })
    }

    @Test
    fun changedCiphertextFailsAuthentication() {
        val passphrase = "right password".toCharArray()
        val encrypted = encryptLikeAstral(zip("cookies.json"), passphrase)
        encrypted[34] = (encrypted[34].toInt() xor 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            SonarImportCrypto.decrypt(encrypted.inputStream(), passphrase)
        }
        assertTrue(passphrase.all { it == '\u0000' })
    }

    @Test
    fun rejectsZipSlipAbsolutePathsAndBackslashes() {
        for (name in listOf("files/../outside", "files/nested/../../outside", "/files/outside", "files\\outside")) {
            assertThrows(InvalidAstralExport::class.java) {
                AstralImportZip.read(zip(name), { true }) { _, _ -> error("Unsafe entry reached callback") }
            }
        }
    }

    @Test
    fun rejectsDuplicateNames() {
        val archive = zip("files/first", "files/other")
        // Both names have the same length. Replace the local and central copies.
        val other = "files/other".toByteArray()
        val first = "files/first".toByteArray()
        for (offset in 0..archive.size - other.size) {
            if (other.indices.all { archive[offset + it] == other[it] }) {
                first.copyInto(archive, offset)
            }
        }
        assertThrows(InvalidAstralExport::class.java) {
            AstralImportZip.read(archive, { false }) { _, _ -> }
        }
    }

    @Test
    fun rejectsTruncatedZipDirectory() {
        val archive = zip("cookies.json")
        assertThrows(InvalidAstralExport::class.java) {
            AstralImportZip.read(archive.copyOf(archive.size - 1), { false }) { _, _ -> }
        }
    }

    @Test
    fun rejectsOversizedExpandedMetadataBeforePassingItToImporter() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cookies.json"))
            val block = ByteArray(1024)
            repeat(4 * 1024 + 1) { zip.write(block) }
            zip.closeEntry()
        }

        assertThrows(AstralExportTooLarge::class.java) {
            AstralImportZip.read(out.toByteArray(), { false }) { _, _ -> error("Oversized entry reached callback") }
        }
    }

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("fixture".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun encryptLikeAstral(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val spec = PBEKeySpec(passphrase, salt, 210_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        } finally {
            key.fill(0)
        }
        val out = ByteArrayOutputStream()
        out.write("SNMIG1".toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        cipher.update(plaintext)?.let { out.write(it) }
        out.write(cipher.doFinal())
        return out.toByteArray()
    }
}
