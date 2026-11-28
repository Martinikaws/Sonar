package app.sonar.extensions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal val catalogJson = Json { ignoreUnknownKeys = true }

internal enum class ExtensionForge(val host: String) {
    GITHUB("api.github.com"),
    GITLAB("gitlab.com"),
    CODEBERG("codeberg.org");

    fun search(topic: String): HttpUrl {
        val url = HttpUrl.Builder().scheme("https").host(host)
        when (this) {
            GITHUB -> url.addPathSegments("search/repositories")
                .addQueryParameter("q", "topic:$topic fork:true archived:false")
                .addQueryParameter("sort", "updated").addQueryParameter("per_page", "100")
            GITLAB -> url.addPathSegments("api/v4/projects")
                .addQueryParameter("topic", topic).addQueryParameter("visibility", "public")
                .addQueryParameter("order_by", "last_activity_at").addQueryParameter("sort", "desc")
                .addQueryParameter("archived", "false").addQueryParameter("per_page", "100")
            CODEBERG -> url.addPathSegments("api/v1/repos/search")
                .addQueryParameter("q", topic).addQueryParameter("topic", "true")
                .addQueryParameter("sort", "updated").addQueryParameter("order", "desc")
                .addQueryParameter("archived", "false").addQueryParameter("limit", "50")
        }
        return url.build()
    }

    fun repositories(body: String): List<ExtensionRepository> {
        val rows = when (this) {
            GITHUB -> catalogJson.decodeFromString<RepositorySearch>(body).items
            GITLAB -> catalogJson.decodeFromString<List<RepositoryRow>>(body)
            CODEBERG -> catalogJson.decodeFromString<RepositorySearch>(body).data
        }
        return rows.mapNotNull { repository(it) }
    }

    fun repository(body: String): ExtensionRepository? =
        repository(catalogJson.decodeFromString<RepositoryRow>(body))

    fun repositoryUrl(path: String): HttpUrl {
        require(validPath(path))
        val url = HttpUrl.Builder().scheme("https").host(host)
        when (this) {
            GITHUB -> url.addPathSegment("repos").addPathSegments(path)
            GITLAB -> url.addPathSegments("api/v4/projects").addPathSegment(path)
            CODEBERG -> url.addPathSegments("api/v1/repos").addPathSegments(path)
        }
        return url.build()
    }

    private fun validPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.size >= 2 && (this == GITLAB || parts.size == 2) &&
            parts.all { safeSegment.matches(it) }
    }

    private fun repository(row: RepositoryRow): ExtensionRepository? {
        val path = row.fullName ?: row.namespacePath ?: return null
        if (row.archived || row.empty || !validPath(path)) return null
        val branch = row.branch?.takeIf { it.isNotBlank() } ?: return null
        if (branch.split('/').any { !safeSegment.matches(it) }) return null
        val owner = if (this == GITLAB) row.namespace else row.owner
        val identity = owner?.id?.takeIf { it > 0 }?.let {
            row.id.takeIf { id -> id > 0 }?.let { id -> "$host:$id:$it|$path" }
        }
        return ExtensionRepository(this, path, branch, identity)
    }
}

private val safeSegment = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")

@Serializable
private data class RepositorySearch(
    val items: List<RepositoryRow> = emptyList(),
    val data: List<RepositoryRow> = emptyList(),
)

@Serializable
private data class RepositoryRow(
    val id: Long = 0,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("path_with_namespace") val namespacePath: String? = null,
    @SerialName("default_branch") val branch: String? = null,
    val owner: RepositoryOwner? = null,
    val namespace: RepositoryOwner? = null,
    val archived: Boolean = false,
    val empty: Boolean = false,
)

@Serializable
private data class RepositoryOwner(val id: Long = 0, val login: String = "")

internal data class ExtensionRepository(
    val forge: ExtensionForge,
    val path: String,
    val branch: String,
    val identity: String?,
) {
    val source: String get() = "${forge.host}/$path"

    fun file(name: String): HttpUrl = when (forge) {
        ExtensionForge.GITHUB -> "https://raw.githubusercontent.com/$path".toHttpUrl().newBuilder()
            .addPathSegment(branch).addPathSegment(name).build()
        ExtensionForge.GITLAB -> "https://gitlab.com/$path/-/raw".toHttpUrl().newBuilder()
            .addPathSegments(branch).addPathSegment(name).build()
        ExtensionForge.CODEBERG -> "https://codeberg.org/$path/raw/branch".toHttpUrl().newBuilder()
            .addPathSegments(branch).addPathSegment(name).build()
    }
}

@Serializable
internal data class ExtensionCatalog(
    val format: Int = 1,
    val extensions: List<CatalogExtension> = emptyList(),
    val blocked: List<ExtensionBlock> = emptyList(),
) {
    companion object {
        fun parse(body: String): ExtensionCatalog =
            catalogJson.decodeFromString<ExtensionCatalog>(body).also { require(it.format == 1) }
    }
}

@Serializable
internal data class CatalogExtension(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val apiVersion: Int = 1,
    val description: String = "",
    val url: String = "",
    val sha256: String = "",
    val repo: String? = null,
) {
    fun preview() = ExtensionManifest(id, name, version, author, versionCode, apiVersion, description)

    companion object {
        fun from(manifest: ExtensionManifest) = with(manifest) {
            CatalogExtension(id, name, version, versionCode, author, apiVersion, description)
        }
    }
}

@Serializable
internal data class ExtensionBlock(val id: String, val sha256: List<String> = emptyList()) {
    fun matches(id: String, digest: String): Boolean =
        this.id == id && (sha256.isEmpty() || sha256.any { it.equals(digest, ignoreCase = true) })
}

@Serializable
internal data class ExtensionBlockList(val blocked: List<ExtensionBlock>)

@Serializable
internal data class ExtensionRating(
    @SerialName("+1") val up: Int = 0,
    @SerialName("-1") val down: Int = 0,
) {
    val score: Double get() = (up + 3.5) / (up.toLong() + down + 5)
    val stars: Double get() = 1 + 4 * score
}

@Serializable
private data class RatingIssue(
    val user: RepositoryOwner? = null,
    @SerialName("repository_url") val repositoryUrl: String,
    val reactions: ExtensionRating = ExtensionRating(),
)

@Serializable
private data class RatingSearch(val items: List<RatingIssue>)

internal fun parseExtensionRatings(body: String, repositories: Set<String>): Map<String, ExtensionRating> =
    buildMap {
        for (issue in catalogJson.decodeFromString<RatingSearch>(body).items) {
            val repo = issue.repositoryUrl.removePrefix("https://api.github.com/repos/").lowercase()
            if (repo in repositories && repo !in this &&
                repo.substringBefore('/').equals(issue.user?.login, ignoreCase = true) &&
                issue.reactions.up >= 0 && issue.reactions.down >= 0
            ) put(repo, issue.reactions)
        }
    }
