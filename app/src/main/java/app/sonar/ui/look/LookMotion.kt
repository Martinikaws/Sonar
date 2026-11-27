package app.sonar.ui.look

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberLookReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val setting = Settings.Global.ANIMATOR_DURATION_SCALE
    fun reduced() = Settings.Global.getFloat(resolver, setting, 1f) == 0f

    val state = remember(resolver) { mutableStateOf(reduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = reduced()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
        state.value = reduced()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state.value
}
