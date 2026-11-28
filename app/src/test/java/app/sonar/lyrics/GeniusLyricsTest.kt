package app.sonar.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusLyricsTest {
    @Test fun searchRejectsWrongSongsArtistsAndExternalUrls() {
        val response = """
            {"response":{"sections":[{"hits":[
                {"result":{"title":"Halo Again","artist_names":"Beyoncé","url":"https://genius.com/Halo-again-lyrics"}},
                {"result":{"title":"Halo","artist_names":"Tribute Band","url":"https://genius.com/Tribute-halo-lyrics"}},
                {"result":{"title":"Halo","artist_names":"Beyoncé","url":"https://example.com/Halo-lyrics"}},
                {"result":{"title":"Halo","artist_names":"Beyoncé","url":"https://genius.com:8443/Halo-lyrics"}},
                {"result":{"title":"Halo","artist_names":"Beyoncé & Guest","url":"https://genius.com/Beyonce-halo-lyrics"}}
            ]}]}}
        """.trimIndent()

        val pages = GeniusLyrics.songPages(response, "Halo - Remastered", "Beyonce")
        assertEquals(listOf("https://genius.com/Beyonce-halo-lyrics"), pages.map { it.toString() })
    }

    @Test fun emptyAndMalformedSearchResponsesHaveNoPages() {
        for (response in listOf("", "{", "{}", """{"response":{"sections":[]}}""")) {
            assertTrue(GeniusLyrics.songPages(response, "Halo", "Beyonce").isEmpty())
        }
    }

    @Test fun duplicateMatchingHitsReturnOnePage() {
        val url = "https://genius.com/Beyonce-halo-lyrics"
        val hit = """{"result":{"title":"Halo","artist_names":"Beyonce","url":"$url"}}"""
        val response = """{"response":{"sections":[{"hits":[$hit]},{"hits":[$hit]}]}}"""

        assertEquals(listOf(url), GeniusLyrics.songPages(response, "Halo", "Beyonce").map { it.toString() })
    }

    @Test fun sharedGuestDoesNotMatchWrongLeadArtist() {
        val response = """
            {"response":{"sections":[{"hits":[
                {"result":{"title":"Halo","primary_artist":{"name":"Tribute Band"},"artist_names":"Tribute Band & Guest","url":"https://genius.com/Tribute-halo-lyrics"}},
                {"result":{"title":"Halo","primary_artist":{"name":"Beyoncé"},"artist_names":"Beyoncé & Guest","url":"https://genius.com/Beyonce-halo-lyrics"}}
            ]}]}}
        """.trimIndent()

        val pages = GeniusLyrics.songPages(response, "Halo", "Beyonce feat. Guest")
        assertEquals(listOf("https://genius.com/Beyonce-halo-lyrics"), pages.map { it.toString() })
    }

    @Test fun extractsOnlyLyricsContainersAndSkipsNestedHeaderBlocks() {
        val page = """
            <div>Outside</div><div data-lyrics-container="true">
            <div data-exclude-from-selection="true"><div>Description</div></div>
            <a>First line</a><br/>Second line<div>Third line</div></div>
            <div data-lyrics-container="true">Last line</div>
        """.trimIndent()

        assertEquals(
            listOf("<a>First line</a><br/>Second line<div>Third line</div>", "Last line"),
            GeniusLyrics.lyricFragments(page).map(String::trim),
        )
        assertTrue(GeniusLyrics.lyricFragments("<div>Outside</div>").isEmpty())
    }
}
