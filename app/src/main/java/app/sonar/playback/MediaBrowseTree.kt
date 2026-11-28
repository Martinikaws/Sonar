package app.sonar.playback

import app.sonar.backend.youtube.YouTubeBackend as MvYouTubeBackend
import app.sonar.data.SmartShuffle as MvSmartShuffle
import app.sonar.data.SpotifyApi as MvSpotifyApi
import app.sonar.download.DownloadExtras as MvDownloadExtras
import app.sonar.download.DownloadService as MvDownloadService
import app.sonar.download.YouTubeDownloads as MvYouTubeDownloads
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.sonar.R
import app.sonar.SonarApplication
import app.sonar.backend.BackendId
import app.sonar.backend.SearchLabels
import app.sonar.data.Catalog
import app.sonar.data.CatalogPlaylist
import app.sonar.data.CatalogTrack
import app.sonar.data.SearchResults
import app.sonar.nativecore.NativeBridge
import app.sonar.ui.EXTRA_CONTEXT_LABEL
import app.sonar.ui.EXTRA_CONTEXT_ORDERED
import app.sonar.ui.EXTRA_CONTEXT_URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The browse tree Android Auto (and any other `MediaBrowser`) reads.
 *
 * Auto never draws the app's own UI: it asks the session for a root, walks the
 * children it answers with, and plays whatever item is tapped. So everything
 * the car can reach has to exist here rather than in Compose.
 *
 * Two things make this more than a listing:
 *
 * - **The engine may not be running.** The car can bind the service with the
 *   app never having been opened, so every read waits for the native session to
 *   finish connecting instead of answering with an empty shelf.
 * - **A tapped track is not a queue.** Auto sends back exactly the one item it
 *   was given, which would play a single song and stop. The id of every track
 *   carries the node it was listed under, and [onSetMediaItems] expands it back
 *   into the whole playlist positioned at that track — which is what tapping
 *   the third song in a playlist means everywhere else.
 */
