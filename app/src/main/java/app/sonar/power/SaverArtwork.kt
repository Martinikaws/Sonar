package app.sonar.power

internal const val SAVER_ARTWORK_SIZE = 384

private val appleSize = Regex("""(https://[a-z0-9-]+\.mzstatic\.com/[^?]*/)(\d+)x(\d+)([^/?]*(?:\?.*)?)""")
private val googleSize = Regex("""(https://(?:lh3|yt3)\.googleusercontent\.com/[^?]*=w)(\d+)-h(\d+)(.*)""")
private val googleSquare = Regex("""(https://yt3\.ggpht\.com/[^?]*=s)(?:\d+-s)*(\d+)(.*)""")
private val videoSize = Regex("""(https://i\.ytimg\.com/vi/[^/]+/)(?:sddefault|maxresdefault)(\.jpg(?:\?.*)?)""")

internal fun smallerArtworkUrl(url: String): String {
    appleSize.matchEntire(url)?.destructured?.let { (prefix, width, height, suffix) ->
        val (w, h) = cappedArtworkDimensions(width, height) ?: return url
        return "${prefix}${w}x$h$suffix"
    }
    googleSize.matchEntire(url)?.destructured?.let { (prefix, width, height, suffix) ->
        val (w, h) = cappedArtworkDimensions(width, height) ?: return url
        return "${prefix}$w-h$h$suffix"
    }
    googleSquare.matchEntire(url)?.destructured?.let { (prefix, size, suffix) ->
        if ((size.toIntOrNull() ?: 0) > SAVER_ARTWORK_SIZE) return "$prefix$SAVER_ARTWORK_SIZE$suffix"
    }
    videoSize.matchEntire(url)?.destructured?.let { (prefix, suffix) ->
        return "${prefix}hqdefault$suffix"
    }
    return url
}

private fun cappedArtworkDimensions(width: String, height: String): Pair<Int, Int>? {
    val w = width.toIntOrNull() ?: return null
    val h = height.toIntOrNull() ?: return null
    val longest = maxOf(w, h)
    if (w == 0 || h == 0 || longest <= SAVER_ARTWORK_SIZE) return null
    return (w.toLong() * SAVER_ARTWORK_SIZE / longest).toInt().coerceAtLeast(1) to
        (h.toLong() * SAVER_ARTWORK_SIZE / longest).toInt().coerceAtLeast(1)
}
