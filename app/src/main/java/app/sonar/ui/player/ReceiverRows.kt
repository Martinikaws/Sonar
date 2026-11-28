package app.sonar.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sonar.R
import app.sonar.parity.devices.CAST_SERVICE
import app.sonar.parity.devices.CONNECT_SERVICE
import app.sonar.parity.devices.CastReceiver
import app.sonar.parity.devices.castReceiver
import app.sonar.parity.devices.discoverLanServices
import app.sonar.parity.devices.matchingCastReceiver
import app.sonar.parity.devices.sonosConnectId
import app.sonar.ui.MainViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun ReceiverRows(state: MainViewModel.DevicesState, onSelect: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var cast by remember { mutableStateOf(emptyList<CastReceiver>()) }
    var sonos by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                coroutineScope {
                    launch {
                        discoverLanServices(context, CAST_SERVICE)
                            .catch { emit(emptyList()) }
                            .collect { cast = it.mapNotNull { service -> service.castReceiver() } }
                    }
                    launch {
                        val identities = mutableMapOf<String, String>()
                        discoverLanServices(context, CONNECT_SERVICE)
                            .catch { emit(emptyList()) }
                            .collectLatest { services ->
                                val names = services.mapTo(mutableSetOf()) { it.serviceName }
                                identities.keys.retainAll(names)
                                sonos = identities.values.toSet()
                                for (service in services) {
                                    if (service.serviceName !in identities) {
                                        service.sonosConnectId()?.let { identities[service.serviceName] = it }
                                    }
                                }
                                sonos = identities.values.toSet()
                            }
                    }
                }
            } finally {
                cast = emptyList()
                sonos = emptySet()
            }
        }
    }

    val names = state.devices.map { it.name }
    state.devices.forEach { device ->
        val receiver = matchingCastReceiver(device.id, device.name, device.type, cast, names)
        val label = when {
            device.id in sonos -> stringResource(R.string.nearby_output_sonos)
            receiver?.group == true -> stringResource(R.string.nearby_output_cast_group)
            receiver != null -> stringResource(R.string.nearby_output_cast)
            else -> device.type
        }
        DeviceRow(device, switching = state.switchingTo == device.id, label = label) {
            onSelect(device.id)
        }
    }
}
