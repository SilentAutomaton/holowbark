package net.holowbark.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.holowbark.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
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
        private const val YGG_SRC_PORT = 55353
        private const val TIMEOUT_MS = 4000
        private const val YGG_TLD = ".ygg"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // txId → (clientIpv4 bytes, clientPort)
    private val pendingYgg = ConcurrentHashMap<Int, Pair<ByteArray, Int>>()

    fun stop() { scope.cancel() }

    /** Called by PacketRouter when it sees a UDP packet destined for PROXY_ADDR:53. */
    fun handleQuery(tunPacket: ByteArray) {
        val headerLen = tunPacket.ipv4HeaderLen()
        if (headerLen < 20 || tunPacket.size < headerLen + UDP_HEADER_LEN + 12) return
        val clientIp = tunPacket.copyOfRange(12, 16)
        val clientPort = tunPacket.u16(headerLen)
        val dnsPayload = tunPacket.copyOfRange(headerLen + UDP_HEADER_LEN, tunPacket.size)

        val name = extractDnsName(dnsPayload)
        val viaYgg = name.endsWith(YGG_TLD)
        AppLogger.d(TAG, "DNS query ${name.ifEmpty { "(empty)" }} → ${if (viaYgg) "ygg" else "upstream"}")

        if (viaYgg) routeViaYgg(clientIp, clientPort, dnsPayload)
        else routeViaUpstream(clientIp, clientPort, dnsPayload)
    }

    /**
     * Called by YggdrasilManager.readLoop() when an IPv6 UDP packet arrives from
     * a 200::/7 source on port 53 (Yggdrasil DNS response).
     */
    fun handleYggDnsResponse(packet: ByteArray) {
        val answer = packet.udpPayload() ?: return
        if (answer.size < 4) return
        val (clientIp, clientPort) = pendingYgg.remove(answer.dnsTransactionId()) ?: return
        writeToTun(buildIPv4UdpReply(PROXY_IP, clientIp, DNS_PORT, clientPort, answer))
    }

    private fun routeViaYgg(clientIp: ByteArray, clientPort: Int, dnsPayload: ByteArray) {
        val ourBytes = parseIpv6Bytes(yggMgr.getAddress()) ?: run {
            AppLogger.w(TAG, "routeViaYgg: Ygg address not available")
            return
        }
        val txId = dnsPayload.dnsTransactionId()
        pendingYgg[txId] = clientIp to clientPort
        yggMgr.writePacket(
            buildIPv6UDP(ourBytes, yggDnsResolver.address, YGG_SRC_PORT, DNS_PORT, dnsPayload)
        )
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
                socket.send(DatagramPacket(dnsPayload, dnsPayload.size, upstream, DNS_PORT))
                val buf  = ByteArray(4096)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                writeToTun(
                    buildIPv4UdpReply(PROXY_IP, clientIp, DNS_PORT, clientPort, buf.copyOf(recv.length))
                )
            } catch (e: Exception) {
                if (isActive) AppLogger.w(TAG, "upstream DNS failed: $e")
            } finally {
                socket?.close()
            }
        }
    }
}
