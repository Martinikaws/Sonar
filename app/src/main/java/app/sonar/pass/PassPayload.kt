package app.sonar.pass

import app.sonar.migration.SonarImportCrypto
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val PASS_ROUTE = "pass"
internal const val PASS_LINK_PREFIX = "sonar://pass/v1/"
internal const val PASS_MAX_FRAME = 32 * 1024
internal const val PASS_KEY_BYTES = 16

@Serializable
internal class PassPayload(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val name: String,
    val pageId: String?,
) {
    fun encode(passphrase: String): ByteArray =
        seal("session:", Json.encodeToString(serializer(), this), passphrase)

    companion object {
        fun decode(frame: ByteArray, passphrase: String): PassPayload =
            Json.decodeFromString(serializer(), open("session:", frame, passphrase)).also { payload ->
                require(payload.cookie.isNotBlank() && payload.cookie.all { it in ' '..'~' })
            }
    }
}

@Serializable
internal class PassInvite(val host: String, val port: Int, val key: String) {
    fun encode(passphrase: String): String =
        PASS_LINK_PREFIX + encodeBase64(seal("invite:", Json.encodeToString(serializer(), this), passphrase))

    companion object {
        fun accepts(link: String?): Boolean =
            link != null && link.length <= 512 && link.startsWith(PASS_LINK_PREFIX) &&
                link.removePrefix(PASS_LINK_PREFIX).matches(Regex("[A-Za-z0-9_-]+"))

        fun decode(link: String, passphrase: String): PassInvite {
            require(accepts(link))
            val bytes = decodeBase64(link.removePrefix(PASS_LINK_PREFIX))
            return Json.decodeFromString(serializer(), open("invite:", bytes, passphrase))
        }
    }
}

internal fun passRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
internal fun encodeBase64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun decodeBase64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

private fun seal(prefix: String, value: String, passphrase: String): ByteArray {
    val plaintext = (prefix + value).encodeToByteArray()
    try {
        require(plaintext.size <= PASS_MAX_FRAME - 50)
        return SonarImportCrypto.encrypt(plaintext, passphrase.toCharArray())
    } finally {
        plaintext.fill(0)
    }
}

private fun open(prefix: String, frame: ByteArray, passphrase: String): String {
    require(frame.size in 1..PASS_MAX_FRAME)
    val plaintext = SonarImportCrypto.decrypt(frame.inputStream(), passphrase.toCharArray())
    try {
        val text = plaintext.decodeToString(throwOnInvalidSequence = true)
        require(text.startsWith(prefix))
        return text.removePrefix(prefix)
    } finally {
        plaintext.fill(0)
    }
}
