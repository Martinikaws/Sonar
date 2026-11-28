# Astral export format v1

This reference describes the format implemented by Astral's `migration/MigrationCrypto.kt` and `migration/SonarExport.kt`, and the portable data Sonar restores from it.

## Encrypted envelope

The file contains the six ASCII bytes `SNMIG1`, a 16-byte random salt, a 12-byte random IV, and the ciphertext followed by a 16-byte GCM authentication tag. Derive the 256-bit AES key from the passphrase using PBKDF2-HMAC-SHA256 with the stored salt and 210,000 iterations. The exporter takes the passphrase as a Java character array and requires at least eight characters. AES-GCM uses no additional authenticated data. The authenticated plaintext is a ZIP of at most 64 MiB.

## ZIP entries

- `manifest.json` contains `formatVersion` (integer `1`), `appId` and `appVersion` (strings), `createdAt` (UTC ISO 8601 timestamp ending in `Z`), and `entries` (each other ZIP path exactly once).
- `cookies.json` is an array of records with string `url` and `cookie` fields and an optional string `profile`. The cookie string is the WebView cookie header. Sonar restores supported Spotify and YouTube Music sessions when the corresponding session is unoccupied.
- `prefs/<name>.xml` contains Android shared preferences. Sonar restores an allowlist of compatible Boolean settings.
- `files/<relative-path>` contains files from Astral's app storage. Sonar reads the portable entries below.

JSON is UTF-8. ZIP names are relative, slash-separated paths.

## Portable files

- `files/local_playlists.json` is a versioned JSON object with `version: 1`, `playlists`, and `additions`. Each playlist has an `id`, `name`, `created` timestamp, and `items`. Items carry `key`, `uri`, `title`, `artist`, `album`, `duration` in milliseconds, and optional `art`. Sonar restores playlist items by their tags and duration and resolves them against audio on the device when available.
- `files/listen_history.jsonl` contains one JSON object per line. The fields include `at` (end time in Unix milliseconds), `ms` (listened milliseconds), `key` (track identifier), and track metadata such as `title`, `artist`, `artists`, `album`, and `cover`.
- `files/listen_history.owner` identifies the active history's owner. Account keys are the first 12 bytes of the SHA-256 digest of the UTF-8 Spotify account ID, encoded in lowercase hex. `files/listen-history/<key>.jsonl` contains parked history for that account using the same JSONL format. Sonar restores only history matching the signed-in account.
