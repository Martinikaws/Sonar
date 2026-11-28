package app.sonar.ui.settings

import app.sonar.backend.spotify.SpotifyWebPlayback as MvSpotifyWebPlayback
import app.sonar.data.CatalogTrack as MvCatalogTrack
import app.sonar.data.ContextCacheStore as MvContextCacheStore
import app.sonar.data.DownloadQuality as MvDownloadQuality
import app.sonar.data.DownloadStore as MvDownloadStore
import app.sonar.data.PreferencesStore as MvPreferencesStore
import app.sonar.data.GlassProfile
import app.sonar.playback.AudioRoute
import app.sonar.playback.BuiltInEqPresets
import app.sonar.playback.Equalizer
import app.sonar.playback.soloist.SoloistDownload
import app.sonar.ui.glass.GlassStyle
import app.sonar.ui.glass.isGlassSupported
import androidx.compose.material3.Switch
import app.sonar.download.DownloadExtras as MvDownloadExtras
import app.sonar.download.DownloadService as MvDownloadService
import app.sonar.download.YouTubeDownloads as MvYouTubeDownloads
import app.sonar.playback.soloist.SoloistRuntime as MvSoloistRuntime
import app.sonar.playback.websdk.WebSdkActivity as MvWebSdkActivity
import app.sonar.playback.websdk.WebSdkRecovery as MvWebSdkRecovery
import app.sonar.ui.components.ProfilePicture as MvProfilePicture
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sonar.update.Updater
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.AudioWaveform
import com.composables.icons.lucide.BatteryCharging
import com.composables.icons.lucide.Bug
import com.composables.icons.lucide.CaseSensitive
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleUser
import com.composables.icons.lucide.Contrast
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Italic
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.LifeBuoy
import com.composables.icons.lucide.ListMusic
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.MicVocal
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.MoveHorizontal
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Plane
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Scissors
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Wifi
import app.sonar.ui.glass.backdrop.Backdrop
import app.sonar.BuildConfig
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import app.sonar.ui.glass.shapes.ContinuousCapsule
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import app.sonar.R
import app.sonar.ui.look.LookPicker
import app.sonar.data.AppLanguages
import app.sonar.backend.BackendId
import app.sonar.data.CrossfadeSteps
import app.sonar.data.EffectQuality
import app.sonar.data.Quality
import app.sonar.ui.MainViewModel
import app.sonar.ui.glass.LiquidButton
import app.sonar.ui.theme.Ink
import app.sonar.ui.theme.InkDim
import app.sonar.ui.theme.softShadow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.sonar.ui.components.GlassSearchBar

/**
 * Everything that is configuration rather than listening.
 *
 * Reached from the avatar in the home header, not from the tab bar. The bar has
 * three places you go to play something and settings is not one of them: it is
 * somewhere you visit twice a year, and giving it a permanent quarter of the
 * navigation would say otherwise.
 *
 * One list, grouped, with a search over it: every setting is on this screen,
 * and the few that need a screen of their own (the equalizer, the glass, the
 * Web API application) open from a single row. Which groups there are and what
 * is in them is SETTINGS_INDEX; see SettingsSearch.
 */
