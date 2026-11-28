package app.sonar.power

import org.junit.Assert.assertEquals
import org.junit.Test

class SaverArtworkTest {
    @Test fun keepsAppleCropFormatAndQuery() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/288x384SHT.AMTPPS01.webp?l=it-IT",
            smallerArtworkUrl("https://is1-ssl.mzstatic.com/image/1200x1600SHT.AMTPPS01.webp?l=it-IT"),
        )
    }

    @Test fun capsYouTubeArtwork() {
        assertEquals(
            "https://yt3.googleusercontent.com/cover=w384-h192-p-l90-rj",
            smallerArtworkUrl("https://yt3.googleusercontent.com/cover=w720-h360-p-l90-rj"),
        )
        assertEquals(
            "https://yt3.ggpht.com/cover=s384-c",
            smallerArtworkUrl("https://yt3.ggpht.com/cover=s60-s720-c"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/video/hqdefault.jpg",
            smallerArtworkUrl("https://i.ytimg.com/vi/video/sddefault.jpg"),
        )
    }

    @Test fun leavesSmallAndUnsupportedSourcesAlone() {
        for (url in listOf(
            "https://is1-ssl.mzstatic.com/image/100x100bb.jpg",
            "https://i.scdn.co/image/cover",
            "https://example.com/image/1200x1200bb.jpg",
        )) {
            assertEquals(url, smallerArtworkUrl(url))
        }
    }
}
