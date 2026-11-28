package app.sonar.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.sonar.backend.PlaybackHost
import app.sonar.data.LocalLibrary
import app.sonar.flac.FlacDataSource
import app.sonar.radio.RadioPlayback

/** The ExoPlayer used for local files, FLAC sources and live radio. */
@OptIn(UnstableApi::class)
object LocalPlayerFactory {

    // Wrapped so a song queued from a folder plays next instead of after the
    // whole folder; see PlayNextPlayer.
    fun create(host: PlaybackHost): Player = PlayNextPlayer(build(host))

    private fun build(host: PlaybackHost): ExoPlayer = ExoPlayer.Builder(host.context, renderers(host))
        .setMediaSourceFactory(mediaSourceFactory(host))
        .setHandleAudioBecomingNoisy(true)
        // Ducks and pauses for whatever else wants the speaker, as the YouTube
        // player does; the Spotify path gets this from its own output.
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setLooper(host.looper)
        .build()

    private fun mediaSourceFactory(host: PlaybackHost): MediaSource.Factory {
        val local = DefaultMediaSourceFactory(
            ResolvingDataSource.Factory(
                DefaultDataSource.Factory(host.context) { FlacDataSource(host.context) },
                // The queue carries `local:track:<id>`, which is an
                // identity rather than a location: it survives being
                // written to disk and read back next week, where a
                // `content://` URI is only meaningful while the row it
                // points at still exists. The location is worked out here,
                // at the moment of opening the file.
                ResolvingDataSource.Resolver { spec ->
                    val uri = spec.uri.toString()
                    if (LocalLibrary.isLocal(uri)) {
                        spec.buildUpon().setUri(LocalLibrary.contentUri(uri)).build()
                    } else {
                        spec
                    }
                },
            ),
        )
        val radio = DefaultMediaSourceFactory(DefaultHttpDataSource.Factory())
        return object : MediaSource.Factory by local {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource =
                (if (RadioPlayback.owns(mediaItem.mediaId)) radio else local).createMediaSource(mediaItem)
        }
    }

    /**
     * The sink, with the app's own vocoder in its chain.
     *
     * Speed and pitch otherwise fall to the platform's stretcher, which is
     * built for speech and sounds like it on music — and the same song altered
     * the same amount would sound worse from the phone than from Spotify. See
     * BungeeAudioProcessor.
     */
    internal fun renderers(host: PlaybackHost) =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(host.context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // The hardware path applies speed itself, below the processors,
                // which would leave the vocoder with nothing to do and the
                // platform doing it after all.
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioProcessorChain(BungeeProcessorChain())
                .build()
        }
}
