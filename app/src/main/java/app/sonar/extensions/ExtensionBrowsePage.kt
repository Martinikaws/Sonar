package app.sonar.extensions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.ui.settings.ActionRow
import app.sonar.ui.settings.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionTabs(browsing: Boolean, enabled: Boolean, switch: () -> Unit) {
    PrimaryTabRow(selectedTabIndex = if (browsing) 1 else 0) {
        Tab(selected = !browsing, enabled = enabled || !browsing, onClick = { if (browsing) switch() },
            text = { Text(stringResource(R.string.extstore_installed)) })
        Tab(selected = browsing, enabled = enabled || browsing, onClick = { if (!browsing) switch() },
            text = { Text(stringResource(R.string.extstore_browse)) })
    }
}

@Composable
internal fun ExtensionBrowsePage(
    store: ExtensionStore,
    padding: PaddingValues,
    header: @Composable () -> Unit,
    showInstalled: () -> Unit,
) {
    val discovery = remember { ExtensionDiscovery() }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ExtensionBrowseResult?>(null) }
    var installed by remember { mutableStateOf(emptyMap<String, ExtensionStore.Installed>()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmation by remember { mutableStateOf<ExtensionListing?>(null) }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            installed = withContext(Dispatchers.IO) { store.installed().associateBy { it.id } }
            result = discovery.browse(installed.values)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = null
            error = R.string.extstore_error
        } finally {
            loading = false
        }
    }

    LaunchedEffect(store) { refresh() }
    val canInstall = !loading && !installing && result?.incomplete == false
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item("top") { header() }
        item("tabs") { ExtensionTabs(browsing = true, enabled = !installing, switch = showInstalled) }
        item("browse-actions") {
            Section(null) {
                val message = when {
                    installing -> R.string.extstore_installing
                    loading -> R.string.extstore_loading
                    error != null -> error
                    result?.incomplete == true && result?.listings.isNullOrEmpty() -> R.string.extstore_error
                    result?.incomplete == true -> R.string.extstore_incomplete
                    result?.listings.isNullOrEmpty() -> R.string.extstore_empty
                    else -> null
                }
                message?.let { BrowseText(stringResource(it)) }
                if (!loading && !installing) {
                    ActionRow(stringResource(R.string.extstore_check_updates), destructive = false) {
                        scope.launch { refresh() }
                    }
                }
            }
        }
        items(result?.listings.orEmpty(), key = { "${it.source}:${it.entry.id}" }) { listing ->
            val entry = listing.entry
            val current = installed[entry.id]
            val update = current?.let(listing::isUpdate) == true
            Section(entry.name) {
                BrowseText(stringResource(R.string.ext_details, entry.version, entry.author))
                if (entry.description.isNotBlank()) BrowseText(entry.description)
                BrowseText(listing.source)
                listing.rating?.let { rating ->
                    val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                    BrowseText(stringResource(R.string.extstore_rating, format.format(rating.stars), rating.up.toLong() + rating.down))
                }
                when {
                    current != null && !update -> BrowseText(stringResource(R.string.extstore_installed))
                    listing.packageUrl == null -> BrowseText(stringResource(R.string.extstore_unavailable))
                    canInstall -> {
                        val action = if (update) R.string.extstore_update else R.string.extstore_install
                        ActionRow(stringResource(action, entry.name), destructive = false) { confirmation = listing }
                    }
                }
            }
        }
    }

    confirmation?.takeIf { canInstall }?.let { listing ->
        val update = installed[listing.entry.id]?.let(listing::isUpdate) == true
        val action = if (update) R.string.extstore_update else R.string.extstore_install
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(action, listing.entry.name)) },
            text = { Text(stringResource(R.string.extstore_confirm, listing.entry.version, listing.source)) },
            confirmButton = {
                TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = {
                    confirmation = null
                    installing = true
                    error = null
                    scope.launch {
                        try {
                            installed = withContext(Dispatchers.IO) {
                                discovery.install(store, listing, update)
                                store.installed().associateBy { it.id }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = R.string.extstore_install_error
                        } finally {
                            installing = false
                        }
                    }
                }) { Text(stringResource(action, listing.entry.name)) }
            },
            dismissButton = {
                TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { confirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BrowseText(text: String) {
    Text(text, Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
}
