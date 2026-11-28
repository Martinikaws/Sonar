package app.sonar.parity.devices

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

private val nearbyToken = Regex("[A-Za-z0-9]{8,128}")
private const val INVITE = "invite"
private const val HOST = "host"

internal fun nearbyJamToken(value: String?): String? = value?.takeIf(nearbyToken::matches)

internal fun nearbyJamName(value: String?): String? =
    value?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        ?.trim()?.take(48)?.takeIf(String::isNotEmpty)

internal fun advertiseNearbyJam(context: Context, token: String, host: String?): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(NsdManager::class.java) ?: error("NSD unavailable")
    val invite = nearbyJamToken(token) ?: error("Invalid Jam invite")
    val info = NsdServiceInfo().apply {
        serviceName = "Sonar Jam ${UUID.randomUUID()}"
        serviceType = JAM_SERVICE
        // Android requires an SRV port. Joining uses Spotify and the TXT invite.
        port = 9
        setAttribute(INVITE, invite)
        nearbyJamName(host)?.let { setAttribute(HOST, it) }
    }
    val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { trySend(true) }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { close(IllegalStateException("NSD registration failed ($code)")) }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }
    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { manager.unregisterService(listener) } }
}

internal fun NsdServiceInfo.jamInvite(): Pair<String, String?>? =
    nearbyJamToken(txt(INVITE))?.let { it to nearbyJamName(txt(HOST)) }
