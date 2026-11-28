package app.sonar.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playlists the listener has pinned to the top of their library.
 *
 * Kept here rather than on the account: Spotify has no such concept, so there
 * is nothing to send and nothing to read back. That also means the pins belong
 * to this device, which is the honest behaviour for something the service does
 * not know about.
 *
 * New pins are appended to the listener's shelf order.
 */
class PinnedPlaylistStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(load())

    /** URIs in shelf order. */
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    fun isPinned(uri: String): Boolean = uri in _pinned.value

    @Synchronized
    fun toggle(uri: String) {
        val current = _pinned.value
        val updated = if (uri in current) {
            current - uri
        } else {
            (current + uri).takeLast(LIMIT)
        }
        save(updated)
    }

    @Synchronized
    fun move(visibleUris: Set<String>, from: String, to: String) {
        val current = _pinned.value
        val updated = current.movePinnedPlaylist(visibleUris, from, to)
        if (updated != current) save(updated)
    }

    private fun save(updated: List<String>) {
        _pinned.value = updated
        prefs.edit().putString(KEY_PINNED, updated.joinToString(SEPARATOR)).apply()
    }

    private fun load(): List<String> =
        prefs.getString(KEY_PINNED, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private companion object {
        const val FILE_NAME = "sonar_pinned_playlists"
        const val KEY_PINNED = "pinned"

        /** A URI contains no whitespace, so this can never appear inside one. */
        const val SEPARATOR = "\n"

        /** Enough that nobody meets it; a guard against a runaway list. */
        const val LIMIT = 50
    }
}

internal fun List<String>.movePinnedPlaylist(visibleUris: Set<String>, from: String, to: String): List<String> {
    val visible = filter { it in visibleUris }.toMutableList()
    val source = visible.indexOf(from)
    val target = visible.indexOf(to)
    if (source < 0 || target < 0 || source == target) return this
    visible.add(target, visible.removeAt(source))
    val reordered = visible.iterator()
    // Keep pins from other library snapshots in their original slots.
    return map { if (it in visibleUris) reordered.next() else it }
}

/**
 * Lifts the pinned playlists to the front, whatever the sort underneath.
 *
 * The sort the listener chose still applies: this only says which rows come
 * first, and pinning is an instruction the sort has no business overruling.
 */
fun List<CatalogPlaylist>.withPinnedFirst(pinned: List<String>): List<CatalogPlaylist> {
    if (pinned.isEmpty()) return this
    val rank = pinned.withIndex().associate { (index, uri) -> uri to index }
    val (top, rest) = partition { rank.containsKey(it.uri) }
    return top.sortedBy { rank.getValue(it.uri) } + rest
}
