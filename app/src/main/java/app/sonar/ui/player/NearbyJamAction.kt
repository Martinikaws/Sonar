package app.sonar.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sonar.R
import app.sonar.data.Jam
import app.sonar.parity.devices.JAM_SERVICE
import app.sonar.parity.devices.advertiseNearbyJam
import app.sonar.parity.devices.discoverLanServices
import app.sonar.parity.devices.jamInvite
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Users
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
internal fun NearbyJamAction(session: Jam.Session?, busy: Boolean) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var open by remember(session?.id) { mutableStateOf(false) }
    var attempt by remember(session?.id) { mutableStateOf(0) }
    var failed by remember(session?.id, open, attempt) { mutableStateOf(false) }
    var joinFailed by remember(open) { mutableStateOf(false) }
    var ready by remember(session?.id, open, attempt) { mutableStateOf(false) }
    var hosts by remember(session?.id, open, attempt) { mutableStateOf(emptyList<Pair<String, String?>>()) }
    val owner = session?.takeIf { it.ownedByMe }
    if (session != null && owner == null) return

    LaunchedEffect(lifecycle, open, attempt, owner?.token) {
        if (!open) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ready = false
            failed = false
            hosts = emptyList()
            if (owner != null) {
                val host = owner.hostDevice ?: owner.members.firstOrNull { it.owner }?.name
                advertiseNearbyJam(context, owner.token, host)
                    .catch { failed = true }
                    .collect { ready = it }
            } else {
                val initialScan = launch { delay(3_000); ready = true }
                discoverLanServices(context, JAM_SERVICE)
                    .catch { failed = true }
                    .collect { services ->
                        hosts = services.mapNotNull { it.jamInvite() }.distinctBy { it.first }
                    }
                initialScan.cancel()
            }
        }
    }

    ActionRow(
        Lucide.Users,
        stringResource(
            when {
                open && failed -> R.string.nearby_retry
                owner != null && open -> R.string.nearby_hide_jam
                owner != null -> R.string.nearby_share_lan
                open -> R.string.nearby_stop_search
                else -> R.string.nearby_find_jam
            },
        ),
        busy && !open,
    ) {
        if (failed) {
            attempt++
        } else {
            open = !open
        }
    }
    if (!open) return
    when {
        failed -> Note(stringResource(R.string.nearby_jam_error))
        owner != null -> Note(stringResource(if (ready) R.string.nearby_jam_visible else R.string.nearby_jam_starting))
        hosts.isEmpty() -> Note(stringResource(if (ready) R.string.nearby_jam_empty else R.string.nearby_jam_searching))
        else -> hosts.forEach { (token, host) ->
            ActionRow(
                Lucide.Users,
                if (host == null) stringResource(R.string.nearby_join_jam) else stringResource(R.string.nearby_join_named_jam, host),
                busy,
            ) {
                scope.launch {
                    joinFailed = false
                    joinFailed = !Jam.join(token) && Jam.error.value == null
                }
            }
        }
    }
    if (joinFailed) Note(stringResource(R.string.nearby_join_failed))
}
