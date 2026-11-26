package app.sonar.migration

import android.content.ActivityNotFoundException
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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.sonar.R
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import javax.crypto.BadPaddingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal const val ASTRAL_IMPORT_ROUTE = "astral-import"
private const val IMPORT_TITLE = "Import from Astral"

private sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Done(val result: AstralImport.Result) : ImportState
    data class Error(val message: String) : ImportState
}

@Composable
internal fun AstralImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<Uri?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    val busy = state == ImportState.Importing
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            document = uri
            state = ImportState.Idle
        }
    }

    BackHandler(enabled = busy) {}
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(48.dp)) {
                Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(IMPORT_TITLE, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose the .snmig file Astral exported, then enter its passphrase.")
            Button(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    try {
                        picker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        state = ImportState.Error("The file picker is unavailable. Enable a document picker and try again.")
                    }
                },
            ) {
                Text(if (document == null) "Choose Astral export" else "Choose a different export")
            }
            if (document != null) Text("Export selected")
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("Export passphrase") },
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
                    state = ImportState.Importing
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            state = ImportState.Done(AstralImport.importDocument(context, uri, secret))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: BadPaddingException) {
                            state = ImportState.Error(
                                "The passphrase is incorrect or the export is damaged. " +
                                    "Re-enter the passphrase or make a new export in Astral.",
                            )
                        } catch (_: InvalidAstralExport) {
                            state = ImportState.Error("Choose a complete .snmig export made by Astral. This file is invalid or unsupported.")
                        } catch (_: AstralExportTooLarge) {
                            state = ImportState.Error("This export exceeds Sonar's import limits. Try a smaller export from Astral.")
                        } catch (_: Exception) {
                            state = ImportState.Error(
                                "Import could not finish. Some changes may have been saved. " +
                                    "Check that the file is accessible and try again.",
                            )
                        } finally {
                            secret.fill('\u0000')
                        }
                    }
                },
            ) {
                Text("Import")
            }

            when (val status = state) {
                ImportState.Idle -> Unit
                ImportState.Importing -> Text(
                    "Importing…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                is ImportState.Error -> Text(
                    status.message,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                )
                is ImportState.Done -> ImportSummary(status.result)
            }
            Text("Downloads do not migrate and must be downloaded again.")
        }
    }
}

@Composable
private fun ImportSummary(result: AstralImport.Result) {
    val (files, skippedFiles, settings, skippedSettings, skippedPreferenceFiles, cookies, skippedCookies, skippedRecords) =
        result
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Import complete", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("Files imported $files, skipped $skippedFiles.")
        Text(
            "Settings imported $settings, skipped $skippedSettings in checked files. " +
                "$skippedPreferenceFiles preference files supplied no settings.",
        )
        Text("WebView cookies accepted $cookies, skipped $skippedCookies. $skippedRecords cookie records were skipped.")
        Text(
            "Use Sonar sign-in for Spotify. Open YouTube Music sign-in to finish connecting your account. " +
                "The service may ask you to sign in again.",
        )
        Text("Unsupported data, install-bound keys, Web Storage and folder permissions do not migrate.")
    }
}
