package app.sonar.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One station of the radio-browser.info directory, reduced to what the screen shows and the player needs. */
@Serializable
data class RadioStation(
    val uuid: String,
    val name: String,
    val url: String,
    val homepage: String = "",
    val favicon: String = "",
    val tags: List<String> = emptyList(),
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    /** An HLS playlist: told to the player, as its address does not always end in .m3u8. */
    val hls: Boolean = false,
)

/** [stations] the app can play, and how many more matched that it cannot (see [RadioParsing.playable]). */
data class RadioResults(val stations: List<RadioStation>, val unplayable: Int)

@Serializable
private class StationDto(
    val stationuuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
)

@Serializable
private class TagDto(val name: String = "")

object RadioParsing {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** https only: the app allows no cleartext traffic, and a station is no reason to relax that. */
    fun playable(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    private fun looksHls(url: String) =
        runCatching { java.net.URI(url.trim()).path.orEmpty() }.getOrDefault("").endsWith(".m3u8", ignoreCase = true)

    /** The directory's answer to a station search; throws SerializationException on anything but a JSON list. */
    fun stations(text: String, limit: Int = Int.MAX_VALUE): RadioResults {
        val all = json.decodeFromString<List<StationDto>>(text).filter { it.stationuuid.isNotBlank() }
        val kept = all.mapNotNull { d ->
            // url_resolved has the redirects followed already.
            val url = d.urlResolved.ifBlank { d.url }.trim()
            if (!playable(url)) return@mapNotNull null
            RadioStation(
                uuid = d.stationuuid,
                name = d.name.trim().ifBlank { "?" },
                url = url,
                homepage = d.homepage,
                favicon = d.favicon,
                tags = d.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                country = d.country,
                codec = d.codec,
                bitrate = d.bitrate,
                hls = d.hls == 1 || looksHls(url),
            )
        }
        return RadioResults(kept.distinctBy { it.uuid }.take(limit), unplayable = all.size - kept.size)
    }

    fun tags(text: String): List<String> =
        json.decodeFromString<List<TagDto>>(text).map { it.name.trim() }.filter { it.isNotEmpty() }
}
