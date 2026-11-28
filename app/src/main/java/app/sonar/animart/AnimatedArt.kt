package app.sonar.animart

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.sonar.SonarApplication
import app.sonar.ui.look.rememberLookReducedMotion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
internal data class AnimatedArt(val url: String, val mediaSourceFactory: MediaSource.Factory)

@OptIn(UnstableApi::class)
@Composable
internal fun rememberAnimatedArt(catalogUrl: String?): AnimatedArt? {
    // TODO(NEEDS HUMAN): Verify Apple's unofficial motion URLs and release matching on a device.
    val url = animatedArtUrl(catalogUrl) ?: return null
    if (rememberLookReducedMotion()) return null

    val application = LocalContext.current.applicationContext as SonarApplication
    val power = application.powerMode.conserving
    val conserving by power.collectAsStateWithLifecycle()
    val connectivity = remember(application) {
        application.getSystemService(ConnectivityManager::class.java)
    } ?: return null
    if (!canFetchAnimatedArt(rememberArtworkWifi(connectivity), conserving)) return null

    val factory by produceState<MediaSource.Factory?>(null, application, connectivity) {
        value = withContext(Dispatchers.IO) {
            try {
                val upstream = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { request ->
                    if (!canFetchAnimatedArt(connectivity.hasArtworkWifi(), power.value) ||
                        animatedArtUrl(request.uri.toString()) == null
                    ) throw IOException("Animated artwork requires Wi-Fi and HTTPS")
                    request
                }
                val cached = CacheDataSource.Factory()
                    .setCache(AnimatedArtDiskCache.get(application))
                    .setCacheKeyFactory { animatedArtCacheKey(it.uri.toString()) }
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .setUpstreamDataSourceFactory(upstream)
                DefaultMediaSourceFactory(cached)
            } catch (_: IOException) {
                null
            }
        }
    }
    return factory?.let { AnimatedArt(url, it) }
}

@Composable
internal fun rememberAppleMotionEligible(conserving: Boolean): Boolean {
    val application = LocalContext.current.applicationContext as SonarApplication
    val enabled by application.preferences.canvasEnabled.collectAsStateWithLifecycle()
    val source by application.preferences.animatedCoverSource.collectAsStateWithLifecycle()
    val offline by app.sonar.playback.OfflineMode.active.collectAsStateWithLifecycle()
    if (!enabled || source != app.sonar.data.PreferencesStore.CoverSource.APPLE_MUSIC ||
        conserving || offline
    ) return false
    val connectivity = remember(application) {
        application.getSystemService(ConnectivityManager::class.java)
    } ?: return false
    return rememberArtworkWifi(connectivity)
}

@Composable
private fun rememberArtworkWifi(connectivity: ConnectivityManager): Boolean {
    val wifi = remember(connectivity) { mutableStateOf(connectivity.hasArtworkWifi()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                wifi.value = connectivity.hasArtworkWifi()
            }

            override fun onLost(network: Network) {
                wifi.value = connectivity.hasArtworkWifi()
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        wifi.value = connectivity.hasArtworkWifi()
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return wifi.value
}

internal fun SonarApplication.canRequestAppleMotion(): Boolean =
    preferences.canvasEnabled.value &&
        preferences.animatedCoverSource.value == app.sonar.data.PreferencesStore.CoverSource.APPLE_MUSIC &&
        !app.sonar.playback.OfflineMode.active.value &&
        canFetchAnimatedArt(
            getSystemService(ConnectivityManager::class.java)?.hasArtworkWifi() == true,
            powerMode.conserving.value,
        )

private fun ConnectivityManager.hasArtworkWifi(): Boolean =
    getNetworkCapabilities(activeNetwork)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true

@OptIn(UnstableApi::class)
private object AnimatedArtDiskCache {
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "animated-art"),
        LeastRecentlyUsedCacheEvictor(48L * 1024 * 1024),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }.also { it.checkInitialization() }
}
