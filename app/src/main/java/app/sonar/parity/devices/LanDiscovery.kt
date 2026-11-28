package app.sonar.parity.devices

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal const val JAM_SERVICE = "_sonar-jam._tcp"
internal const val CAST_SERVICE = "_googlecast._tcp"
internal const val CONNECT_SERVICE = "_spotify-connect._tcp"

private val resolveLock = Mutex()

private sealed interface DiscoveryEvent {
    data class Presence(val info: NsdServiceInfo, val found: Boolean) : DiscoveryEvent
    data class Resolved(val original: NsdServiceInfo, val result: NsdServiceInfo) : DiscoveryEvent
}

internal fun discoverLanServices(context: Context, type: String): Flow<List<NsdServiceInfo>> = flow {
    val manager = context.getSystemService(NsdManager::class.java) ?: error("NSD unavailable")
    coroutineScope {
        val events = Channel<DiscoveryEvent>(Channel.BUFFERED)
        val present = mutableMapOf<String, NsdServiceInfo>()
        val resolved = mutableMapOf<String, NsdServiceInfo>()
        launch {
            try {
                discoveries(manager, type).collect { events.send(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                events.close(error)
            }
        }
        try {
            for (event in events) {
                when (event) {
                    is DiscoveryEvent.Presence -> {
                        val info = event.info
                        val name = info.serviceName
                        if (event.found) {
                            if (present.putIfAbsent(name, info) == null) launch {
                                resolveLock.withLock {
                                    if (present[name] === info) {
                                        resolve(manager, info)?.let { events.send(DiscoveryEvent.Resolved(info, it)) }
                                    }
                                }
                            }
                        } else {
                            present.remove(name)
                            if (resolved.remove(name) != null) emit(resolved.values.toList())
                        }
                    }
                    is DiscoveryEvent.Resolved -> {
                        val (original, result) = event
                        if (present[original.serviceName] === original) {
                            resolved[original.serviceName] = result
                            emit(resolved.values.toList())
                        }
                    }
                }
            }
        } finally {
            events.cancel()
        }
    }
}

private fun discoveries(manager: NsdManager, type: String): Flow<DiscoveryEvent.Presence> = callbackFlow {
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            close(IllegalStateException("NSD discovery failed ($code)"))
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) { trySend(DiscoveryEvent.Presence(info, true)) }
        override fun onServiceLost(info: NsdServiceInfo) { trySend(DiscoveryEvent.Presence(info, false)) }
    }
    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
}

@Suppress("DEPRECATION")
private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
    withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = continuation.resume(null)
                override fun onServiceResolved(info: NsdServiceInfo) = continuation.resume(info)
            }
            try {
                manager.resolveService(info, listener)
            } catch (_: IllegalArgumentException) {
                continuation.resume(null)
            } catch (_: IllegalStateException) {
                continuation.resume(null)
            }
        }
    }

internal fun NsdServiceInfo.txt(key: String): String? =
    attributes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        ?.value?.toString(Charsets.UTF_8)
