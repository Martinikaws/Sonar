package app.sonar.library

import android.content.Context
import app.sonar.data.CatalogTrack
import app.sonar.data.LocalLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class LocalPlaylist(val id: String, val name: String, val tracks: List<CatalogTrack> = emptyList())

/** Playlists that live on this device only; they may mix catalogue and phone tracks. */
@Serializable
data class LocalPlaylists(val lists: List<LocalPlaylist> = emptyList()) {
    fun create(id: String, name: String, tracks: List<CatalogTrack> = emptyList()): LocalPlaylists =
        if (name.isBlank()) this else copy(lists = lists + LocalPlaylist(id, name.trim(), tracks.distinctBy { it.uri }))

    fun rename(id: String, name: String): LocalPlaylists =
        if (name.isBlank()) this else edit(id) { it.copy(name = name.trim()) }

    fun delete(id: String) = copy(lists = lists.filterNot { it.id == id })

    /** Adding a track already in the list is a no-op. */
    fun add(id: String, track: CatalogTrack): LocalPlaylists =
        edit(id) { if (it.tracks.any { t -> t.uri == track.uri }) it else it.copy(tracks = it.tracks + track) }

    fun remove(id: String, trackUri: String): LocalPlaylists =
        edit(id) { it.copy(tracks = it.tracks.filterNot { t -> t.uri == trackUri }) }

    internal fun mergeImported(
        id: String,
        playlist: LocalPlaylist,
        deviceTracks: List<CatalogTrack>,
    ): LocalPlaylists {
        fun resolve(tracks: List<CatalogTrack>) = tracks.map { track ->
            val match = if (LocalLibrary.isSpotifyLocal(track.uri)) LocalLibrary.match(track, deviceTracks) else null
            if (match != null) track.copy(uri = match.uri, localFile = match.localFile) else track
        }.distinctBy { it.uri }
        val resolved = resolve(playlist.tracks)
        val tags = playlist.tracks.map(LocalLibrary::spotifyUri)
        val resolvedTags = resolved.map(LocalLibrary::spotifyUri)
        val existing = lists.firstOrNull { list ->
            list.name == playlist.name && list.tracks.all {
                LocalLibrary.isLocal(it.uri) || LocalLibrary.isSpotifyLocal(it.uri)
            } && list.tracks.map(LocalLibrary::spotifyUri).let { it == tags || it == resolvedTags }
        }
        return if (existing == null) create(id, playlist.name, resolved)
        else edit(existing.id) { it.copy(tracks = resolve(it.tracks)) }
    }

    private fun edit(id: String, change: (LocalPlaylist) -> LocalPlaylist) =
        copy(lists = lists.map { if (it.id == id) change(it) else it })
}

class LocalPlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sonar_local_playlists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<LocalPlaylists> = mutable.asStateFlow()

    fun create(name: String, tracks: List<CatalogTrack> = emptyList()) =
        update { it.create(UUID.randomUUID().toString(), name, tracks) }
    fun rename(id: String, name: String) = update { it.rename(id, name) }
    fun delete(id: String) = update { it.delete(id) }
    fun add(id: String, track: CatalogTrack) = update { it.add(id, track) }
    fun remove(id: String, trackUri: String) = update { it.remove(id, trackUri) }

    internal fun mergeImported(playlists: List<LocalPlaylist>, deviceTracks: List<CatalogTrack>) = update { current ->
        playlists.fold(current) { state, playlist ->
            state.mergeImported(UUID.randomUUID().toString(), playlist, deviceTracks)
        }
    }

    @Synchronized
    private fun update(change: (LocalPlaylists) -> LocalPlaylists) {
        val after = change(mutable.value)
        if (after == mutable.value) return
        prefs.edit().putString(KEY, json.encodeToString(LocalPlaylists.serializer(), after)).apply()
        mutable.value = after
    }

    private fun load(): LocalPlaylists = runCatching {
        json.decodeFromString(LocalPlaylists.serializer(), prefs.getString(KEY, null) ?: return@runCatching LocalPlaylists())
    }.getOrDefault(LocalPlaylists())

    private companion object { const val KEY = "lists" }
}