@Composable
fun SettingsScreen(
    state: MainViewModel.UiState,
    webApi: MainViewModel.WebApiState,
    contentPadding: PaddingValues,
    backdrop: Backdrop,
    deviceName: String,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    onDisconnectWebApi: () -> Unit,
    onLogOut: () -> Unit,
    onShowTutorial: () -> Unit,
    /** The chosen language tag, empty for the phone's own. */
    language: String,
    onLanguage: (String) -> Unit,
    onBack: () -> Unit,
    /** Opens the Google sign-in web view; see YouTubeLoginScreen. */
    onYouTubeSignIn: () -> Unit = {},
    /** After switching channel: the library and the home belong to the new one. */
    onYouTubeChannelChange: () -> Unit = {},
    onAstralImport: () -> Unit,
    onPlayFlac: (List<app.sonar.data.CatalogTrack>, Int) -> Unit = { _, _ -> },
) {
    val ready = state as? MainViewModel.UiState.Ready
    val context = LocalContext.current
    val sonarApp = remember(context) {
        context.applicationContext as app.sonar.SonarApplication
    }
    /**
     * Spotify's own settings are hidden while another source is playing.
     *
     * Not disabled — removed. The account, the registered Web API application,
     * the Connect device and the tutorial that explains all three describe a
     * service the app is not currently using, and leaving them on screen makes
     * the two sources look like one confused one.
     */
    val spotifyActive by sonarApp.preferences.backend.collectAsStateWithLifecycle()
    val showSpotify = spotifyActive == BackendId.SPOTIFY
    // Spotify through the official SDK, which mixes its own audio: none of the
    // engine's crossfade, bitrate, effects or downloads reach it.
    val sdkPlayback by MvWebSdkRecovery.inUse.collectAsStateWithLifecycle()

    /**
     * Which page is open over the list, or null for the list itself.
     *
     * A page inside this screen rather than a second destination: every page
     * reads the same state and the same stores. Remembered across a rotation.
     */
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // The back gesture closes the page first and leaves the settings second,
    // which is the order the screen is read in.
    BackHandler(enabled = open != null) { open = null }

    // Held above the pages, so coming back from one finds the same search and
    // the same place in the list.
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // One accent for the whole screen; see LocalSettingsAccent.
    val accent = rememberSettingsAccent()

    // Registered once for the screen, not once per row.
    //
    // Both of these used to be made inside a row of the About page, and a row
    // of a lazy list is made and thrown away every time it scrolls past the
    // edge: registering and unregistering an activity launcher on every pass
    // is what froze that page when it was scrolled down and back up.
    val scope = rememberCoroutineScope()
    var pendingReport by remember { mutableStateOf<java.io.File?>(null) }
    val saveReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val file = pendingReport
        pendingReport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("no stream for $uri")
                }
                android.widget.Toast.makeText(context, context.getString(R.string.report_saved), android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.util.Log.w("SonarReport", "report not saved: $it")
                android.widget.Toast.makeText(context, context.getString(R.string.report_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val askToSaveReport: (java.io.File) -> Unit = { file ->
        pendingReport = file
        saveReport.launch(file.name)
    }

    val updater = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).updater
    }
    var pendingUpdate by remember { mutableStateOf<Updater.State.Available?>(null) }
    val installPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = pendingUpdate ?: return@rememberLauncherForActivityResult
        pendingUpdate = null
        scope.launch { if (updater.canInstall()) updater.install(update) }
    }
    val askToInstall: (Updater.State.Available) -> Unit = { update ->
        pendingUpdate = update
        installPermission.launch(updater.permissionIntent())
    }

    // The lyrics folder picker, here for the same reason as the report's.
    val pickLyricsFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // Let go of the folder this replaces, so the grants do not pile up.
        sonarApp.preferences.lyricsFolder.value.takeIf { it.isNotBlank() && it != tree.toString() }?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(old),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        sonarApp.preferences.setLyricsFolder(tree.toString())
    }

    // Set rather than assigned from inside, because the page being drawn is a
    // value handed to the animation below and cannot be written to.
    val goTo: (SettingsPage?) -> Unit = { open = it }

    // The page's own heading, wherever the page puts it: in the scrolling list
    // for most of them, above a pinned preview for the glass.
    val heading: @Composable (SettingsPage?) -> Unit = { shown ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidButton(
                onClick = { if (shown != null) goTo(null) else onBack() },
                backdrop = backdrop,
            ) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(shown?.title ?: R.string.settings),
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }

    // Which blocks this source and this phone have at all; search only looks
    // among these, so the empty state agrees with what is drawn.
    val sdkRecoveryOffered = showSpotify &&
        (MvSpotifyWebPlayback.needed(context) || MvWebSdkRecovery.isConfigured(context))
    val soloistOffered = showSpotify && MvSoloistRuntime.isSupported(context)
    val soloistSetup = if (soloistOffered) rememberSoloistSetup() else null
    val available: (String) -> Boolean = { id ->
        when (id) {
            "account", "webapi", "tutorial", "audio-sources", "spotify-dj", "connected-device-label", "spicy-lyrics" -> showSpotify
            // YouTube Music's own; the account section draws nothing on Spotify.
            "youtube-account", "youtube-quality" -> !showSpotify
            // Spotify's sign-out, so only while Spotify is the source.
            "logout" -> showSpotify && ready != null
            "web-sdk-recovery" -> sdkRecoveryOffered
            "engine" -> soloistOffered
            // The bitrate is librespot's; ExoPlayer takes what YouTube serves.
            "quality" -> showSpotify && !sdkPlayback
            "effect-quality", "crossfade", "downloads" -> !sdkPlayback
            // Where the phone's own .lrc files are; needed from Android 13 (#158).
            "lyrics-folder" -> android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
            "links" -> android.os.Build.VERSION.SDK_INT >= 31
            else -> true
        }
    }
    val glassConfig by sonarApp.glass.config.collectAsStateWithLifecycle()
    val eqRoute by AudioRoute.current.collectAsStateWithLifecycle()
    val eqCurves by Equalizer.curves.collectAsStateWithLifecycle()
    val eqNames by Equalizer.names.collectAsStateWithLifecycle()
    val eqOutputs = (listOf(eqRoute.key) + eqNames.keys + eqCurves.keys).distinct()
    val entries = SETTINGS_INDEX.filter { available(it.id) }.map { entry ->
        when (entry.id) {
            "glass" -> entry.copy(keywords = glassSearchKeywords(
                supported = isGlassSupported(),
                liquid = glassConfig.style != GlassStyle.TRANSPARENT,
                ios27 = glassConfig.ios27,
                tinted = glassConfig.tintHue >= 0f,
                profiles = GlassProfile.entries.map { it.label },
            ))
            "webapi" -> entry.copy(keywords = entry.keywords + buildList {
                if (webApi.connected) {
                    addAll(listOf(R.string.application, R.string.disconnect))
                } else {
                    addAll(listOf(R.string.turn_on_search, R.string.copy))
                    if (!webApi.connecting) add(R.string.connect)
                }
            })
            "equalizer" -> entry.copy(keywords = entry.keywords + buildList {
                addAll(BuiltInEqPresets.map { it.first })
                if (eqOutputs.size > 1) add(R.string.eq_devices)
                eqOutputs.filter { eqNames[it].isNullOrBlank() }.forEach { key ->
                    add(when (AudioRoute.kindOf(key)) {
                        AudioRoute.Kind.SPEAKER -> R.string.eq_speaker
                        AudioRoute.Kind.WIRED -> R.string.eq_wired
                        AudioRoute.Kind.BLUETOOTH -> R.string.eq_bluetooth
                        AudioRoute.Kind.USB -> R.string.eq_usb
                        AudioRoute.Kind.OTHER -> R.string.eq_other
                    })
                }
            })
            "engine" -> entry.copy(keywords = entry.keywords + buildList {
                if (soloistSetup != null && soloistSetup.download !is SoloistDownload.Progress.Running) {
                    add(if (soloistSetup.installed) R.string.soloist_check_update else R.string.soloist_download)
                }
            })
            else -> entry
        }
    }
    val found = matchSettings(entries, query) { context.getString(it) }

    // One block of the list, by its id in SETTINGS_INDEX.
    val block: @Composable (String) -> Unit = { id ->
    when (id) {
        // Who this is, before anything that can be done about it.
        "account" -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MvProfilePicture(
                    url = ready?.avatarUrl,
                    name = ready?.displayName,
                    size = 96.dp,
                    modifier = Modifier
                        .size(96.dp)
                        .softShadow(CircleShape, elevation = 16.dp),
                    decodeSize = 96.dp,
                )
                Text(
                    ready?.displayName ?: stringResource(R.string.not_connected),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (ready != null) {
                        stringResource(R.string.playlist_count, ready.playlists.size)
                    } else {
                        stringResource(R.string.log_in_to_resume)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
            }
            Section(null) {
                InfoRow(stringResource(R.string.connect_device), deviceName, icon = Lucide.Smartphone)
            }
        }

        "youtube-account" -> {
            YouTubeAccountSection(
                onSignIn = onYouTubeSignIn,
                onChannelChange = onYouTubeChannelChange,
            )
        }

        "logout" -> {
                Section(null) {
                    ActionRow(stringResource(R.string.log_out), destructive = true, onClick = onLogOut)
                }
        }

        "webapi" -> Section(null) {
            PageRow(
                stringResource(R.string.web_api),
                stringResource(if (webApi.connected) R.string.connected else R.string.not_connected),
            ) { goTo(SettingsPage.WebApi) }
        }

        "astral-import" -> Section(null) {
            ActionRow(
                label = stringResource(R.string.astral_import_title),
                destructive = false,
                onClick = onAstralImport,
            )
        }

        "tutorial" -> {
            Section(stringResource(R.string.guide)) {
                ActionRow(stringResource(R.string.see_setup_again), destructive = false) {
                    onShowTutorial()
                }
            }
        }

        "web-sdk-recovery" -> {
            Section(stringResource(R.string.web_sdk_settings_title)) {
                ActionRow(stringResource(R.string.web_sdk_settings_open), destructive = false) {
                    context.startActivity(android.content.Intent(
                        context, MvWebSdkActivity::class.java,
                    ).putExtra("recovery", true))
                }
                if (sdkPlayback) Text(
                    stringResource(R.string.web_sdk_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                )
            }
        }

        "backend" -> {
            BackendSection()
        }

        "engine" -> {
            soloistSetup?.let { EngineSection(it) }
        }

        "audio-sources" -> {
            AudioSourcesSection()
        }
        "flac-sources" -> Section(null) {
            PageRow(stringResource(R.string.flac_settings), stringResource(R.string.flac_settings_summary)) {
                goTo(SettingsPage.Flac)
            }
        }

        "hidden-content" -> Section(null) {
            PageRow(stringResource(R.string.hidden_settings_title), stringResource(R.string.hidden_settings_summary)) {
                goTo(SettingsPage.Hidden)
            }
        }
        "local-playlists" -> Section(null) {
            PageRow(stringResource(R.string.hidden_local_title), stringResource(R.string.hidden_local_summary)) {
                goTo(SettingsPage.LocalPlaylists)
            }
        }

        "equalizer" -> Section(null) {
            PageRow(
                stringResource(R.string.equalizer),
                stringResource(R.string.page_equalizer_summary),
            ) { goTo(SettingsPage.Equalizer) }
        }

        "quality" -> {
            QualitySection()
        }

        // YouTube Music's own, in the place Spotify's quality has.
        "youtube-quality" -> {
            YouTubeQualitySection()
        }

        // The effects run on our own output, so this one holds for both backends.
        "effect-quality" -> {
            EffectQualitySection()
        }

        "power" -> app.sonar.power.PowerSettings(backdrop)

        "keep-playing" -> {
            val prefs = remember(context) {
                (context.applicationContext as app.sonar.SonarApplication).preferences
            }
            val keepPlaying by prefs.keepPlayingOnClose.collectAsStateWithLifecycle()
            Section(stringResource(R.string.page_playback)) {
                DownloadSwitch(
                    label = stringResource(R.string.keep_playing_on_close),
                    checked = keepPlaying,
                    backdrop = backdrop,
                    icon = Lucide.ListMusic,
                    onChange = prefs::setKeepPlayingOnClose,
                )
                Text(
                    stringResource(R.string.keep_playing_on_close_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(start = ROW_TEXT_START, end = 18.dp, bottom = 14.dp),
                )
                // Spotify's engine keeps its own audio focus; YouTube Music's
                // player is ExoPlayer's, which handles focus as one whole.
                if (showSpotify) {
                    val pauseForOthers by prefs.pauseForOtherApps.collectAsStateWithLifecycle()
                    RowDivider()
                    DownloadSwitch(
                        label = stringResource(R.string.pause_for_other_apps),
                        checked = pauseForOthers,
                        backdrop = backdrop,
                        icon = Lucide.Pause,
                        onChange = prefs::setPauseForOtherApps,
                    )
                    Text(
                        stringResource(R.string.pause_for_other_apps_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        modifier = Modifier.padding(start = ROW_TEXT_START, end = 18.dp, bottom = 14.dp),
                    )
                }
                // Only where it does anything: SponsorBlock knows YouTube videos.
                if (!showSpotify) {
                    val sponsorBlock by prefs.sponsorBlock.collectAsStateWithLifecycle()
                    RowDivider()
                    DownloadSwitch(
                        label = stringResource(R.string.sponsor_block),
                        checked = sponsorBlock,
                        backdrop = backdrop,
                        icon = Lucide.Scissors,
                        onChange = prefs::setSponsorBlock,
                    )
                    Text(
                        stringResource(R.string.sponsor_block_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        modifier = Modifier.padding(start = ROW_TEXT_START, end = 18.dp, bottom = 14.dp),
                    )
                }
            }
        }

        "crossfade" -> {
            CrossfadeSection(backdrop)
        }

        // Autoplay: automatically append similar tracks when queue reaches the end.
        "autoplay" -> {
            AutoplaySection(backdrop)
        }

        // Spotify's DJ: offered and played with its voice, or kept out of sight.
        "spotify-dj" -> {
            val enabled by sonarApp.preferences.spotifyDj.collectAsStateWithLifecycle()
            Section(stringResource(R.string.spotify_dj)) {
                DownloadSwitch(
                    label = stringResource(R.string.spotify_dj_title),
                    icon = Lucide.MicVocal,
                    checked = enabled,
                    backdrop = backdrop,
                    onChange = sonarApp.preferences::setSpotifyDj,
                )
            }
        }

        "connected-device-label" -> {
            val enabled by sonarApp.preferences.showConnectedDevice.collectAsStateWithLifecycle()
            Section(stringResource(R.string.connected_device_label)) {
                DownloadSwitch(
                    label = stringResource(R.string.connected_device_label),
                    checked = enabled,
                    onChange = sonarApp.preferences::setShowConnectedDevice,
                    backdrop = backdrop,
                )
            }
        }

        "player-haptics" -> Section(null) {
            DownloadSwitch(
                label = stringResource(R.string.haptics_player_controls),
                checked = sonarApp.preferences.playerHaptics.collectAsStateWithLifecycle().value,
                backdrop = backdrop,
                onChange = sonarApp.preferences::setPlayerHaptics,
            )
        }

        "lyrics-keep-awake" -> Section(null) {
            DownloadSwitch(
                label = stringResource(R.string.haptics_lyrics_keep_awake),
                checked = sonarApp.preferences.lyricsKeepAwake.collectAsStateWithLifecycle().value,
                backdrop = backdrop,
                onChange = sonarApp.preferences::setLyricsKeepAwake,
            )
        }

        "lyrics-source" -> {
            PreferredLyricsSection(showSpotify)
        }

        "lyrics-look" -> Section(stringResource(R.string.look_title)) {
            LookPicker()
        }

        "lyrics-folder" -> {
            LyricsFolderSection(onPick = { runCatching { pickLyricsFolder.launch(null) } })
        }

        // Spicy Lyrics is asked by Spotify track id, so only on that source.
        "spicy-lyrics" -> {
            SpicyLyricsSection()
        }

        // On either source: Apple's moving covers play over YouTube Music too.
        "canvas" -> {
            CanvasSection(backdrop)
        }

        // Where the space went, before anything that can be done about it.
        "storage-chart" -> {
            StorageChart()
        }

        // For YouTube Music too: its downloads wait for Wi-Fi like Spotify's
        // (#153). Only the parts that are Spotify's own stay with Spotify.
        "downloads" -> {
            DownloadsSection(backdrop, spotify = showSpotify)
        }

        // The cleaning up, under everything it cleans. Each says what goes
        // and what stays.
        "cleanup" -> {
            var clearedLists by remember { mutableStateOf(false) }
            var clearedCache by remember { mutableStateOf(false) }
            Section(stringResource(R.string.storage_cleanup)) {
                ActionRow(
                    stringResource(R.string.app_clear_list_cache),
                    destructive = false,
                    icon = Lucide.Eraser,
                ) {
                    scope.launch {
                        MvContextCacheStore(context).clear()
                        clearedLists = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.app_clear_list_cache_done),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                RowDivider()
                // Everything the app keeps to work faster and nothing it was
                // asked to keep: images it has drawn, pages it has read, the
                // engine's own scratch files. The music is not in here.
                ActionRow(
                    stringResource(R.string.app_clear_cache),
                    destructive = false,
                    icon = Lucide.Trash,
                ) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            }
                        }
                        clearedCache = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.app_clear_cache_done),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // Read so the two flags are used; they keep the rows from being
            // pressed twice in a row with nothing to show for it.
            if (clearedLists && clearedCache) Spacer(Modifier.height(0.dp))
        }

        "language" -> {
            Section(stringResource(R.string.language)) {
                AppLanguages.forEachIndexed { index, (tag, name) ->
                    if (index > 0) RowDivider()
                    ChoiceRow(
                        label = name.ifEmpty { stringResource(R.string.system_language) },
                        selected = tag == language,
                    ) { onLanguage(tag) }
                }
            }
        }

        // Light or dark, whatever the phone is.
        "theme" -> {
            val store = remember(context) {
                (context.applicationContext as app.sonar.SonarApplication).preferences
            }
            val mode by store.themeMode.collectAsStateWithLifecycle()
            Section(stringResource(R.string.theme_title)) {
                MvPreferencesStore.ThemeMode.entries.forEachIndexed { index, choice ->
                    if (index > 0) RowDivider()
                    ChoiceRow(
                        label = stringResource(
                            when (choice) {
                                MvPreferencesStore.ThemeMode.SYSTEM -> R.string.theme_system
                                MvPreferencesStore.ThemeMode.LIGHT -> R.string.theme_light
                                MvPreferencesStore.ThemeMode.DARK -> R.string.theme_dark
                            },
                        ),
                        selected = choice == mode,
                        icon = when (choice) {
                            MvPreferencesStore.ThemeMode.SYSTEM -> Lucide.Smartphone
                            MvPreferencesStore.ThemeMode.LIGHT -> Lucide.Sun
                            MvPreferencesStore.ThemeMode.DARK -> Lucide.Moon
                        },
                    ) { store.setThemeMode(choice) }
                }
                // True black under the dark theme, for OLED screens (#139).
                val pureBlack by store.pureBlack.collectAsStateWithLifecycle()
                RowDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .toggleable(pureBlack, role = Role.Switch, onValueChange = store::setPureBlack)
                        .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RowIcon(Lucide.Contrast)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.theme_pure_black), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.theme_pure_black_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                    PlainToggle(checked = pureBlack)
                }
            }
        }

        "looks" -> Section(null) { PageRow(stringResource(R.string.looks_title), stringResource(R.string.looks_summary)) { goTo(SettingsPage.Looks) } }

        "glass" -> Section(null) {
            PageRow(
                stringResource(R.string.glass),
                stringResource(R.string.page_glass_summary),
            ) { goTo(SettingsPage.Glass) }
        }

        // What the bar does, which is behaviour rather than material.
        "bar" -> {
            val glass = remember(context) {
                (context.applicationContext as app.sonar.SonarApplication).glass
            }
            val folds by glass.barFolds.collectAsStateWithLifecycle()
            Section(stringResource(R.string.page_app_bar)) {
                DownloadSwitch(
                    label = stringResource(R.string.bar_folds),
                    checked = folds,
                    backdrop = backdrop,
                    icon = Lucide.MoveHorizontal,
                    onChange = glass::setBarFolds,
                )
            }
        }

        // The face the app is set in: its own, or the phone's.
        "font" -> {
            val store = remember(context) {
                (context.applicationContext as app.sonar.SonarApplication).preferences
            }
            val systemFont by store.systemFont.collectAsStateWithLifecycle()
            val serifNames by store.serifArtistNames.collectAsStateWithLifecycle()
            Section(stringResource(R.string.font_title)) {
                DownloadSwitch(
                    label = stringResource(R.string.font_system),
                    checked = systemFont,
                    backdrop = backdrop,
                    icon = Lucide.CaseSensitive,
                    onChange = store::setSystemFont,
                )
                RowDivider()
                DownloadSwitch(
                    label = stringResource(R.string.font_serif_artists),
                    checked = serifNames,
                    backdrop = backdrop,
                    icon = Lucide.Italic,
                    onChange = store::setSerifArtistNames,
                )
            }
        }

        // The settings that are Android's rather than the app's.
        "system" -> {
            Section(stringResource(R.string.page_app_system)) {
                ActionRow(
                    stringResource(R.string.app_system_settings),
                    destructive = false,
                    icon = Lucide.Settings,
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                }
                RowDivider()
                ActionRow(
                    stringResource(R.string.app_battery_settings),
                    destructive = false,
                    icon = Lucide.BatteryCharging,
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                }
            }
        }

        "links" -> {
                Section(stringResource(R.string.spotify_links)) {
                    Text(
                        stringResource(R.string.spotify_links_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                    RowDivider()
                    ActionRow(
                        stringResource(R.string.open_by_default),
                        destructive = false,
                    ) {
                        // Android's own screen, because this is Android's own
                        // decision: an app cannot claim a domain it does not
                        // own, and the listener granting it here is the whole
                        // point of the design.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                }
        }

        "author" -> {
            Section(stringResource(R.string.fix2_upstream_credit)) {
                val uriHandler = LocalUriHandler.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(GITHUB_URL) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(GITHUB_USER, style = MaterialTheme.typography.titleMedium)
                        Text(
                            GITHUB_URL.removePrefix("https://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                    Icon(
                        Lucide.ArrowUpRight,
                        contentDescription = null,
                        tint = InkDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // The version as a statement rather than a row, and the update under
        // it, never folded away.
        "update" -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.scanfix_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    BuildConfig.BUILD_TYPE,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }
            Section(null) {
                UpdateRow(askToInstall)
            }
        }

        "about" -> {
            val uriHandler = LocalUriHandler.current
            Section(stringResource(R.string.about)) {
                ActionRow(
                    label = stringResource(R.string.scanfix_corresponding_source),
                    destructive = false,
                    icon = Lucide.ArrowUpRight,
                ) { uriHandler.openUri("https://github.com/Martinikaws/Sonar") }
                RowDivider()
                Licences()
                RowDivider()
                ReportRows(name = ready?.displayName, onSave = askToSaveReport)
            }
        }

        // Where to take a question, an idea or a problem.
        "help" -> {
            val uriHandler = LocalUriHandler.current
            Section(stringResource(R.string.help_feedback)) {
                ActionRow(
                    stringResource(R.string.telegram_community),
                    destructive = false,
                    icon = Lucide.Send,
                ) { uriHandler.openUri(TELEGRAM_COMMUNITY_URL) }
                RowDivider()
                ActionRow(
                    stringResource(R.string.help_discussions),
                    destructive = false,
                    icon = Lucide.MessagesSquare,
                ) { uriHandler.openUri(DISCUSSIONS_URL) }
                RowDivider()
                ActionRow(
                    stringResource(R.string.help_report_problem),
                    destructive = false,
                    icon = Lucide.Bug,
                ) { uriHandler.openUri(NEW_ISSUE_URL) }
            }
        }

        // A reference for whoever wants to check what the app asks Spotify for.
        "permissions" -> {
            Section(stringResource(R.string.permissions_asked)) {
                Text(
                    stringResource(R.string.permissions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                SCOPES.forEach { (scope, why) ->
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            scope,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                            modifier = Modifier.weight(0.9f),
                        )
                        Text(
                            stringResource(why),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1.1f),
                        )
                    }
                }
            }
        }

        else -> {}
    }
    }

    val listPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding() + 24.dp,
    )

    // A page arrives from the side it was opened from and leaves the other way.
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsAccent provides accent) {
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            val forward = initialState == null
            val width = { w: Int -> if (forward) w / 4 else -w / 4 }
            val back = { w: Int -> if (forward) -w / 4 else w / 4 }
            (
                slideInHorizontally(tween(PAGE_TRAVEL_MS), width) +
                    fadeIn(tween(PAGE_TRAVEL_MS))
                ) togetherWith (
                slideOutHorizontally(tween(PAGE_TRAVEL_MS), back) +
                    fadeOut(tween(PAGE_FADE_MS))
                )
        },
        label = "settingsPage",
    ) { shown ->
    when (shown) {
        SettingsPage.Looks -> app.sonar.ui.looks.LooksPage(sonarApp.preferences, listPadding) { heading(shown) }
        SettingsPage.Equalizer -> EqualizerPage(
            contentPadding = contentPadding,
            header = { heading(shown) },
        )

        SettingsPage.Glass -> GlassPage(
            backdrop = backdrop,
            contentPadding = contentPadding,
            header = { heading(shown) },
        )

        SettingsPage.Hidden -> app.sonar.library.HiddenContentPage(
            sonarApp.hiddenContent, listPadding, header = { heading(shown) },
        )

        SettingsPage.LocalPlaylists -> app.sonar.library.LocalPlaylistsPage(
            sonarApp.localPlaylists, onPlayFlac, listPadding, header = { heading(shown) },
        )

        SettingsPage.Flac -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
            item("top") { heading(shown) }
            items(app.sonar.flac.FlacProvider.entries.size) { index ->
                FlacProviderSettings(app.sonar.flac.FlacProvider.entries[index], sonarApp.flacSources, onPlayFlac)
            }
        }

        SettingsPage.WebApi -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
            item("top") { heading(shown) }
            item("webapi") {
            Section(stringResource(R.string.web_api)) {
                if (webApi.connected) {
                    InfoRow(stringResource(R.string.application), stringResource(R.string.connected))
                    RowDivider()
                    InfoRow(
                        stringResource(R.string.client_id),
                        webApi.clientId.take(8) + if (webApi.clientId.length > 8) "…" else "",
                    )
                    RowDivider()
                    ActionRow(stringResource(R.string.disconnect), destructive = true, onClick = onDisconnectWebApi)
                } else {
                    // The full explanation, because with no application
                    // connected search does not work at all and the reason is
                    // not something anyone would guess.
                    WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
                }
            }
            }
        }

        null -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = listPadding) {
            item("top") { heading(null) }

            item("search") {
                GlassSearchBar(
                    query = query,
                    onQuery = { query = it },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    placeholder = stringResource(R.string.settings_search_hint),
                    backdrop = backdrop,
                    onSubmit = { keyboard?.hide() },
                )
            }

            // Said, with the query, and the way back to the whole list.
            if (query.isNotBlank() && found.isEmpty()) item("search-empty") {
                Section(null) {
                    Text(
                        stringResource(R.string.settings_search_empty, query.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    RowDivider()
                    ActionRow(stringResource(R.string.settings_search_clear), destructive = false) { query = "" }
                }
            }

            SettingsGroup.entries.forEach { group ->
                val entries = found.filter { it.group == group }
                if (entries.isEmpty()) return@forEach
                groupHeading(group)
                entries.forEach { entry -> groupItem(entry.id, group) { block(entry.id) } }
                groupEnd(group)
            }
        }
    }
    }
    }
}

