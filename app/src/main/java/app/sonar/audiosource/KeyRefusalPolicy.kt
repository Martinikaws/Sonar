package app.sonar.audiosource

/**
 * Whether a refused Spotify key should move the current song to another source now.
 *
 * Only while the song is still waiting to start: a refusal for a preload, or for a
 * song already playing, must not interrupt it. [refusal] is the engine's number
 * (0 over, 1 refused, 2 refused to the account).
 */
internal fun shouldFallBackOnKeyRefusal(refusal: Long, wantsToPlay: Boolean, started: Boolean): Boolean =
    refusal != 0L && wantsToPlay && !started

/**
 * Whether to go straight to another source without asking Spotify for the key first.
 *
 * Only for an account Spotify refuses every key ([accountRefused]), with no web player
 * set up to play it instead, and once Spotify has already refused a song in this run:
 * that first real attempt is what notices the account being given keys again.
 */
internal fun shouldSkipRefusedSpotify(
    accountRefused: Boolean,
    webPlayerConfigured: Boolean,
    refusedThisRun: Boolean,
): Boolean = accountRefused && !webPlayerConfigured && refusedThisRun
