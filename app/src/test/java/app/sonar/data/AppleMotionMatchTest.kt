package app.sonar.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMotionMatchTest {
    private val track = AppleMotionTrack("Song", "Artist", "Album", 200_000)

    @Test
    fun requiresTheSameTrackAndReleaseWithSimilarDuration() {
        assertEquals(500L, track.matchDistance(track.copy(title = "song", durationMs = 200_500)))
        assertNull(track.matchDistance(track.copy(title = "Song (Live)")))
        assertNull(track.matchDistance(track.copy(artist = "Other Artist")))
        assertNull(track.matchDistance(track.copy(album = "Album (Deluxe)")))
        assertNull(track.matchDistance(track.copy(durationMs = 203_000)))
        assertNull(track.matchDistance(track.copy(durationMs = 0)))
    }

    @Test
    fun prefersSquareVariantsAndSkipsUnusableUrls() {
        val videos = Json.parseToJsonElement(
            """
            {
                "motionDetailSquare": {"video": "https://example.com/detail-square"},
                "motionSquareVideo1x1": {"video": "https://example.com/square"},
                "motionDetailTall": {"video": "https://example.com/tall"}
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals("https://example.com/detail-square", appleMotionUrl(videos))
        assertEquals(
            "https://example.com/square",
            appleMotionUrl(JsonObject(videos + ("motionDetailSquare" to JsonNull))),
        )
        assertEquals(
            "https://example.com/tall",
            appleMotionUrl(JsonObject(videos - setOf("motionDetailSquare", "motionSquareVideo1x1"))),
        )
    }

    @Test
    fun confirmedMissCanBeCached() = runBlocking {
        val result = appleMotionFallback<String>(Result.success(null)) { Result.success(null) }
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun missedStorefrontAndFailedStorefrontLeaveMotionUnresolved() = runBlocking {
        val miss = Result.success<String?>(null)
        val failure = Result.failure<String?>(IOException("Storefront unavailable"))
        assertFalse(appleMotionFallback(miss) { failure }.isSuccess)
        assertFalse(appleMotionFallback(failure) { miss }.isSuccess)
    }

    @Test
    fun confirmedVideoWinsEvenWhenTheLocalRequestFailed() = runBlocking {
        val failure = Result.failure<String?>(IOException("Storefront unavailable"))
        val result = appleMotionFallback(failure) { Result.success("https://example.com/cover.m3u8") }
        assertEquals("https://example.com/cover.m3u8", result.getOrThrow())
    }
}
