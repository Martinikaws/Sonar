package app.sonar.library.importlink

import app.sonar.data.util.SpotifyLink
import app.sonar.data.util.SpotifyLinkParser
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal sealed interface PlaylistLink {
    data class Spotify(val id: String) : PlaylistLink
    data class YouTube(val id: String) : PlaylistLink
    data object Invalid : PlaylistLink
    data object Unsupported : PlaylistLink
}

private val spotifyPath = Regex("/(?:intl-[a-zA-Z]+/)?playlist/[a-zA-Z0-9]{22}/?")
private val youtubeId = Regex("[a-zA-Z0-9_-]+")

internal fun parsePlaylistLink(input: String): PlaylistLink {
    val url = try {
        URI(input.trim())
    } catch (_: URISyntaxException) {
        return PlaylistLink.Invalid
    }
    if (url.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
        url.host == null || url.userInfo != null
    ) return PlaylistLink.Invalid

    val path = url.rawPath.orEmpty()
    return when (url.host.lowercase(Locale.ROOT)) {
        "open.spotify.com", "spotify.com" -> {
            val parsed = if (spotifyPath.matches(path)) SpotifyLinkParser.parse(url.toString()) else null
            if (parsed is SpotifyLink.Playlist) PlaylistLink.Spotify(parsed.id) else PlaylistLink.Invalid
        }
        "music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com" -> {
            val id = url.rawQuery.orEmpty().split('&')
                .singleOrNull { it.startsWith("list=") }
                ?.substringAfter('=')
            if (path in setOf("/playlist", "/watch") && id != null && youtubeId.matches(id)) {
                PlaylistLink.YouTube(id)
            } else {
                PlaylistLink.Invalid
            }
        }
        else -> PlaylistLink.Unsupported
    }
}
