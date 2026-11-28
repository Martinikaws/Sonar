package app.sonar.library.importlink

import app.sonar.R
import app.sonar.SonarApplication
import app.sonar.data.Catalog
import app.sonar.data.CatalogTrack
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlaylistImport(val name: String, val tracks: List<CatalogTrack>, val missing: Int)

internal suspend fun loadPlaylist(container: SonarApplication, link: PlaylistLink): PlaylistImport =
    when (link) {
        is PlaylistLink.Spotify -> loadSpotify(container, link.id)
        is PlaylistLink.YouTube -> loadYouTube(container, link.id)
        else -> error("Expected a supported playlist link")
    }

private suspend fun loadSpotify(container: SonarApplication, id: String): PlaylistImport {
    if (container.webApi.isReady) {
        try {
            val name = container.api.playlist(id).name.ifBlank {
                container.getString(R.string.import_spotify_playlist)
            }
            val (tracks, missing) = container.spotifyBackend.webApiPlaylistTracks(id, requireComplete = true)
            return PlaylistImport(name, tracks, missing)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Some public playlists are only readable through Spotify's catalog gateway.
        }
    }
    val uri = "spotify:playlist:$id"
    var total = 0
    val entries = container.gateway.readAll { offset ->
        container.gateway.playlistTracks(uri, offset)?.also { page ->
            check(page.nextOffset == null || page.nextOffset > offset)
            total = page.total
        }
    }
    currentCoroutineContext().ensureActive()
    val tracks = checkNotNull(entries).filter { it.uri.startsWith("spotify:track:") }
    val name = Catalog.playlistName(uri)?.takeIf { it.isNotBlank() }
        ?: container.getString(R.string.import_spotify_playlist)
    return PlaylistImport(name, tracks, (total - tracks.size).coerceAtLeast(0))
}

private suspend fun loadYouTube(container: SonarApplication, id: String): PlaylistImport {
    val first = YouTube.playlist(id).getOrThrow()
    val name = first.playlist.title
    check(name.isNotBlank())
    val songs = first.songs.toMutableList()
    var missing = first.unavailableCount
    val seen = mutableSetOf<String>()
    var token = first.songsContinuation ?: first.continuation
    while (token != null) {
        check(seen.add(token))
        val page = YouTube.playlistContinuation(token).getOrThrow()
        songs += page.songs
        missing += page.unavailableCount
        token = page.continuation
    }
    val tracks = songs.filterNot { it.isEpisode }.map(container.youtubeBackend::toCatalogTrack)
    return PlaylistImport(name, tracks, missing + songs.size - tracks.size)
}
