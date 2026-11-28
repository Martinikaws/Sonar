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
        assertNull(animatedArtUrl("http://a.mzstatic.com/cover.mp4"))
        assertNull(animatedArtUrl("https://user@a.mzstatic.com/cover.mp4"))
        assertNull(animatedArtUrl("https://"))
        assertNull(animatedArtUrl("https://example.com/cover.m3u8"))
        assertNull(animatedArtUrl("https://a.mzstatic.com.example.com/cover.m3u8"))
        assertNull(animatedArtUrl("https://fakemzstatic.com/cover.m3u8"))
        assertNull(animatedArtUrl("https://a.mzstatic.com:8443/cover.m3u8"))
        assertNull(animatedArtUrl("https://other.itunes.apple.com/cover.m3u8"))
        val master = "https://Mvod.iTunes.Apple.com/cover.m3u8"
        val segment = "https://a.mzstatic.com:443/segment.mp4"
        assertEquals(master, animatedArtUrl(master))
        assertEquals(segment, animatedArtUrl(segment))
    }

    @Test
    fun cacheSharesFragmentsButKeepsDistinctMediaQueries() {
        val segment = "https://example.com/segment.mp4"
        assertEquals(segment, animatedArtCacheKey("$segment#t=2"))
        assertNotEquals(animatedArtCacheKey("$segment?part=1"), animatedArtCacheKey("$segment?part=2"))
    }
}
