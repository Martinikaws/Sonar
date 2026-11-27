package app.sonar.audiosource

internal fun chooseTrackSource(
    sourceOrder: List<AudioSource>,
    freeAccount: Boolean,
    youtubeFallback: Boolean,
    onlinePlaybackAllowed: Boolean = true,
): AudioSource? {
    if (freeAccount) return AudioSource.YOUTUBE_MUSIC.takeIf { onlinePlaybackAllowed }
    return sourceOrder.firstOrNull {
        it == AudioSource.SPOTIFY ||
            (it == AudioSource.YOUTUBE_MUSIC && youtubeFallback && onlinePlaybackAllowed)
    }
}
