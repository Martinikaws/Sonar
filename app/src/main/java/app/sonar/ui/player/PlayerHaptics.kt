package app.sonar.ui.player

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.SonarApplication

@Composable
internal fun rememberPlayerHaptic(): (Int) -> Unit {
    val view = LocalView.current
    val preferences = (LocalContext.current.applicationContext as SonarApplication).preferences
    val enabled by preferences.playerHaptics.collectAsStateWithLifecycle()
    val latestEnabled by rememberUpdatedState(enabled)
    return { feedback -> if (latestEnabled) view.performHapticFeedback(feedback) }
}

internal fun likeHaptic(liked: Boolean): Int =
    if (Build.VERSION.SDK_INT >= 34) {
        if (liked) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.CONTEXT_CLICK
    }

internal val scrubHaptic: Int
    get() = if (Build.VERSION.SDK_INT >= 34) {
        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

internal fun crossesScrubTick(previous: Float?, current: Float): Boolean =
    previous == null || (previous * 20).toInt() != (current * 20).toInt()

@Composable
internal fun KeepScreenAwakeForLyrics(active: Boolean) {
    if (!active) return
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}
