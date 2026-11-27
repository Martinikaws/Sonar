package app.sonar.library

import app.sonar.data.CatalogTrack
import kotlinx.serialization.Serializable

@Serializable
data class HiddenSong(val uri: String, val name: String, val artist: String)

@Serializable
data class BannedArtist(val uri: String, val name: String)

/**
 * Songs and artists the listener never wants queued. Device-local: the services have no such
 * list. A track picked by hand still plays; queues, radio and lists skip them.
 */
@Serializable
data class HiddenContent(
    val songs: List<HiddenSong> = emptyList(),
    val artists: List<BannedArtist> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty()

    fun isHidden(track: CatalogTrack): Boolean {
        if (isEmpty) return false
        if (songs.any { it.uri == track.uri }) return true
        return artists.any { a -> a.uri == track.artistUri || track.artists.any { it.uri == a.uri } }
    }

    /** Drops hidden tracks, but never [keep]: the song the listener tapped plays. */
    fun filter(tracks: List<CatalogTrack>, keep: String? = null): List<CatalogTrack> =
        if (isEmpty) tracks else tracks.filter { it.uri == keep || !isHidden(it) }

    fun hideSong(track: CatalogTrack): HiddenContent =
        if (track.uri.isBlank() || songs.any { it.uri == track.uri }) this
        else copy(songs = listOf(HiddenSong(track.uri, track.name, track.artist)) + songs)

    fun showSong(uri: String) = copy(songs = songs.filterNot { it.uri == uri })

    fun banArtist(uri: String?, name: String): HiddenContent =
        if (uri.isNullOrBlank() || artists.any { it.uri == uri }) this
        else copy(artists = listOf(BannedArtist(uri, name)) + artists)

    fun unbanArtist(uri: String) = copy(artists = artists.filterNot { it.uri == uri })
}
