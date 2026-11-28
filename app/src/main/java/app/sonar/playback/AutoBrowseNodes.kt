package app.sonar.playback

internal object AutoBrowseNodes {
    const val ROOT = "sq/root"
    const val LIBRARY = "sq/library"
    const val PLAYLISTS = "sq/playlists"
    const val RECENT = "sq/recent"
    const val RADIO = "sq/radio"
    const val DOWNLOADS = "sq/downloads"
    const val SEARCH_PREFIX = "sq/q/"
    const val STATION_PREFIX = "sq/station/"
    private const val TRACK_PREFIX = "sq/t/"

    fun isOfflineNode(id: String): Boolean =
        id == ROOT || id == LIBRARY || id == DOWNLOADS || id == RECENT

    fun children(parentId: String, offline: Boolean = false): List<String> = when {
        offline && (parentId == ROOT || parentId == LIBRARY) -> listOf(DOWNLOADS)
        offline -> emptyList()
        parentId == ROOT -> listOf(LIBRARY, PLAYLISTS, RADIO, RECENT)
        parentId == LIBRARY -> listOf(PLAYLISTS, DOWNLOADS)
        else -> emptyList()
    }

    fun trackId(parentId: String, trackUri: String): String = "$TRACK_PREFIX$parentId/$trackUri"

    fun splitTrackId(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(TRACK_PREFIX)) return null
        val body = mediaId.removePrefix(TRACK_PREFIX)
        // Search queries can contain slashes. Track URIs use colon-delimited IDs.
        val cut = body.lastIndexOf('/')
        if (cut <= 0 || cut == body.lastIndex) return null
        return body.take(cut) to body.substring(cut + 1)
    }

    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong())
        val to = (from + pageSize).coerceAtMost(items.size.toLong())
        return items.subList(from.toInt(), to.toInt())
    }
}