/** How long a page takes to arrive, and how quickly the last one goes. */
private const val PAGE_TRAVEL_MS = 260
private const val PAGE_FADE_MS = 140


/**
 * What Square is keeping, as a bar and a legend.
 *
 * Three answers to one question — where did the space go — and they are not
 * the same kind of thing: the music is what was asked for, the covers and
 * words beside it are what make it read as music offline, and the caches are
 * the app's own workings, which it rebuilds if they go. A number for each
 * would make them look alike; a bar says which one is the space.
 */
@Composable
private fun StorageChart() {
    val context = LocalContext.current
    val sonarApp = remember(context) { context.applicationContext as app.sonar.SonarApplication }
    val files by sonarApp.downloads.files.collectAsStateWithLifecycle()

    // Measured off the disk rather than from the index: a Canvas is video, and
    // the caches have nothing to be counted from but their own directories.
    val sizes by androidx.compose.runtime.produceState(Triple(0L, 0L, 0L), files.size) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val audio = files.values.sumOf { it.bytes }
            val extras = runCatching { MvDownloadExtras.bytes() }.getOrDefault(0L)
            val caches = runCatching {
                context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }.getOrDefault(0L)
            Triple(audio, extras, caches)
        }
    }
    val (audio, extras, caches) = sizes
    val total = (audio + extras + caches).coerceAtLeast(1L)
    val accent = settingsAccent()
    val parts = listOf(
        Triple(R.string.storage_music, audio, accent),
        Triple(R.string.storage_extras, extras, accent.copy(alpha = 0.55f)),
        Triple(R.string.storage_caches, caches, Ink.copy(alpha = 0.3f)),
    )

    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.copy(alpha = 0.07f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            android.text.format.Formatter.formatShortFileSize(context, audio + extras + caches),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(ContinuousCapsule()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (_, bytes, colour) ->
                // A share too small to see is left out rather than drawn as a
                // sliver nobody can read.
                val share = bytes.toFloat() / total
                if (share > 0.004f) {
                    Box(Modifier.fillMaxHeight().weight(share).background(colour))
                }
            }
        }
        parts.forEach { (label, bytes, colour) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(ContinuousCapsule()).background(colour))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    android.text.format.Formatter.formatShortFileSize(context, bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
            }
        }
    }
}

