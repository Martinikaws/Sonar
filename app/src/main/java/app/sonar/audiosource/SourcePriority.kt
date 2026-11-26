package app.sonar.audiosource

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class AudioSource { FLAC, SPOTIFY, YOUTUBE_MUSIC }

/** Spotify first, then Soloist's lossless stream, then a YouTube Music match. */
val DEFAULT_SOURCE_ORDER = listOf(AudioSource.SPOTIFY, AudioSource.FLAC, AudioSource.YOUTUBE_MUSIC)

data class SourcePriority(
    val order: List<AudioSource> = DEFAULT_SOURCE_ORDER,
    val disabled: Set<AudioSource> = emptySet(),
) {
    init {
        require(order.size == AudioSource.entries.size && order.toSet().size == order.size)
    }

    fun move(source: AudioSource, toIndex: Int): SourcePriority {
        val reordered = order.toMutableList()
        reordered.remove(source)
        reordered.add(toIndex.coerceIn(0, reordered.size), source)
        return copy(order = reordered)
    }

    fun setEnabled(source: AudioSource, enabled: Boolean): SourcePriority =
        copy(disabled = if (enabled) disabled - source else disabled + source)

    fun effective(): List<AudioSource> =
        order.filterNot { it in disabled }.ifEmpty { listOf(AudioSource.SPOTIFY) }

    /**
     * The enabled sources still to try for a track once [tried] have failed,
     * best first. FLAC is left out unless Soloist can serve it.
     */
    fun fallbacks(tried: Set<AudioSource>, flacReady: Boolean): List<AudioSource> =
        effective().filter { it !in tried && (it != AudioSource.FLAC || flacReady) }

    fun toJson(): String = JSONObject()
        .put("order", JSONArray(order.map { it.name }))
        .put("disabled", JSONArray(disabled.map { it.name }))
        .toString()

    companion object {
        fun fromJson(json: String?): SourcePriority {
            if (json == null) return SourcePriority()
            val obj = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return SourcePriority()
            }
            val order = obj.optJSONArray("order").readSources()
            val disabled = obj.optJSONArray("disabled").readSources()
            return SourcePriority((order + DEFAULT_SOURCE_ORDER).distinct(), disabled.toSet())
        }

        private fun JSONArray?.readSources(): List<AudioSource> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index ->
                val name = opt(index)
                AudioSource.entries.firstOrNull { it.name == name }
            }
        }
    }
}

class SourcePriorityStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _priority = MutableStateFlow(SourcePriority.fromJson(prefs.getString(KEY_PRIORITY, null)))
    val priority: StateFlow<SourcePriority> = _priority.asStateFlow()

    fun set(priority: SourcePriority) {
        if (priority == _priority.value) return
        prefs.edit().putString(KEY_PRIORITY, priority.toJson()).apply()
        _priority.value = priority
    }

    private companion object {
        const val FILE_NAME = "sonar_audio_source"
        const val KEY_PRIORITY = "priority"
    }
}
