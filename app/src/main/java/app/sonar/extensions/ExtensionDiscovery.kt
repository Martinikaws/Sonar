package app.sonar.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ExtensionListing(
    val entry: CatalogExtension,
    val source: String,
    val origin: String?,
    val packageUrl: HttpUrl?,
    val blocks: List<ExtensionBlock> = emptyList(),
    val ratingsRepo: String? = null,
    val rating: ExtensionRating? = null,
    val index: HttpUrl? = null,
) {
    fun isBlockedBy(officialBlocks: List<ExtensionBlock>): Boolean =
        (officialBlocks + blocks).any { it.matches(entry.id, entry.sha256) }

    fun isUpdate(installed: ExtensionStore.Installed): Boolean =
        origin != null && origin == installed.origin &&
            installed.manifest?.let { it.id == entry.id && it.versionCode < entry.versionCode } == true
}

internal data class ExtensionBrowseResult(val listings: List<ExtensionListing>, val incomplete: Boolean)

internal class ExtensionDiscovery {
    suspend fun browse(installed: Collection<ExtensionStore.Installed>): ExtensionBrowseResult = coroutineScope {
        val official = async(Dispatchers.IO) { attempt { readCatalog(officialIndex) } }
        val searches = ExtensionForge.entries.flatMap { forge ->
            topics.map { topic ->
                async(Dispatchers.IO) {
                    attempt { forge.repositories(requireNotNull(forge.search(topic).body()).decodeToString()) }
                }
            }
        }.awaitAll()
        val found = searches.filterNotNull().flatten().distinctBy { it.source }
        val known = found.mapNotNull { it.identity }.toSet()
        val saved = installed.mapNotNull { it.origin }.filter { '|' in it && it !in known }.distinct()
        val pinned = saved.chunked(8).flatMap { batch ->
            batch.map { origin -> async(Dispatchers.IO) { attempt { readPinnedRepository(origin) } } }.awaitAll()
        }
        val repos = (found + pinned.filterNotNull()).distinctBy { it.source }
        val officialCatalog = official.await()
        val officialListings = officialCatalog?.listings(officialIndex, null).orEmpty()
        val githubRepos = repos.filter { it.forge == ExtensionForge.GITHUB }.map { it.path.lowercase() }.toSet() +
            officialListings.mapNotNull { it.ratingsRepo }
        val ratings = async(Dispatchers.IO) {
            if (githubRepos.isEmpty()) emptyMap() else attempt {
                parseExtensionRatings(requireNotNull(ratingsUrl.body()).decodeToString(), githubRepos)
            }
        }
        val blocks = async(Dispatchers.IO) { attempt { readBlocks() } }
        val discovered = repos.chunked(8).flatMap { batch ->
            batch.map { repo -> async(Dispatchers.IO) { attempt { readRepository(repo) } } }.awaitAll()
        }
        val votes = ratings.await()
        val blocked = blocks.await()
        val officialBlocks = blocked.orEmpty() + officialCatalog?.blocked.orEmpty()
        val listings = (officialListings + discovered.filterNotNull().flatten())
            .filterNot { it.isBlockedBy(officialBlocks) }
            .map { listing -> listing.copy(rating = listing.ratingsRepo?.let { votes?.get(it) }) }
            .distinctBy { it.source to it.entry.id }
            .sortedWith(compareByDescending<ExtensionListing> { it.rating?.score ?: 0.7 }
                .thenBy { it.entry.name.lowercase() })
        ExtensionBrowseResult(
            if (blocked == null || officialCatalog == null) listings.map { it.copy(packageUrl = null) } else listings,
            officialCatalog == null || votes == null || blocked == null ||
                searches.any { it == null } || pinned.any { it == null } || discovered.any { it == null },
        )
    }

    fun install(store: ExtensionStore, listing: ExtensionListing, update: Boolean) {
        val entry = listing.entry
        val origin = requireNotNull(listing.origin)
        val url = requireNotNull(listing.packageUrl)
        if ('|' in origin) requireNotNull(readPinnedRepository(origin))
        val bytes = requireNotNull(url.body(ExtensionPackage.MAX_ZIP))
        val officialCatalog = requireNotNull(readCatalog(officialIndex))
        val localBlocks = listing.index?.let { index ->
            val catalog = if (index == officialIndex) officialCatalog else requireNotNull(readCatalog(index))
            require(entry in catalog.extensions)
            if (index == officialIndex) emptyList() else catalog.blocked
        }.orEmpty()
        require((readBlocks() + officialCatalog.blocked + localBlocks).none { it.matches(entry.id, entry.sha256) })
        store.installFromCatalog(bytes, entry, origin, update)
    }

