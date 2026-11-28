package app.sonar.playback.automix

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.Player
import app.sonar.SonarApplication
import app.sonar.nativecore.NativeBridge
import app.sonar.playback.Mixes
import app.sonar.playback.OfflineMode
import app.sonar.ui.EXTRA_CONTEXT_URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object AutoMix {
    data class PairKey(val from: String, val to: String) {
        val preferenceKey: String get() = "$from>$to"
    }

    data class PairInfo(
        val key: PairKey,
        val outgoing: String,
        val incoming: String,
        val outLength: Long,
        val inLength: Long,
    )

    data class State(
        val enabled: Boolean = false,
        val beatMatch: Boolean = true,
        val pair: PairInfo? = null,
        val suggested: TransitionTiming? = null,
        val saved: TransitionTiming? = null,
        val outLength: Long = 0,
        val inLength: Long = 0,
        val outBars: List<Long> = emptyList(),
        val inBars: List<Long> = emptyList(),
        val loading: Boolean = false,
        val unavailable: Boolean = false,
        val cachedTracks: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analyses = android.util.LruCache<String, Mixes.Analysis>(64)
    private val pending = mutableSetOf<String>()
    private val failed = android.util.LruCache<String, Long>(64)
    private var cacheVersion = 0
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
        refresh(context)
    }

    fun setBeatMatch(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("beat_match", enabled).apply()
        refresh(context)
    }

    fun observe(context: Context, player: Player, localNative: Boolean) {
        val current = player.currentMediaItem
        val next = player.nextMediaItemIndex.takeIf { it in 0 until player.mediaItemCount }
            ?.let(player::getMediaItemAt)
        val pair = if (localNative && player.repeatMode != Player.REPEAT_MODE_ONE &&
            current != null && next != null && current.mediaId != next.mediaId &&
            current.mediaId.startsWith("spotify:track:") && next.mediaId.startsWith("spotify:track:") &&
            current.mediaMetadata.extras?.getString(EXTRA_CONTEXT_URI)?.startsWith("spotify:album:") != true
        ) {
            PairInfo(
                PairKey(current.mediaId, next.mediaId),
                current.mediaMetadata.title?.toString() ?: current.mediaId,
                next.mediaMetadata.title?.toString() ?: next.mediaId,
                player.duration.takeIf { it > 0 } ?: current.mediaMetadata.durationMs ?: 0,
                next.mediaMetadata.durationMs ?: 0,
            )
        } else {
            null
        }
        if (pair != state.value.pair) {
            mutableState.value = state.value.copy(pair = pair)
            refresh(context)
        }
    }

    fun resetPair() {
        mutableState.value = state.value.copy(
            pair = null, suggested = null, saved = null, outBars = emptyList(),
            inBars = emptyList(), outLength = 0, inLength = 0, loading = false, unavailable = false,
        )
    }

    // TODO: Connect to LibrespotPlayer.followMix so the native mixer has one plan writer.
    fun transition(from: String, to: String): Mixes.Transition? {
        val value = state.value
        if (!value.enabled || value.pair?.key != PairKey(from, to)) return null
        val timing = value.saved ?: value.suggested?.takeIf { !value.loading && !value.unavailable } ?: return null
        return Mixes.Transition(-1, from, to, timing.outMs, timing.inMs, timing.fadeMs, 0f, null, timing.speedIn)
    }

    fun save(context: Context, key: PairKey, timing: TransitionTiming?) {
        val value = state.value
        if (value.pair?.key != key || timing != null && !timing.fits(value.outLength, value.inLength)) return
        val editor = preferences(context).edit()
        if (timing == null) {
            editor.remove(key.preferenceKey)
        } else {
            editor.putString(key.preferenceKey, listOf(timing.outMs, timing.inMs, timing.fadeMs, timing.speedIn).joinToString(","))
        }
        editor.apply()
        refresh(context)
    }

    fun clearAnalysis(context: Context) {
        cacheVersion++
        analyses.evictAll()
        failed.evictAll()
        pending.clear()
        refresh(context, requestAnalysis = false)
    }

    fun refresh(context: Context, requestAnalysis: Boolean = true) {
        val application = context.applicationContext as SonarApplication
        val prefs = preferences(application)
        val value = state.value.copy(
            enabled = prefs.getBoolean("enabled", false),
            beatMatch = prefs.getBoolean("beat_match", true),
        )
        val pair = value.pair
        val a = pair?.let { analyses.get(it.key.from) }
        val b = pair?.let { analyses.get(it.key.to) }
        val outLength = pair?.outLength?.takeIf { it > 0 } ?: a?.lengthMs ?: 0
        val inLength = pair?.inLength?.takeIf { it > 0 } ?: b?.lengthMs ?: 0
        val fade = application.crossfade.durationMs().toLong()
        val saved = if (pair != null && fade > 0) {
            read(application, pair.key)?.takeIf { it.fits(outLength, inLength) }
        } else {
            null
        }
        val suggested = if (a != null && b != null) {
            TransitionPlanner.plan(
                outLength, inLength, fade, a.fadeOutMs, b.fadeInMs,
                a.downbeats, b.downbeats, value.beatMatch,
            )
        } else if (pair != null) {
            val overlap = minOf(fade, outLength - 1, inLength)
            TransitionTiming(outLength - overlap, 0, overlap).takeIf { it.fits(outLength, inLength) }
        } else {
            null
        }
        val needed = listOfNotNull(pair?.key?.from?.takeIf { a == null }, pair?.key?.to?.takeIf { b == null })
        val network = application.getSystemService(ConnectivityManager::class.java)
        if (requestAnalysis && value.enabled && fade > 0 && !OfflineMode.active.value &&
            network?.activeNetwork != null && !network.isActiveNetworkMetered
        ) {
            fetch(application, needed)
        }
        val loading = needed.any { it in pending }
        mutableState.value = value.copy(
            suggested = suggested,
            saved = saved,
            outLength = outLength,
            inLength = inLength,
            outBars = a?.downbeats?.toList().orEmpty(),
            inBars = b?.downbeats?.toList().orEmpty(),
            loading = loading,
            unavailable = value.enabled && fade > 0 && needed.isNotEmpty() && !loading,
            cachedTracks = analyses.size(),
        )
        Mixes.onChange?.invoke()
    }

    private fun fetch(context: Context, uris: List<String>) {
        val now = android.os.SystemClock.elapsedRealtime()
        val missing = uris.filter { (failed.get(it) ?: 0) <= now && pending.add(it) }
        if (missing.isEmpty()) return
        val version = cacheVersion
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                Mixes.parseAnalyses(NativeBridge.tracksMixAnalysis(JSONArray(missing).toString()))
            }
            if (version != cacheVersion) return@launch
            pending.removeAll(missing.toSet())
            found.forEach { (uri, analysis) -> analyses.put(uri, analysis) }
            (missing - found.keys).forEach { failed.put(it, now + 30_000) }
            refresh(context, requestAnalysis = false)
        }
    }

    private fun read(context: Context, key: PairKey): TransitionTiming? {
        val values = preferences(context).getString(key.preferenceKey, null)?.split(",") ?: return null
        if (values.size != 4) return null
        return TransitionTiming(
            values[0].toLongOrNull() ?: return null,
            values[1].toLongOrNull() ?: return null,
            values[2].toLongOrNull() ?: return null,
            values[3].toFloatOrNull() ?: return null,
        )
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("sonar_automix", Context.MODE_PRIVATE)
}
