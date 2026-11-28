package app.sonar.parity.modules

import app.sonar.extensions.ExtensionPackage
import app.sonar.extensions.readLimited
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal fun extensionPackageUrl(input: String): HttpUrl? =
    input.trim().toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    }

// TODO(X05): Decide which UI surfaces and host actions extensions may register, and which need confirmation.
// TODO(X06): Approve audio and download provider roles, their separate default orders and fallback rules.
// TODO(X07): Choose metadata provider order and define when missing fields or errors trigger fallback.
// TODO(X08): Define which appearance tokens themes may override and how overrides are validated.
// TODO(X09): Decide what device identity and attestation data may leave the device and how re-enrollment works.
// TODO(play-counting): Decide what playback events may be shared and how users grant or revoke account consent.
internal object ExtensionDownload {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetch(url: HttpUrl): ByteArray {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val body = checkNotNull(response.body)
            require(body.contentLength() <= ExtensionPackage.MAX_ZIP)
            body.byteStream().readLimited(ExtensionPackage.MAX_ZIP)
        }
    }
}
