package net.holowbark.vpn

import net.holowbark.AppLogger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** ConnectivityManager.getConnectionOwnerUid: protocol, local, remote → uid. */
typealias OwnerLookup = (Int, InetSocketAddress, InetSocketAddress) -> Int

/**
 * Drops packets whose owning app the app split keeps out of the tunnel.
 *
 * Android routes by uid, but a socket bound to the TUN with SO_BINDTODEVICE skips
 * that routing: since kernel 5.7 any app can do it without root, so an excluded app
 * can still reach the server and learn the tunnel is there. Only the packet's owner,
 * asked of the platform, tells the two apart.
 *
 * TCP is judged on the SYN alone: a closed socket reports uid 0, so later segments
 * of a finished connection would be misjudged. UDP is judged per 5-tuple and
 * judged again once the verdict expires, so a reused port does not inherit it for
 * long. ICMP, fragments and unknown next headers carry no ports and so no owner;
 * they are dropped.
 *
 * The lookup is a binder call. It runs on [executor] so a burst of new flows does
 * not stall the TUN read loop, and the packets of a flow wait until it returns.
 */
class OwnerFilter(
    private val uids: Set<Int>,
    private val isAllowList: Boolean,
    private val lookup: OwnerLookup,
    private val forward: (ByteArray) -> Unit,
    private val executor: Executor = Executors.newFixedThreadPool(LOOKUP_THREADS),
    private val clock: () -> Long = System::nanoTime,
) {
    companion object {
        private const val TAG = "OwnerFilter"
        /** android.os.Process.INVALID_UID: no owner, or an app outside the tunnel. */
        const val INVALID_UID = -1
        private const val LOOKUP_THREADS = 4
        private const val UDP_TTL_NANOS = 10_000_000_000L
        private const val TCP_IDLE_NANOS = 3_600_000_000_000L
        // ponytail: fixed caps with no hold timeout; a lookup that never returns
        // fills them and new flows drop until the tunnel restarts.
        private const val MAX_HELD_PER_FLOW = 16
        private const val MAX_PENDING_FLOWS = 256
        private const val PURGE_EVERY = 1024
        private const val TCP_HEADER_LEN = 20
        private const val TCP_FLAGS_OFFSET = 13
        private const val TCP_SYN = 0x02
        private const val TCP_SYN_ACK = 0x12
        private const val IPV4_FRAGMENT_MASK = 0x3FFF
    }

    private data class Flow(val protocol: Int, val local: InetSocketAddress, val remote: InetSocketAddress)

    private class Verdict(val isAdmitted: Boolean, @Volatile var expiresAt: Long)

    private val verdicts = ConcurrentHashMap<Flow, Verdict>()
    private val pending = HashMap<Flow, MutableList<ByteArray>>()
    private var putsSincePurge = 0

    fun admit(packet: ByteArray) {
        val flow = packet.flow() ?: return
        val isTcp = flow.protocol == IP_PROTO_TCP
        val isSyn = isTcp && packet.isTcpSyn()
        if (!isSyn) {
            val verdict = verdicts[flow]
            val now = clock()
            if (verdict != null && now < verdict.expiresAt) {
                if (isTcp) verdict.expiresAt = now + TCP_IDLE_NANOS
                if (verdict.isAdmitted) forward(packet)
                return
            }
            if (isTcp) return
        }
        synchronized(pending) {
            val held = pending[flow]
            if (held != null) {
                if (held.size < MAX_HELD_PER_FLOW) held.add(packet)
                return
            }
            if (pending.size >= MAX_PENDING_FLOWS) return
            pending[flow] = mutableListOf(packet)
        }
        executor.execute { judge(flow) }
    }

    fun stop() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun judge(flow: Flow) {
        val uid = runCatching { lookup(flow.protocol, flow.local, flow.remote) }
            .onFailure { AppLogger.w(TAG, "owner lookup: $it") }
            .getOrDefault(INVALID_UID)
        val isAdmitted = when {
            uid == INVALID_UID -> false
            isAllowList -> uid in uids
            else -> uid !in uids
        }
        val ttl = if (flow.protocol == IP_PROTO_TCP) TCP_IDLE_NANOS else UDP_TTL_NANOS
        verdicts[flow] = Verdict(isAdmitted, clock() + ttl)
        purgeExpired()
        val held = synchronized(pending) { pending.remove(flow) } ?: return
        if (isAdmitted) held.forEach(forward)
        else AppLogger.d(TAG, "dropped flow of uid $uid: $flow")
    }

    private fun purgeExpired() {
        synchronized(verdicts) {
            if (++putsSincePurge < PURGE_EVERY) return
            putsSincePurge = 0
        }
        val now = clock()
        verdicts.values.removeIf { now >= it.expiresAt }
    }

    private fun ByteArray.flow(): Flow? {
        val protocol: Int
        val l4: Int
        val src: InetAddress
        val dst: InetAddress
        when {
            isIpv4() && size >= IPV4_MIN_LEN -> {
                if (u16(6) and IPV4_FRAGMENT_MASK != 0) return null
                protocol = ipv4Protocol()
                l4 = ipv4HeaderLen()
                src = InetAddress.getByAddress(copyOfRange(12, 16))
                dst = InetAddress.getByAddress(copyOfRange(16, 20))
            }
            isIpv6() && size >= IPV6_HEADER_LEN -> {
                protocol = ipv6NextHeader()
                l4 = IPV6_HEADER_LEN
                src = InetAddress.getByAddress(copyOfRange(8, 24))
                dst = InetAddress.getByAddress(copyOfRange(24, 40))
            }
            else -> return null
        }
        val minL4 = when (protocol) {
            IP_PROTO_TCP -> TCP_HEADER_LEN
            IP_PROTO_UDP -> UDP_HEADER_LEN
            else -> return null
        }
        if (size < l4 + minL4) return null
        return Flow(protocol, InetSocketAddress(src, u16(l4)), InetSocketAddress(dst, u16(l4 + 2)))
    }

    private fun ByteArray.isTcpSyn(): Boolean {
        val l4 = if (isIpv4()) ipv4HeaderLen() else IPV6_HEADER_LEN
        return this[l4 + TCP_FLAGS_OFFSET].toInt() and TCP_SYN_ACK == TCP_SYN
    }
}
