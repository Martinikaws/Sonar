package app.sonar.library

import app.sonar.data.CatalogPlaylist
import app.sonar.data.CatalogTrack
import app.sonar.data.DownloadStore
import app.sonar.data.LocalLibrary
import kotlinx.serialization.Serializable
import java.text.Collator

enum class LibrarySort(val key: String) {
    CUSTOM("custom"),
    CREATOR("creator"),
}

@Serializable
data class LibraryFolder(val id: String, val name: String)

@Serializable
data class LibraryOrganization(
    val folders: List<LibraryFolder> = emptyList(),
    val membership: Map<String, String> = emptyMap(),
    val orders: Map<String, List<String>> = emptyMap(),
    val hiddenTracks: Map<String, Set<String>> = emptyMap(),
) {
    fun atLevel(playlistUri: String, folderId: String?): Boolean =
        membership[playlistUri] == folderId

    fun customOrder(folderId: String?): List<String> =
        orders[folderId ?: ROOT].orEmpty()

    fun renameFolder(folderId: String, name: String): LibraryOrganization {
        val title = name.trim()
        if (title.isEmpty()) return this
        return copy(folders = folders.map { if (it.id == folderId) it.copy(name = title) else it })
    }

    fun deleteFolder(folderId: String): LibraryOrganization = copy(
        folders = folders.filterNot { it.id == folderId },
        membership = membership.filterValues { it != folderId },
        orders = orders - folderId,
    )

    fun moveToFolder(playlistUri: String, folderId: String?): LibraryOrganization {
        if (playlistUri.isBlank() || (folderId != null && folders.none { it.id == folderId })) return this
        if (membership[playlistUri] == folderId) return this
        return copy(
            membership = if (folderId == null) membership - playlistUri
            else membership + (playlistUri to folderId),
            orders = orders.mapValues { (_, uris) -> uris - playlistUri },
        )
    }

    fun setHidden(playlistUri: String, trackUri: String, hidden: Boolean): LibraryOrganization {
        if (playlistUri.isBlank() || trackUri.isBlank()) return this
        val tracks = hiddenTracks[playlistUri].orEmpty()
        val updated = if (hidden) tracks + trackUri else tracks - trackUri
        return copy(hiddenTracks = if (updated.isEmpty()) hiddenTracks - playlistUri
            else hiddenTracks + (playlistUri to updated))
    }

    fun reorder(
        folderId: String?,
        movableUris: List<String>,
        from: String,
        to: String,
    ): LibraryOrganization {
        if (folderId != null && folders.none { it.id == folderId }) return this
        val saved = customOrder(folderId)
        val visible = movableUris.distinct().filter { atLevel(it, folderId) }
            .inCustomLibraryOrder(saved) { it }
        val source = visible.indexOf(from)
        val target = visible.indexOf(to)
        if (source < 0 || target < 0 || source == target) return this

        val savedSet = saved.toSet()
        val visibleSet = visible.toSet()
        val moved = visible.toMutableList().apply { add(target, removeAt(source)) }.iterator()
        // Keep remembered positions for playlists absent from the current snapshot.
        val baseline = visible.filterNot(savedSet::contains) + saved
        val reordered = baseline.map { if (it in visibleSet) moved.next() else it }
        return copy(orders = orders + ((folderId ?: ROOT) to reordered))
    }

    companion object {
        private const val ROOT = "root"
    }
}

/** The input is Sonar's playlist collection, excluding albums, artists and podcasts. */
fun CatalogPlaylist.canOrganize(pinned: Collection<String>): Boolean =
    uri !in pinned && uri != LocalLibrary.CONTEXT_URI && uri != DownloadStore.SINGLES &&
        uri != app.sonar.data.Catalog.DJ_URI &&
        !uri.endsWith(":collection")

/** Newly discovered playlists appear first, in catalog order. */
fun <T> List<T>.inCustomLibraryOrder(saved: List<String>, key: (T) -> String): List<T> {
    val ranks = saved.withIndex().associate { (index, uri) -> uri to index }
    return sortedBy { ranks[key(it)] ?: -1 }
}

fun List<CatalogPlaylist>.inCreatorOrder(creator: (CatalogPlaylist) -> String): List<CatalogPlaylist> {
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    return sortedWith { left, right ->
        val comparison = collator.compare(creator(left), creator(right))
        if (comparison != 0) comparison else collator.compare(left.name, right.name)
    }
}

enum class PlaylistTrackSort {
    ORIGINAL, TITLE, ARTIST, ADDED, DURATION;

    companion object {
        fun fromPreference(value: String?): PlaylistTrackSort =
            entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

fun List<CatalogTrack>.visiblePlaylistTracks(
    sort: PlaylistTrackSort,
    descending: Boolean,
    hiddenUris: Set<String> = emptySet(),
    matches: (CatalogTrack) -> Boolean = { true },
): List<CatalogTrack> {
    val visible = filter { it.uri !in hiddenUris && matches(it) }
    val sorted = when (sort) {
        PlaylistTrackSort.ORIGINAL -> visible
        PlaylistTrackSort.TITLE -> visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        PlaylistTrackSort.ARTIST -> visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
        PlaylistTrackSort.ADDED -> visible.sortedWith(compareByDescending(nullsFirst()) { it.addedAt })
        PlaylistTrackSort.DURATION -> visible.sortedBy { it.durationMs }
    }
    return if (descending) sorted.reversed() else sorted
}

fun List<CatalogPlaylist>.atLibraryLevel(
    organization: LibraryOrganization,
    folderId: String?,
    movableUris: Set<String>,
): List<CatalogPlaylist> = filter { playlist ->
    if (playlist.uri in movableUris) organization.atLevel(playlist.uri, folderId)
    else folderId == null
}

/** Retain original playback indexes when hiding rows from the playlist view. */
fun List<CatalogTrack>.visiblePlaylistTrackIndexes(hiddenUris: Set<String>): List<Int> =
    indices.filter { this[it].uri !in hiddenUris }