/** A row that opens a page. */
@Composable
private fun PageRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Which file the engine asks Spotify for.
 *
 * The three fixed steps are the ones the account is offered, and there is no
 * fourth: this client is served Ogg Vorbis at 320 kbps and below, never a
 * lossless file, so a "lossless" row would be a promise nothing can keep.
 */
/**
 * Which service the app plays from.
 *
 * The two are not equivalent and the note says so rather than letting the user
 * find out: Spotify is the account's own library, its playlists and its Connect
 * devices, while YouTube Music here is anonymous — the catalogue and search
 * work, an account's own library does not exist to read.
 *
 * Changing it restarts playback, so it is a setting rather than a switch in the
 * player.
 */
@Composable
private fun BackendSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val chosen by store.backend.collectAsStateWithLifecycle()

    Section(stringResource(R.string.backend)) {
        BackendId.entries.forEachIndexed { index, backend ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(
                    when (backend) {
                        BackendId.SPOTIFY -> R.string.backend_spotify
                        BackendId.YOUTUBE_MUSIC -> R.string.backend_youtube
                    },
                ),
                selected = backend == chosen,
            ) { store.setBackend(backend) }
        }
    }
}

/**
 * Which player plays Spotify: librespot, or Spotify's own Soloist.
 *
 * librespot needs nothing and is what downloads go through. Soloist needs
 * three things a listener does once, shown here as steps: their own key from
 * Spotify's developer dashboard, the binary from Spotify's CDN, and a sign-in
 * from the Spotify app, which sees "Square" on the network. Signed in, Soloist
 * becomes the engine.
 */
