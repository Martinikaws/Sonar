package app.sonar.migration

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal class InvalidAstralExport : IOException("Invalid Astral export")
internal class AstralExportTooLarge : IOException("Astral export exceeds import limits")

internal object SonarImportCrypto {
    // GCM providers can hold both the ciphertext and plaintext during authentication.
    const val MAX_ZIP_BYTES = 64 * 1024 * 1024
    private val magic = "SNMIG1".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 34
    private const val TAG_BYTES = 16
    private const val MAX_EXPORT_BYTES = HEADER_BYTES + MAX_ZIP_BYTES + TAG_BYTES

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        try {
            require(plaintext.size <= MAX_ZIP_BYTES)
            val header = ByteArray(HEADER_BYTES).also { SecureRandom().nextBytes(it) }
            magic.copyInto(header)
            return header + cipher(header, passphrase, Cipher.ENCRYPT_MODE).doFinal(plaintext)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /** Consumes the passphrase and authenticates the entire archive before returning it. */
    fun decrypt(input: InputStream, passphrase: CharArray): ByteArray {
        try {
            val header = ByteArray(HEADER_BYTES)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) throw InvalidAstralExport()
                offset += count
            }
            if (!magic.indices.all { header[it] == magic[it] }) throw InvalidAstralExport()
            val cipher = cipher(header, passphrase, Cipher.DECRYPT_MODE)

            // Android's GCM provider buffers decryption until the tag is verified.
            val plaintext = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = HEADER_BYTES
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_EXPORT_BYTES) throw AstralExportTooLarge()
                cipher.update(buffer, 0, count)?.let { if (it.isNotEmpty()) plaintext.write(it) }
            }
            if (total < HEADER_BYTES + TAG_BYTES) throw InvalidAstralExport()
            val final = cipher.doFinal()
            if (plaintext.size() == 0) return final
            plaintext.write(final)
            return plaintext.toByteArray()
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun cipher(header: ByteArray, passphrase: CharArray, mode: Int): Cipher {
        if (passphrase.size < 8) throw AEADBadTagException()
        val spec = PBEKeySpec(passphrase, header.copyOfRange(6, 22), 210_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BYTES * 8, header.copyOfRange(22, HEADER_BYTES)))
            }
        } finally {
            key.fill(0)
        }
    }
}
