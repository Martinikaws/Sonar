package app.sonar.backend.youtube

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.sonar.backend.PlaybackHost
import app.sonar.audiosource.SpotifyTrackKey
import app.sonar.audiosource.YtAudioForSpotify
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Builds the ExoPlayer the YouTube Music backend plays through.
 *
 * A real ExoPlayer, unlike the Spotify side: there is no engine to hand audio
 * to, the tracks are ordinary HTTPS files, and the queue, gapless transitions
 * and buffering are all things ExoPlayer already does properly.
 */
@UnstableApi
object YouTubePlayerFactory {
    internal data class PlayingAudio(val mediaId: String, val codec: String, val kbps: Int)

    private val _playingAudio = kotlinx.coroutines.flow.MutableStateFlow<PlayingAudio?>(null)
    internal val playingAudio: kotlinx.coroutines.flow.StateFlow<PlayingAudio?> = _playingAudio

    /**
     * The sound of a Spotify track from its YouTube Music match: the item keeps
     * its Spotify id and metadata, and only its URI is this plus the video id.
     * Always audio alone, never retitled; see YtAudioForSpotify.
     */
    const val SPOTIFY_AUDIO_PREFIX = "sonar-ytaudio:"

    internal fun spotifyTrackKey(item: androidx.media3.common.MediaItem): SpotifyTrackKey? {
        if (!item.mediaId.startsWith("spotify:track:")) return null
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val duration = metadata.durationMs?.takeIf { it > 0 } ?: return null
        val artists = metadata.extras?.getStringArrayList(app.sonar.ui.EXTRA_ARTIST_NAMES)
            ?.takeIf { it.isNotEmpty() } ?: metadata.artist?.toString().orEmpty().split(", ")
        if (artists.none { it.isNotBlank() }) return null
        return SpotifyTrackKey(
            id = item.mediaId.substringAfterLast(':'),
            title = title,
            artists = artists,
            durationMs = duration,
        )
    }

    internal suspend fun catalogSpotifyTrackKey(uri: String): SpotifyTrackKey? {
        val track = try {
            app.sonar.data.Catalog.tracks(listOf(uri)).firstOrNull()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SonarYT", "Spotify metadata unavailable for source selection", error)
            null
        } ?: return null
        return SpotifyTrackKey(
            id = uri.substringAfterLast(':'),
            title = track.name,
            artists = track.artists.map { it.name }.ifEmpty { track.artist.split(", ") },
            durationMs = track.durationMs,
        )
    }

    fun create(
        host: PlaybackHost,
        spotifyMatcher: YtAudioForSpotify? = null,
        spotifyFallback: Boolean = false,
        spotifyUnavailable: Boolean = false,
    ): Player {
        fun loadControl() = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val crossfadeStore = (host.context.applicationContext as? app.sonar.SonarApplication)?.crossfade

        // One speaker for both players; see PcmMixer.
        val mixer = PcmMixer()
        val mainSlot = MixerSlot()
        val tailSlot = MixerSlot(follows = mainSlot, initialFade = 0f).apply { held = true }

        val resolver = YouTubeStreamResolver(host.context.applicationContext, spotifyMatcher)
        val dataSources = ResolvingDataSource.Factory(
            // Files as well as the network: a kept song resolves to one on the
            // phone, which an HTTP source cannot open.
            ChunkedDataSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(
                    host.context,
                    DefaultHttpDataSource.Factory(),
                ),
            ),
            resolver,
        )

