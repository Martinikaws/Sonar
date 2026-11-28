package app.sonar.parity.devices

import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

internal data class CastReceiver(val id: String, val name: String, val group: Boolean)

internal fun NsdServiceInfo.castReceiver(): CastReceiver? {
    val id = txt("id")?.takeIf(String::isNotBlank) ?: return null
    val name = txt("fn")?.takeIf(String::isNotBlank) ?: return null
    return CastReceiver(id, name, txt("md") == "Google Cast Group")
}

internal fun castConnectId(name: String): String =
    MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun matchingCastReceiver(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    receivers: List<CastReceiver>,
    connectNames: List<String>,
): CastReceiver? {
    val distinct = receivers.distinctBy { it.id }
    return distinct.filter { castConnectId(it.name) == deviceId }.singleOrNull()
        ?: distinct.filter { it.name == deviceName }.singleOrNull()?.takeIf {
            deviceType.startsWith("CAST", ignoreCase = true) &&
                connectNames.count { name -> name == deviceName } == 1
        }
}

// TODO(device validation): Add authenticated Cast receiver launch and local media handoff.
@Suppress("DEPRECATION")
internal suspend fun NsdServiceInfo.sonosConnectId(): String? {
    if (port != 1400 || txt("CPath")?.trimEnd('/') != "/spotifyzc") return null
    val addresses = if (Build.VERSION.SDK_INT >= 34) hostAddresses else listOfNotNull(host)
    val address = addresses.filterIsInstance<Inet4Address>().firstOrNull {
        it.isSiteLocalAddress || it.isLinkLocalAddress
    } ?: return null

    // Query identity only from the fixed Sonos endpoint. LAN discovery never sends account credentials.
    return withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, 1400), 1_500)
                val request = "GET /spotifyzc?action=getInfo&version=2.7.1 HTTP/1.0\r\n" +
                    "Host: ${address.hostAddress}:1400\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                val input = socket.getInputStream().buffered()
                val response = ByteArray(16 * 1024)
                val deadline = SystemClock.elapsedRealtime() + 1_500
                var size = 0
                while (size < response.size) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) return@use null
                    socket.soTimeout = remaining.toInt()
                    val count = input.read(response, size, response.size - size)
                    if (count < 0) break
                    size += count
                }
                val http = String(response, 0, size, Charsets.UTF_8)
                if (!http.substringBefore("\r\n").matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) {
                    return@use null
                }
                val json = JSONObject(http.substringAfter("\r\n\r\n", ""))
                json.optString("deviceID").takeIf { json.optInt("status", 101) == 101 && it.isNotBlank() }
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}

// TODO(device validation): Pair new Sonos outputs through Spotify consent and validate group coordinator handoff.
