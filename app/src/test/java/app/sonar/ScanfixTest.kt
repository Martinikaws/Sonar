package app.sonar

import app.sonar.backend.youtube.isYouTubeLoginCompletion
import app.sonar.backend.youtube.isYouTubeLoginNavigation
import app.sonar.data.jamInviteToken
import app.sonar.data.redactJamTokens
import app.sonar.diagnostics.Report
import app.sonar.update.MAX_UPDATE_BYTES
import app.sonar.update.requireUpdateSize
import app.sonar.update.selectSonarAsset
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ScanfixTest {
    @Test fun validatesEntireJamInvite() {
        assertEquals("GoodToken123", jamInviteToken("https://open.spotify.com/socialsession/GoodToken123?si=abc"))
        assertEquals("GoodToken123", jamInviteToken("spotify:socialsession:GoodToken123"))
        for (url in listOf(
            "http://open.spotify.com/socialsession/GoodToken123",
            "https://open.spotify.com.evil.test/socialsession/GoodToken123",
            "https://fake@open.spotify.com/socialsession/GoodToken123",
            "https://open.spotify.com:444/socialsession/GoodToken123",
            "https://open.spotify.com/socialsession/GoodToken123/another",
            "https://open.spotify.com/socialsession/GoodToken123!",
            "https://open.spotify.com/socialsession/GoodToken123#fragment",
            "prefix https://open.spotify.com/socialsession/GoodToken123",
        )) assertNull(url, jamInviteToken(url))
    }

    @Test fun redactsJamTokensFromCurrentAndArchivedLogs() {
        val text = "POST /social-connect/v2/sessions/join/GoodToken123?x=1 " +
            "https://open.spotify.com/socialsession/GoodToken123 spotify:socialsession:GoodToken123 " +
            """{"join_session_token":"GoodToken123"}"""
        val redacted = redactJamTokens(text)
        assertFalse(redacted.contains("GoodToken123"))
        assertEquals(4, Regex("<token>").findAll(redacted).count())
        assertEquals(redacted, Report.redact(text, emptyList()))
    }

    @Test fun restrictsYouTubeLoginHosts() {
        assertTrue(isYouTubeLoginCompletion("https://music.youtube.com/"))
        assertTrue(isYouTubeLoginNavigation("https://accounts.google.com/"))
        assertTrue(isYouTubeLoginNavigation("https://www.youtube.com/"))
        assertFalse(isYouTubeLoginCompletion("https://www.youtube.com/"))
        for (url in listOf(
            "http://music.youtube.com/",
            "https://evil.test/music.youtube.com",
            "https://music.youtube.com.evil.test/",
            "https://fake@music.youtube.com/",
            "https://music.youtube.com:444/",
            "intent://music.youtube.com/",
        )) {
            assertFalse(url, isYouTubeLoginCompletion(url))
            assertFalse(url, isYouTubeLoginNavigation(url))
        }
    }

    @Test fun selectsOnlyMatchingSonarAssetNames() {
        val assets = listOf(
            "other-arm64-v8a.apk", "sonar-0.1.4-universal.apk",
            "sonar-0.1.4-armeabi-v7a.apk", "sonar-0.1.4-arm64-v8a.apk",
        )
        assertEquals(assets[3], selectSonarAsset("0.1.4", assets, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(assets[2], selectSonarAsset("0.1.4", assets, listOf("armeabi-v7a")))
        assertEquals(assets[1], selectSonarAsset("0.1.4", assets, listOf("x86")))
        assertNull(selectSonarAsset("0.1.4", listOf(assets[0], assets[3]), listOf("x86")))
        assertNull(selectSonarAsset("0.1.5", assets, listOf("arm64-v8a")))
    }

    @Test fun enforces200MiBCeiling() {
        requireUpdateSize(-1)
        requireUpdateSize(200L * 1024 * 1024)
        assertThrows(IOException::class.java) { requireUpdateSize(MAX_UPDATE_BYTES + 1) }
    }
}
