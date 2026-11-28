package app.sonar.extensions

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sonar.R
import app.sonar.ui.settings.ActionRow
import app.sonar.ui.settings.Section
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

@Composable
internal fun ExtensionsPage(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ExtensionStore(context.filesDir) }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(emptyList<ExtensionStore.Installed>()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var digest by rememberSaveable { mutableStateOf("") }
    var pendingDigest by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Int, String?>?>(null) }

    suspend fun checkStatus(id: String) {
        busy = true
        checking = true
        try {
            val report = withContext(Dispatchers.IO) {
                val response = ExtensionRuntime(store.load(id)).status(Locale.getDefault())
                response?.let { Json.parseToJsonElement(it).jsonObject }
            }
            status = when {
                report == null -> R.string.ext_review_status_unavailable to null
                else -> {
                    val live = report.getValue("live").jsonPrimitive.boolean
                    val detail = report["detail"]?.jsonPrimitive?.contentOrNull
                    (if (live) R.string.ext_review_status_live else R.string.ext_review_status_inactive) to detail
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = R.string.ext_review_status_error to null
        } finally {
            checking = false
            busy = false
        }
    }

    suspend fun refresh(errorMessage: Int, operation: () -> Unit) {
        busy = true
        error = null
        try {
            installed = withContext(Dispatchers.IO) {
                operation()
                store.installed()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = errorMessage
        } finally {
            busy = false
        }
    }

    LaunchedEffect(store) { refresh(R.string.ext_load_error) {} }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val expected = pendingDigest
        pendingDigest = null
        if (uri != null && expected != null) {
            scope.launch {
                refresh(R.string.ext_install_error) {
                    val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use {
                        it.readLimited(ExtensionPackage.MAX_ZIP)
                    }
                    store.install(bytes, expected)
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("top") { header() }
        item("actions") {
            Section(null) {
                if (!busy && pendingDigest == null) {
                    ActionRow(stringResource(R.string.ext_install), destructive = false, icon = Lucide.FolderOpen) {
                        digest = ""
                        importing = true
                    }
                }
                val message = when {
                    checking -> R.string.ext_review_status_loading
                    busy -> R.string.ext_loading
                    error != null -> error
                    installed.isEmpty() -> R.string.ext_empty
                    else -> null
                }
                message?.let {
                    Text(stringResource(it), Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
                if (error == R.string.ext_load_error) {
                    ActionRow(stringResource(R.string.ext_retry), destructive = false) {
                        scope.launch { refresh(R.string.ext_load_error) {} }
                    }
                }
            }
        }
        items(installed, key = { it.id }) { entry ->
            val manifest = entry.manifest
            val name = manifest?.name ?: "${entry.id}.zip"
            Section(name) {
                Text(
                    if (manifest == null) stringResource(R.string.ext_review_unreadable)
                    else stringResource(R.string.ext_details, manifest.version, manifest.author),
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!busy && pendingDigest == null) {
                    if (manifest != null) {
                        ActionRow(stringResource(R.string.ext_review_check_status), destructive = false) {
                            scope.launch { checkStatus(entry.id) }
                        }
                    }
                    ActionRow(
                        stringResource(R.string.ext_remove, name),
                        destructive = true,
                        icon = Lucide.Trash,
                    ) { removing = entry.id }
                }
            }
        }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text(stringResource(R.string.ext_install)) },
            text = {
                OutlinedTextField(
                    value = digest,
                    onValueChange = { digest = it.trim().take(128) },
                    label = { Text(stringResource(R.string.ext_sha256)) },
                    supportingText = { Text(stringResource(R.string.ext_sha256_help)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 44.dp),
                    enabled = ExtensionPackage.validDigest(digest),
                    onClick = {
                        importing = false
                        pendingDigest = digest
                        try {
                            picker.launch(arrayOf("*/*"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            pendingDigest = null
                            error = R.string.ext_picker_error
                        }
                    },
                ) { Text(stringResource(R.string.ext_choose_package)) }
            },
            dismissButton = {
                TextButton(onClick = { importing = false }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    status?.let { (message, detail) ->
        val result = stringResource(message)
        AlertDialog(
            onDismissRequest = { status = null },
            title = { Text(stringResource(R.string.ext_review_status_title)) },
            text = { Text(if (detail.isNullOrBlank()) result else "$result\n$detail") },
            confirmButton = {
                TextButton(onClick = { status = null }, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.ext_review_close))
                }
            },
        )
    }

    installed.firstOrNull { it.id == removing }?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.ext_remove, entry.manifest?.name ?: "${entry.id}.zip")) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { refresh(R.string.ext_remove_error) { store.uninstall(entry.id) } }
                }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.ext_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
