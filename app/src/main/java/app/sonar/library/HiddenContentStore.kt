package app.sonar.library

import android.content.Context
import app.sonar.data.CatalogTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** One instance per app session; see [HiddenContent]. */
class HiddenContentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sonar_hidden_content", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<HiddenContent> = mutable.asStateFlow()

    fun hideSong(track: CatalogTrack) = update { it.hideSong(track) }
    fun showSong(uri: String) = update { it.showSong(uri) }
    fun banArtist(uri: String?, name: String) = update { it.banArtist(uri, name) }
    fun unbanArtist(uri: String) = update { it.unbanArtist(uri) }

    @Synchronized
    private fun update(change: (HiddenContent) -> HiddenContent) {
        val after = change(mutable.value)
        if (after == mutable.value) return
        prefs.edit().putString(KEY, json.encodeToString(HiddenContent.serializer(), after)).apply()
        mutable.value = after
    }

    // A damaged value reads as empty rather than crashing the app at start.
    private fun load(): HiddenContent = runCatching {
        json.decodeFromString(HiddenContent.serializer(), prefs.getString(KEY, null) ?: return@runCatching HiddenContent())
    }.getOrDefault(HiddenContent())

    private companion object { const val KEY = "content" }
}
