package app.sonar.pass

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class PassWifiUnavailable : IllegalStateException()

internal object PassTransport {
    suspend fun offer(
        context: Context,
        payload: PassPayload,
        onReady: suspend (link: String, passphrase: String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val (_, local, prefixLength) = wifi(context)
        val passphrase = encodeBase64(passRandomBytes(9))
        val key = passRandomBytes(PASS_KEY_BYTES)
        val frame = payload.encode(passphrase)
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(local, 0))
            server.soTimeout = 500
            val link = PassInvite(local.hostAddress.orEmpty(), server.localPort, encodeBase64(key)).encode(passphrase)
            onReady(link, passphrase)
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                val peer = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                peer.use { socket ->
                    if (!sameSubnet(local, socket.inetAddress, prefixLength)) return@use
                    val sent = try {
                        val input = DataInputStream(socket.getInputStream())
                        val suppliedKey = ByteArray(PASS_KEY_BYTES)
                        val handshakeDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 3_000)
                        for (index in suppliedKey.indices) {
                            val remaining = handshakeDeadline - SystemClock.elapsedRealtime()
                            if (remaining <= 0) throw SocketTimeoutException()
                            socket.soTimeout = remaining.toInt()
                            suppliedKey[index] = input.readByte()
                        }
                        ensureActive()
                        if (!MessageDigest.isEqual(key, suppliedKey)) return@use
                        DataOutputStream(socket.getOutputStream()).apply {
                            writeInt(frame.size)
                            write(frame)
                            flush()
                        }
                        true
                    } catch (_: java.io.IOException) {
                        false
                    }
                    if (sent) return@withContext true
                }
            }
            false
        }
    }

    suspend fun receive(context: Context, link: String, passphrase: String): PassPayload =
        withContext(Dispatchers.IO) {
            val invite = PassInvite.decode(link, passphrase)
            val key = decodeBase64(invite.key)
            require(invite.port in 1..65535 && key.size == PASS_KEY_BYTES)
            val remote = parseAddress(invite.host)
            val (network, local, prefixLength) = wifi(context)
            require(remote != local && sameSubnet(local, remote, prefixLength))
            val frame = network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(remote, invite.port), 3_000)
                socket.soTimeout = 3_000
                socket.getOutputStream().write(key)
                val input = DataInputStream(socket.getInputStream())
                val size = input.readInt()
                require(size in 1..PASS_MAX_FRAME)
                val deadline = SystemClock.elapsedRealtime() + 3_000
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    ensureActive()
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw SocketTimeoutException()
                    socket.soTimeout = remaining.toInt()
                    val count = input.read(bytes, offset, size - offset)
                    if (count < 0) throw java.io.EOFException()
                    offset += count
                }
                bytes
            }
            PassPayload.decode(frame, passphrase)
        }

    private fun wifi(context: Context): Triple<android.net.Network, Inet4Address, Int> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: throw PassWifiUnavailable()
        val network = manager.activeNetwork ?: throw PassWifiUnavailable()
        if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            throw PassWifiUnavailable()
        }
        val link = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 1..32
        } ?: throw PassWifiUnavailable()
        return Triple(network, link.address as Inet4Address, link.prefixLength)
    }

    internal fun parseAddress(host: String): InetAddress {
        val parts = host.split('.')
        require(parts.size == 4)
        val bytes = parts.map { part ->
            require(part.length in 1..3 && part.all { it in '0'..'9' })
            val value = part.toInt()
            require(value in 0..255)
            value.toByte()
        }
        return InetAddress.getByAddress(bytes.toByteArray())
    }

    internal fun sameSubnet(local: Inet4Address, remote: InetAddress, prefixLength: Int): Boolean {
        if (remote !is Inet4Address || !remote.isSiteLocalAddress) return false
        val mask = -1 shl (32 - prefixLength)
        return ((ByteBuffer.wrap(local.address).int xor ByteBuffer.wrap(remote.address).int) and mask) == 0
    }
}
