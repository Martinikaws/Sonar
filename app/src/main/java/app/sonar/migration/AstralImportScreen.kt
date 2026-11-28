package app.sonar.migration

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sonar.R
import java.io.Serializable
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import javax.crypto.BadPaddingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal const val ASTRAL_IMPORT_ROUTE = "astral-import"

internal sealed interface ImportOutcome : Serializable {
    data class Done(val result: AstralImport.Result) : ImportOutcome
    data class Error(val message: Int) : ImportOutcome
}

internal class AstralImportViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<ImportOutcome?>(null)
        private set

    fun clearOutcome() {
        outcome = null
    }

    fun importDocument(context: Context, uri: Uri, secret: CharArray) {
        if (busy) {
            secret.fill('\u0000')
            return
        }
        outcome = null
        busy = true
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                outcome = ImportOutcome.Done(AstralImport.importDocument(context, uri, secret))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: BadPaddingException) {
                outcome = ImportOutcome.Error(R.string.fix2_import_bad_passphrase)
            } catch (_: InvalidAstralExport) {
                outcome = ImportOutcome.Error(R.string.fix2_import_invalid)
            } catch (_: AstralExportTooLarge) {
                outcome = ImportOutcome.Error(R.string.fix2_import_too_large)
            } catch (_: Exception) {
                outcome = ImportOutcome.Error(R.string.fix2_import_failed)
            } finally {
                secret.fill('\u0000')
                busy = false
            }
        }
    }
}

@Composable
internal fun AstralImportScreen(onBack: () -> Unit) {
    val model: AstralImportViewModel = viewModel()
    val busy = model.busy
    val completed = model.outcome
    val context = LocalContext.current.applicationContext
    val focus = LocalFocusManager.current
    var document by rememberSaveable { mutableStateOf<Uri?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var savedOutcome by rememberSaveable { mutableStateOf<ImportOutcome?>(null) }
    if (completed != null && completed != savedOutcome) {
        SideEffect { savedOutcome = completed }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            document = uri
            model.clearOutcome()
            savedOutcome = null
        }
    }

    BackHandler(enabled = busy) {}
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(48.dp)) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.astral_import_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.fix2_import_instructions))
            Text(stringResource(R.string.migration_existing_logins))
            Text(stringResource(R.string.migration_local_audio))
            Button(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    try {
                        picker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        model.clearOutcome()
                        savedOutcome = ImportOutcome.Error(R.string.fix2_import_picker_unavailable)
                    }
                },
            ) {
                Text(stringResource(if (document == null) R.string.fix2_import_choose else R.string.fix2_import_choose_other))
            }
            if (document != null) Text(stringResource(R.string.fix2_import_selected))
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text(stringResource(R.string.fix2_import_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = document != null && passphrase.length >= 8 && !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    val uri = document ?: return@Button
                    focus.clearFocus()
                    val secret = passphrase.toCharArray()
                    passphrase = ""
                    savedOutcome = ImportOutcome.Error(R.string.fix2_import_interrupted)
                    model.importDocument(context, uri, secret)
                },
            ) {
                Text(stringResource(R.string.fix2_import_action))
            }

            if (busy) {
                Text(
                    stringResource(R.string.fix2_import_progress),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                when (val status = completed ?: savedOutcome) {
                    null -> Unit
                    is ImportOutcome.Error -> Text(
                        stringResource(status.message),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                    is ImportOutcome.Done -> ImportSummary(status.result)
                }
            }
            Text(stringResource(R.string.fix2_import_downloads))
        }
    }
}

@Composable
private fun ImportSummary(result: AstralImport.Result) {
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.fix2_import_complete),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.fix2_import_files_count, result.filesImported, result.filesSkipped))
        if (result.historyNeedsAccount) {
            Text(stringResource(R.string.migration_history_account))
        }
        Text(stringResource(
            R.string.fix2_import_settings_count,
            result.preferencesImported, result.preferencesSkipped, result.preferenceFilesSkipped,
        ))
        Text(stringResource(
            R.string.fix2_import_cookies_count,
            result.cookiesAccepted, result.cookiesSkipped, result.cookieRecordsSkipped,
        ))
        if (result.cookiesSkippedForExistingSession > 0) {
            Text(stringResource(R.string.fix2_import_session_preserved, result.cookiesSkippedForExistingSession))
        }
        Text(stringResource(R.string.fix2_import_sign_in))
        Text(stringResource(R.string.fix2_import_unsupported_data))
    }
}
