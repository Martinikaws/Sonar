# Changelog

## 1.1.0

### Features

- Live internet radio with station search, popular stations, favorites and recent stations. Playback uses HTTPS streams.
- On-device playlists with create, rename, delete, add and remove actions. Import Spotify and YouTube Music playlist links, including paginated playlists, with imported and skipped track counts. Imported tracks retain their playback source.
- Hide individual songs and ban artists from queues and radio. Review and undo both in Settings. Reorder pinned playlists in the library.
- Spotify Home filters for music and podcasts, with additional filters when supplied by Spotify. Home also shows new releases from followed artists, with a connection prompt when Spotify access is missing.
- Listening recap for a month, year or all time, with top tracks, albums, source totals and a downloads breakdown where available. Account history can add plays. Listening time comes from Sonar.
- Genius lyrics lookup and a link to the matching page.
- Tablet layout with a navigation rail and room for the player alongside browsing.
- Astral import for supported sessions, preferences, local playlists and history for the matching Spotify account. Existing sign-ins are preserved. See [Astral export format](MIGRATION_FORMAT.md).
- Find and join Jams shared on the local network. The output picker discovers Cast devices, Cast groups and Sonos devices and can use matching Spotify Connect outputs.
- Pass transfers a YouTube Music sign-in between phones on the same Wi-Fi using an expiring QR code, a passphrase and confirmation on the receiving phone.
- Browse the extension catalog with listings from the index, GitHub and GitLab, ratings where available, and update checks. Manage checksum-verified packages from the catalog, a local ZIP or an HTTPS URL. Enable, disable or remove installed extensions and check their JavaScript status.
- Modules offers BackMusic artwork and spectrum or waveform visualization when playback supplies audio frames.
- Android Auto adds Library and Downloads browsing, expands playlist and search results, and exposes playback actions through the car media session.

### Settings

- Looks adds cover-derived colors, a custom accent, cover rotation and show or hide switches for shuffle, repeat, lyrics and the playback source label.
- Opt-in Apple Music animated artwork looks for a matching release and falls back to still artwork.
- Headset and savers adds 15-second podcast seeking, low-battery and cellular data saver switches, and a player restart action. Saver mode reduces artwork work and pauses audio preloading.
- Separate settings for player haptics and keeping the screen on during lyrics.
- Spotify stream quality labels now read Normal, High and Very High alongside Auto. YouTube Music offers Auto, Best available and Data saver for newly loaded streams. Auto accounts for metered connections, and some higher-quality streams require Premium. The player displays the audio format and bitrate when available.
- AutoMix settings add beat analysis for adjacent Spotify tracks with crossfade enabled, diagnostics and cache clearing. The transition editor supports cue adjustment, bar snapping, overlap, and saving or resetting a track pair's plan.
- Open Modules from Settings or enable its navigation tab.

### Fixes

- Soloist and Spotify Connect playback is more reliable, Spotify data is fetched from its own gateway first with the Web API as a fallback, and YouTube playback handles the new player script (ported from Square 2.11.3).
- Spotify playback starts faster: radio is queued behind the tapped song, the next YouTube stream is prefetched, and a refused Spotify track is skipped after the first refusal.
- Artist pages list every single and EP, tapping a recommended song plays it, and covers animate on mobile data.
- Added Simplified Chinese.
- A Spotify audio key refusal during playback startup can trigger source fallback immediately. Repeated refusals are handled per affected track.
- Route live radio to the local player and keep live stations out of the saved song queue. Ignore stale radio entries on restore.
- Apply hidden song and banned artist filters when new tracks arrive through queue continuations.
- Check that higher-quality signed-in YouTube Music audio URLs are playable before using them and fall back when needed. Key signed-in stream lookups by account and channel, and clear the signed-in stream cache on playback errors.
- Keep followed-release and Android Auto caches tied to the active account. Android Auto updates its actions when offline mode changes.
- Restrict Apple Music animation requests and redirects to allowed hosts and match the release before using motion artwork.

### Known limits

- AutoMix can save transition plans. Native mixer integration and a separate preview transport are pending.
- Cast receiver launch and local media handoff are pending. New Sonos outputs still need pairing through Spotify consent and validation of group coordinator handoff.
- Extension UI registration and host actions, audio and download provider ordering and fallback, metadata provider ordering and fallback, theme overrides, device enrollment and attestation, and consent for sharing playback counts still need decisions.
- Confirm that Genius permits in-app display of lyrics from its pages before release.
- Verify Apple Music motion hosts, HLS redirects and release matching on a device.
- Verify Google Assistant and legacy custom actions on a paired Android Auto host.
