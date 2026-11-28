package app.sonar.extensions

import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCatalogTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/extensions/$name.json")).readText()

    // TODO: Record non-empty GitHub, Codeberg and GitHub issues API responses from a network-enabled run.
    private fun sample(name: String): String =
        catalogJson.parseToJsonElement(fixture("discovery-samples")).jsonObject.getValue(name).toString()

    @Test
    fun discoversPublicRepositoriesOnAllForges() {
        val github = ExtensionForge.GITHUB.repositories(sample("github")).single()
        // Fields captured from https://gitlab.com/api/v4/projects?per_page=1.
        val gitlab = ExtensionForge.GITLAB.repositories(fixture("gitlab-projects")).single()
        val codeberg = ExtensionForge.CODEBERG.repositories(sample("codeberg")).single()
        assertEquals("api.github.com:1:2|dev/sample", github.identity)
        assertEquals("https://raw.githubusercontent.com/dev/sample/main/index.json", github.file("index.json").toString())
        assertEquals("gitlab.com:89062838:135853779|rachel.green.r/ecoscan-77498374", gitlab.identity)
        assertEquals("https://gitlab.com/rachel.green.r/ecoscan-77498374/-/raw/main/index.json", gitlab.file("index.json").toString())
        assertEquals("https://codeberg.org/dev/sample/raw/branch/main/index.json", codeberg.file("index.json").toString())
        assertEquals("https://gitlab.com/api/v4/projects/group%2Fsubgroup%2Fsample",
            ExtensionForge.GITLAB.repositoryUrl("group/subgroup/sample").toString())
        for (forge in ExtensionForge.entries) {
            for (topic in listOf("sonar-extension", "myspoti-extension")) {
                assertTrue(forge.search(topic).toString().contains(topic))
            }
        }
    }

    @Test
    fun acceptsRatingsFromTheRepositoryOwner() {
        val ratings = parseExtensionRatings(sample("ratings"), setOf("dev/sample"))
        assertEquals(mapOf("dev/sample" to ExtensionRating(14, 1)), ratings)
        assertEquals(4.5, ratings.getValue("dev/sample").stars, 0.001)
    }

    @Test
    fun readsRecordedIndexAndBlockListAndMatchesPackageBlocks() {
        assertTrue(ExtensionCatalog.parse(fixture("official-index")).extensions.isEmpty())
        assertTrue(catalogJson.decodeFromString<ExtensionBlockList>(fixture("official-blocked")).blocked.isEmpty())
        val index = ExtensionCatalog.parse(sample("index"))
        val entry = index.extensions.single()
        assertTrue(index.blocked.any { it.matches(entry.id, entry.sha256.uppercase()) })
        assertFalse(index.blocked.any { it.matches(entry.id, "b".repeat(64)) })
        assertTrue(index.blocked.any { it.matches("io.github.blocked", "") })
    }

    @Test
    fun rejectsUnexpectedPackageUrls() {
        assertNull(ExtensionDiscovery.allowedUrl("http://github.com/pkg.zip".toHttpUrl()))
        assertNull(ExtensionDiscovery.allowedUrl("https://github.com.example.org/pkg.zip".toHttpUrl()))
        assertNull(ExtensionDiscovery.allowedUrl("https://github.com:444/pkg.zip".toHttpUrl()))
        assertNull(ExtensionDiscovery.allowedUrl("https://user@github.com/pkg.zip".toHttpUrl()))
        val approved = "https://codeberg.org/dev/sample/pkg.zip".toHttpUrl()
        assertEquals(approved, ExtensionDiscovery.allowedUrl(approved))
    }

    @Test
    fun appliesBlocksFromAnOfficialIndexWithNoListingsToThirdPartyPackages() {
        val index = ExtensionCatalog.parse("""{"blocked":[{"id":"io.github.example"}]}""")
        val entry = ExtensionCatalog.parse(sample("index")).extensions.single()
        val listing = ExtensionListing(entry, "third-party", null, null)

        assertTrue(index.extensions.isEmpty())
        assertTrue(listing.isBlockedBy(index.blocked))
        assertFalse(listing.copy(entry = entry.copy(id = "io.github.other")).isBlockedBy(index.blocked))
    }

    @Test
    fun updateRequiresNewerVersionAndMatchingOrigin() {
        val entry = ExtensionCatalog.parse(sample("index")).extensions.single()
        val listing = ExtensionListing(entry, "source", "pinned", null)
        val older = ExtensionStore.Installed(entry.id, entry.preview().copy(versionCode = 1), "pinned")
        assertTrue(listing.isUpdate(older))
        assertFalse(listing.isUpdate(older.copy(origin = "other")))
        assertFalse(listing.isUpdate(older.copy(origin = null)))
        assertFalse(listing.isUpdate(older.copy(manifest = entry.preview())))
    }
}