@Composable
private fun EngineSection(soloist: SoloistSetupUi) {
    // Soloist's three steps, a screen each, over the settings: opened by
    // choosing it while it is not set up yet, or from its row afterwards.
    var guide by remember { mutableStateOf(false) }
    if (guide) app.sonar.ui.onboarding.SoloistGuide(onClose = { guide = false })
    val ready = soloist.savedKey.isNotEmpty() && soloist.installed && soloist.signedIn

    Section(stringResource(R.string.engine)) {
        ChoiceRow(stringResource(R.string.engine_librespot), selected = !soloist.chosen) { soloist.choose(false) }
        RowDivider()
        ChoiceRow(stringResource(R.string.engine_soloist), selected = soloist.chosen) {
            soloist.choose(true)
            if (!ready) guide = true
        }
        RowDivider()
        Text(
            stringResource(R.string.engine_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
        if (!soloist.chosen) return@Section

        // Given up on for now: said, with the way back.
        soloist.failed?.let { reason ->
            RowDivider()
            Text(
                stringResource(R.string.soloist_failed, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
            RowDivider()
            ActionRow(stringResource(R.string.soloist_retry), destructive = false) { soloist.choose(true) }
        }

        // Where it is, in a line, and the steps again from here.
        RowDivider()
        Text(
            when {
                ready -> soloistAccountStatus(soloist)
                soloist.savedKey.isEmpty() -> stringResource(R.string.soloist_key_note)
                !soloist.installed -> soloistDownloadStatus(soloist)
                else -> soloistAccountStatus(soloist)
            },
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        if (ready) {
            Text(
                soloistDownloadStatus(soloist),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            )
        }
        RowDivider()
        ActionRow(stringResource(R.string.soloist_setup), destructive = false, icon = Lucide.ArrowUpRight) {
            guide = true
        }
        if (soloist.installed && soloist.download !is SoloistDownload.Progress.Running) {
            RowDivider()
            ActionRow(stringResource(R.string.soloist_check_update), destructive = false, onClick = soloist.fetch)
        }
    }
}

@Composable
private fun AudioSourcesSection() {
    val context = LocalContext.current
    val sonarApp = remember(context) { context.applicationContext as app.sonar.SonarApplication }
    val store = sonarApp.sourcePriority
    val priority by store.priority.collectAsStateWithLifecycle()
    val youtubeFallback by sonarApp.preferences.youtubeFallback.collectAsStateWithLifecycle()

    Section(stringResource(R.string.settings_group_sources)) {
        Text(
            stringResource(R.string.audio_sources_track_order),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .toggleable(
                    value = youtubeFallback,
                    role = Role.Switch,
                    onValueChange = sonarApp.preferences::setYouTubeFallback,
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.youtube_audio_fallback), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.youtube_audio_fallback_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }
            Switch(checked = youtubeFallback, onCheckedChange = null)
        }
        priority.order.forEachIndexed { index, source ->
            RowDivider()
            val label = stringResource(
                when (source) {
                    app.sonar.audiosource.AudioSource.SPOTIFY -> R.string.audio_source_spotify
                    app.sonar.audiosource.AudioSource.FLAC -> R.string.audio_source_flac
                    app.sonar.audiosource.AudioSource.YOUTUBE_MUSIC -> R.string.audio_source_youtube
                },
            )
            val enabled = source !in priority.disabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The whole row switches, as DownloadSwitch does; the arrows are apart.
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .toggleable(value = enabled, role = Role.Switch) { store.set(priority.setEnabled(source, it)) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) Ink else InkDim,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = null)
                }
                SourceMoveButton(
                    Lucide.ChevronUp,
                    stringResource(R.string.audio_source_move_up, label),
                    enabled = index > 0,
                ) { store.set(priority.move(source, index - 1)) }
                SourceMoveButton(
                    Lucide.ChevronDown,
                    stringResource(R.string.audio_source_move_down, label),
                    enabled = index < priority.order.lastIndex,
                ) { store.set(priority.move(source, index + 1)) }
            }
        }
    }
}

/** An arrow of the source list, at a full touch target. */
@Composable
private fun SourceMoveButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Ink else InkDim.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The Google account YouTube Music reads a library with.
 *
 * Only shown while that backend is the active one: on Spotify it would be an
 * account for a service the app is not currently playing from.
 *
 * Signing in is optional and the section says so. Search and playback work
 * without it; what it adds is the user's own playlists.
 */
@Composable
private fun YouTubeAccountSection(onSignIn: () -> Unit, onChannelChange: () -> Unit) {
    val context = LocalContext.current
    val sonarApp = remember(context) {
        context.applicationContext as app.sonar.SonarApplication
    }
    val backend by sonarApp.preferences.backend.collectAsStateWithLifecycle()
    if (backend != BackendId.YOUTUBE_MUSIC) return

    val account = remember(sonarApp) { sonarApp.youtubeAccount }
    val name by account.accountName.collectAsStateWithLifecycle()
    val expired by account.expired.collectAsStateWithLifecycle()
    val pageId by account.pageId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Asked for once the account is there, and only then: it is a signed-in
    // call, and there is nothing to switch between while signed out.
    var channels by remember {
        mutableStateOf<List<com.metrolist.innertube.models.YouTubeChannel>>(emptyList())
    }
    LaunchedEffect(name) {
        channels = if (name == null) emptyList() else account.channels()
    }

    Section(stringResource(R.string.youtube_account)) {
        if (name == null) {
            ChoiceRow(
                label = stringResource(R.string.youtube_sign_in),
                selected = false,
                onClick = onSignIn,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    // A cookie Google has stopped accepting. Said here rather
                    // than left to be guessed from a library that went empty.
                    if (expired) {
                        Text(
                            stringResource(R.string.youtube_session_expired),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                }
            }
            if (expired) {
                RowDivider()
                ChoiceRow(
                    label = stringResource(R.string.youtube_sign_in_again),
                    selected = false,
                    onClick = onSignIn,
                )
            }

            // The channels this Google account owns. One of them is the
            // personal account nobody uses and another is the channel with the
            // subscriptions on it, and until this list existed there was no
            // way to say which one the app was reading.
            if (channels.size > 1) {
                RowDivider()
                Text(
                    stringResource(R.string.youtube_channel),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkDim,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
                )
                channels.forEach { channel ->
                    ChoiceRow(
                        label = channel.handle?.takeIf { it.isNotBlank() }
                            ?.let { "${channel.name} · $it" }
                            ?: channel.name,
                        selected = channel.pageId == pageId,
                    ) {
                        scope.launch {
                            // Only a switch that Google accepted is worth
                            // reloading a library for.
                            if (account.useChannel(channel)) onChannelChange()
                        }
                    }
                }
            }
            RowDivider()
            ChoiceRow(
                label = stringResource(R.string.youtube_sign_out),
                selected = false,
            ) {
                scope.launch { sonarApp.youtubeBackend.logOut() }
            }
        }
    }
}

/**
 * The looping clip, on or off for everything.
 *
 * Off is not only "do not draw it": no clip is fetched at all, so a listener who
 * turns this off stops paying for a video on every track as well as seeing one.
 */
@Composable
private fun CanvasSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val enabled by store.canvasEnabled.collectAsStateWithLifecycle()
    val source by store.animatedCoverSource.collectAsStateWithLifecycle()

    Section(stringResource(R.string.canvas)) {
        DownloadSwitch(
            label = stringResource(R.string.canvas_show),
            icon = Lucide.Film,
            checked = enabled,
            backdrop = backdrop,
            onChange = store::setCanvasEnabled,
        )
    }
    // Only with them on: off, nothing moves whichever it would have been.
    if (enabled) {
        Section(stringResource(R.string.animated_cover_source)) {
            app.sonar.data.PreferencesStore.CoverSource.entries.forEachIndexed { index, choice ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    label = stringResource(
                        when (choice) {
                            app.sonar.data.PreferencesStore.CoverSource.SPOTIFY -> R.string.animated_cover_spotify
                            app.sonar.data.PreferencesStore.CoverSource.APPLE_MUSIC -> R.string.animated_cover_apple
                        },
                    ),
                    selected = choice == source,
                ) { store.setAnimatedCoverSource(choice) }
            }
        }
    }
}

