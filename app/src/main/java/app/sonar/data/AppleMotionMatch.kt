package app.sonar.data

import app.sonar.animart.animatedArtUrl
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class AppleMotionTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    fun matchDistance(candidate: AppleMotionTrack): Long? {
        fun same(a: String, b: String) = a.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)
        if (!same(title, candidate.title) || !same(artist, candidate.artist) ||
            !same(album, candidate.album) || durationMs <= 0 || candidate.durationMs <= 0
        ) return null
        return abs(durationMs - candidate.durationMs).takeIf { it <= 2_000 }
    }
}

internal fun appleMotionUrl(videos: JsonObject): String? =
    listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4")
        .firstNotNullOfOrNull { key ->
            val variant = videos[key] as? JsonObject
            animatedArtUrl((variant?.get("video") as? JsonPrimitive)?.content)
        }

internal suspend fun <T : Any> appleMotionFallback(
    local: Result<T?>,
    fallback: suspend () -> Result<T?>,
): Result<T?> {
    if (local.getOrNull() != null) return local
    val alternate = fallback()
    return if (alternate.getOrNull() != null || local.isSuccess) alternate else local
}