        fun buildPlayer(handleFocus: Boolean, slot: MixerSlot) =
            ExoPlayer.Builder(host.context, renderers(host, MixerOutputProvider(host.context, mixer, slot)))
            .setMediaSourceFactory(
                VideoAwareSourceFactory(
                    DefaultMediaSourceFactory(dataSources), dataSources, spotifyMatcher != null || spotifyUnavailable,
                ),
            )
            .setLoadControl(loadControl())
            .setHandleAudioBecomingNoisy(true)
            // Ducks and pauses for whatever else wants the speaker. The Spotify
            // path gets this from its own output; ExoPlayer will not ask for focus
            // at all unless it is told to handle it.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ handleFocus,
            )
            .setLooper(host.looper)
            .build()

        return buildPlayer(true, mainSlot).let { exo ->
                exo.addAnalyticsListener(Diagnostics)
                // A song YouTube plays only to an account, with none signed in:
                // said, and passed over, rather than left at --:-- with no word
                // of why. See SignedInStreams.
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // A signed-in address that failed is not handed out again.
                        SignedInStreams.forget()
                        if (spotifyFallback || spotifyUnavailable) return
                        if (spotifyMatcher != null) {
                            val failed = exo.currentMediaItem
                            android.os.Handler(host.looper).post {
                                if (exo.currentMediaItem != failed || exo.playerError !== error ||
                                    !exo.playWhenReady || exo.repeatMode != Player.REPEAT_MODE_OFF ||
                                    !exo.hasNextMediaItem()
                                ) return@post
                                exo.seekToNextMediaItem()
                                exo.prepare()
                            }
                            return
                        }
                        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                        val message = when {
                            causes.any { it is SignedInStreams.SignInRequired } ->
                                app.sonar.R.string.youtube_sign_in_required
                            causes.any { it is SignedInStreams.Refused } ->
                                app.sonar.R.string.youtube_signed_in_unplayable
                            else -> {
                                recover(exo, error)
                                return
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                host.context,
                                host.context.getString(message),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                        if (exo.hasNextMediaItem()) {
                            exo.seekToNextMediaItem()
                            exo.prepare()
                            exo.play()
                        }
                    }
                })
                // Video for a video, sound for a song, as the queue moves.
                exo.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (!events.containsAny(
                                Player.EVENT_TRACKS_CHANGED,
                                Player.EVENT_MEDIA_ITEM_TRANSITION,
                                Player.EVENT_PLAYBACK_STATE_CHANGED,
                                Player.EVENT_PLAYER_ERROR,
                            )
                        ) return
                        if (spotifyMatcher != null || spotifyFallback || spotifyUnavailable) return
                        _playingAudio.value = resolver.playingAudio(player)
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        YouTubeVideoMode.onItem(mediaItem?.mediaId)
                        // A video whose song is already known says so at once.
                        mediaItem?.mediaId?.takeIf { it.startsWith(YouTubeBackend.TRACK_PREFIX) }
                            ?.let(YouTubeBackend::videoIdOfUri)
                            ?.takeIf(SongCounterparts::wanted)
                            ?.let { video -> SongCounterparts.known(video)?.let { retitle(exo, video, it) } }
                        // The next one asked for now, while this one plays.
                        val next = exo.nextMediaItemIndex
                        if (next >= 0 && (host.context.applicationContext as? app.sonar.SonarApplication)
                                ?.powerMode?.conserving?.value != true
                        ) {
                            exo.getMediaItemAt(next).mediaId
                                .takeIf { it.startsWith(YouTubeBackend.URI_SCHEME) }
                                ?.let(YouTubeStreams::prefetch)
                        }
                    }
                })
                val fades = YouTubeFadeController(exo, crossfadeStore, mixer, mainSlot, tailSlot) {
                    buildPlayer(false, tailSlot)
                }
                val preferences = (host.context.applicationContext as? app.sonar.SonarApplication)
                    ?.preferences
                // Each song found for a video in the queue renames its entry, so
                // the player shows what it plays; see SongCounterparts.
                if (spotifyMatcher == null && !spotifyFallback && !spotifyUnavailable) {
                    CoroutineScope(SupervisorJob() + android.os.Handler(host.looper).asCoroutineDispatcher()).launch {
                        SongCounterparts.resolved.collect { (video, song) ->
                            if (SongCounterparts.wanted(video)) retitle(exo, video, song)
                        }
                    }
                }
                // The talking and the sketches around a music video; see SponsorBlock.
                SponsorBlock(exo) { preferences?.sponsorBlock?.value ?: true }
                // Every change of song the listener asks for goes through the
                // fade; see SkipFadePlayer.
                mixer.skipFadeMs = { preferences?.skipFadeMs() ?: 0 }
                SkipFadePlayer(exo, fades) { preferences?.skipFadeMs() ?: 0 }
            }
    }

    /**
     * Shows [song] in place of [video] wherever the queue has it. Same stream
     * address, so nothing reloads: only the name, the picture and the id the
     * rest of the app reads (lyrics, credits, likes) change.
     */
    /** The song last picked up again after an error, and when, and how often. */
    private var recovering: Triple<String, Long, Int>? = null

    /**
     * Picks the song up again where it stopped, after an error that was not
     * about the account.
     *
     * Everything else used to be left as it was: a stream address that had
     * expired, a 403 halfway through, a connection that dropped for a moment,
     * and the player sat stopped in its error with the song half played until
     * the listener came back and chose another. Asked again, with the addresses
     * it had forgotten, almost every one of these plays on. One that fails
     * [RETRIES] times in a row within [RETRY_WINDOW_MS] is passed over, as a
     * song that cannot be played would be.
     */
    private fun recover(
        exo: ExoPlayer,
        error: androidx.media3.common.PlaybackException,
    ) {
        val item = exo.currentMediaItem ?: return
        val id = item.mediaId
        if (!exo.playWhenReady) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = recovering?.takeIf { it.first == id && now - it.second < RETRY_WINDOW_MS }
        val attempt = (last?.third ?: 0) + 1
        recovering = Triple(id, now, attempt)
        YouTubeStreams.forget(YouTubeBackend.videoIdOfUri(id))
        val at = exo.currentPosition
        val index = exo.currentMediaItemIndex
        android.util.Log.w("SonarYT", "playback failed (${error.errorCodeName}), try $attempt at ${at}ms")
        android.os.Handler(exo.applicationLooper).postDelayed({
            // Moved on in the meantime: nothing of this to pick up.
            if (exo.currentMediaItem?.mediaId != id || exo.playerError == null) return@postDelayed
            if (attempt > RETRIES) {
                if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() else return@postDelayed
            } else {
                exo.seekTo(index, at)
            }
            exo.prepare()
            exo.play()
        }, RETRY_DELAY_MS * attempt)
    }

    private const val RETRIES = 3
    private const val RETRY_DELAY_MS = 1_500L
    private const val RETRY_WINDOW_MS = 60_000L

    private fun retitle(player: ExoPlayer, video: String, song: com.metrolist.innertube.models.SongItem) {
        val listed = "${YouTubeBackend.TRACK_PREFIX}$video"
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            if (item.mediaId != listed) continue
            val metadata = item.mediaMetadata.buildUpon()
                .setTitle(song.title)
                .setArtist(song.artists.joinToString(", ") { it.name })
                .setAlbumTitle(song.album?.name ?: item.mediaMetadata.albumTitle)
                .setArtworkUri(android.net.Uri.parse(song.thumbnail.atSize(COVER_SIZE)))
                .build()
            player.replaceMediaItem(
                index,
                item.buildUpon()
                    .setMediaId("${YouTubeBackend.TRACK_PREFIX}${song.id}")
                    .setMediaMetadata(metadata)
                    .build(),
            )
        }
    }

    /** Temporary: chasing a stall where the position stops with the sink idle. */
    private object Diagnostics : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            android.util.Log.w(TAG, "underrun size=$bufferSize sinceFeed=${elapsedSinceLastFeedMs}ms")
        }

        override fun onPlaybackStateChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            state: Int,
        ) {
            android.util.Log.w(TAG, "state=$state pos=${eventTime.currentPlaybackPositionMs}")
        }

        override fun onPlayWhenReadyChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            playWhenReady: Boolean,
            reason: Int,
        ) {
            android.util.Log.w(TAG, "playWhenReady=$playWhenReady reason=$reason")
        }

        override fun onPlaybackParametersChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            parameters: androidx.media3.common.PlaybackParameters,
        ) {
            android.util.Log.w(TAG, "params speed=${parameters.speed} pitch=${parameters.pitch}")
            // Saved here as on the Spotify path (see LibrespotPlayer): never
            // saved for a YouTube song, the effects button went on reading the
            // speed of the last Spotify one, lit after "Original".
            app.sonar.playback.AudioEffects.rememberSpeedAndPitch(parameters.speed, parameters.pitch)
        }

        override fun onLoadError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            android.util.Log.w(TAG, "load error ${loadEventInfo.uri.host}: ${error.message}")
        }

        override fun onAudioSinkError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            audioSinkError: Exception,
        ) {
            android.util.Log.e(TAG, "sink error", audioSinkError)
        }

        private const val TAG = "SonarYT"
    }

    /**
     * Renderers whose audio sink resamples in software.
     *
     * ExoPlayer would otherwise hand a non-unity speed straight to `AudioTrack`,
     * and on this hardware that track stops consuming: the session stays
     * PLAYING and its buffer keeps filling while the position stands still, so
     * the sound goes and the progress bar jumps back to where it stalled.
     * Sonic — the same resampler Media3 uses when the platform cannot oblige —
     * has no such trouble, and it is what the Spotify path already effectively
     * does through its own output.
     */
    private fun renderers(
        host: PlaybackHost,
        output: androidx.media3.exoplayer.audio.AudioOutputProvider,
    ) =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(host.context) {
            // Video stays on the phone's hardware decoders: FFmpeg's are
            // software, and a 1080p picture through them falls behind and stalls.
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) = super.buildVideoRenderers(
                context,
                EXTENSION_RENDERER_MODE_OFF,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out,
            )

            init {
                // FFmpeg ahead of the phone's own decoders. Releasing a platform
                // audio codec while another song is starting aborts the whole
                // app on this hardware, inside MediaCodec's metrics
                // (`ubsan: sub-overflow` in updateMediametrics); a decoder of the
                // app's own has no such code to fall into. The platform ones stay
                // behind it for whatever FFmpeg cannot decode.
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            }

            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioOutputProvider(output)
                // Karaoke, ahead of Sonic. The default chain was all this sink
                // had, so the dial moved and the voice stayed: the removal is a
                // processor of this app's, and a chain that does not list it
                // does not run it. Before the stretcher, because it works on
                // what the two channels share and a stretched pair shares less.
                //
                // Speed and pitch by the app's own vocoder, as on Spotify and
                // for the phone's own files. The default chain left them to
                // Sonic, which was built for speech: on music it smears the
                // attacks and adds a metallic edge, and the same effect sounded
                // plainly worse on a YouTube song. See BungeeAudioProcessor.
                .setAudioProcessorChain(
                    // Then every stream at the mixer's rate, so a 44.1 kHz song
                    // and a 48 kHz one can be summed; see MixerRateProcessor.
                    app.sonar.playback.BungeeProcessorChain(after = arrayOf(MixerRateProcessor())),
                )
                .build()
        }
}

