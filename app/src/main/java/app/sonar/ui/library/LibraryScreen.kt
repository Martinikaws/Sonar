package app.sonar.ui.library

import app.sonar.ui.components.glassMenuTrigger
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.annotation.StringRes
import app.sonar.library.LibraryOrganization
import app.sonar.library.LibraryOrganizationControls
import app.sonar.library.LibraryOrganizationStore
import app.sonar.library.LibrarySort
import app.sonar.library.atLibraryLevel
import app.sonar.library.canOrganize
import app.sonar.library.inCreatorOrder
import app.sonar.library.inCustomLibraryOrder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.sonar.ui.components.horizontalEdgeFade
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sonar.ui.glass.backdrop.Backdrop
import app.sonar.R
import app.sonar.ui.components.SonarStackedLogo
import app.sonar.data.CatalogPlaylist
import app.sonar.data.sortedByRecentlyOpened
import app.sonar.data.withLocalFilesFirst
import app.sonar.data.withLikedSecond
import app.sonar.data.withPinnedFirst
import app.sonar.ui.MainViewModel
import app.sonar.ui.components.Artwork
import app.sonar.ui.glass.LiquidButton
import app.sonar.ui.glass.pressable
import app.sonar.ui.player.GlassFilm
import app.sonar.ui.theme.Ink
import app.sonar.ui.theme.InkDim
import app.sonar.ui.theme.softShadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import app.sonar.ui.components.CHOICE_MENU_WIDTH
import app.sonar.ui.components.GlassChoiceMenu
import app.sonar.ui.glass.backdrop.backdrops.layerBackdrop
import app.sonar.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import app.sonar.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import app.sonar.ui.glass.backdrop.backdrops.rememberBackdropFreeze
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDownUp
import com.composables.icons.lucide.ChartNoAxesColumn
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Radio

/** How the playlists are arranged. */
private enum class Layout { GRID, LIST }

/** What the library is sorted by. */
private enum class Order(val key: String, @StringRes val label: Int) {
    RECENT("recent", R.string.recently_opened),
    NAME("name", R.string.name),
    ADDED("added", R.string.spotify_order),
    CUSTOM(LibrarySort.CUSTOM.key, R.string.library_sort_custom),
    CREATOR(LibrarySort.CREATOR.key, R.string.library_sort_creator),
}

/**
 * Which kind of thing the library is showing.
 *
 * The chips used to be the sort, which put the three least-used controls in the
 * most prominent place on the screen and left no way at all to say "only
 * albums". The sort is a menu now; the chips answer the question a library is
 * actually asked, which is what am I looking at.
 */
private enum class Filter(@StringRes val label: Int, @StringRes val count: Int) {
    ALL(R.string.library_all, R.string.item_count),
    PLAYLISTS(R.string.playlists, R.string.playlist_count),
    ARTISTS(R.string.artists, R.string.artist_count),
    ALBUMS(R.string.albums, R.string.album_count),
    PODCASTS(R.string.podcasts, R.string.podcast_count),
}

/**
 * Every playlist on the account.
 *
 * Rewritten alongside the home page and for the same reason: this was a plain
 * Material list with a divider between every row, which is the one thing on
 * screen that cannot be made of glass. It is now the same material as the rest —
 * and, being the screen you come to when you know what you are looking for, it
 * gets the controls the home page has no room for: a grid, and a sort.
 */
