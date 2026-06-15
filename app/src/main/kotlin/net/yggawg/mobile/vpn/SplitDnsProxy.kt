package net.yggawg.mobile.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.yggawg.mobile.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal split-DNS proxy for Holowbark.
 *
 * DNS queries for `.ygg` domains are forwarded to [yggDnsResolver] via the Yggdrasil overlay
 * (using [yggMgr]). All other queries are forwarded to [upstreamDns] via a protected socket
 * that bypasses the VPN tunnel.
 *
 * Packet flow:
 *   System resolver → DNS query to 198.18.0.53:53 → TUN
 *   PacketRouter intercepts → handleQuery()
 *   .ygg: craft IPv6 UDP → yggMgr → Yggdrasil overlay → resolver
 *   other: DatagramSocket (protected) → upstream DNS
 *   Response → craft IPv4 UDP reply from 198.18.0.53:53 → TUN → system resolver
 *
 * IPv6 DNS responses from Yggdrasil are intercepted in YggdrasilManager.readLoop()
 * and delivered to handleYggDnsResponse() rather than forwarded to TUN directly.
 */
class SplitDnsProxy(
    private val upstreamDns: InetAddress?,
    private val yggDnsResolver: Inet6Address,
    private val yggMgr: YggdrasilManager,
    private val protect: (DatagramSocket) -> Boolean,
    private val writeToTun: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "SplitDnsProxy"
        val PROXY_IP: ByteArray = byteArrayOf(198.toByte(), 18, 0, 53)
        val PROXY_ADDR: InetAddress = InetAddress.getByAddress(PROXY_IP)
        private const val YGG_SRC_PORT = 55353
        private const val TIMEOUT_MS = 4000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // txId → (clientIpv4 bytes, clientPort)
    private val pendingYgg = ConcurrentHashMap<Int, Pair<ByteArray, Int>>()

    fun stop() { scope.cancel() }

    /** Called by PacketRouter when it sees a UDP packet destined for PROXY_ADDR:53. */
    fun handleQuery(tunPacket: ByteArray) {
        if (tunPacket.size < 28) return
        val ihl = (tunPacket[0].toInt() and 0x0F) * 4
        if (ihl < 20 || tunPacket.size < ihl + 8) return
        val clientIp   = tunPacket.copyOfRange(12, 16)
        val clientPort = ((tunPacket[ihl].toInt() and 0xFF) shl 8) or
                          (tunPacket[ihl + 1].toInt() and 0xFF)
        val dnsPayload = tunPacket.copyOfRange(ihl + 8, tunPacket.size)
        if (dnsPayload.size < 12) return

        val name = extractDnsName(dnsPayload)
        AppLogger.d(TAG, "DNS query: name=${name.ifEmpty { "(empty)" }} → ${if (name.endsWith(".ygg")) "ygg" else "upstream"}")

        if (name.endsWith(".ygg")) {
            routeViaYgg(clientIp, clientPort, dnsPayload)
        } else {
            routeViaUpstream(clientIp, clientPort, dnsPayload)
        }
    }

    /**
     * Called by YggdrasilManager.readLoop() when an IPv6 UDP packet arrives from
     * a 200::/7 source on port 53 (Yggdrasil DNS response).
     */
    fun handleYggDnsResponse(packet: ByteArray) {
        if (packet.size < 48) return
        val udpLen     = ((packet[44].toInt() and 0xFF) shl 8) or (packet[45].toInt() and 0xFF)
        val payloadLen = udpLen - 8
        if (payloadLen < 4 || packet.size < 48 + payloadLen) return
        val dnsResponse = packet.copyOfRange(48, 48 + payloadLen)
        val txId        = ((dnsResponse[0].toInt() and 0xFF) shl 8) or
                           (dnsResponse[1].toInt() and 0xFF)
        val pending = pendingYgg.remove(txId) ?: return
        val (clientIp, clientPort) = pending
        writeToTun(buildIPv4UdpReply(PROXY_IP, clientIp, 53, clientPort, dnsResponse))
    }

    private fun routeViaYgg(clientIp: ByteArray, clientPort: Int, dnsPayload: ByteArray) {
        val ourBytes = parseYggSelfAddr(yggMgr.getAddress()) ?: run {
            AppLogger.w(TAG, "routeViaYgg: Ygg address not available")
            return
        }
        val txId = ((dnsPayload[0].toInt() and 0xFF) shl 8) or (dnsPayload[1].toInt() and 0xFF)
        pendingYgg[txId] = clientIp to clientPort
        val pkt = buildIPv6UDP(ourBytes, yggDnsResolver.address, YGG_SRC_PORT, 53, dnsPayload)
        yggMgr.writePacket(pkt)
        scope.launch {
            delay(TIMEOUT_MS.toLong())
            pendingYgg.remove(txId)
        }
    }

    private fun routeViaUpstream(clientIp: ByteArray, clientPort: Int, dnsPayload: ByteArray) {
        val upstream = upstreamDns ?: run {
            AppLogger.w(TAG, "routeViaUpstream: no upstream DNS configured")
            return
        }
        scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = TIMEOUT_MS
                socket.send(DatagramPacket(dnsPayload, dnsPayload.size, upstream, 53))
                val buf  = ByteArray(4096)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val reply = buildIPv4UdpReply(PROXY_IP, clientIp, 53, clientPort, buf.copyOf(recv.length))
                writeToTun(reply)
            } catch (e: Exception) {
                if (isActive) AppLogger.w(TAG, "upstream DNS failed: $e")
            } finally {
                socket?.close()
            }
        }
    }
}
