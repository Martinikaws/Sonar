package app.sonar.data

import java.net.URI

private val invitePath = Regex("/socialsession/([A-Za-z0-9]{8,})")
private val inviteToken = Regex("[A-Za-z0-9]{8,}")
private val jamSecrets = Regex(
    """(?i)((?:social-connect/v\d+/sessions/join/|socialsession[/:])|(?:join_session_token|invite_token)["']?\s*[:=]\s*["']?)[A-Za-z0-9._~%+-]+""",
)

internal fun jamInviteToken(text: String?): String? {
    val uri = text?.trim()?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (uri.rawFragment != null) return null
    if (uri.scheme.equals("spotify", ignoreCase = true) && uri.isOpaque) {
        val value = uri.rawSchemeSpecificPart.removePrefix("socialsession:")
        return value.takeIf { it != uri.rawSchemeSpecificPart && inviteToken.matches(it) }
    }
    val authority = uri.rawAuthority?.lowercase(java.util.Locale.ROOT)
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        (authority != "open.spotify.com" && authority != "open.spotify.com:443")
    ) return null
    return invitePath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
}

internal fun redactJamTokens(text: String): String = jamSecrets.replace(text) {
    "${it.groupValues[1]}<token>"
}