@Composable
fun LibraryScreen(
    state: MainViewModel.UiState,
    contentPadding: PaddingValues,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    /** Off the network by choice, from the wait; see ConnectingState. */
    onGoOffline: () -> Unit = {},
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    /** URIs most recently opened first; see PlaylistOrderStore. */
    playlistOrder: List<String>,
    /** URIs the listener pinned to the top; see PinnedPlaylistStore. */
    pinned: List<String> = emptyList(),
    organizationStore: LibraryOrganizationStore,
    organization: LibraryOrganization,
    /** False when the source cannot be written to; see MusicBackend. */
    canEdit: Boolean = false,
    onCreatePlaylist: () -> Unit = {},
    /** Long press: rename and delete live in a sheet, like a track's actions. */
    onPlaylistMenu: (CatalogPlaylist) -> Unit = {},
    /** The artists the account follows; empty leaves the shelf out entirely. */
    artists: List<app.sonar.data.SearchItem> = emptyList(),
    onOpenArtist: (app.sonar.data.SearchItem) -> Unit = {},
    /** The albums the account has saved; see MainViewModel.savedAlbums. */
    albums: List<CatalogPlaylist> = emptyList(),
    /** The podcasts the account follows; none leaves their chip out. */
    podcasts: List<CatalogPlaylist> = emptyList(),
    /** Tries the servers again from the offline banner; null hides the button. */
    onRetryOnline: (suspend () -> Boolean)? = null,
    /**
     * Suggest signing in to the source's account: the library is the page an
     * account fills, so it is the first place its absence shows.
     */
    signInHint: Boolean = false,
    onSignIn: () -> Unit = {},
    /** Signed out of Spotify: the other source, and the settings; see SignedOutOptions. */
    onUseYouTube: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenRadio: (() -> Unit)? = null,
    listeningEvents: List<app.sonar.data.ListeningEvent>? = null,
    /** Whether those were played from Spotify, rather than YouTube Music. */
    listeningFromSpotify: Boolean = true,
    backdrop: Backdrop,
) {
    when (state) {
        // The same page the home tab shows signed out, mark and all: the two
        // are one state reached from two tabs, and a plain title here made
        // them read as two different apps.
        MainViewModel.UiState.LoggedOut -> Centered(
            Modifier.verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            SonarStackedLogo(height = 104.dp)
            Text(
                stringResource(R.string.unofficial_client),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction(stringResource(R.string.log_in_with_spotify), backdrop, onLogIn)
            app.sonar.ui.components.SignedOutOptions(onUseYouTube, onOpenSettings)
        }

        MainViewModel.UiState.Connecting,
        MainViewModel.UiState.Loading,
        -> app.sonar.ui.components.ConnectingState(backdrop, onGoOffline)

        is MainViewModel.UiState.Failed -> Centered {
            Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction(stringResource(R.string.retry), backdrop, onRetry)
            GlassAction(stringResource(R.string.log_out), backdrop, onLogOut)
        }

        is MainViewModel.UiState.Ready -> {
            // Both are the listener's own decision about their library rather
            // than a mode of this screen, so they are read from where they were
            // left and written as they change; see LibraryViewStore.
            val appContext = LocalContext.current.applicationContext
            val view = remember(appContext) {
                (appContext as app.sonar.SonarApplication).libraryView
            }
            var layout by remember { mutableStateOf(if (view.grid) Layout.GRID else Layout.LIST) }
            var order by remember {
                mutableStateOf(Order.entries.firstOrNull { it.key == view.order } ?: Order.RECENT)
            }
            var filter by remember { mutableStateOf(Filter.ALL) }
            var openFolderId by remember { mutableStateOf<String?>(null) }
            val folder = organization.folders.firstOrNull { it.id == openFolderId }
            val folderId = folder?.id
            var sortOpen by remember { mutableStateOf(false) }
            var recapOpen by remember { mutableStateOf(false) }
            // Recorded so the menu has the tiles behind it to blur, rather than
            // the page colour: over a flat fill glass has nothing to bend and
            // comes out looking like a hole. Safe to record — the menu is drawn
            // outside the grid, so nothing in this layer samples it.
            val listBackdrop = rememberLayerBackdrop()
            val listBackdropFreeze = rememberBackdropFreeze()
            val gridState = rememberLazyGridState()
            val listState = rememberLazyListState()
            var descending by remember { mutableStateOf(view.descending) }
            val onOrderChosen: (Order) -> Unit = {
                order = it
                view.order = it.key
            }
            var sortAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
            val sortTrigger = app.sonar.ui.components.rememberGlassMenuTrigger()

            val artistItems = remember(artists) {
                artists.map { artist ->
                    CatalogPlaylist(
                        uri = artist.uri,
                        name = artist.title,
                        artworkUrl = artist.artworkUrl,
                    )
                }
            }

            // What the chips are asking for, before any sorting.
            val shown = remember(state.playlists, albums, artistItems, podcasts, filter) {
                when (filter) {
                    Filter.ALL -> (state.playlists + albums + podcasts).distinctBy { it.uri }
                    Filter.PODCASTS -> podcasts.distinctBy { it.uri }
                    Filter.PLAYLISTS -> state.playlists.distinctBy { it.uri }
                    Filter.ALBUMS -> albums.distinctBy { it.uri }
                    Filter.ARTISTS -> artistItems.distinctBy { it.uri }
                }
            }

            // What the field at the top of the list is asking for, on top of the
            // chips: the same act as the field inside a record, narrowing what
            // is already here rather than fetching anything.
            var query by remember { mutableStateOf("") }
            val movableUris = remember(state.playlists, pinned) {
                state.playlists.filter { it.canOrganize(pinned) }.mapTo(mutableSetOf()) { it.uri }
            }
            val matching = remember(shown, query, organization, folderId, movableUris) {
                val atLevel = shown.atLibraryLevel(organization, folderId, movableUris)
                if (query.isBlank()) atLevel
                else atLevel.filter { it.name.contains(query.trim(), ignoreCase = true) }
            }
            val customOrder = organization.customOrder(folderId)
            val movableAtLevel = remember(state.playlists, organization, folderId, movableUris) {
                state.playlists
                    .filter { it.uri in movableUris && organization.atLevel(it.uri, folderId) }
                    .distinctBy { it.uri }
                    .inCustomLibraryOrder(customOrder) { it.uri }
            }
            val showOrganization = filter == Filter.ALL || filter == Filter.PLAYLISTS
            val visibleFolders = if (showOrganization && folder == null) {
                organization.folders.filter { it.name.contains(query.trim(), ignoreCase = true) }
            } else emptyList()
            val visibleArtists = if (filter == Filter.ALL && folder == null) {
                artists.filter { it.title.contains(query.trim(), ignoreCase = true) }
            } else emptyList()
            val organizationControls: @Composable () -> Unit = {
                LibraryOrganizationControls(
                    store = organizationStore,
                    folder = folder,
                    query = query,
                    visibleFolders = visibleFolders,
                    movablePlaylists = movableAtLevel,
                    onOpenFolder = {
                        openFolderId = it
                        filter = Filter.PLAYLISTS
                        query = ""
                    },
                    onChooseCustom = { onOrderChosen(Order.CUSTOM) },
                )
            }

            val playlists = remember(matching, playlistOrder, pinned, order, descending, customOrder) {
                when (order) {
                    Order.RECENT -> matching.sortedByRecentlyOpened(playlistOrder)
                    Order.NAME -> matching.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                    // The order the account added them, which is what the
                    // rootlist arrives in.
                    Order.ADDED -> matching
                    Order.CUSTOM -> matching.inCustomLibraryOrder(customOrder) { it.uri }
                    Order.CREATOR -> matching.inCreatorOrder { it.subtitle.orEmpty() }
                }
                    // The direction belongs to the sort rather than beside it:
                    // reversing "recently opened" is "least recently", and a
                    // separate control for that would be a second sort.
                    .let { if (descending && order != Order.CUSTOM) it.reversed() else it }
                    // The phone's own music and what is kept on it outrank
                    // the sort: they are not playlists competing for a place
                    // but fixed shelves of the library, and one nobody has
                    // opened yet would otherwise sit sixtieth among lists.
                    .withLocalFilesFirst()
                    // And pinning outranks even those: it is the one
                    // instruction the listener gave about this list
                    // themselves, and a shelf they never asked for standing
                    // above the lists they chose to keep at the top is the app
                    // overruling them (#25).
                    .withPinnedFirst(pinned)
                    // Once more at the end, because every row below is keyed
                    // on its address and a list that holds one twice does not
                    // draw, it crashes. Spotify can hand the same playlist back
                    // twice, and a paged list can repeat an item across pages.
                    .distinctBy { it.uri }
            }

            val emptyMessage = when {
                playlists.isNotEmpty() || visibleFolders.isNotEmpty() || visibleArtists.isNotEmpty() -> null
                query.isNotBlank() -> R.string.fix2_library_no_results
                folder != null -> R.string.fix2_library_empty_folder
                else -> null
            }

            // The month in music takes the library's place while it is open,
            // and gives it back as it was: the scroll positions and choices
            // above are held outside this.
            AnimatedContent(
                targetState = recapOpen && listeningEvents != null,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "libraryRecap",
            ) { showRecap ->
            if (showRecap) {
                MonthlyListeningPage(
                    events = listeningEvents.orEmpty(),
                    fromSpotify = listeningFromSpotify,
                    backdrop = backdrop,
                    contentPadding = contentPadding,
                    onBack = { recapOpen = false },
                )
            } else {
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // On its side the screen is short, and the heading held still
                // over the list covered half of it: there it scrolls away with
                // the list instead, as the first thing in it.
                val sideways = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                // The order, a disc at the end of the filter chips, the size of
                // the header's buttons: upstream's GlassPullDownButton, which
                // the sort menu grows out of. The order chosen is ticked in it.
                val sortButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    app.sonar.ui.components.GlassPullDownButton(
                        icon = Lucide.ArrowDownUp,
                        semanticLabel = stringResource(R.string.sort) + ": " + stringResource(order.label),
                        onOpen = { sortOpen = true },
                        onAnchor = { sortAnchor = it },
                        backdrop = backdrop,
                        trigger = sortTrigger,
                        ink = Ink,
                        buttonSize = 42.dp,
                        modifier = buttonModifier,
                    )
                }
                val heading: @Composable () -> Unit = {
                    Header(
                        count = playlists.size,
                        layout = layout,
                        order = order,
                        filter = filter,
                        hasPodcasts = podcasts.isNotEmpty(),
                        backdrop = backdrop,
                        topPadding = contentPadding.calculateTopPadding(),
                        onLayout = {
                            layout = it
                            view.grid = it == Layout.GRID
                        },
                        onOrder = { onOrderChosen(it) },
                        onFilter = {
                            filter = it
                            if (it != Filter.PLAYLISTS) openFolderId = null
                        },
                        sort = { sortButton(Modifier.padding(end = 20.dp)) },
                        canEdit = canEdit,
                        onCreatePlaylist = onCreatePlaylist,
                        onOpenRecap = if (listeningEvents != null) ({ recapOpen = true }) else null,
                        onOpenRadio = onOpenRadio,
                    )
                }
                if (!sideways) heading()


                val listPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding(),
                )

                when (layout) {
                    Layout.GRID -> LazyVerticalGrid(
                        state = gridState,
                        // As many as fit: two on a phone held upright, five or six on its side,
                        // where two filled the screen with a pair of tiles taller than it.
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = listPadding,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .topFade { scrolledAway(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
                            .nestedScroll(listBackdropFreeze.connection)
                            .layerBackdrop(
                                listBackdrop,
                                frozen = { listBackdropFreeze.frozen() || gridState.isScrollInProgress },
                            ),
                    ) {
                        if (sideways) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
                                Box(Modifier.bleed(20.dp)) { heading() }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                            app.sonar.ui.components.ListSearchField(
                                query = query,
                                onQuery = { query = it },
                                placeholder = stringResource(R.string.search_library),
                                backdrop = backdrop,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }

                        // Why the library is shorter than usual, at the top
                        // of the library. Nothing at all when there is a
                        // connection; see OfflineNotice.
                        item(span = { GridItemSpan(maxLineSpan) }, key = "offline") {
                            app.sonar.ui.components.OfflineNotice(onRetry = onRetryOnline)
                        }

                        if (signInHint) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "sign-in") {
                                app.sonar.ui.components.SignInHint(onSignIn = onSignIn)
                            }
                        }

                        if (showOrganization) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "organization") {
                                organizationControls()
                            }
                        }

                        if (visibleArtists.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "artists") {
                                ArtistShelf(visibleArtists, onOpenArtist)
                            }
                        }

                        if (emptyMessage != null) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                                LibraryEmptyMessage(stringResource(emptyMessage))
                            }
                        }

                        items(playlists, key = { "pl_${it.uri}" }) { playlist ->
                            GridTile(
                                playlist,
                                pinned = playlist.uri in pinned,
                                onClick = { open(playlist, artists, onOpenPlaylist, onOpenArtist) },
                                onLongClick = if (canEdit || playlist.uri in movableUris) {
                                    { onPlaylistMenu(playlist) }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    Layout.LIST -> LazyColumn(
                        state = listState,
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .topFade { scrolledAway(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
                            .nestedScroll(listBackdropFreeze.connection)
                            .layerBackdrop(
                                listBackdrop,
                                frozen = { listBackdropFreeze.frozen() || listState.isScrollInProgress },
                            ),
                    ) {
                        if (sideways) {
                            item(key = "heading") { Box(Modifier.bleed(20.dp)) { heading() } }
                        }
                        item(key = "search") {
                            app.sonar.ui.components.ListSearchField(
                                query = query,
                                onQuery = { query = it },
                                placeholder = stringResource(R.string.search_library),
                                backdrop = backdrop,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }

                        item(key = "offline") {
                            app.sonar.ui.components.OfflineNotice(onRetry = onRetryOnline)
                        }

                        if (signInHint) {
                            item(key = "sign-in") {
                                app.sonar.ui.components.SignInHint(
                                    onSignIn = onSignIn,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }

                        if (showOrganization) {
                            item(key = "organization") { organizationControls() }
                        }

                        if (visibleArtists.isNotEmpty()) {
                            item(key = "artists") { ArtistShelf(visibleArtists, onOpenArtist) }
                        }

                        if (emptyMessage != null) {
                            item(key = "empty") { LibraryEmptyMessage(stringResource(emptyMessage)) }
                        }

                        items(playlists, key = { "pl_${it.uri}" }) { playlist ->
                            ListRow(
                                playlist,
                                pinned = playlist.uri in pinned,
                                onClick = { open(playlist, artists, onOpenPlaylist, onOpenArtist) },
                                onLongClick = if (canEdit || playlist.uri in movableUris) {
                                    { onPlaylistMenu(playlist) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }


            // The app's own menu rather than Material's card: see GlassMenu.
            // The same one the track sort on a playlist page opens, for the
            // same reason — a menu that arrives from another design system is
            // the one surface on screen that says so.
            GlassChoiceMenu(
                visible = sortOpen,
                anchor = sortAnchor,
                backdrop = rememberCombinedBackdrop(backdrop, listBackdrop),
                onDismiss = { sortOpen = false },
                trigger = sortTrigger,
                // "Aperte di recente" and its tick do not fit in 200.
                width = 240.dp,
            ) {
                Order.entries.forEach { entry ->
                    app.sonar.ui.components.GlassMenuItem(
                        stringResource(entry.label),
                        selected = entry == order,
                        onClick = {
                            sortOpen = false
                            onOrderChosen(entry)
                        },
                    )
                }
                if (order != Order.CUSTOM) {
                    app.sonar.ui.components.GlassMenuDivider()
                    app.sonar.ui.components.GlassMenuItem(
                        stringResource(R.string.sort_descending),
                        checked = descending,
                        onClick = {
                            descending = !descending
                            view.descending = descending
                        },
                    )
                }
            }
            }
            }
            }
        }
    }
}

@Composable
private fun LibraryEmptyMessage(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = InkDim,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Where a menu hanging off the sort button's right edge goes. */
/**
 * Opens whatever the tile stands for.
 *
 * The grid holds playlists, albums and, under the artists chip, artists. The
 * first two are track lists and open the same way; an artist is a page of its
 * own, and the only thing that knows which is which is the URI.
 */
private fun open(
    item: CatalogPlaylist,
    artists: List<app.sonar.data.SearchItem>,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    onOpenArtist: (app.sonar.data.SearchItem) -> Unit,
) {
    val artist = artists.firstOrNull { it.uri == item.uri }
    if (artist != null) onOpenArtist(artist) else onOpenPlaylist(item)
}

@Composable
private fun Header(
    count: Int,
    layout: Layout,
    order: Order,
    filter: Filter,
    /** Whether the podcast chip has anything under it. */
    hasPodcasts: Boolean = false,
    backdrop: Backdrop,
    topPadding: Dp,
    onLayout: (Layout) -> Unit,
    onOrder: (Order) -> Unit,
    onFilter: (Filter) -> Unit,
    /** The sort, at the end of the chips' row. */
    sort: @Composable () -> Unit,
    canEdit: Boolean,
    onCreatePlaylist: () -> Unit,
    onOpenRecap: (() -> Unit)? = null,
    onOpenRadio: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        // Laid out as every main tab's heading is (see TabHeader): the title
        // in the wordmark's letters where the wordmark stands on the home
        // page, the count over it where the account is there.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                // Counting whatever the chips are showing, and saying so: the
                // same number labelled "playlists" under the albums chip is a
                // line that contradicts the screen it sits on.
                Text(
                    stringResource(filter.count, count),
                    style = MaterialTheme.typography.labelLarge,
                    color = InkDim,
                )
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .height(44.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    app.sonar.ui.components.SonarTitle(
                        stringResource(R.string.library),
                        height = app.sonar.ui.components.TITLE_HEIGHT,
                        modifier = Modifier.offset(y = -app.sonar.ui.components.TITLE_HEIGHT * 0.15f),
                    )
                }
            }

            // Upstream's blend group: a pressed button swells into its
            // neighbour and the two melt like drops.
            app.sonar.ui.glass.GlassBlendGroup(backdrop = backdrop) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenRadio != null) {
                        LiquidButton(
                            onClick = onOpenRadio, backdrop = backdrop,
                            modifier = Modifier.padding(end = 8.dp).size(42.dp),
                            contentHeight = 42.dp, contentPadding = 0.dp,
                        ) {
                            Icon(Lucide.Radio,
                                contentDescription = stringResource(R.string.radio_title),
                                tint = Ink, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (onOpenRecap != null) {
                        LiquidButton(
                            onClick = onOpenRecap, backdrop = backdrop,
                            modifier = Modifier.padding(end = 8.dp).size(42.dp),
                            contentHeight = 42.dp, contentPadding = 0.dp,
                        ) {
                            Icon(Lucide.ChartNoAxesColumn,
                                contentDescription = stringResource(R.string.recap_title),
                                tint = Ink, modifier = Modifier.size(20.dp))
                        }
                    }

                    // Only where a playlist can actually be made: on a source with no
                    // account signed in, a plus that always failed would be worse than
                    // no plus at all.
                    if (canEdit) {
                        LiquidButton(
                            onClick = onCreatePlaylist,
                            backdrop = backdrop,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(42.dp),
                            contentHeight = 42.dp,
                            contentPadding = 0.dp,
                        ) {
                            Icon(
                                Lucide.Plus,
                                contentDescription = stringResource(R.string.new_playlist),
                                tint = Ink,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // One button that swaps between the two arrangements rather than a
                    // pair of them: with two options, a toggle showing the *other* one
                    // is both smaller and unambiguous.
                    LiquidButton(
                        onClick = { onLayout(if (layout == Layout.GRID) Layout.LIST else Layout.GRID) },
                        backdrop = backdrop,
                        modifier = Modifier.size(42.dp),
                        contentHeight = 42.dp,
                        contentPadding = 0.dp,
                    ) {
                        Icon(
                            if (layout == Layout.GRID) Lucide.List
                            else Lucide.Grid2x2,
                            contentDescription = stringResource(R.string.change_layout),
                            tint = Ink,
                            modifier = Modifier.size(20.dp),
                        )
                    }
            }
            }
        }

        // The chips say what is being shown, the whole width to themselves:
        // the sort went up beside the other buttons, since it is a choice made
        // once and then left alone, and the chips need the room.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Scrolled sideways rather than squeezed, edge to edge, and fading
            // out where it runs on under the sort button (#166).
            val chipsScroll = rememberScrollState()
            Row(
                Modifier
                    .weight(1f)
                    .then(app.sonar.ui.components.SideScrollNoBack)
                    .horizontalEdgeFade(chipsScroll)
                    .horizontalScroll(chipsScroll)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Filter.entries.filter { it != Filter.PODCASTS || hasPodcasts }.forEach { entry ->
                app.sonar.ui.components.FilterChip(
                backdrop = backdrop,
                    label = stringResource(entry.label),
                    selected = entry == filter,
                    onClick = { onFilter(entry) },
                )
            }
            }
            sort()
        }
    }
}

@Composable
private fun GridTile(
    playlist: CatalogPlaylist,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(
        Modifier.pressable(onClick, onLongClick = onLongClick),
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp, spot = 0.4f),
            corner = 20.dp,
            decodeSize = 220.dp,
        )
        Row(
            Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pinned) PinMark(Modifier.padding(end = 6.dp))
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ListRow(
    playlist: CatalogPlaylist,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .pressable(
                onClick,
                shape = RoundedCornerShape(16.dp),
                pressedScale = 0.98f,
                onLongClick = onLongClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier
                .size(52.dp)
                .softShadow(RoundedCornerShape(14.dp), elevation = 10.dp),
            corner = 14.dp,
            decodeSize = 52.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        if (pinned) PinMark()
    }
}

/** The quiet mark on a pinned row. Small: the position is the message. */
@Composable
private fun PinMark(modifier: Modifier = Modifier) {
    Icon(
        Lucide.Pin,
        contentDescription = stringResource(R.string.pinned),
        tint = InkDim,
        modifier = modifier.size(14.dp),
    )
}

/** A glass pill, for the handful of places that need a button at all. */
@Composable
private fun GlassAction(label: String, backdrop: Backdrop, onClick: () -> Unit) {
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        contentHeight = 50.dp,
        contentPadding = 26.dp,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Ink)
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** A harder film for the chip that is on, matching the home page. */
private val SelectedFilm = Color.White.copy(alpha = 0.26f)

/**
 * The artists the account follows, along the top of the library.
 *
 * Round, and in a row that scrolls sideways: it is the shape Spotify made mean
 * "a person" and the one that keeps a long list from pushing the playlists off
 * the screen. Part of the list rather than pinned above it, so it scrolls away
 * with everything else — a shelf that will not leave is a shelf in the way.
 */
@Composable
private fun ArtistShelf(
    artists: List<app.sonar.data.SearchItem>,
    onOpen: (app.sonar.data.SearchItem) -> Unit,
) {
    val uniqueArtists = remember(artists) { artists.distinctBy { it.uri } }
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            stringResource(R.string.artists_you_follow),
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        val uniqueArtists = remember(artists) { artists.distinctBy { it.uri } }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(uniqueArtists, key = { "artist_${it.uri}" }) { artist ->
                Column(
                    Modifier
                        .width(76.dp)
                        .pressable({ onOpen(artist) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(
                        url = artist.artworkUrl,
                        title = artist.title,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 36.dp,
                        decodeSize = 72.dp,
                    )
                    Text(
                        artist.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Out past [horizontal] of the padding it was laid out in, on both sides. */
private fun Modifier.bleed(horizontal: androidx.compose.ui.unit.Dp): Modifier =
    layout { measurable, constraints ->
        val extra = horizontal.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = constraints.minWidth + 2 * extra,
                maxWidth = constraints.maxWidth + 2 * extra,
            ),
        )
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
    }

/**
 * How far the list has gone under the header, 0 at the top and 1 once it has
 * scrolled [FADE_DISTANCE_PX]: the home page's measure.
 */
private fun scrolledAway(index: Int, offset: Int): Float =
    if (index > 0) 1f else (offset / FADE_DISTANCE_PX).coerceIn(0f, 1f)

/**
 * The list fading out as it passes under the chips, as the home page's does:
 * a mask over its top [FADE_FRACTION], coming in as it scrolls. Read while
 * drawing, so the scroll does not recompose the page.
 */
private fun Modifier.topFade(amount: () -> Float): Modifier = this
    .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val a = amount()
        if (a > 0.01f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Transparent,
                    (FADE_FRACTION * a) to Color.Black,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
    }

/** The home page's: the fade's depth as a share of the list, and how soon it comes in. */
private const val FADE_FRACTION = 0.045f
private const val FADE_DISTANCE_PX = 140f
