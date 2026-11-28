package app.sonar.power

import androidx.media3.common.Player

internal fun restartPlayback(player: Player) {
    if (!player.isCommandAvailable(Player.COMMAND_STOP) || !player.isCommandAvailable(Player.COMMAND_PREPARE)) return
    val wasPlaying = player.playWhenReady
    player.stop()
    player.prepare()
    if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) player.playWhenReady = wasPlaying
}
