package app.moonvault.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.moonvault.ui.onboarding.BackendChoiceScreen
import app.moonvault.ui.theme.MoonvaultTheme

/**
 * The first screen of a new install, shown on its own without touching the
 * install's own choice: `adb shell am start -n app.moonvault/.dev.SetupPreview`.
 * Picking a service just closes it.
 */
class SetupPreview : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MoonvaultTheme(darkTheme = true) {
                BackendChoiceScreen(onChoose = { finish() })
            }
        }
    }
}
