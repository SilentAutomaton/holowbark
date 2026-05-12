package net.yggawg.mobile.vpn

import kotlinx.coroutines.*
import net.yggawg.mobile.AppLogger
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Userspace packet dispatcher sitting on the single TUN file descriptor.
 *
 * Routing table:
 *  1. IPv6 200::/7  → Yggdrasil overlay
 *  2. Everything else → AmneziaWG tunnel
 *
 * Yggdrasil peer IPs are excluded from the VPN routes at the Builder level
 * (excludeRoute on API 33+), so their traffic never enters the TUN.
 */
class PacketRouter(
    private val tunFd: android.os.ParcelFileDescriptor,
    private val ygg: YggdrasilManager,
    private val awg: AwgManager,
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

    // -------------------------------------------------------------------------
    // Read loop
    // -------------------------------------------------------------------------

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
        val dst = packet.destinationAddress() ?: return
        if (dst.isYggdrasil()) {
            ygg.writePacket(packet)
        } else {
            awg.writePacket(packet)
        }
    }

    // -------------------------------------------------------------------------
    // Packet parsing helpers
    // -------------------------------------------------------------------------

    private fun ByteArray.destinationAddress(): InetAddress? {
        if (isEmpty()) return null
        return when ((this[0].toInt() and 0xF0) ushr 4) {
            4    -> parseIPv4Dest()
            6    -> parseIPv6Dest()
            else -> null
        }
    }

    /** IPv4: destination address at bytes [16..19] */
    private fun ByteArray.parseIPv4Dest(): InetAddress? {
        if (size < 20) return null
        return try {
            InetAddress.getByAddress(copyOfRange(16, 20))
        } catch (e: UnknownHostException) { null }
    }

    /** IPv6: destination address at bytes [24..39] */
    private fun ByteArray.parseIPv6Dest(): InetAddress? {
        if (size < 40) return null
        return try {
            InetAddress.getByAddress(copyOfRange(24, 40))
        } catch (e: UnknownHostException) { null }
    }
}

/**
 * Yggdrasil overlay: 200::/7
 * First byte of IPv6 address with mask 0xFE == 0x02, i.e. byte ∈ {0x02, 0x03}.
 */
fun InetAddress.isYggdrasil(): Boolean {
    if (this !is Inet6Address) return false
    return (address[0].toInt() and 0xFE) == 0x02
}
