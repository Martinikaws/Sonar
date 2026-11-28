package app.sonar.animart

import java.net.URI

internal fun canFetchAnimatedArt(wifi: Boolean, conserving: Boolean): Boolean =
    wifi && !conserving

internal fun animatedArtUrl(catalogUrl: String?): String? {
    val uri = catalogUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    return catalogUrl.takeIf {
        uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null
    }
}

internal fun animatedArtCacheKey(url: String): String = url.substringBefore('#')