/**
 * Turns a `ytmusic:track:` URI into the URL the audio really lives at.
 *
 * Resolved at load time rather than when the queue is built, and this is the
 * whole reason for the indirection: a YouTube stream URL is signed and expires
 * within hours, so a queue holding resolved URLs would be a queue that stops
 * working while it sits there. The URI keeps its meaning; only the resolution
 * is perishable.
 *
 * Runs on ExoPlayer's loading thread, so blocking here is correct.
 */
@UnstableApi
private class YouTubeStreamResolver(
    private val context: android.content.Context,
    private val spotifyMatcher: YtAudioForSpotify?,
) : ResolvingDataSource.Resolver {
    private val audioRates = android.util.LruCache<String, Int>(64)

    fun playingAudio(player: Player): YouTubePlayerFactory.PlayingAudio? {
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) return null
        val item = player.currentMediaItem ?: return null
        if (!item.mediaId.startsWith(YouTubeBackend.URI_SCHEME)) return null
        for (group in player.currentTracks.groups) {
            if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                val format = group.getTrackFormat(index)
                val codec = when (format.sampleMimeType) {
                    "audio/opus" -> "Opus"
                    "audio/mp4a-latm" -> "AAC"
                    "audio/vorbis" -> "Vorbis"
                    else -> return null
                }
                val uri = item.localConfiguration?.uri?.toString()?.substringBefore('#')
                val kbps = format.averageBitrate.takeIf { it >= 1000 }?.div(1000)
                    ?: uri?.let(audioRates::get) ?: return null
                return YouTubePlayerFactory.PlayingAudio(item.mediaId, codec, kbps)
            }
        }
        return null
    }

    private fun DataSpec.withAudio(stream: SignedInStreams.Stream): DataSpec {
        val key = uri.toString().substringBefore('#')
        val kbps = stream.kbps
        if (kbps != null) audioRates.put(key, kbps) else audioRates.remove(key)
        return withUri(android.net.Uri.parse(stream.url))
    }

    /**
     * Asked without an account, and again as the account where YouTube will
     * only play the song to one; see SignedInStreams.
     */
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec =
        resolveStream(resolveSpotifyMatch(dataSpec))

    private fun resolveSpotifyMatch(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != SPOTIFY_MATCH_SCHEME) return dataSpec
        val matcher = spotifyMatcher ?: throw java.io.IOException("No enabled audio source for this track")
        val id = uri.lastPathSegment ?: throw java.io.IOException("YouTube Music matching requires a Spotify track")
        val key = SpotifyTrackKey(
            id = id,
            title = uri.getQueryParameter("title").orEmpty(),
            artists = uri.getQueryParameters("artist"),
            durationMs = uri.getQueryParameter("duration")?.toLongOrNull() ?: 0L,
        )
        // ResolvingDataSource calls this synchronously on the loading thread.
        val match = try {
            kotlinx.coroutines.runBlocking {
                val completeKey = if (key.durationMs > 0) key else
                    YouTubePlayerFactory.catalogSpotifyTrackKey("spotify:track:$id")
                completeKey?.let { matcher.findAudio(it) }
            }
        } catch (error: Exception) {
            throw java.io.IOException("YouTube Music match lookup failed", error)
        } ?: throw java.io.IOException("No YouTube Music match for ${key.id}")
        return dataSpec.withUri(android.net.Uri.parse("${YouTubePlayerFactory.SPOTIFY_AUDIO_PREFIX}${match.videoId}"))
    }

    private fun resolveStream(dataSpec: DataSpec): DataSpec = try {
        resolveAnonymously(dataSpec)
    } catch (error: Exception) {
        if (!SignedInStreams.needsAccount(error)) throw error
        val base = dataSpec.uri.toString().substringBefore('#')
        val kind = when (dataSpec.uri.fragment) {
            PART_VIDEO -> SignedInStreams.Kind.VIDEO
            PART_AUDIO -> SignedInStreams.Kind.AUDIO
            else -> if (base.startsWith(YouTubePlayerFactory.SPOTIFY_AUDIO_PREFIX)) SignedInStreams.Kind.AUDIO else if (YouTubeVideoMode.wantsVideo(base)) SignedInStreams.Kind.MUXED else SignedInStreams.Kind.AUDIO
        }
        val id = YouTubeBackend.videoIdOfUri(base)
        val stream = try {
            val chosen = quality
            SignedInStreams.stream(
                context, id, kind, wantsLowRate(chosen),
                bestAvailable = chosen == app.sonar.data.PreferencesStore.YouTubeQuality.HIGH,
            )
        } catch (refused: SignedInStreams.SignInRequired) {
            throw refused
        } catch (refused: Exception) {
            android.util.Log.w("SonarYTSignedIn", "$id refused: ${refused.message}")
            throw SignedInStreams.Refused(id, refused)
        }
        if (kind == SignedInStreams.Kind.VIDEO) dataSpec.withUri(android.net.Uri.parse(stream.url))
        else dataSpec.withAudio(stream)
    }

    private fun resolveAnonymously(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        if (uri.startsWith(YouTubePlayerFactory.SPOTIFY_AUDIO_PREFIX)) {
            val id = YouTubeBackend.videoIdOfUri(uri)
            app.sonar.download.YouTubeDownloads.fileFor(context, "${YouTubeBackend.TRACK_PREFIX}$id")
                ?.let { return dataSpec.withUri(android.net.Uri.fromFile(it)) }
            YouTubeStreams.ensureNewPipe()
            return dataSpec.withAudio(bestAudio(YouTubeStreams.info(id), id))
        }
        if (!uri.startsWith(YouTubeBackend.TRACK_PREFIX) && !uri.startsWith(YouTubeBackend.VIDEO_PREFIX)) {
            return dataSpec
        }
        // "#video" and "#audio" are the two halves of a video played in high
        // quality; see VideoAwareSourceFactory.
        val part = dataSpec.uri.fragment
        val base = uri.substringBefore('#')
        val video = part == null && YouTubeVideoMode.wantsVideo(base)

        // A song kept on the phone plays from the phone. Not while the video is
        // showing and there is a network to fetch it over: the file is the
        // sound alone.
        val kept = app.sonar.download.YouTubeDownloads.fileFor(context, base)
        // Never for half of a video: the picture is the official video's, and
        // its sound has to be that video's too or the two drift apart.
        if (kept != null && part == null &&
            (!video || app.sonar.playback.OfflineMode.active.value)
        ) {
            audioRates.remove(base)
            return dataSpec.withUri(android.net.Uri.fromFile(kept))
        }

        // NewPipe holds its downloader in a static, and every extractor reads it
        // at construction: without this the first resolve dies on "downloader is
        // null". Done here rather than at startup so a session that never plays
        // a YouTube track never builds one.
        YouTubeStreams.ensureNewPipe()

        val songId = YouTubeBackend.videoIdOfUri(base)
        // A song watched rather than heard is its official video, sound and
        // all; see YouTubeStreams.watchSource. A video is already itself.
        val watching = part != null || video
        val (videoId, info) = if (watching && base.startsWith(YouTubeBackend.TRACK_PREFIX)) {
            YouTubeStreams.watchSource(songId)
        } else {
            // A music video from a list, heard: its song, found now if the list
            // did not get to it before the row was tapped; see SongCounterparts.
            val heard = if (!watching && base.startsWith(YouTubeBackend.TRACK_PREFIX) &&
                SongCounterparts.wanted(songId)
            ) {
                runCatching { SongCounterparts.findBlocking(songId) }.getOrNull()?.id
            } else {
                null
            } ?: songId
            heard to YouTubeStreams.info(heard)
        }

        // The picture alone, as sharp as is worth it on a phone: H.264 first,
        // which every phone decodes in hardware, then whatever else there is.
        if (part == PART_VIDEO) {
            val picture = info.videoOnlyStreams
                .filter { !it.content.isNullOrEmpty() && it.height in 1..MAX_VIDEO_HEIGHT }
                .sortedWith(
                    compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                        .thenByDescending { it.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 },
                )
                .firstOrNull()?.content
                ?: info.videoStreams.filter { !it.content.isNullOrEmpty() }.maxByOrNull { it.height }?.content
                ?: error("nessun video per $videoId")
            return dataSpec.withUri(android.net.Uri.parse(picture))
        }

        // With the video showing, one of YouTube's muxed streams: those are the
        // only ones carrying picture and sound in a single file, and a single
        // file is what a progressive source can play. They top out at 720p,
        // which is the price of not building a DASH manifest here.
        val url = if (video && part == null) {
            info.videoStreams
                .filter { !it.content.isNullOrEmpty() }
                .maxByOrNull { it.height }
                ?.content
        } else {
            null
        }
        if (url == null) return dataSpec.withAudio(bestAudio(info, videoId))
        audioRates.remove(base)
        return dataSpec.withUri(android.net.Uri.parse(url))
    }

    private val quality
        get() = (context.applicationContext as? app.sonar.SonarApplication)?.preferences?.youtubeQuality?.value

    private fun bestAudio(info: StreamInfo, videoId: String): SignedInStreams.Stream {
        val chosen = quality
        fun rank(stream: org.schabi.newpipe.extractor.stream.AudioStream) =
            YouTubeAudioQuality(stream.itag, stream.averageBitrate)
        val order = YouTubeAudioQuality.order(wantsLowRate(chosen))
        val bestAvailable = chosen == app.sonar.data.PreferencesStore.YouTubeQuality.HIGH
        return YouTubeAudioQuality.firstPlayable(
            info.audioStreams.filter { it.isUrl && !it.content.isNullOrEmpty() },
            isOriginal = {
                it.audioTrackType == null ||
                    it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
            },
            order = compareBy(order, ::rank),
        ) { candidate ->
            val rank = rank(candidate)
            val url = candidate.content
            val upgrade = if (bestAvailable) SignedInStreams.tryUpgradeAudio(context, videoId, rank) else null
            upgrade ?: if (SignedInStreams.isAudioUrlPlayable(url)) {
                SignedInStreams.Stream(url, rank.kbps.takeIf { it > 0 })
            } else {
                null
            }
        } ?: error("nessuna traccia audio per $videoId")
    }

    /** Whether the lower rate is wanted now; see PreferencesStore.youtubeQuality. */
    private fun wantsLowRate(chosen: app.sonar.data.PreferencesStore.YouTubeQuality?): Boolean {
        return when (chosen) {
            app.sonar.data.PreferencesStore.YouTubeQuality.LOW -> true
            app.sonar.data.PreferencesStore.YouTubeQuality.HIGH -> false
            else -> context.getSystemService(android.net.ConnectivityManager::class.java)
                ?.isActiveNetworkMetered == true
        }
    }

}

