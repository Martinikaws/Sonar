package app.sonar.animart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedArtPolicyTest {
    @Test
    fun fetchRequiresWifiAndNoConservation() {
        assertTrue(canFetchAnimatedArt(wifi = true, conserving = false))
        assertFalse(canFetchAnimatedArt(wifi = true, conserving = true))
        assertFalse(canFetchAnimatedArt(wifi = false, conserving = false))
        assertFalse(canFetchAnimatedArt(wifi = false, conserving = true))
    }

    @Test
    fun missingOrUnsafeUrlsFallBackToStillArtwork() {
        assertNull(animatedArtUrl(null))
        assertNull(animatedArtUrl("http://example.com/cover.mp4"))
        assertNull(animatedArtUrl("https://user@example.com/cover.mp4"))
        assertNull(animatedArtUrl("https://"))
        assertEquals("https://example.com/cover.m3u8", animatedArtUrl("https://example.com/cover.m3u8"))
    }

    @Test
    fun cacheSharesFragmentsButKeepsDistinctMediaQueries() {
        val segment = "https://example.com/segment.mp4"
        assertEquals(segment, animatedArtCacheKey("$segment#t=2"))
        assertNotEquals(animatedArtCacheKey("$segment?part=1"), animatedArtCacheKey("$segment?part=2"))
    }
}
