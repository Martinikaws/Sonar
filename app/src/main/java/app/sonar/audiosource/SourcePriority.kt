package app.sonar.audiosource

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class AudioSource { FLAC, SPOTIFY, YOUTUBE_MUSIC }

internal enum class TrackSourceAction { WAIT, PLAY_SPOTIFY, LOOK_UP_FLAC, LOOK_UP_YOUTUBE, RECOVER_WITH_WEB, EXHAUSTED }

/** Spotify first, then a lossless provider, then a YouTube Music match. */
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
     * Spotify is the only source that can actually play: FLAC and YouTube Music
     * count only once set up, so the default order (all enabled, none set up)
     * still leaves Soloist free to play.
     */
    fun spotifyOnly(flacReady: Boolean, youtubeAllowed: Boolean): Boolean =
        effective().filter {
            when (it) {
                AudioSource.SPOTIFY -> true
                AudioSource.FLAC -> flacReady
                AudioSource.YOUTUBE_MUSIC -> youtubeAllowed
            }
        } == listOf(AudioSource.SPOTIFY)

    /**
     * The enabled sources still to try for a track once [tried] have failed,
     * best first. FLAC requires a configured provider.
     */
    fun fallbacks(tried: Set<AudioSource>, flacReady: Boolean): List<AudioSource> =
        effective().filter { it !in tried && (it != AudioSource.FLAC || flacReady) }

    /** The next playable source for this attempt. */
    fun nextTrackSource(
        tried: Set<AudioSource>,
        youtubeAllowed: Boolean,
        spotifyPending: Boolean = false,
        flacReady: Boolean = false,
    ): AudioSource? {
        if (spotifyPending) return null
        return fallbacks(tried, flacReady).firstOrNull {
            it != AudioSource.YOUTUBE_MUSIC || youtubeAllowed
        }
    }

    internal fun trackSourceAction(
        tried: Set<AudioSource>,
        spotifyUnavailable: Boolean,
        lookupPending: Boolean,
        recoverWithWeb: Boolean,
        youtubeAllowed: Boolean = true,
        flacReady: Boolean = false,
    ): TrackSourceAction {
        val spotifyEnabled = AudioSource.SPOTIFY in effective()
        val spotifyPending = spotifyEnabled && AudioSource.SPOTIFY in tried && !spotifyUnavailable
        if (lookupPending || spotifyPending) return TrackSourceAction.WAIT
        return when (nextTrackSource(tried, youtubeAllowed, flacReady = flacReady)) {
            AudioSource.SPOTIFY -> TrackSourceAction.PLAY_SPOTIFY
            AudioSource.FLAC -> TrackSourceAction.LOOK_UP_FLAC
            AudioSource.YOUTUBE_MUSIC -> TrackSourceAction.LOOK_UP_YOUTUBE
            else -> if (spotifyEnabled && recoverWithWeb) TrackSourceAction.RECOVER_WITH_WEB else TrackSourceAction.EXHAUSTED
        }
    }

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
