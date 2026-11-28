package app.sonar.animart

import java.net.URI
import java.util.Locale

internal fun canFetchAnimatedArt(wifi: Boolean, conserving: Boolean): Boolean =
    wifi && !conserving

internal fun animatedArtUrl(catalogUrl: String?): String? {
    val uri = catalogUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    return catalogUrl.takeIf {
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "mvod.itunes.apple.com" || host.endsWith(".mzstatic.com"))
    }
}

internal fun animatedArtCacheKey(url: String): String = url.substringBefore('#')
