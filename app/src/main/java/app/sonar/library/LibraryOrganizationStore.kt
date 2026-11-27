package app.sonar.library

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID

/** Use one instance per app session. Sort selection stays in Sonar's library view preferences. */
class LibraryOrganizationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sonar_library_organization", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val backup = AtomicFile(File(context.applicationContext.filesDir, "library_organization.json.bak"))
    private var writable = true
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
        if (!writable) return
        val before = mutableState.value
        val after = transform(before)
        if (after == before) return
        prefs.edit().putString(KEY_STATE, json.encodeToString(LibraryOrganization.serializer(), after)).apply()
        mutableState.value = after
    }

    private fun load(): LibraryOrganization {
        val raw = prefs.getString(KEY_STATE, null) ?: return LibraryOrganization()
        val snapshot = decodeLibraryOrganization(json, raw)
        if (snapshot == null || snapshot.damaged) {
            val backedUp = preserve(raw)
            writable = snapshot != null && backedUp
        }
        return snapshot?.organization ?: LibraryOrganization()
    }

    private fun preserve(raw: String): Boolean {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        return try {
            val output = backup.startWrite()
            try {
                output.write(bytes)
                backup.finishWrite(output)
            } catch (failure: IOException) {
                backup.failWrite(output)
                throw failure
            }
            backup.readFully().contentEquals(bytes)
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val KEY_STATE = "organization"
    }
}

internal data class LibraryOrganizationSnapshot(val organization: LibraryOrganization, val damaged: Boolean)

internal fun decodeLibraryOrganization(json: Json, raw: String): LibraryOrganizationSnapshot? {
    val root = try {
        json.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null

    var damaged = false
    fun <T> checked(value: T?): T? {
        if (value == null) damaged = true
        return value
    }
    fun string(value: JsonElement): String? =
        checked((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
    fun array(value: JsonElement): List<JsonElement> = checked(value as? JsonArray).orEmpty()
    fun <T> entries(key: String, decode: (JsonElement) -> T?): Map<String, T> =
        checked((root[key] ?: JsonObject(emptyMap())) as? JsonObject).orEmpty()
            .mapNotNull { (id, value) -> decode(value)?.let { id to it } }.toMap()

    val recovered = LibraryOrganization(
        folders = array(root["folders"] ?: JsonArray(emptyList())).mapNotNull {
            checked(try {
                json.decodeFromJsonElement(LibraryFolder.serializer(), it)
            } catch (_: SerializationException) {
                null
            })
        },
        membership = entries("membership", ::string),
        orders = entries("orders") { value ->
            checked(value as? JsonArray)?.mapNotNull(::string)
        },
        hiddenTracks = entries("hiddenTracks") { value ->
            checked(value as? JsonArray)?.mapNotNull(::string)?.toSet()
        },
    )
    return LibraryOrganizationSnapshot(recovered, damaged)
}
