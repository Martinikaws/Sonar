package app.sonar.power

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.R
import app.sonar.ui.glass.backdrop.Backdrop
import app.sonar.ui.settings.ActionRow
import app.sonar.ui.settings.DownloadSwitch
import app.sonar.ui.settings.RowDivider
import app.sonar.ui.settings.Section
import com.composables.icons.lucide.BatteryCharging
import com.composables.icons.lucide.ListMusic
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Wifi

@Composable
internal fun PowerSettings(backdrop: Backdrop) {
    val context = LocalContext.current
    val container = context.applicationContext as app.sonar.SonarApplication
    val preferences = container.preferences
    val headsetSeek by preferences.episodeHeadsetSeek.collectAsStateWithLifecycle()
    val batterySaver by preferences.batterySaver.collectAsStateWithLifecycle()
    val dataSaver by preferences.dataSaver.collectAsStateWithLifecycle()
    val restartRequests = container.powerMode.restartRequests
    val restartHandlers by restartRequests.subscriptionCount.collectAsStateWithLifecycle()

    Section(stringResource(R.string.power_settings)) {
        DownloadSwitch(stringResource(R.string.power_headset_seek), headsetSeek, backdrop, Lucide.ListMusic, preferences::setEpisodeHeadsetSeek)
        RowDivider()
        DownloadSwitch(stringResource(R.string.power_battery_saver), batterySaver, backdrop, Lucide.BatteryCharging, preferences::setBatterySaver)
        RowDivider()
        DownloadSwitch(stringResource(R.string.power_data_saver), dataSaver, backdrop, Lucide.Wifi, preferences::setDataSaver)
        if (restartHandlers > 0) {
            RowDivider()
            ActionRow(stringResource(R.string.power_restart_player), destructive = false, icon = Lucide.RotateCw) {
                restartRequests.tryEmit(Unit)
            }
        }
    }
}
