package app.sonar.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.sonar.data.PreferencesStore
import app.sonar.nativecore.NativeBridge
import coil.intercept.Interceptor
import coil.size.Dimension
import coil.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class PowerMode(context: Context, preferences: PreferencesStore) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val low = MutableStateFlow(isLow(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
    private val systemSaver = MutableStateFlow(power?.isPowerSaveMode == true)
    private val cellular = MutableStateFlow(isCellular())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val restartRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val conserving = combine(preferences.batterySaver, preferences.dataSaver, low, systemSaver, cellular) {
        batterySaver, dataSaver, lowBattery, powerSave, mobile ->
        shouldConserve(batterySaver, dataSaver, lowBattery || powerSave, mobile)
    }.stateIn(
        scope, SharingStarted.Eagerly,
        shouldConserve(preferences.batterySaver.value, preferences.dataSaver.value, low.value || systemSaver.value, cellular.value),
    )

    init {
        scope.launch {
            conserving.collect { active ->
                runCatching { NativeBridge.setAudioPreloading(!active) }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> low.value = isLow(intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> systemSaver.value = power?.isPowerSaveMode == true
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                cellular.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            override fun onLost(network: Network) {
                cellular.value = isCellular()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun isCellular(): Boolean =
        connectivity?.let { manager -> manager.getNetworkCapabilities(manager.activeNetwork) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    private fun isLow(intent: Intent?): Boolean {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 0) ?: 0
        return level >= 0 && scale > 0 && level.toLong() * 100 <= scale.toLong() * 15
    }

    val artworkSize = Interceptor { chain ->
        if (!conserving.value) return@Interceptor chain.proceed(chain.request)

        val request = chain.request
        val source = request.data as? String
        val smaller = source?.let(::smallerArtworkUrl)
        val capped = chain.withSize(Size(cap(chain.size.width), cap(chain.size.height)))
        capped.proceed(if (smaller != source) request.newBuilder().data(smaller).build() else request)
    }

    private fun cap(dimension: Dimension): Dimension.Pixels =
        Dimension.Pixels((dimension as? Dimension.Pixels)?.px?.coerceAtMost(SAVER_ARTWORK_SIZE) ?: SAVER_ARTWORK_SIZE)
}