/**
 * Plays a video as two sources side by side: the picture alone and the sound
 * alone. YouTube serves anything sharper than 360p only that way; the file that
 * carries both is the small one.
 *
 * Decided when the item is added, so it applies to videos (which always want
 * their picture) and to songs added while the video button is on.
 */
@UnstableApi
private class VideoAwareSourceFactory(
    private val base: DefaultMediaSourceFactory,
    dataSources: androidx.media3.datasource.DataSource.Factory,
    private val matchSpotify: Boolean,
) : androidx.media3.exoplayer.source.MediaSource.Factory by base {

    private val progressive = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSources)

    override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource {
        if (matchSpotify && mediaItem.mediaId.startsWith("spotify:")) {
            val uri = android.net.Uri.Builder().scheme(SPOTIFY_MATCH_SCHEME).authority("track")
            if (mediaItem.mediaId.startsWith("spotify:track:")) {
                uri.appendPath(mediaItem.mediaId.substringAfterLast(':'))
            }
            YouTubePlayerFactory.spotifyTrackKey(mediaItem)?.let { track ->
                uri.appendQueryParameter("title", track.title)
                    .appendQueryParameter("duration", track.durationMs.toString())
                track.artists.forEach { uri.appendQueryParameter("artist", it) }
            }
            return progressive.createMediaSource(mediaItem.buildUpon().setUri(uri.build()).build())
        }
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return base.createMediaSource(mediaItem)
        val ours = uri.startsWith(YouTubeBackend.TRACK_PREFIX) || uri.startsWith(YouTubeBackend.VIDEO_PREFIX)
        if (!ours || !YouTubeVideoMode.wantsVideo(uri)) return base.createMediaSource(mediaItem)
        fun half(part: String) = progressive.createMediaSource(
            mediaItem.buildUpon().setUri("$uri#$part").build(),
        )
        return androidx.media3.exoplayer.source.MergingMediaSource(half(PART_VIDEO), half(PART_AUDIO))
    }
}