@UnstableApi
class MediaBrowseTree(
    context: Context,
    private val scope: CoroutineScope,
    /**
     * Puts the right player behind the session for a queue about to be set.
     *
     * The service's own, passed in rather than reached for: this class is the
     * one place every queue goes through, whoever set it — the app, the car,
     * an assistant — and the music on the phone plays through a different
     * player than either streaming service. See PlaybackService.ensurePlayerFor.
     */
    private val ensurePlayerFor: suspend (String?) -> Unit = {},
    /**
     * What to play when something asks to resume with nothing loaded: a media
     * button, a headset, the system's media controls after a restart. Null when
     * there is nothing saved either.
     */
    private val resumption: () -> MediaSession.MediaItemsWithStartPosition? = { null },
) : MediaLibrarySession.Callback {

    /**
     * Play pressed on a player with nothing in it.
     *
     * Without an answer here Media3 has nothing to start, and a service it has
     * already brought to the foreground for the button has to show a playing
     * notification within seconds or Android ends the app. The answer is the
     * session last saved, which is also what the app itself opens on.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        resumption()?.let { Futures.immediateFuture(it) }
            ?: Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))

    private val container = context.applicationContext as SonarApplication

    /**
     * Resources read in the app's own language.
     *
     * A service is not re-created when the language changes and below Android 13
     * its configuration is the phone's, not the app's — the same reason the
     * engine asks [SonarApplication.language] rather than its own resources.
     */
    private val strings: Context = container.language.wrap(container)

    /** Track lists already resolved, keyed by the node they were listed under. */
    private val trackCache = mutableMapOf<String, List<CatalogTrack>>()
    private var lastSearch: Pair<String, SearchResults>? = null
    private val playlistCache = mutableMapOf<BackendId, List<CatalogPlaylist>>()

    /** Playlist names, so a queue built from the car is labelled like one built in the app. */
    private val nodeLabels = mutableMapOf<String, String>()

    private data class BrowseAccount(
        val spotifySignedIn: Boolean,
        val spotifyLogin: Any,
        val spotifyWebLogin: Any,
        val spotifyUsername: String?,
        val youtubeLogin: Any,
    )

    private var cachedAccount: BrowseAccount? = null

    private fun refreshBrowseAccount(): BrowseAccount {
        val account = BrowseAccount(
            container.spotifySignedIn,
            container.tokenStore.browseAccountRevision,
            container.webApi.tokens.browseAccountRevision,
            if (NativeBridge.isConnected) NativeBridge.username() else null,
            container.youtubeAccount.browseAccountRevision,
        )
        if (cachedAccount != account) {
            trackCache.clear()
            playlistCache.clear()
            browseFolders.clear()
            nodeLabels.clear()
            lastSearch = null
            cachedAccount = account
        }
        return account
    }

    private val offlineBrowsers = mutableSetOf<MediaSession.ControllerInfo>()

    private fun offlineBrowse(browser: MediaSession.ControllerInfo, params: LibraryParams? = null): Boolean =
        OfflineMode.active.value || browser in offlineBrowsers || params?.isOffline == true

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        offlineBrowsers.remove(controller)
    }

    /** One catalogue read at a time: the car fires children requests in parallel. */
    private val lock = Mutex()

    /**
     * The buttons the car draws beside play and skip.
     *
     * Android Auto shows the transport it knows about and nothing else: shuffle
     * and repeat are the player's own modes, and a session that does not offer
     * them as commands simply has no way to say so. They are declared per
     * controller rather than for everyone, so the phone's notification keeps
     * its own two buttons instead of growing four.
     *
     * The icons say what pressing will show, not what pressing does: this is
     * how every other player behaves, and the alternative is a control whose
     * picture disagrees with the thing it is describing.
     */
    private fun layoutFor(
        player: androidx.media3.common.Player,
        /**
         * The buttons of the system's own session, which two places read.
         *
         * The car does not get a layout of its own: Android Auto connects the
         * old way and draws whatever the platform session carries, and that is
         * the shade's. So this one list serves both. The shade has room for
         * two beside the transport and takes the first two, shuffle and the
         * heart; the car has room for more and gets radio after them, which is
         * where it was before the heart arrived and pushed it out. Repeat is a
         * setting people change once, and stays in the app.
         */
        shadeAndCar: Boolean = false,
        offline: Boolean = false,
    ): ImmutableList<CommandButton> {
        val shuffle = CommandButton.Builder(
            if (player.shuffleModeEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .setDisplayName(strings.getString(R.string.shuffle))
            // Where each goes, said rather than left to the order. Some
            // phones' media controls (OxygenOS, #84) showed only the transport
            // for a layout that did not say, and Spotify's two beside it.
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()

        if (!shadeAndCar) {
            return ImmutableList.of(
                shuffle,
                CommandButton.Builder(
                    when (player.repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_ONE ->
                            CommandButton.ICON_REPEAT_ONE
                        androidx.media3.common.Player.REPEAT_MODE_ALL ->
                            CommandButton.ICON_REPEAT_ALL
                        else -> CommandButton.ICON_REPEAT_OFF
                    },
                )
                    .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                    .setDisplayName(strings.getString(R.string.repeat))
                    .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }

        val currentUri = player.currentMediaItem?.mediaId
        val liked = container.likedStore.isLiked(currentUri)
        val heart = CommandButton.Builder()
            .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
            .setDisplayName(
                strings.getString(if (liked) R.string.remove_from_liked else R.string.auto_like),
            )
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        val radio = CommandButton.Builder(CommandButton.ICON_RADIO)
            .setSessionCommand(SessionCommand(CMD_RADIO, Bundle.EMPTY))
            .setDisplayName(strings.getString(R.string.radio))
            // Beside the transport where there is no heart to put there.
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
        // The heart only where it can do something: a button that did nothing
        // was what a YouTube Music listener with no account found here.
        return ImmutableList.builder<CommandButton>().apply {
            add(shuffle)
            if (likeable(currentUri)) add(heart)
            if (!offline && radioSeed(currentUri)) add(radio)
        }.build()
    }

    fun layoutFor(session: MediaSession, controller: MediaSession.ControllerInfo): ImmutableList<CommandButton> =
        layoutFor(
            session.player,
            shadeAndCar = session.isMediaNotificationController(controller) ||
                (trustedController(session, controller) &&
                    (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller))),
            offline = offlineBrowse(controller),
        )

    /**
     * Whether the song can be liked from here: any Spotify track, and a YouTube
     * Music one when an account is signed in to keep it in.
     */
    private fun likeable(uri: String?): Boolean = uri != null && (
        (uri.startsWith("spotify:track:") && container.spotifySignedIn) ||
            (uri.startsWith(MvYouTubeBackend.TRACK_PREFIX) && container.youtubeAccount.isSignedIn)
        )

    private fun trustedController(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean {
        val packageName = controller.packageName
        if (packageName == container.packageName && controller.uid == android.os.Process.myUid()) return true
        if (controller.isTrusted) return true

        // Media3's car helpers identify package names. Verify their installed UID and system status.
        if (!session.isAutoCompanionController(controller) && !session.isAutomotiveController(controller) &&
            packageName !in SYSTEM_MEDIA_PACKAGES
        ) return false
        val info = try {
            container.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return false
        }
        return info.uid == controller.uid && info.flags and SYSTEM_APP_FLAGS != 0
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val trusted = trustedController(session, controller)
        val commands = (if (trusted) MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            else MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS).buildUpon()
            .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .add(SessionCommand(MvSmartShuffle.COMMAND, Bundle.EMPTY))
            .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .add(SessionCommand(CMD_RADIO, Bundle.EMPTY))
            .apply { if (trusted) add(SessionCommand(CMD_LIKE, Bundle.EMPTY)) }
            .build()

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setMediaButtonPreferences(layoutFor(session, controller))
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val player = session.player
        when (customCommand.customAction) {
            MvSmartShuffle.COMMAND -> {
                return scope.future {
                    try {
                        if (player is LibrespotPlayer) player.cycleShuffle()
                        else if (player is app.sonar.playback.soloist.SoloistPlayer) player.cycleShuffle()
                        else player.shuffleModeEnabled = !player.shuffleModeEnabled
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("SonarSmartShuffle", "cannot enable: ${e.message}")
                        android.widget.Toast.makeText(container, R.string.smart_shuffle_unavailable, android.widget.Toast.LENGTH_LONG).show()
                        SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                    }
                }
            }
            CMD_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            CMD_REPEAT -> player.repeatMode = when (player.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_OFF ->
                    androidx.media3.common.Player.REPEAT_MODE_ALL
                androidx.media3.common.Player.REPEAT_MODE_ALL ->
                    androidx.media3.common.Player.REPEAT_MODE_ONE
                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
            }
            CMD_RADIO -> {
                if (offlineBrowse(controller) || !radioSeed(player.currentMediaItem?.mediaId)) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
                startRadio(session, controller)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CMD_LIKE -> {
                if (!trustedController(session, controller)) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                }
                return toggleLike(session)
            }

            else -> return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
            )
        }
        // Redrawn at once on each controller so their layouts (car vs shade) remain distinct.
        session.connectedControllers.forEach { ctrl ->
            session.setMediaButtonPreferences(
                ctrl,
                layoutFor(session, ctrl),
            )
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private val likeLock = Mutex()

    private fun toggleLike(session: MediaSession): ListenableFuture<SessionResult> {
        val item = session.player.currentMediaItem
        val uri = item?.mediaId
        if (item == null || uri == null || !likeable(uri)) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        val youtube = uri.startsWith(MvYouTubeBackend.TRACK_PREFIX)
        val id = uri.substringAfterLast(':')

        return scope.future {
            likeLock.withLock {
                val nowLiked = container.likedStore.toggle(uri)
                session.connectedControllers.forEach { ctrl ->
                    session.setMediaButtonPreferences(ctrl, layoutFor(session, ctrl))
                }
                val callResult = runCatching {
                    // YouTube Music keeps liked songs as a list of its own, and
                    // liking is adding to it, as the app's own plus does.
                    if (youtube) {
                        val liked = MvYouTubeBackend.LIKED_MUSIC_URI
                        if (nowLiked) container.youtubeBackend.addToPlaylist(liked, uri)
                        else container.youtubeBackend.removeFromPlaylist(liked, uri)
                    } else {
                        try {
                            if (nowLiked) container.api.saveToLibrary(uri, MvSpotifyApi.EMPTY_BODY)
                            else container.api.removeFromLibrary(uri)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if ((e as? retrofit2.HttpException)?.code() == 403 ||
                                !container.gateway.setInLibrary(listOf(uri), keep = nowLiked)
                            ) throw e
                        }
                    }
                }
                callResult.onSuccess {
                    trackCache.keys.removeAll { it.endsWith(":collection") || it == MvYouTubeBackend.LIKED_MUSIC_URI }
                    android.util.Log.i(TAG, "toggleLike remote sync OK for $id (nowLiked=$nowLiked)")
                }.onFailure { ex ->
                    android.util.Log.w(TAG, "toggleLike remote sync failed for $id: ${ex.message}", ex)
                    if (container.likedStore.isLiked(uri) == nowLiked) container.likedStore.toggle(uri)
                    session.connectedControllers.forEach { ctrl ->
                        session.setMediaButtonPreferences(ctrl, layoutFor(session, ctrl))
                    }
                    if (ex is CancellationException) throw ex
                }

                if (callResult.isSuccess && container.downloadSettings.downloadLikedSongs.value) {
                    if (nowLiked) {
                        val meta = item.mediaMetadata
                        val track = container.downloads.trackOf(uri) ?: CatalogTrack(
                            uri = uri,
                            name = meta.title?.toString().orEmpty(),
                            artist = meta.artist?.toString().orEmpty(),
                            album = meta.albumTitle?.toString().orEmpty(),
                            durationMs = meta.durationMs ?: 0L,
                            artworkUrl = meta.artworkUri?.toString(),
                        )
                        container.downloads.addLiked(track)
                        MvDownloadService.start(container)
                    } else {
                        container.downloads.removeLiked(uri)
                        container.downloads.pruneOrphans().forEach { orphanUri ->
                            runCatching { MvYouTubeDownloads.forget(container, orphanUri) }
                            MvDownloadExtras.forget(orphanUri)
                        }
                    }
                }
                SessionResult(if (callResult.isSuccess) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_IO)
            }
        }
    }

    /**
     * A station from what is playing, without a screen to ask anything of.
     *
     * The same list the player's own radio button builds: the service assembles it
     * and this asks for it. If it will not, the queue is left alone rather than
     * replaced with something nobody chose.
     */
    private fun radioSeed(uri: String?): Boolean = when {
        uri == null || OfflineMode.active.value -> false
        uri.startsWith("spotify:track:") ->
            container.spotifySignedIn && !container.preferences.spotifyFreeAccount.value && NativeBridge.isConnected
        else -> container.youtubeBackend.isReady &&
            (uri.startsWith(MvYouTubeBackend.TRACK_PREFIX) || uri.startsWith(MvYouTubeBackend.VIDEO_PREFIX))
    }

    private suspend fun radioTracks(seed: String): List<CatalogTrack> =
        if (seed.startsWith("spotify:track:")) {
            val station = "spotify:station:track:${seed.substringAfterLast(':')}"
            Catalog.tracks(Catalog.contextTrackUris(station).take(TRACK_LIMIT))
        } else {
            container.youtubeBackend.radio(seed).take(TRACK_LIMIT)
        }

    private fun startRadio(session: MediaSession, controller: MediaSession.ControllerInfo) {
        val player = session.player
        val uri = player.currentMediaItem?.mediaId ?: return
        if (!radioSeed(uri)) return

        scope.launch {
            val account = refreshBrowseAccount()
            val tracks = try {
                container.hiddenContent.state.value.filter(radioTracks(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no station for $uri", e)
                return@launch
            }
            if (tracks.isEmpty() || player.currentMediaItem?.mediaId != uri ||
                offlineBrowse(controller) || !radioSeed(uri) || refreshBrowseAccount() != account
            ) return@launch
            val parent = "${AutoBrowseNodes.STATION_PREFIX}$uri"
            nodeLabels[parent] = strings.getString(R.string.radio)
            player.setMediaItems(tracks.map { queueItem(it, parent) })
            player.prepare()
            player.play()
        }
    }

    private fun shelf(id: String): MediaItem? {
        val (title, type) = when (id) {
            AutoBrowseNodes.ROOT -> R.string.app_name to MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            AutoBrowseNodes.LIBRARY -> R.string.auto_library to MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            AutoBrowseNodes.PLAYLISTS -> R.string.playlists to MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            AutoBrowseNodes.RADIO -> R.string.radio to MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            AutoBrowseNodes.RECENT -> R.string.play_again to MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            AutoBrowseNodes.DOWNLOADS -> R.string.auto_downloads to MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            else -> return null
        }
        return browsable(id, strings.getString(title), type)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val offlineChanged = if (params?.isOffline == true) offlineBrowsers.add(browser)
        else offlineBrowsers.remove(browser)
        if (offlineChanged) session.setMediaButtonPreferences(browser, layoutFor(session, browser))
        val offline = offlineBrowse(browser)
        val rootParams = LibraryParams.Builder()
            .setRecent(params?.isRecent ?: false)
            .setOffline(offline)
            .setSuggested(params?.isSuggested ?: false)
            .setExtras(Bundle(params?.extras ?: Bundle.EMPTY).apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", !offline)
            })
            .build()
        return Futures.immediateFuture(
            LibraryResult.ofItem(checkNotNull(shelf(AutoBrowseNodes.ROOT)), rootParams),
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        refreshBrowseAccount()
        val offline = offlineBrowse(browser)
        val parts = AutoBrowseNodes.splitTrackId(mediaId)
        val item = if (parts == null) {
            if (offline && !AutoBrowseNodes.isOfflineNode(mediaId)) null
            else shelf(mediaId) ?: browseFolders[mediaId]
        } else {
            val (parent, uri) = parts
            tracksOf(parent, offline).firstOrNull { it.uri == uri }?.let { playable(parent, it) }
        }
        item?.let { LibraryResult.ofItem(it, null) }
            ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        try {
            if (params?.isOffline == true) offlineBrowsers.add(browser)
            val children = childrenOf(parentId, offlineBrowse(browser))
            LibraryResult.ofItemList(ImmutableList.copyOf(AutoBrowseNodes.page(children, page, pageSize)), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "children of $parentId failed", e)
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
        }
    }

    private val browseFolders = mutableMapOf<String, MediaItem>()

    private fun browseFolder(id: String, title: String, artwork: String?, type: Int): MediaItem {
        nodeLabels[id] = title
        return browsable(id, title, type, artwork?.let(Uri::parse)).also { browseFolders[id] = it }
    }

    private suspend fun childrenOf(parentId: String, offline: Boolean): List<MediaItem> {
        refreshBrowseAccount()
        if (offline && !AutoBrowseNodes.isOfflineNode(parentId)) return emptyList()
        return when (parentId) {
            AutoBrowseNodes.ROOT, AutoBrowseNodes.LIBRARY ->
                AutoBrowseNodes.children(parentId, offline).mapNotNull(::shelf)

            AutoBrowseNodes.PLAYLISTS -> lock.withLock {
                val backend = container.activeBackend
                if (backend.id == BackendId.SPOTIFY) {
                    if (!container.spotifySignedIn) return@withLock emptyList()
                    waitForSpotify()
                }
                val account = refreshBrowseAccount()
                val playlists = try {
                    backend.playlists()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    check(refreshBrowseAccount() == account)
                    playlistCache[backend.id] ?: throw e
                }
                check(refreshBrowseAccount() == account)
                playlistCache[backend.id] = playlists
                playlists.map { browseFolder(it.uri, it.name, it.artworkUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            }

            AutoBrowseNodes.RADIO -> container.recentStore.tracks.value
                .filter { radioSeed(it.uri) }
                .take(RADIO_LIMIT)
                .map {
                    browseFolder(
                        "${AutoBrowseNodes.STATION_PREFIX}${it.uri}", it.name, it.artworkUrl,
                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    )
                }

            else -> tracksOf(parentId, offline).map { playable(parentId, it) }
        }
    }

    private suspend fun tracksOf(parentId: String, offline: Boolean = false): List<CatalogTrack> {
        if (offline && !AutoBrowseNodes.isOfflineNode(parentId)) return emptyList()
        when (parentId) {
            AutoBrowseNodes.RECENT -> {
                val recent = container.recentStore.tracks.value
                if (!offline) return recent
                return downloadedTracks(recent)
            }
            AutoBrowseNodes.DOWNLOADS -> {
                container.downloads.ensureLoaded()
                val tracks = container.downloads.files.value.keys.mapNotNull(container.downloads::trackOf)
                return if (offline) downloadedTracks(tracks) else tracks
            }
        }
        if (parentId.startsWith(AutoBrowseNodes.SEARCH_PREFIX)) {
            val prefix = "${AutoBrowseNodes.SEARCH_PREFIX}${container.activeBackend.id}/"
            if (!parentId.startsWith(prefix)) return emptyList()
            return search(parentId.removePrefix(prefix)).second.tracks
        }
        val seed = parentId.removePrefix(AutoBrowseNodes.STATION_PREFIX)
        val isStation = seed != parentId && radioSeed(seed)
        val backend = when {
            parentId.startsWith("spotify:") -> container.spotifyBackend
            container.youtubeBackend.owns(parentId) -> container.youtubeBackend
            else -> null
        }
        if (!isStation && backend == null) return emptyList()

        return lock.withLock {
            refreshBrowseAccount()
            if (isStation && !radioSeed(seed)) return@withLock emptyList()
            trackCache[parentId]?.let { return@withLock it }
            if (backend?.id == BackendId.SPOTIFY && container.spotifySignedIn) waitForSpotify()
            val account = refreshBrowseAccount()
            val tracks = when {
                isStation -> radioTracks(seed)
                parentId.startsWith("spotify:playlist:") ->
                    container.spotifyBackend.browsePlaylistTracks(parentId, TRACK_LIMIT)
                else -> backend?.tracksOf(parentId).orEmpty()
            }.take(TRACK_LIMIT)
            check(refreshBrowseAccount() == account)
            if (tracks.isNotEmpty()) trackCache[parentId] = tracks
            tracks
        }
    }

    private suspend fun downloadedTracks(tracks: List<CatalogTrack>): List<CatalogTrack> =
        withContext(Dispatchers.IO) {
            container.downloads.ensureLoaded()
            tracks.filter { container.downloads.hasDownloadedAudio(it.uri) }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        try {
            val items = if (offlineBrowse(browser, params)) emptyList() else searchItems(query)
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "search failed", e)
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        try {
            val items = if (offlineBrowse(browser, params)) emptyList() else searchItems(query)
            LibraryResult.ofItemList(ImmutableList.copyOf(AutoBrowseNodes.page(items, page, pageSize)), params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "search results failed", e)
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
        }
    }

    private suspend fun search(query: String): Pair<String, SearchResults> {
        val backend = container.activeBackend
        val node = "${AutoBrowseNodes.SEARCH_PREFIX}${backend.id}/${query.trim()}"
        if (query.isBlank()) return node to SearchResults()
        return lock.withLock {
            val account = refreshBrowseAccount()
            lastSearch?.takeIf { it.first == node }?.let { return@withLock it }
            val results = backend.search(
                query.trim(),
                SearchLabels(
                    artist = strings.getString(R.string.artist),
                    album = strings.getString(R.string.album),
                    playlist = strings.getString(R.string.playlist),
                    podcast = strings.getString(R.string.podcast),
                ),
            )
            check(refreshBrowseAccount() == account)
            check(!results.isEmpty || !backend.searchNeedsSetup)
            nodeLabels[node] = strings.getString(R.string.search)
            (node to results).also { lastSearch = it }
        }
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        val (node, results) = search(query)
        return buildList {
            results.tracks.forEach { add(playable(node, it)) }
            results.albums.forEach {
                add(browseFolder(it.uri, it.title, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM))
            }
            results.artists.forEach {
                add(browseFolder(it.uri, it.title, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ARTIST))
            }
            results.playlists.forEach {
                add(browseFolder(it.uri, it.title, it.artworkUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST))
            }
        }
    }

    private suspend fun voiceQueue(item: MediaItem): MediaSession.MediaItemsWithStartPosition {
        val extras = item.requestMetadata.extras
        val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST).orEmpty()
        val (name, type) = when (focus) {
            "vnd.android.cursor.item/artist" -> artist to MediaMetadata.MEDIA_TYPE_ARTIST
            "vnd.android.cursor.item/album" ->
                extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM).orEmpty() to MediaMetadata.MEDIA_TYPE_ALBUM
            "vnd.android.cursor.item/playlist" ->
                extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST).orEmpty() to MediaMetadata.MEDIA_TYPE_PLAYLIST
            else -> extras?.getString(MediaStore.EXTRA_MEDIA_TITLE).orEmpty() to MediaMetadata.MEDIA_TYPE_MUSIC
        }
        val title = name.ifBlank { item.requestMetadata.searchQuery.orEmpty().trim() }
        val query = if (name.isNotBlank() && artist.isNotBlank() && type != MediaMetadata.MEDIA_TYPE_ARTIST) {
            "$title $artist"
        } else {
            title
        }
        val (node, results) = search(query)
        val trackIndex = results.tracks.indexOfFirst {
            it.name.equals(title, ignoreCase = true) &&
                (artist.isBlank() || it.artist.contains(artist, ignoreCase = true))
        }
        val folders = when (type) {
            MediaMetadata.MEDIA_TYPE_ARTIST -> results.artists
            MediaMetadata.MEDIA_TYPE_ALBUM -> results.albums
            MediaMetadata.MEDIA_TYPE_PLAYLIST -> results.playlists
            MediaMetadata.MEDIA_TYPE_MUSIC -> if (focus == null && trackIndex < 0) {
                (results.artists + results.albums + results.playlists)
                    .filter { it.title.equals(title, ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
        val folder = folders.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: folders.firstOrNull()
        if (folder != null) {
            val tracks = container.hiddenContent.state.value.filter(tracksOf(folder.uri))
            if (tracks.isNotEmpty()) {
                nodeLabels[folder.uri] = folder.title
                return MediaSession.MediaItemsWithStartPosition(tracks.map { queueItem(it, folder.uri) }, 0, 0L)
            }
        }
        val requested = results.tracks.getOrNull(trackIndex)?.uri
        val tracks = container.hiddenContent.state.value.filter(results.tracks, keep = requested)
        check(tracks.isNotEmpty()) { "No tracks found for voice search" }
        return MediaSession.MediaItemsWithStartPosition(
            tracks.map { queueItem(it, node) },
            tracks.indexOfFirst { it.uri == requested }.coerceAtLeast(0),
            0L,
        )
    }
    // --- Playback -----------------------------------------------------------

    /**
     * Expands the single item a browser plays back into its whole list.
     *
     * Required, not optional, for a second reason too: a session never hands a
     * controller's items to the player directly, and the default implementation
     * rejects every item that carries no playback URI. Ours carry a Spotify URI
     * as their media id and nothing else, so without this the `setMediaItems`
     * call is dropped silently and only the following `play()` reaches the
     * engine — "Player::play called from invalid state: Stopped".
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val offline = offlineBrowse(controller)
        val single = mediaItems.singleOrNull()
        if (single != null && single.requestMetadata.searchQuery != null && single.mediaId.isEmpty()) {
            // TODO Verify Assistant and legacy custom actions on a paired Android Auto host.
            check(trustedController(mediaSession, controller)) { "Untrusted voice search controller" }
            check(!offline) { "Voice search requires online browsing" }
            return@future voiceQueue(single)
        }

        val parts = single?.mediaId?.let(AutoBrowseNodes::splitTrackId)
        if (offline && parts == null) checkOfflineItems(controller, mediaItems)

        ensurePlayerFor(
            mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
                ?: mediaItems.firstOrNull()?.mediaId,
        )

        if (parts == null) return@future MediaSession.MediaItemsWithStartPosition(
                mediaItems.map(::stripBrowseId).toMutableList(),
                startIndex,
                startPositionMs,
            )

        val (parentId, trackUri) = parts
        check(trustedController(mediaSession, controller)) { "Untrusted browse controller" }
        val tracks = container.hiddenContent.state.value.filter(tracksOf(parentId, offline), keep = trackUri)
        val index = tracks.indexOfFirst { it.uri == trackUri }
        if (index < 0) {
            check(!offline) { "Browse track is unavailable offline" }
            return@future MediaSession.MediaItemsWithStartPosition(
                mediaItems.map(::stripBrowseId).toMutableList(),
                startIndex,
                startPositionMs,
            )
        }

        MediaSession.MediaItemsWithStartPosition(
            tracks.map { queueItem(it, parentId) }.toMutableList(),
            index,
            startPositionMs,
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        scope.future {
            if (offlineBrowse(controller)) checkOfflineItems(controller, mediaItems)
            mediaItems.map(::stripBrowseId).toMutableList()
        }

    private suspend fun checkOfflineItems(controller: MediaSession.ControllerInfo, items: List<MediaItem>) {
        val explicitOffline = controller in offlineBrowsers
        val uris = items.mapNotNull { item ->
            AutoBrowseNodes.splitTrackId(item.mediaId)?.second ?: item.mediaId.takeIf { explicitOffline }
        }
        if (uris.isEmpty()) return
        withContext(Dispatchers.IO) {
            container.downloads.ensureLoaded()
            check(uris.all(container.downloads::hasDownloadedAudio)) { "Browse track is unavailable offline" }
        }
    }

    /** Turns a browse id back into the plain Spotify URI [PlayQueue] insists on. */
    private fun stripBrowseId(item: MediaItem): MediaItem {
        val uri = AutoBrowseNodes.splitTrackId(item.mediaId)?.second ?: return item
        return item.buildUpon().setMediaId(uri).build()
    }

    private fun queueItem(track: CatalogTrack, parentId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.uri)
            .setMediaMetadata(
                metadata(track)
                    .setExtras(
                        Bundle().apply {
                            // Only a real context can be handed to the engine as
                            // one; a search result list is a set of tracks that
                            // happen to be together.
                            if (parentId.startsWith("spotify:")) {
                                putString(EXTRA_CONTEXT_URI, parentId)
                                putBoolean(EXTRA_CONTEXT_ORDERED, true)
                            } else {
                                putBoolean(EXTRA_CONTEXT_ORDERED, false)
                            }
                            nodeLabels[parentId]?.let { putString(EXTRA_CONTEXT_LABEL, it) }
                        },
                    )
                    .build(),
            )
            .build()

    // --- Items --------------------------------------------------------------

    private fun browsable(
        id: String,
        title: String,
        mediaType: Int,
        artworkUri: Uri? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .setArtworkUri(artworkUri)
                .build(),
        )
        .build()

    private fun playable(parentId: String, track: CatalogTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(AutoBrowseNodes.trackId(parentId, track.uri))
            .setMediaMetadata(metadata(track).build())
            .build()

    private fun metadata(track: CatalogTrack): MediaMetadata.Builder =
        MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setDurationMs(track.durationMs)
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

    /**
     * Waits for the native session, rather than answering with an empty shelf.
     *
     * The car binds the service cold — the app may not have been opened since
     * the phone booted — and the access-point handshake takes a moment. An empty
     * list at that point is indistinguishable from an empty account, and Auto
     * caches what it is told.
     */
    private suspend fun waitForSpotify() {
        repeat(CONNECT_TRIES) {
            if (container.spotifyBackend.isReady) return
            delay(CONNECT_POLL_MS)
        }
    }

    private companion object {
        private val SYSTEM_MEDIA_PACKAGES = setOf(
            "android", "com.android.systemui", "com.android.bluetooth",
            "com.google.android.bluetooth", "com.google.android.googlequicksearchbox",
        )
        private const val SYSTEM_APP_FLAGS = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        const val TAG = "MediaBrowseTree"

        /** The two modes the car cannot reach through the standard transport. */
        const val CMD_SHUFFLE = "app.sonar.SHUFFLE"
        const val CMD_REPEAT = "app.sonar.REPEAT"
        const val CMD_RADIO = "app.sonar.RADIO"
        const val CMD_LIKE = "app.sonar.LIKE"

        const val TRACK_LIMIT = 100
        const val RADIO_LIMIT = 12

        const val CONNECT_TRIES = 40
        const val CONNECT_POLL_MS = 250L
    }
}
