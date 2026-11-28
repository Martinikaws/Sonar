package app.sonar.home

import app.sonar.data.HomeShelf
import app.sonar.data.SpotifyHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class HomeFacet(val shelves: List<HomeShelf>, val children: List<HomeSubFilter>)
internal data class HomeSubFilter(val id: String, val label: String)

internal suspend fun parseHomeFacet(json: String, parent: String): HomeFacet? = withContext(Dispatchers.Default) {
    val home = runCatching {
        JSONObject(json).getJSONObject("data").getJSONObject("home").also {
            it.getJSONObject("sectionContainer").getJSONObject("sections").getJSONArray("items")
        }
    }.getOrNull() ?: return@withContext null
    val chips = home.optJSONArray("homeChips")
    val root = (0 until (chips?.length() ?: 0)).firstNotNullOfOrNull { index ->
        chips?.optJSONObject(index)?.takeIf { it.optString("id") == parent }
    }
    val children = root?.optJSONArray("subChips")
    val subFilters = (0 until (children?.length() ?: 0)).mapNotNull { index ->
        val chip = children?.optJSONObject(index) ?: return@mapNotNull null
        val id = chip.optString("id").takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: return@mapNotNull null
        val label = chip.optJSONObject("label")?.optString("transformedLabel").orEmpty()
        HomeSubFilter(id, label).takeIf { label.isNotBlank() }
    }.distinctBy { it.id }
    HomeFacet(SpotifyHome.parse(json, podcasts = parent == "podcasts-chip"), subFilters)
}
