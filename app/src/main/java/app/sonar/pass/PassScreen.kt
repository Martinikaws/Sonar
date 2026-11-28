package app.sonar.pass

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.sonar.R
import app.sonar.SonarApplication
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException

internal const val PASS_DESTINATION = "$PASS_ROUTE?invite={invite}"

internal fun NavGraphBuilder.passScreen(onBack: () -> Unit, onAccepted: () -> Unit) {
    composable(
        PASS_DESTINATION,
        arguments = listOf(navArgument("invite") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }),
    ) { entry ->
        PassScreen(entry.arguments?.getString("invite"), onBack, onAccepted)
    }
}

@Composable
private fun PassScreen(invite: String?, onBack: () -> Unit, onAccepted: () -> Unit) {
    val context = LocalContext.current
    val account = (context.applicationContext as SonarApplication).youtubeAccount
    val accountName by account.accountName.collectAsState()
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var link by remember(invite) { mutableStateOf(invite) }
    var passphrase by remember(invite) { mutableStateOf("") }
    var pending by remember(invite) { mutableStateOf<PassPayload?>(null) }
    var status by remember(invite) { mutableStateOf<Int?>(null) }
    var offer by remember { mutableStateOf<Pair<ImageBitmap, String>?>(null) }
    var sending by remember { mutableStateOf<Job?>(null) }
    var receiving by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val busy = sending != null || receiving || scanning

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.pass_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.pass_instructions))
            val received = pending
            val incoming = link
            val outgoing = offer
            when {
                sending != null -> {
                    if (outgoing != null) {
                        Image(
                            bitmap = outgoing.first,
                            contentDescription = stringResource(R.string.pass_qr_description),
                            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
                        )
                        Text(stringResource(R.string.pass_sender_code, outgoing.second))
                    }
                    PassButton(R.string.pass_cancel) {
                        sending?.cancel()
                        offer = null
                        status = null
                    }
                }
                received != null -> {
                    Text(stringResource(R.string.pass_confirm))
                    if (received.name.isNotBlank()) Text(received.name)
                    PassButton(R.string.pass_accept) {
                        status = when {
                            account.isSignedIn -> R.string.pass_existing_session
                            runCatching { account.acceptPass(received) }.getOrDefault(false) -> R.string.pass_received
                            else -> R.string.pass_error
                        }
                        pending = null
                        if (status == R.string.pass_received) onAccepted()
                    }
                    PassButton(R.string.pass_cancel) { pending = null }
                }
                incoming != null && accountName == null -> {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it.take(12) },
                        label = { Text(stringResource(R.string.pass_passphrase)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    PassButton(R.string.pass_receive, enabled = !busy && passphrase.length == 12) {
                        val code = passphrase
                        passphrase = ""
                        focus.clearFocus()
                        receiving = true
                        status = R.string.pass_receiving
                        scope.launch {
                            try {
                                if (account.isSignedIn) {
                                    link = null
                                    status = R.string.pass_existing_session
                                } else {
                                    pending = PassTransport.receive(context, incoming, code)
                                    link = null
                                    status = null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: AEADBadTagException) {
                                status = R.string.pass_unlock_error
                            } catch (_: PassWifiUnavailable) {
                                status = R.string.pass_wifi_error
                            } catch (_: Exception) {
                                status = R.string.pass_error
                            } finally {
                                receiving = false
                            }
                        }
                    }
                    PassButton(R.string.pass_cancel, enabled = !busy) {
                        link = null
                        passphrase = ""
                        status = null
                    }
                }
                else -> {
                    if (accountName != null) {
                        if (incoming != null) Text(stringResource(R.string.pass_existing_session))
                        PassButton(R.string.pass_send, enabled = !busy) {
                            status = R.string.pass_preparing
                            sending = scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    val session = account.passSession()
                                    if (session == null || account.expired.value) {
                                        status = R.string.pass_no_session
                                        return@launch
                                    }
                                    val sent = PassTransport.offer(context, session) { uri, code ->
                                        val image = qrBitmap(uri)
                                        withContext(Dispatchers.Main) {
                                            offer = image to code
                                            status = R.string.pass_waiting
                                        }
                                    }
                                    status = if (sent) R.string.pass_sent else R.string.pass_timeout
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: PassWifiUnavailable) {
                                    status = R.string.pass_wifi_error
                                } catch (_: Exception) {
                                    status = R.string.pass_error
                                } finally {
                                    offer = null
                                    sending = null
                                }
                            }
                            sending?.start()
                        }
                    } else {
                        Text(stringResource(R.string.pass_no_session))
                        PassButton(R.string.pass_scan, enabled = !busy) {
                            status = null
                            scanning = true
                            try {
                                val options = GmsBarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build()
                                GmsBarcodeScanning.getClient(context, options).startScan()
                                    .addOnSuccessListener { result ->
                                        val value = result.rawValue
                                        if (PassInvite.accepts(value)) link = value
                                        else status = R.string.pass_invalid_qr
                                    }
                                    .addOnFailureListener { status = R.string.pass_scan_error }
                                    .addOnCompleteListener { scanning = false }
                            } catch (_: Exception) {
                                scanning = false
                                status = R.string.pass_scan_error
                            }
                        }
                    }
                }
            }
            status?.let {
                Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
private fun PassButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}

private fun qrBitmap(link: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