@Composable
private fun AutoplaySection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val enabled by store.autoplayInfinite.collectAsStateWithLifecycle()

    Section(stringResource(R.string.autoplay)) {
        DownloadSwitch(
            label = stringResource(R.string.autoplay_infinite_title),
            icon = Lucide.Repeat,
            checked = enabled,
            backdrop = backdrop,
            onChange = store::setAutoplayInfinite,
        )
    }
}

@Composable
private fun EffectQualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).effectQuality
    }
    val chosen by store.quality.collectAsStateWithLifecycle()

    Section(stringResource(R.string.effect_quality)) {
        EffectQuality.entries.forEachIndexed { index, quality ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(quality.label),
                selected = quality == chosen,
            ) { store.set(quality) }
        }
    }
}

@Composable
private fun CrossfadeSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).crossfade
    }
    val preferences = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val chosen by store.seconds.collectAsStateWithLifecycle()
    val trimSilence by preferences.trimSilence.collectAsStateWithLifecycle()
    val skipFade by preferences.skipFade.collectAsStateWithLifecycle()

    Section(stringResource(R.string.crossfade)) {
        CrossfadeSteps.forEachIndexed { index, seconds ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = if (seconds == 0) {
                    stringResource(R.string.crossfade_off)
                } else {
                    stringResource(R.string.crossfade_seconds, seconds)
                },
                selected = seconds == chosen,
            ) { store.set(seconds) }
        }
        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.trim_silence),
            icon = Lucide.Scissors,
            checked = trimSilence,
            backdrop = backdrop,
            onChange = preferences::setTrimSilence,
        )
        RowDivider()
        // Beside the crossfade, which is the same movement at the end of a
        // track rather than at the listener's hand, and separate from it: one
        // is about how records end, the other about what happens when somebody
        // has heard enough.
        DownloadSwitch(
            label = stringResource(R.string.skip_fade),
            icon = Lucide.AudioWaveform,
            checked = skipFade,
            backdrop = backdrop,
            onChange = preferences::setSkipFade,
        )
        RowDivider()
        Text(
            stringResource(R.string.skip_fade_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
        RowDivider()
        Text(
            stringResource(R.string.quality_restarts),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun QualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).quality
    }
    val chosen by store.quality.collectAsStateWithLifecycle()

    Section(stringResource(R.string.quality)) {
        Quality.entries.forEachIndexed { index, quality ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(
                    when (quality) {
                        Quality.Auto -> quality.label
                        Quality.High -> R.string.stream_quality_very_high
                        Quality.Medium -> R.string.stream_quality_high
                        Quality.Low -> R.string.stream_quality_normal
                    },
                ),
                selected = quality == chosen,
            ) { store.set(quality) }
        }
        // What automatic is deciding, and from what. A setting that answers
        // "it depends" should say what it depends on.
        if (chosen == Quality.Auto) {
            RowDivider()
            val link = store.linkKbps()
            InfoRow(
                stringResource(R.string.quality_link),
                if (link > 0) {
                    stringResource(R.string.quality_link_value, link, store.automatic())
                } else {
                    stringResource(R.string.quality_link_unknown, store.automatic())
                },
            )
        }
        RowDivider()
        Text(
            stringResource(R.string.quality_restarts),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * The listener's own Spicy Lyrics key.
 *
 * Optional: without it the source is simply not asked. The key comes from
 * Square's page in Spicy Lyrics' catalogue, which gives each person their own,
 * as the API's terms ask of an app that runs on other people's phones.
 */
@Composable
private fun SpicyLyricsSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val saved by store.spicyLyricsKey.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    val uriHandler = LocalUriHandler.current
    Section(stringResource(R.string.spicy_lyrics_title)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.spicy_lyrics_note),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
            )
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.spicy_lyrics_key)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
        RowDivider()
        if (draft.trim() != saved) {
            ActionRow(stringResource(R.string.save), destructive = false, icon = Lucide.Check) {
                store.setSpicyLyricsKey(draft)
            }
            RowDivider()
        }
        if (saved.isNotEmpty()) {
            ActionRow(stringResource(R.string.spicy_lyrics_remove), destructive = true) {
                store.setSpicyLyricsKey("")
            }
            RowDivider()
        }
        ActionRow(stringResource(R.string.spicy_lyrics_get_key), destructive = false, icon = Lucide.ArrowUpRight) {
            runCatching { uriHandler.openUri(app.sonar.backend.lyrics.SpicyLyrics.KEY_PAGE) }
        }
    }
}

/**
 * The lyrics source to show first, or none and the best of them.
 *
 * Folded under one row showing the choice: the list is every source there
 * is, and most people never open it. Only the sources the current service
 * asks, so a YouTube Music listener is not offered Spotify's own words.
 */
