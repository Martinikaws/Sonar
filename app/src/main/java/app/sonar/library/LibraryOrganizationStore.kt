package app.sonar.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

/** Use one instance per app session. Sort selection stays in Sonar's library view preferences. */
class LibraryOrganizationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sonar_library_organization", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    fun reorder(folderId: String?, movableUris: List<String>, from: String, to: String) =
        update { it.reorder(folderId, movableUris, from, to) }

    fun createFolder(name: String) {
        val title = name.trim()
        if (title.isBlank()) return
        update { it.copy(folders = it.folders + LibraryFolder(UUID.randomUUID().toString(), title)) }
    }

    fun renameFolder(folderId: String, name: String) = update { it.renameFolder(folderId, name) }

    fun deleteFolder(folderId: String) = update { it.deleteFolder(folderId) }

    fun moveToFolder(playlistUri: String, folderId: String?) =
        update { it.moveToFolder(playlistUri, folderId) }

    fun setHidden(playlistUri: String, trackUri: String, hidden: Boolean) =
        update { it.setHidden(playlistUri, trackUri, hidden) }

    @Synchronized
    private fun update(transform: (LibraryOrganization) -> LibraryOrganization) {
        val before = mutableState.value
        val after = transform(before)
        if (after == before) return
        prefs.edit().putString(KEY_STATE, json.encodeToString(LibraryOrganization.serializer(), after)).apply()
        mutableState.value = after
    }

    private fun load(): LibraryOrganization {
        val raw = prefs.getString(KEY_STATE, null) ?: return LibraryOrganization()
        return try {
            json.decodeFromString(LibraryOrganization.serializer(), raw)
        } catch (_: SerializationException) {
            LibraryOrganization()
        }
    }

    private companion object {
        const val KEY_STATE = "organization"
    }
}