private const val PART_VIDEO = "video"
private const val PART_AUDIO = "audio"
private const val SPOTIFY_MATCH_SCHEME = "sonar-ytmatch"

/** Past this a phone screen shows no difference, and the data plan does. */
private const val MAX_VIDEO_HEIGHT = 1080

/** NewPipe's one-time setup, shared by the player and the downloads. */
object YouTubeStreams {
    const val WATCH_URL = "https://www.youtube.com/watch?v="

    private val infos = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StreamInfo>>()
    private val watched = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, StreamInfo>>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** One fetch per key at a time: the two halves of a video ask at once. */
    private fun lockFor(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /**
     * What YouTube says about a video, kept for a while: the two halves of a
     * video ask within milliseconds of each other, and the URLs stay good for
     * hours.
     */
    fun info(videoId: String): StreamInfo = synchronized(lockFor(videoId)) {
        val now = android.os.SystemClock.elapsedRealtime()
        infos[videoId]?.takeIf { now - it.first < INFO_TTL_MS }?.let { return it.second }
        ensureNewPipe()
        val fresh = StreamInfo.getInfo(ServiceList.YouTube, "$WATCH_URL$videoId")
        infos[videoId] = now to fresh
        if (infos.size > 64) infos.entries.minByOrNull { it.value.first }?.let { infos.remove(it.key) }
        fresh
    }

    /**
     * What to show for a song when it is watched: its official video where
     * there is one that can be played, the song's own upload otherwise. Decided
     * once per song and kept, so the picture and the sound — asked for
     * separately — always come from the same upload. A video that answers
     * "age-restricted" cannot be watched without an account, and is passed over.
     */
    fun watchSource(songId: String): Pair<String, StreamInfo> = synchronized(lockFor("watch:$songId")) {
        val now = android.os.SystemClock.elapsedRealtime()
        watched[songId]?.takeIf { now - it.first < INFO_TTL_MS }?.let { return it.second }
        val song = info(songId)
        val chosen = runCatching { OfficialVideos.forSong(songId, song) }.getOrNull()
            ?.let { id ->
                runCatching { id to info(id) }
                    .onFailure {
                        android.util.Log.i("SonarYTVideo", "official video $id unplayable: ${it.message}")
                        // Said under the picture, so the cover standing in for
                        // the video is not mistaken for a bug.
                        YouTubeVideoMode.markUnavailable(
                            songId,
                            if (it is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException) {
                                YouTubeVideoMode.Unavailable.AGE_RESTRICTED
                            } else {
                                YouTubeVideoMode.Unavailable.OTHER
                            },
                        )
                    }
                    .getOrNull()
            }
            ?: (songId to song)
        watched[songId] = now to chosen
        chosen
    }

    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SonarYTPrefetch").apply { isDaemon = true }
    }

    /** Asks ahead for what [uri] will need, so the next item starts at once. */
    fun prefetch(uri: String) {
        val id = YouTubeBackend.videoIdOfUri(uri)
        prefetcher.execute {
            runCatching {
                if (uri.startsWith(YouTubeBackend.TRACK_PREFIX) && YouTubeVideoMode.wantsVideo(uri)) {
                    watchSource(id)
                } else {
                    info(id)
                }
            }
        }
    }

    /** Drops what is kept about [videoId], so its addresses are asked for again. */
    fun forget(videoId: String) {
        infos.remove(videoId)
        watched.remove(videoId)
    }

    private const val INFO_TTL_MS = 60 * 60 * 1000L

    @Volatile
    private var initialised = false

    /** Idempotent: several tracks resolve on the same thread pool. */
    @Synchronized
    fun ensureNewPipe() {
        if (initialised) return
        NewPipe.init(NewPipeDownloader.create(), Localization.DEFAULT)
        initialised = true
    }
}
