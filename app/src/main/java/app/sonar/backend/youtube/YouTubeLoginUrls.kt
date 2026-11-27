package app.sonar.backend.youtube

import java.net.URI

private fun httpsHost(url: String?): String? {
    val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
        uri.port !in listOf(-1, 443)
    ) return null
    val host = uri.host ?: return null
    if (!uri.rawAuthority.equals(host, ignoreCase = true) &&
        !uri.rawAuthority.equals("$host:443", ignoreCase = true)
    ) return null
    return host.lowercase(java.util.Locale.ROOT)
}

internal fun isYouTubeLoginCompletion(url: String?): Boolean = httpsHost(url) == "music.youtube.com"

internal fun isYouTubeLoginNavigation(url: String?): Boolean {
    val host = httpsHost(url) ?: return false
    return listOf("google.com", "youtube.com").any { host == it || host.endsWith(".$it") }
}
