package app.sonar.migration

import app.sonar.data.CatalogArtist
import app.sonar.data.CatalogTrack
import app.sonar.data.ListeningEvent
import app.sonar.data.LocalLibrary
import app.sonar.library.LocalPlaylist
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object AstralDataConverters {
    const val PLAYLISTS = "files/local_playlists.json"
    const val HISTORY = "files/listen_history.jsonl"
    const val HISTORY_OWNER = "files/listen_history.owner"

    fun ownerKey(account: String): String = MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun parkedHistory(key: String) = "files/listen-history/$key.jsonl"

    fun playlists(bytes: ByteArray): List<LocalPlaylist>? {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.optInt("version") != 1) return null
        val lists = root.getJSONArray("playlists")
        return (0 until lists.length()).mapNotNull { index ->
            val list = lists.getJSONObject(index)
            val id = list.getString("id")
            val name = list.getString("name").trim()
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val items = list.optJSONArray("items") ?: JSONArray()
            val tracks = (0 until items.length()).mapNotNull { item ->
                val row = items.getJSONObject(item)
                val track = metadata(row, row.optLong("duration").coerceAtLeast(0))
                track.takeIf { it.name.isNotBlank() }?.asLocalTrack()
            }.distinctBy { it.uri }
            LocalPlaylist(id, name, tracks)
        }
    }

    fun history(bytes: ByteArray, zone: ZoneId): List<ListeningEvent> =
        bytes.inputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).mapNotNull { line ->
                // Astral can export while the last JSONL record is being appended.
                try {
                    listen(JSONObject(line), zone)
                } catch (_: JSONException) {
                    null
                }
            }.distinctBy { it.id }.toList()
        }

    private fun listen(row: JSONObject, zone: ZoneId): ListeningEvent? {
        val ended = row.getLong("at")
        val played = row.getLong("ms")
        if (played < 30_000 || ended < played) return null
        val track = metadata(row)
        if (track.name.isBlank()) return null
        val key = row.getString("key")
        val catalogTrack = when {
            key.startsWith("spotify:track:") || key.startsWith("spotify:episode:") -> track.copy(uri = key)
            key.startsWith("local:") -> track.asLocalTrack()
            else -> return null
        }
        val started = ended - played
        val month = YearMonth.from(Instant.ofEpochMilli(ended).atZone(zone))
        return ListeningEvent(
            id = "astral:$ended:$key",
            track = catalogTrack,
            startedAt = started,
            endedAt = ended,
            listenedMs = played,
            qualified = true,
            measuredMonths = mapOf(month.toString() to played),
        )
    }

    private fun metadata(row: JSONObject, duration: Long = 0): CatalogTrack {
        val artists = row.optJSONArray("artists") ?: JSONArray()
        return CatalogTrack(
            uri = "",
            name = row.optString("title"),
            artist = row.optString("artist"),
            artists = (0 until artists.length()).mapNotNull { index ->
                artists.optString(index).takeIf(String::isNotBlank)?.let { CatalogArtist(it) }
            },
            album = row.optString("album"),
            durationMs = duration,
            artworkUrl = row.optString("cover").takeIf { it.startsWith("https://") },
        )
    }

    // Device-specific media URIs cannot be reused across installs.
    private fun CatalogTrack.asLocalTrack() = copy(uri = LocalLibrary.spotifyUri(this))
}