    fun installUrl(store: ExtensionStore, url: HttpUrl, sha256: String) {
        val bytes = requireNotNull(url.body(ExtensionPackage.MAX_ZIP))
        val manifest = ExtensionPackage.read(bytes, sha256).manifest
        val officialCatalog = requireNotNull(readCatalog(officialIndex))
        require((readBlocks() + officialCatalog.blocked).none { it.matches(manifest.id, sha256) })
        store.install(bytes, sha256)
    }

    private fun readPinnedRepository(origin: String): ExtensionRepository? {
        val forge = ExtensionForge.entries.first { it.host == origin.substringBefore(':') }
        return forge.repositoryUrl(origin.substringAfter('|')).body()
            ?.let { forge.repository(it.decodeToString()) }
            ?.takeIf { it.identity == origin }
    }

    private fun readRepository(repo: ExtensionRepository): List<ExtensionListing> {
        val index = repo.file("index.json")
        readCatalog(index)?.let { return it.listings(index, repo) }
        val body = repo.file("manifest.json").body() ?: return emptyList()
        val manifest = attempt { ExtensionManifest.parse(body.decodeToString()) } ?: return emptyList()
        val entry = CatalogExtension.from(manifest)
        val github = repo.forge == ExtensionForge.GITHUB
        val asset = if (github) attempt {
            "https://api.github.com/repos/${repo.path}/releases/latest".toHttpUrl().body()?.let {
                catalogJson.decodeFromString<Release>(it.decodeToString()).assets
                    .firstOrNull { asset -> asset.name == "extension.zip" }
            }
        } else null
        val digest = asset?.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:").orEmpty()
        val url = asset?.url?.let { allowedUrl(index.resolve(it)) }
        return listOf(ExtensionListing(
            entry.copy(sha256 = digest), repo.source, repo.identity,
            url?.takeIf { repo.identity != null && ExtensionPackage.validDigest(digest) },
            ratingsRepo = repo.path.lowercase().takeIf { github },
        ))
    }

    private fun readCatalog(index: HttpUrl): ExtensionCatalog? =
        index.body()?.let { ExtensionCatalog.parse(it.decodeToString()) }

    private fun ExtensionCatalog.listings(index: HttpUrl, repo: ExtensionRepository?): List<ExtensionListing> {
        val source = repo?.source ?: index.toString()
        val origin = if (repo == null) source else repo.identity
        return extensions.mapNotNull { entry ->
            if (!ExtensionManifest.validId(entry.id)) return@mapNotNull null
            val rating = if (repo == null) entry.repo?.lowercase()
                else repo.path.lowercase().takeIf { repo.forge == ExtensionForge.GITHUB }
            val url = allowedUrl(index.resolve(entry.url)).takeIf {
                origin != null && entry.apiVersion == 1 && entry.versionCode > 0 &&
                    entry.url.isNotBlank() && ExtensionPackage.validDigest(entry.sha256)
            }
            ExtensionListing(entry, source, origin, url, blocked, rating, index = index)
        }
    }

    private fun readBlocks(): List<ExtensionBlock> {
        val body = requireNotNull(blockedUrl.body())
        return catalogJson.decodeFromString<ExtensionBlockList>(body.decodeToString()).blocked
    }

    private fun HttpUrl.body(limit: Int = 1024 * 1024): ByteArray? {
        var target = this
        repeat(5) {
            require(allowedUrl(target) != null)
            val request = Request.Builder().url(target)
                .header("User-Agent", "SONAR-Extensions").build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> return null
                    301, 302, 303, 307, 308 -> target =
                        requireNotNull(response.header("Location")?.let(target::resolve))
                    else -> {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        return requireNotNull(response.body).byteStream().readLimited(limit)
                    }
                }
            }
        }
        throw IOException("Too many redirects")
    }

    @Serializable
    private data class Release(val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(
        val name: String,
        val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        private val topics = listOf("myspoti-extension", "sonar-extension")
        private val officialIndex =
            "https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/index.json".toHttpUrl()
        private val blockedUrl = requireNotNull(officialIndex.resolve("blocked.json"))
        private val ratingsUrl = "https://api.github.com/search/issues".toHttpUrl().newBuilder()
            .addQueryParameter("q", "label:myspoti-ratings is:issue is:open")
            .addQueryParameter("per_page", "100").build()
        private val hosts = setOf(
            "api.github.com", "github.com", "raw.githubusercontent.com", "release-assets.githubusercontent.com",
            "gitlab.com", "codeberg.org",
        )
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build()

        fun allowedUrl(url: HttpUrl?): HttpUrl? = url?.takeIf {
            it.isHttps && it.host in hosts && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
        }
    }
}

private inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: NoSuchElementException) {
    null
}
