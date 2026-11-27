package app.sonar

import android.app.Application
import app.sonar.library.LibraryOrganizationStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.metrolist.innertube.YouTube
import app.sonar.auth.TokenStore
import app.sonar.auth.WebApiAccount
import app.sonar.data.ContextCacheStore
import app.sonar.data.LanguageStore
import app.sonar.data.PlaylistOrderStore
import app.sonar.data.PreferencesStore
import kotlinx.coroutines.launch
import app.sonar.data.RecentStore
import app.sonar.playback.EffectPresetStore
import app.sonar.data.ApiFactory
import app.sonar.data.SpotifyApi
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * Small enough not to need a DI framework, and keeping it explicit makes the
 * one thing that matters obvious: a single [TokenStore] instance, so token
 * refreshes really are serialised across the whole process.
 */
class SonarApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is written down; see Report.
        app.sonar.diagnostics.Report.install(this)
        // Dev builds only: the glass's switches from adb, e.g.
        //   adb shell am broadcast -a app.sonar.GLASS --ez frost false
        if (BuildConfig.GLASS_FLAGS) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                        if (intent.hasExtra("frost")) glass.setFrost(intent.getBooleanExtra("frost", true))
                        if (intent.hasExtra("ios27")) glass.setIos27(intent.getBooleanExtra("ios27", true))
                        // As a GPU that refuses the library's shader leaves it,
                        // until the app is started again.
                        if (intent.hasExtra("upstream") && !intent.getBooleanExtra("upstream", true)) {
                            app.sonar.ui.glass.morph.LiquidGlassRenderer.unavailable(
                                IllegalStateException("switched off from adb"),
                            )
                        }
                    }
                },
                android.content.IntentFilter("app.sonar.GLASS"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // Temporary, dev builds only; see WidevineProbe.
        // YouTube's language and country, before anything asks it for a page.
        language.applyToYouTube()
        app.sonar.diagnostics.WidevineProbe.register(this)
        // The vendored InnerTube module logs through Timber, and with nothing
        // planted every one of those lines went nowhere — which is why a
        // YouTube page that came back in an unexpected shape could only be
        // guessed at. Debug builds only: a release build has no business
        // writing what the account is doing to the system log.
        if (BuildConfig.DEBUG || BuildConfig.VERBOSE_LOG) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }

        // Persistent HTTP cache for InnerTube requests in Android cache directory
        YouTube.cacheDir = cacheDir

        // Before anything can read or write them. The service used to do this on
        // creation, which is late: the player screen and the media session both
        // exist by then and either can announce a default that would be saved
        // over what the listener had set.
        app.sonar.playback.AudioEffects.load(this)
        app.sonar.playback.Equalizer.load(this)

        // Where the extras live, and which tracks are worth keeping them for.
        // Attached here rather than in the service: Catalog reaches for it on
        // any thread and long before anything has started playing.
        app.sonar.download.DownloadExtras.attach(downloads.root, sharedHttpClient) {
            downloads.isDownloaded(it)
        }

        // The other catalogue's answers, kept between runs. Without a store to
        // write to it still works and simply asks again every time — which is
        // the difference between a page that opens on the right picture and one
        // that opens on Spotify's and changes it a second later.
        app.sonar.data.AppleCatalog.attach(this)
        app.sonar.data.LocalFileBindings.attach(this)
        app.sonar.data.PlaylistCovers.attach(this)
        app.sonar.backend.lyrics.LyricsLibrary.attach(
            this,
            spicyKey = { preferences.spicyLyricsKey.value },
            preferred = { preferences.preferredLyricsSource.value },
            lyricsFolder = { preferences.lyricsFolder.value },
        )

        // Two things feed the offline state, and one thing reads it out to the
        // engine. The switch below is the listener's; the watch is the network
        // going; and the engine has to be told either way, because it cannot
        // see either of them and would otherwise go on fetching.
        val offlineScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        app.sonar.playback.SessionKeeper.attach(offlineScope) {
            preferences.backend.value == app.sonar.backend.BackendId.SPOTIFY && spotifySignedIn &&
                !preferences.spotifyFreeAccount.value
        }
        app.sonar.playback.NetworkWatch(this, offlineScope).start()
        offlineScope.launch {
            app.sonar.playback.OfflineMode.active.collect { offline ->
                runCatching { app.sonar.nativecore.NativeBridge.setOfflineOnly(offline) }
            }
        }

        // The listener's own offline switch is persisted, so it has to be put
        // back before anything reads it. Collected rather than read once: it is
        // the one input to OfflineMode that can change without the service
        // being involved.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).launch {
            downloadSettings.offlineMode.collect(
                app.sonar.playback.OfflineMode::setManual,
            )
        }

        // Whether Spotify goes through the official SDK depends on the source
        // too: on YouTube Music the engine's features are all there.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).launch {
            preferences.backend.collect {
                app.sonar.playback.websdk.WebSdkRecovery.refresh(this@SonarApplication)
            }
        }

        // Spotify's DJ, offered or not; see SpotifyDj.enabled.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).launch {
            preferences.spotifyDj.collect { app.sonar.backend.spotify.SpotifyDj.enabled = it }
        }

        // Not a feature: a line in the log saying whether this install has been
        // compiled ahead of time yet. See reportProfileStatus.
        reportProfileStatus(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )
    }

    val tokenStore: TokenStore by lazy { TokenStore(this) }

    /**
     * What is downloaded and who asked for it.
     *
     * One instance, like the token store and for the same reason: the index is
     * a file, and two of these would write over each other. The audio it points
     * at belongs to the engine, which finds it without going through here.
     */
    val downloads: app.sonar.data.DownloadStore by lazy {
        app.sonar.data.DownloadStore(this)
    }

    /** Download quality, Wi-Fi only, the offline switch. */
    val downloadSettings: app.sonar.data.DownloadSettingsStore by lazy {
        app.sonar.data.DownloadSettingsStore(this)
    }

    /** Works through what [downloads] says is still owed. */
    val downloadQueue: app.sonar.download.DownloadQueue by lazy {
        app.sonar.download.DownloadQueue(this, downloads, downloadSettings)
    }

    /**
     * Whether Spotify can be used, which is not the same as holding a token.
     *
     * The engine keeps the credential the access point gave it and logs in with
     * that, so an OAuth session that has lapsed is no longer a reason to show
     * the login screen: everything the app plays goes through the access point,
     * and the access point is still answering. Only the Web API, which is the
     * user's own registered application, needs the token itself.
     */
    val spotifySignedIn: Boolean
        get() = tokenStore.isLoggedIn ||
            app.sonar.auth.EngineCredentials.exist(this)
    val recentStore: RecentStore by lazy { RecentStore(this) }
    val listeningStore by lazy { app.sonar.data.ListeningStore(this) }

    /** Songs found by searching and then played; see [SearchHistoryStore]. */
    val searchHistory: app.sonar.data.SearchHistoryStore by lazy {
        app.sonar.data.SearchHistoryStore(this)
    }

    /** Which playlists were opened most recently, for ordering the home page. */
    val playlistOrder: PlaylistOrderStore by lazy { PlaylistOrderStore(this) }

    /** The playlists the home-screen widget offers; see SonarWidget. */
    val widgetPlaylists: app.sonar.widget.WidgetPlaylists by lazy {
        app.sonar.widget.WidgetPlaylists(this)
    }

    /** Tracks the listener has liked ("Tus me gusta"). */
    val likedStore: app.sonar.data.LikedStore by lazy {
        app.sonar.data.LikedStore(this)
    }

    /** Playlists the listener keeps at the top of the library; this device's own. */
    val pinnedPlaylists: app.sonar.data.PinnedPlaylistStore by lazy {
        app.sonar.data.PinnedPlaylistStore(this)
    }
    /** How the library was left looking: grid or list, and the sort. */
    val libraryOrganization by lazy { LibraryOrganizationStore(this) }

    val libraryView: app.sonar.data.LibraryViewStore by lazy {
        app.sonar.data.LibraryViewStore(this)
    }
    val preferences: PreferencesStore by lazy { PreferencesStore(this) }
    /** One instance, so the settings and the playback service share its flow. */
    internal val flacSources by lazy { app.sonar.flac.FlacSources(this) }

    val sourcePriority: app.sonar.audiosource.SourcePriorityStore by lazy {
        app.sonar.audiosource.SourcePriorityStore(this)
    }

    /** Which file the engine asks Spotify for. */
    val quality: app.sonar.data.QualityStore by lazy {
        app.sonar.data.QualityStore(this)
    }

    /** The identifiers Spotify's gateway wants; kept fresh from the repository. */
    val pathfinderKeys: app.sonar.data.PathfinderKeys by lazy {
        app.sonar.data.PathfinderKeys(this)
    }

    /** Track lists from Spotify's own gateway; see [app.sonar.data.Gateway]. */
    val gateway: app.sonar.data.Gateway by lazy {
        app.sonar.data.Gateway(pathfinderKeys)
    }

    /** Which stretcher works out speed and pitch. */
    val effectQuality: app.sonar.data.EffectQualityStore by lazy {
        app.sonar.data.EffectQualityStore(this)
    }

    /** What the glass is made of, and how much of it the phone has to pay for. */
    val glass: app.sonar.data.GlassStore by lazy {
        app.sonar.data.GlassStore(this)
    }

    /** How long one track dissolves into the next. */
    val crossfade: app.sonar.data.CrossfadeStore by lazy {
        app.sonar.data.CrossfadeStore(this)
    }

    /** What language the app is read in, and what the engine asks Spotify for. */
    val language: LanguageStore by lazy { LanguageStore(this) }

    /** Track lists already resolved, so reopening a playlist is not a reload. */
    val contextCache: ContextCacheStore by lazy { ContextCacheStore(this) }

    /**
     * The user's own Spotify application. Web API calls go through it so they
     * are metered against a quota nobody else shares — see [WebApiAccount].
     */
    val webApi: WebApiAccount by lazy { WebApiAccount(this) }
    internal val spotifySdkAccount by lazy { app.sonar.playback.websdk.WebSdkAccount(this) }

    /**
     * Shared [OkHttpClient] providing a common connection pool, DNS cache, and dispatcher
     * across Spotify API, DownloadExtras, and Coil image loading.
     */
    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val isLowRam = am?.isLowRamDevice ?: false
        val memoryCachePercent = if (isLowRam) 0.12 else 0.20

        return ImageLoader.Builder(this)
            .okHttpClient { sharedHttpClient }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(memoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .allowRgb565(isLowRam)
            .allowHardware(true)
            .respectCacheHeaders(false)
            .build()
    }

    /**
     * The account's country code (e.g. "ES", "MX", "US"), drawn from the authenticated
     * account profile and cached in preferences. If the profile hasn't been fetched yet,
     * falls back to the device's locale country, or "from_token".
     */
    val userCountry: String
        get() = preferences.userCountry?.takeIf { it.length == 2 }?.uppercase()
            ?: java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase()
            ?: "US"

    val api: SpotifyApi by lazy {
        ApiFactory.create(
            tokens = webApi.tokens,
            fallbackTokens = tokenStore,
            nativeToken = { app.sonar.nativecore.NativeBridge.accessToken() },
            baseClient = sharedHttpClient,
            countryProvider = { userCountry },
            debug = BuildConfig.DEBUG,
        )
    }

    /** Checks the project's own releases; there is no store to do it. */
    val updater: app.sonar.update.Updater by lazy {
        app.sonar.update.Updater(this)
    }

    /** The user's saved speed / pitch / reverb combinations. */
    val effectPresets: EffectPresetStore by lazy { EffectPresetStore(this) }

    /** How far into each podcast episode the listener got; see EpisodeProgress. */
    val episodeProgress: app.sonar.data.EpisodeProgress by lazy {
        app.sonar.data.EpisodeProgress(this)
    }

    /**
     * The Spotify source, behind the common backend interface.
     *
     * A single instance because it owns the AudioTrack the native sink writes
     * into, and a second one would be a second sink for the same engine.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val spotifyBackend: app.sonar.backend.SpotifyBackend by lazy {
        app.sonar.backend.SpotifyBackend(this)
    }

    /** The Google session YouTube Music reads a personal library with. */
    val youtubeAccount: app.sonar.backend.youtube.YouTubeAccount by lazy {
        app.sonar.backend.youtube.YouTubeAccount(this)
    }

    /** YouTube Music; see [app.sonar.backend.youtube.YouTubeBackend]. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val youtubeBackend: app.sonar.backend.youtube.YouTubeBackend by lazy {
        // Songs already found for the videos in lists; see SongCounterparts.
        app.sonar.backend.youtube.SongCounterparts.init(filesDir)
        app.sonar.backend.youtube.YouTubeBackend(youtubeAccount)
    }

    /**
     * Whichever of the two the user picked.
     *
     * Read afresh each time rather than held: the setting can change while the
     * app is running, and a cached backend would keep answering as the old one.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val activeBackend: app.sonar.backend.MusicBackend
        get() = when (preferences.backend.value) {
            app.sonar.backend.BackendId.SPOTIFY -> spotifyBackend
            app.sonar.backend.BackendId.YOUTUBE_MUSIC -> youtubeBackend
        }
}
