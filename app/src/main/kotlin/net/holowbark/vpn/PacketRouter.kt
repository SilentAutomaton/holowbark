package net.holowbark.vpn

import kotlinx.coroutines.*
import net.holowbark.AppLogger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Userspace dispatcher on the single TUN file descriptor. Overlay traffic
 * (200::/7) goes to Yggdrasil, everything else to the AWG tunnel, and DNS aimed at
 * the split-proxy address is intercepted before either.
 *
 * Yggdrasil peer addresses are excluded from the VPN routes when the tunnel is
 * built, so their packets never reach this loop.
 */
class PacketRouter(
    private val tunFd: android.os.ParcelFileDescriptor,
    private val ygg: YggdrasilManager,
    private val awg: AwgManager,
    private val dnsProxy: SplitDnsProxy? = null,
) {
    companion object {
        private const val TAG = "PacketRouter"
        private const val BUF_SIZE = 65536
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outStream = FileOutputStream(tunFd.fileDescriptor)

    fun start() {
        scope.launch { readLoop() }
        AppLogger.i(TAG, "PacketRouter started")
    }

    fun stop() {
        scope.cancel()
        AppLogger.i(TAG, "PacketRouter stopped")
    }

    /** Write a packet back into the TUN (inbound from Yggdrasil or AWG). */
    fun writeToTun(packet: ByteArray) {
        try {
            outStream.write(packet)
        } catch (e: Exception) {
            AppLogger.w(TAG, "writeToTun: $e")
        }
    }

    // Read loop

    private fun readLoop() {
        val buf = ByteArray(BUF_SIZE)
        val stream = FileInputStream(tunFd.fileDescriptor)
        while (scope.isActive) {
            val len = try {
                stream.read(buf)
            } catch (e: Exception) {
                if (scope.isActive) AppLogger.w(TAG, "TUN read error: $e")
                break
            }
            if (len <= 0) continue
            dispatch(buf.copyOf(len))
        }
    }

    private fun dispatch(packet: ByteArray) {
        if (dnsProxy != null && packet.isProxyDnsQuery()) {
            dnsProxy.handleQuery(packet)
            return
        }
        val dst = packet.destinationAddress() ?: return
        if (dst.isYggdrasil()) ygg.writePacket(packet) else awg.writePacket(packet)
    }

    private fun ByteArray.isProxyDnsQuery(): Boolean {
        if (!isIpv4() || size < IPV4_MIN_LEN || ipv4Protocol() != IP_PROTO_UDP) return false
        if (!copyOfRange(16, 20).contentEquals(SplitDnsProxy.PROXY_IP)) return false
        val headerLen = ipv4HeaderLen()
        return size >= headerLen + 4 && u16(headerLen + 2) == DNS_PORT
    }

    private fun ByteArray.destinationAddress(): InetAddress? = runCatching {
        when {
            isIpv4() && size >= IPV4_MIN_LEN  -> InetAddress.getByAddress(copyOfRange(16, 20))
            isIpv6() && size >= IPV6_HEADER_LEN -> InetAddress.getByAddress(copyOfRange(24, 40))
            else -> null
        }
    }.getOrNull()
}

private const val IPV4_MIN_LEN = 20

/** True for an address in the Yggdrasil overlay range 200::/7. */
fun InetAddress.isYggdrasil(): Boolean =
    this is Inet6Address && address[0].isYggdrasilPrefix()
