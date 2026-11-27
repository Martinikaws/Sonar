package app.sonar.radio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player

/**
 * A station plays as a one-item queue on the phone's own player: no duration, nothing before or
 * after it. The media id carries the station and the uri carries the stream, so a restored
 * session never mistakes it for a Spotify track.
 */
object RadioPlayback {
    private const val PREFIX = "sonar-radio:"

    fun owns(mediaId: String) = mediaId.startsWith(PREFIX)

    fun mediaId(station: RadioStation) = PREFIX + station.uuid

    fun item(station: RadioStation): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId(station))
        .setUri(station.url)
        .setMimeType(if (station.hls) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.tags.firstOrNull() ?: station.country.ifBlank { null })
                .setArtworkUri(station.favicon.takeIf { RadioParsing.playable(it) }?.let(android.net.Uri::parse))
                .setIsPlayable(true)
                .build(),
        )
        .build()

    fun play(player: Player, station: RadioStation) {
        if (!RadioParsing.playable(station.url)) return
        player.setMediaItem(item(station))
        player.prepare()
        player.play()
    }
}
