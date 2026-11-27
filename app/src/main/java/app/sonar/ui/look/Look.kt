package app.sonar.ui.look

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.sonar.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Look(val key: String, @StringRes val label: Int) {
    CLASSIC("classic", R.string.look_classic),
    IMMERSIVE("immersive", R.string.look_immersive),
    SPOTIFUSION("spotifusion", R.string.look_spotifusion),
    GLASSIFY("glassify", R.string.look_glassify),
    LIQUID_MUSIC("liquid_music", R.string.look_liquid_music),
    STARRY_NIGHT("starry_night", R.string.look_starry_night),
    CUPERTINO("cupertino", R.string.look_cupertino),
    SQUARED("squared", R.string.look_squared);

    val usesSweep: Boolean get() = this != CLASSIC

    companion object {
        fun fromKey(key: String?): Look = entries.firstOrNull {
            it.key.equals(key?.trim(), ignoreCase = true)
        } ?: CLASSIC
    }
}

class LookStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("sonar_lyric_look", Context.MODE_PRIVATE)
    private val selection = MutableStateFlow(Look.fromKey(prefs.all[KEY] as? String))
    val state = selection.asStateFlow()

    @Synchronized
    fun select(look: Look) {
        if (selection.value == look) return
        prefs.edit().putString(KEY, look.key).apply()
        selection.value = look
    }

    companion object {
        private const val KEY = "look"
        private var instance: LookStore? = null

        @Synchronized
        fun get(context: Context): LookStore =
            instance ?: LookStore(context.applicationContext).also { instance = it }
    }
}

@Composable
fun rememberLookStore(): LookStore {
    val context = LocalContext.current
    return remember(context) { LookStore.get(context) }
}