@Composable
private fun PreferredLyricsSection(showSpotify: Boolean) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val chosen by store.preferredLyricsSource.collectAsStateWithLifecycle()
    // Read so that entering or removing a Spicy Lyrics key changes the list.
    val spicyKey by store.spicyLyricsKey.collectAsStateWithLifecycle()
    val sources = remember(showSpotify, spicyKey) {
        app.sonar.backend.lyrics.LyricsLibrary.sourcesFor(
            if (showSpotify) "spotify:track:" else app.sonar.backend.youtube.YouTubeBackend.TRACK_PREFIX,
        )
    }
    val auto = stringResource(R.string.lyrics_preferred_auto)
    val current = sources.firstOrNull { it.name == chosen }?.label ?: auto
    var open by remember { mutableStateOf(false) }
    Section(stringResource(R.string.lyrics_preferred_title)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(current, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            RowDivider()
            ChoiceRow(label = auto, selected = sources.none { it.name == chosen }) {
                store.setPreferredLyricsSource("")
                open = false
            }
            sources.forEach { source ->
                RowDivider()
                ChoiceRow(label = source.label, selected = source.name == chosen) {
                    store.setPreferredLyricsSource(source.name)
                    open = false
                }
            }
        }
        RowDivider()
        Text(
            stringResource(R.string.lyrics_preferred_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * The music folder `.lrc` files are read from, beside their songs.
 *
 * Only from Android 13, where the audio permission stopped reaching files that
 * are not audio; before that they are read without asking. See LocalLyrics.
 */
@Composable
private fun LyricsFolderSection(onPick: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val folder by store.lyricsFolder.collectAsStateWithLifecycle()
    // "primary:Music" reads as "Music"; the root of a card as its name.
    val name = remember(folder) {
        folder.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching {
                android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri))
                    .let { id -> id.substringAfter(':').ifBlank { id.substringBefore(':') } }
            }.getOrNull()
        }
    }
    Section(stringResource(R.string.lyrics_folder_title)) {
        ActionRow(
            name ?: stringResource(R.string.lyrics_folder_choose),
            destructive = false,
            icon = Lucide.FolderOpen,
        ) { onPick() }
        if (name != null) {
            RowDivider()
            ActionRow(stringResource(R.string.lyrics_folder_forget), destructive = true) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        android.net.Uri.parse(folder),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                store.setLyricsFolder("")
            }
        }
        RowDivider()
        Text(
            stringResource(R.string.lyrics_folder_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun YouTubeQualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).preferences
    }
    val chosen by store.youtubeQuality.collectAsStateWithLifecycle()
    Section(stringResource(R.string.quality)) {
        app.sonar.data.PreferencesStore.YouTubeQuality.entries.forEachIndexed { index, quality ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(
                    when (quality) {
                        app.sonar.data.PreferencesStore.YouTubeQuality.AUTO -> R.string.quality_youtube_auto
                        app.sonar.data.PreferencesStore.YouTubeQuality.HIGH -> R.string.quality_youtube_best
                        app.sonar.data.PreferencesStore.YouTubeQuality.LOW -> R.string.quality_youtube_saver
                    },
                ),
                selected = quality == chosen,
            ) { store.setYoutubeQuality(quality) }
        }
        RowDivider()
        Text(
            stringResource(R.string.quality_youtube_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * Checking for, and installing, a new version.
 *
 * A button rather than something that happens on its own: the check is a
 * request to GitHub carrying the user's address, made for the app's benefit
 * rather than theirs, and nothing here needs it badly enough to make it
 * automatic.
 */
@Composable
private fun UpdateRow(onNeedsPermission: (Updater.State.Available) -> Unit) {
    val context = LocalContext.current
    val updater = remember(context) {
        (context.applicationContext as app.sonar.SonarApplication).updater
    }
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val status = when (val current = state) {
        is Updater.State.Idle -> null
        is Updater.State.Checking -> stringResource(R.string.update_checking)
        is Updater.State.UpToDate -> stringResource(R.string.update_none)
        is Updater.State.Available -> stringResource(R.string.update_available, current.version)
        is Updater.State.Downloading ->
            current.progress?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.update_downloading)
        is Updater.State.Installing -> stringResource(R.string.update_installing)
        is Updater.State.Failed ->
            if (current.reason == Updater.REASON_PERMISSION) stringResource(R.string.update_needs_permission)
            else stringResource(R.string.update_failed)
    }

    val busy = state is Updater.State.Checking ||
        state is Updater.State.Downloading ||
        state is Updater.State.Installing

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                scope.launch { updater.checkAndInstall()?.let(onNeedsPermission) }
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.check_for_updates),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * The third-party code this app ships, and under what.
 *
 * Not a nicety: Bungee is MPL-2.0 and the Backdrop components are Apache-2.0,
 * and both licences require the notice to travel with the binary. Collapsed by
 * default because it is an obligation to the authors, not a feature.
 */
@Composable
private fun Licences() {
    var open by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.licences), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            val notices = LICENCES.map { (what, licence) -> "$what — $licence" }
            (notices + listOf(
                stringResource(R.string.scanfix_lucide),
                stringResource(R.string.scanfix_innertube),
                stringResource(R.string.scanfix_fonts),
            )).forEach { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * What is kept on the phone, and the rules for keeping it.
 *
 * The numbers first, because the question a downloads screen is opened with is
 * almost always how much room this is taking.
 */
@Composable
private fun DownloadsSection(
    backdrop: Backdrop,
    /** Spotify the source: its bitrate and its Liked Songs are offered too. */
    spotify: Boolean = true,
) {
    val context = LocalContext.current
    val sonarApp = remember(context) {
        context.applicationContext as app.sonar.SonarApplication
    }
    val store = sonarApp.downloads
    val settings = sonarApp.downloadSettings

    val files by store.files.collectAsStateWithLifecycle()
    val failures by store.failures.collectAsStateWithLifecycle()
    val quality by settings.quality.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val likedSongs by settings.downloadLikedSongs.collectAsStateWithLifecycle()
    val offline by settings.offlineMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Two taps rather than a dialog, and the row says so in between: deleting a
    // library is worth a moment's thought, and a page of settings is the wrong
    // place to grow a modal.
    var confirmingClear by remember { mutableStateOf(false) }

    Section(stringResource(R.string.downloads)) {
        // The audio plus everything kept beside it. Counted rather than summed
        // from the index, because the Canvases are video and a library of them
        // is not a rounding error next to the music.
        val extrasBytes by androidx.compose.runtime.produceState(0L, files.size) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MvDownloadExtras.bytes()
            }
        }
        InfoRow(
            stringResource(R.string.download_storage),
            stringResource(
                R.string.download_storage_used,
                android.text.format.Formatter.formatShortFileSize(
                    context,
                    files.values.sumOf { it.bytes } + extrasBytes,
                ),
            ),
        )
        RowDivider()
        InfoRow(stringResource(R.string.downloaded_tracks), files.size.toString())

        // Spotify's bitrates; YouTube Music downloads what it serves.
        if (spotify) {
            RowDivider()
            Text(
                stringResource(R.string.download_quality),
                style = MaterialTheme.typography.labelLarge,
                color = InkDim,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
            )
            MvDownloadQuality.entries.forEach { entry ->
                ChoiceRow(
                    label = stringResource(entry.label),
                    selected = entry == quality,
                ) { settings.setQuality(entry) }
            }
        }

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.download_wifi_only),
            icon = Lucide.Wifi,
            checked = wifiOnly,
            backdrop = backdrop,
            onChange = settings::setWifiOnly,
        )

        // Spotify's Liked Songs, which the switch keeps downloaded.
        if (spotify) RowDivider()
        if (spotify) DownloadSwitch(
            label = stringResource(R.string.download_liked_songs),
            icon = Lucide.Heart,
            checked = likedSongs,
            backdrop = backdrop,
            onChange = { enable ->
                settings.setDownloadLikedSongs(enable)
                if (enable) {
                    scope.launch {
                        val tracks = sonarApp.likedStore.likedTracks.value
                        if (tracks.isNotEmpty()) {
                            val likedTracksList = tracks.map { uri ->
                                store.trackOf(uri) ?: MvCatalogTrack(
                                    uri = uri,
                                    name = "",
                                    artist = "",
                                )
                            }
                            store.setOwner(MvDownloadStore.LIKED, likedTracksList, label = null)
                            MvDownloadService.start(context)
                        }
                    }
                } else {
                    scope.launch {
                        store.removeOwner(MvDownloadStore.LIKED)
                        store.pruneOrphans().forEach { orphanUri ->
                            runCatching { MvYouTubeDownloads.forget(context, orphanUri) }
                            MvDownloadExtras.forget(orphanUri)
                        }
                    }
                }
            },
        )

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.offline_mode),
            icon = Lucide.Plane,
            checked = offline,
            backdrop = backdrop,
            onChange = settings::setOfflineMode,
        )

        // Always offered, unlike the retry above: the music being here is not
        // the same as its covers and words being here, and a song kept by an
        // older build has none of them. Nothing is re-downloaded but the
        // extras; the audio stays where it is.
        RowDivider()
        ActionRow(
            stringResource(R.string.download_refetch_extras),
            destructive = false,
            icon = Lucide.Image,
        ) {
            scope.launch {
                (context.applicationContext as app.sonar.SonarApplication)
                    .downloadQueue.refetchExtras()
                MvDownloadService.start(context)
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.download_refetch_started),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // Only when there is something to say. A row reporting zero failures is
        // a row about nothing, on a screen that is already long.
        val givenUp = failures.count {
            it.value.attempts >= MvDownloadStore.MAX_ATTEMPTS
        }
        if (givenUp > 0) {
            RowDivider()
            InfoRow(
                stringResource(R.string.downloads),
                stringResource(R.string.download_failed_count, givenUp),
            )
            ActionRow(
                stringResource(R.string.download_retry_failed),
                destructive = false,
                icon = Lucide.RotateCw,
            ) {
                scope.launch {
                    store.retryFailed()
                    MvDownloadService.start(context)
                }
            }

        }

        if (files.isNotEmpty()) {
            RowDivider()
            ActionRow(
                if (confirmingClear) {
                    stringResource(R.string.remove_all_downloads_confirm)
                } else {
                    stringResource(R.string.remove_all_downloads)
                },
                destructive = true,
            ) {
                if (confirmingClear) {
                    confirmingClear = false
                    scope.launch { store.clearAll() }
                } else {
                    confirmingClear = true
                }
            }
        }
    }
}

