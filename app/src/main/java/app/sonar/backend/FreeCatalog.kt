package app.sonar.backend

import app.sonar.data.AlbumDto
import app.sonar.data.CatalogPlaylist
import app.sonar.data.CatalogTrack
import app.sonar.data.PlaylistDto
import app.sonar.data.TrackDto
import app.sonar.data.toCatalogTrack

/**
 * Catalogue reads for a Spotify Free account.
 *
 * Spotify refuses the native session to non-Premium accounts, so every surface
 * that normally asks it goes to the Web API instead, and where the Web API has
 * no answer the result is an empty one rather than a wait. Pure so it can be
 * tested without a device.
 */
object FreeCatalog {

    /** Liked Songs has no address of its own on the Web API; anything ending in `:collection` is routed to it. */
    const val COLLECTION_URI = "spotify:user:me:collection"

    /** True when the catalogue must skip the native session. */
    fun webOnly(freeAccount: Boolean, signedIn: Boolean): Boolean = freeAccount && signedIn

    /** 403 and 404 mean "not for this account or app": a missing section, not an error. */
    fun isRestricted(error: Throwable): Boolean =
        (error as? retrofit2.HttpException)?.code() in RESTRICTED

    /** Runs [block]; a restricted endpoint or a failed read gives [fallback]. */
    inline fun <T> orElse(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }

    fun likedSongs(name: String, cover: String) =
        CatalogPlaylist(uri = COLLECTION_URI, name = name, artworkUrl = cover)

    fun playlist(dto: PlaylistDto) = CatalogPlaylist(
        uri = dto.uri,
        name = dto.name,
        subtitle = dto.owner?.displayName ?: dto.owner?.id,
        artworkUrl = dto.images.firstOrNull()?.url,
    )

    /**
     * An album's own track list carries no album of its own, so the record the
     * tracks came from is put back on each of them.
     */
    fun albumTracks(album: AlbumDto?, items: List<TrackDto>): List<CatalogTrack> =
        items
            .filter { it.isPlayable != false && it.uri.startsWith("spotify:track:") }
            .map { (if (it.album == null && album != null) it.copy(album = album) else it).toCatalogTrack() }

    private val RESTRICTED = setOf(403, 404)
}
