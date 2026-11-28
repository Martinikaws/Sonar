package app.sonar.home

import app.sonar.data.HomeShelf
import app.sonar.data.SpotifyHome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFacetTest {
    @Test
    fun distinguishesMissingOrDamagedPayloadsFromAnEmptyFeed() = runBlocking {
        for (input in listOf("", "{", "{}", """{"data":{"home":{}}}""")) {
            assertNull(parseHomeFacet(input, "music-chip"))
        }
        assertEquals(HomeFacet(emptyList(), emptyList()), parseHomeFacet(feed(), "music-chip"))
    }

    @Test
    fun keepsTheFirstValidChildPerIdOnTheSelectedFacet() = runBlocking {
        val chips = """
            {"id":"podcasts-chip","subChips":[{"id":"other","label":{"transformedLabel":"Other"}}]},
            null,
            {"id":"music-chip","subChips":[
                null,
                {"id":"live","label":{"transformedLabel":" "}},
                {"id":"live","label":{"transformedLabel":"Musica dal vivo 🎵"}},
                {"id":"live","label":{"transformedLabel":"Duplicate"}},
                {"id":"invalid/id","label":{"transformedLabel":"Invalid"}},
                {"id":"no-label"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(HomeSubFilter("live", "Musica dal vivo 🎵")),
            parseHomeFacet(feed(chips = chips), "music-chip")?.children,
        )
        assertEquals(emptyList<HomeSubFilter>(), parseHomeFacet(feed(chips = chips), "unknown")?.children)
    }

    @Test
    fun malformedRowsAndTilesDoNotDiscardValidShelves() = runBlocking {
        val sections = """
            null,
            {"uri":"spotify:section:fixture","data":{"title":{"transformedLabel":"Scelte per te"}},
             "sectionItems":{"items":[false,
                {"uri":"spotify:playlist:fixture","content":{"data":{"__typename":"Playlist","name":"Caffè"}}}
             ]}},
            "unexpected"
        """.trimIndent()
        val shelf = checkNotNull(parseHomeFacet(feed(sections), "music-chip")).shelves.single()
        assertEquals("Scelte per te", shelf.title)
        assertEquals("Caffè", shelf.items.single().name)
        assertEquals("spotify:section:fixture", shelf.sectionUri)
    }

    @Test
    fun podcastTilesAppearOnlyOnThePodcastFacet() = runBlocking {
        val sections = """
            {"data":{"title":{"transformedLabel":"Shows"}},"sectionItems":{"items":[
                {"uri":"spotify:show:fixture","content":{"data":{
                    "__typename":"Podcast","uri":"spotify:show:fixture","name":"A show"
                }}}
            ]}}
        """.trimIndent()
        assertEquals(
            "spotify:show:fixture",
            parseHomeFacet(feed(sections), "podcasts-chip")?.shelves?.single()?.items?.single()?.uri,
        )
        assertEquals(emptyList<HomeShelf>(), parseHomeFacet(feed(sections), "music-chip")?.shelves)
    }

    @Test
    fun premiumFeedGroupsBaselineSectionsAndDeduplicatesPlaylistsAroundMalformedSection() {
        fun baseline(id: String) = """
            {"data":{"__typename":"HomeFeedBaselineSectionData","title":{"transformedLabel":"For you"}},
             "sectionItems":{"items":[
                {"uri":"spotify:playlist:$id","content":{"data":{"__typename":"Playlist","name":"$id"}}}
             ]}}
        """.trimIndent()
        val sections = listOf(baseline("first"), """{"data":false}""", baseline("second"), baseline("first"))

        val shelf = SpotifyHome.parse(feed(sections.joinToString(","))).single()
        assertEquals("For you", shelf.title)
        assertTrue(shelf.gathered)
        assertEquals(listOf("spotify:playlist:first", "spotify:playlist:second"), shelf.items.map { it.uri })
    }

    private fun feed(sections: String = "", chips: String = "") =
        """{"data":{"home":{"sectionContainer":{"sections":{"items":[$sections]}},"homeChips":[$chips]}}}"""
}
