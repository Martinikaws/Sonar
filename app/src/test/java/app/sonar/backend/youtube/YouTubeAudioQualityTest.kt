package app.sonar.backend.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeAudioQualityTest {
    private val opus = YouTubeAudioQuality(251, 160)
    private val premiumAac = YouTubeAudioQuality(141, 256)
    private val standardAac = YouTubeAudioQuality(140, 128)
    private val lowOpus = YouTubeAudioQuality(249, 50)
    private val unknown = YouTubeAudioQuality(-1, -1)

    @Test
    fun bestPrefersOpus251ThenPremiumAac() {
        val order = YouTubeAudioQuality.order(lowRate = false)
        assertEquals(
            listOf(opus, premiumAac, standardAac, lowOpus, unknown),
            listOf(standardAac, unknown, premiumAac, lowOpus, opus).sortedWith(order),
        )
    }

    @Test
    fun failedOriginalOpusFallsBackToUntaggedAacBeforeAlternateTracks() {
        val candidates = listOf(
            "dubbed" to opus,
            "untagged" to premiumAac,
            "descriptive" to opus,
            "original" to opus,
        )
        val attempted = mutableListOf<String>()
        val selected = YouTubeAudioQuality.firstPlayable(
            candidates,
            isOriginal = { it.first == "original" || it.first == "untagged" },
            order = compareBy(YouTubeAudioQuality.order(lowRate = false)) { it.second },
        ) { (track, quality) ->
            attempted += track
            quality.takeUnless { track == "original" }
        }
        assertEquals(premiumAac, selected)
        assertEquals(listOf("original", "untagged"), attempted)
    }

    @Test
    fun dataSaverChoosesLowestKnownBitrateAcrossCodecs() {
        val order = YouTubeAudioQuality.order(lowRate = true)
        assertEquals(lowOpus, listOf(premiumAac, unknown, standardAac, lowOpus, opus).minWithOrNull(order))
        assertEquals(unknown, listOf(unknown).minWithOrNull(order))
    }
}
