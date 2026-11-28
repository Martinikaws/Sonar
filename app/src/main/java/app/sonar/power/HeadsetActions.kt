package app.sonar.power

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.Player

internal fun seekFromMediaButton(intent: Intent, player: Player, episodeSkips: Boolean): Boolean {
    if (player.currentMediaItem?.mediaId?.startsWith("spotify:episode:") != true) return false
    if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !player.isCurrentMediaItemSeekable) {
        return false
    }
    @Suppress("DEPRECATION")
    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
    val offset = when (event.keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> if (episodeSkips) 15_000L else return false
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (episodeSkips) -15_000L else return false
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 15_000L
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> -15_000L
        else -> return false
    }
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        val duration = player.duration.takeIf { it >= 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + offset).coerceIn(0, duration))
    }
    return true
}