@Composable
internal fun DownloadSwitch(
    label: String,
    checked: Boolean,
    backdrop: Backdrop,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Drawn, not glass; see PlainToggle.
        PlainToggle(checked = checked)
    }
}

@Composable
internal fun Section(title: String?, content: @Composable () -> Unit) {
    // Under a group of the same name, said once, by the group.
    val group = LocalGroupTitle.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (title != null && !title.equals(group, ignoreCase = true)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = InkDim,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Ink.copy(alpha = 0.07f)),
            content = { content() },
        )
    }
}

/** A group's mark; the groups themselves are SettingsGroup. */
private fun groupIcon(group: SettingsGroup): ImageVector = when (group) {
    SettingsGroup.ACCOUNT -> Lucide.CircleUser
    SettingsGroup.SETUP -> Lucide.Key
    SettingsGroup.SOUND -> Lucide.AudioWaveform
    SettingsGroup.SOURCES -> Lucide.Layers
    SettingsGroup.LISTENING -> Lucide.ListMusic
    SettingsGroup.LYRICS -> Lucide.MicVocal
    SettingsGroup.COVERS -> Lucide.Film
    SettingsGroup.DOWNLOADS -> Lucide.Download
    SettingsGroup.CLEANUP -> Lucide.Eraser
    SettingsGroup.LANGUAGE -> Lucide.Languages
    SettingsGroup.APPEARANCE -> Lucide.Palette
    SettingsGroup.SYSTEM -> Lucide.Settings
    SettingsGroup.ABOUT -> Lucide.Info
    SettingsGroup.HELP -> Lucide.LifeBuoy
    SettingsGroup.PERMISSIONS -> Lucide.ShieldCheck
}

/** The group a section is drawn in, whose heading it then does not repeat; see Section. */
private val LocalGroupTitle = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * A group's heading, at the top of the panel its sections sit in.
 *
 * The panel is wider than the sections' own cards and quieter than them, so
 * a group reads as the thing that holds settings and a setting as the thing
 * inside: the heading is its top, the sections run down it on the same
 * ground, and [groupEnd] closes it.
 */
private fun LazyListScope.groupHeading(group: SettingsGroup) =
    item("group-${group.name}") {
        Row(
            Modifier
                .animateItem(fadeInSpec = tween(GROUP_MOTION_MS), fadeOutSpec = tween(150))
                .fillMaxWidth()
                .padding(start = GROUP_MARGIN, end = GROUP_MARGIN, top = 6.dp)
                .clip(RoundedCornerShape(GROUP_CORNER, GROUP_CORNER, 0.dp, 0.dp))
                .background(groupPanel())
                .padding(start = 18.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GroupMark(groupIcon(group))
            Text(
                stringResource(group.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
    }

/** The foot of a group's panel, under its last section. */
private fun LazyListScope.groupEnd(group: SettingsGroup) =
    item("group-end-${group.name}") {
        Box(
            Modifier
                .animateItem(fadeInSpec = tween(GROUP_MOTION_MS), fadeOutSpec = tween(150))
                .fillMaxWidth()
                .padding(start = GROUP_MARGIN, end = GROUP_MARGIN, bottom = 6.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(0.dp, 0.dp, GROUP_CORNER, GROUP_CORNER))
                .background(groupPanel()),
        )
    }

/**
 * A group's mark: larger than a setting's and filled with the accent rather
 * than tinted by it, the one place on the page the colour is spent.
 */
@Composable
private fun GroupMark(icon: ImageVector) {
    val accent = settingsAccent()
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(accent.copy(alpha = 0.26f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
    }
}

/** The ground of a group's panel: under the sections' own cards, which stand out on it. */
@Composable
private fun groupPanel(): Color = Ink.copy(alpha = 0.045f)

/** How far a group's panel sits from the edge: outside the sections' own cards. */
private val GROUP_MARGIN = 10.dp
private val GROUP_CORNER = 28.dp
private const val GROUP_MOTION_MS = 220

/** One of a group's sections, fading in and out as a search finds it or not. */
private fun LazyListScope.groupItem(key: String, group: SettingsGroup, content: @Composable () -> Unit) =
    item(key) {
        val title = stringResource(group.title)
        // A column, not a box: some items are more than one section (the
        // covers' switch and, under it, where they come from), and a box
        // drew them on top of each other.
        Column(
            Modifier
                .animateItem(fadeInSpec = tween(GROUP_MOTION_MS), fadeOutSpec = tween(150))
                .padding(horizontal = GROUP_MARGIN)
                .background(groupPanel()),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalGroupTitle provides title) { content() }
        }
    }


@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Puts together a report of the app's last crashes and recent log, with the
 * account taken out, and saves it to a folder or opens the share sheet on it;
 * see Report.
 *
 * Saving is the first of the two because the share sheet on many phones has no
 * way to put a file in a folder, and a report that can only be sent to an app
 * cannot be attached to an issue from the browser.
 */
@Composable
private fun ReportRows(name: String?, onSave: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun failed(error: Throwable) {
        android.util.Log.w("SonarReport", "report failed: $error")
        android.widget.Toast.makeText(context, context.getString(R.string.report_failed), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun prepare(then: (java.io.File) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { app.sonar.diagnostics.Report.build(context, listOfNotNull(name)) }
                .onSuccess(then)
                .onFailure(::failed)
            busy = false
        }
    }

    Column {
        ActionRow(
            stringResource(if (busy) R.string.report_preparing else R.string.save_report),
            destructive = false,
        ) { prepare(onSave) }
        RowDivider()
        ActionRow(stringResource(R.string.send_report), destructive = false) {
            prepare { file ->
                runCatching { app.sonar.diagnostics.Report.share(context, file) }.onFailure(::failed)
            }
        }
        Text(
            stringResource(R.string.send_report_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        )
    }
}

@Composable
internal fun ActionRow(
    label: String,
    destructive: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    if (icon != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowIcon(icon)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (destructive) MaterialTheme.colorScheme.error else Ink,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = if (destructive) MaterialTheme.colorScheme.error else Ink,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    )
}

/** A row of a list where one is picked, with a tick on the one that is. */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Ink else InkDim,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .height(1.dp)
            .background(Ink.copy(alpha = 0.08f)),
    )
}

/** Kept next to the request that asks for them; see MainViewModel.connectWebApi. */
private val SCOPES = listOf(
    "user-top-read" to R.string.scope_top_artists,
    "user-read-recently-played" to R.string.scope_history,
    "user-read-playback-state" to R.string.scope_devices,
    "user-modify-playback-state" to R.string.scope_transfer,
    "playlist-modify-private" to R.string.scope_private_playlists,
    "playlist-modify-public" to R.string.scope_public_playlists,
    "user-library-modify" to R.string.scope_library,
    "ugc-image-upload" to R.string.scope_cover,
)

private val LICENCES = listOf(
    "librespot" to "MIT",
    "Bungee" to "MPL-2.0",
    "AndroidLiquidGlass" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "Spicy Lyrics" to "AGPL-3.0",
    "OkHttp / Retrofit" to "Apache-2.0",
    "yt-dlp ejs" to "Unlicense",
    "meriyah" to "ISC",
    "astring" to "MIT",
    // Soloist's runtime: Debian's glibc 2.41-12+deb13u5, patched as
    // soloist/README.md in the source repository describes.
    "GNU C Library (modified, for Soloist)" to "LGPL-2.1-or-later",
    "libgcc_s, libatomic (for Soloist)" to "GPL-3.0 with GCC Runtime Library Exception",
)

private const val GITHUB_USER = "Lelonio"
private const val GITHUB_URL = "https://github.com/Lelonio"
private const val DISCUSSIONS_URL = "https://github.com/Martinikaws/Sonar/discussions"
private const val TELEGRAM_COMMUNITY_URL = "https://t.me/+SAOhZ4HTFpMwNDJk"
private const val NEW_ISSUE_URL = "https://github.com/Martinikaws/Sonar/issues/new/choose"
