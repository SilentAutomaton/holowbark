package net.yggawg.mobile.vpn

import kotlinx.coroutines.*
import mobile.Yggdrasil
import net.yggawg.mobile.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "YggdrasilManager"

class YggdrasilManager(
    private val onPacketOut: (ByteArray) -> Unit,
    /** Called when Yggdrasil receives an inbound WG protocol packet from the server. */
    private val onWGPacket: ((ByteArray) -> Unit)? = null,
    private val onStatusChange: (state: LayerState, address: String, peerCount: Int) -> Unit = { _, _, _ -> },
) {
    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val PING_TIMEOUT_MS  = 4_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var ygg: Yggdrasil? = null
    /** AWG server 16-byte IPv6 address; packets from this src go to [onWGPacket]. */
    @Volatile var wgServerAddr: ByteArray? = null

    /** Pending ICMPv6 pings: seq → (deferred, sentAt). */
    private val pendingPings = ConcurrentHashMap<Int, Pair<CompletableDeferred<Unit>, Long>>()
    @Volatile private var pingSeq = 0

    // -------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------

    fun start(peers: List<String>, privateKey: String = "", multicast: Boolean = false) {
        if (ygg != null) return
        AppLogger.i(TAG, "Starting Yggdrasil with ${peers.size} peer(s), multicast=$multicast")
        peers.forEachIndexed { i, p -> AppLogger.d(TAG, "  peer[$i] = $p") }

        val cfg = buildConfig(peers, privateKey, multicast)
        val inst = Yggdrasil()
        try {
            inst.startJSON(cfg.toString().toByteArray())
            ygg = inst
            val addr = runCatching { inst.addressString }.getOrDefault("")
            YggNetworkState.selfAddress.value = addr
            AppLogger.i(TAG, "Yggdrasil started — address: $addr")
            onStatusChange(LayerState.STARTING, addr, 0)
            scope.launch { readLoop(inst) }
            scope.launch { pollPeers(inst) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start Yggdrasil: $e")
            onStatusChange(LayerState.ERROR, "", 0)
        }
    }

    fun stop() {
        scope.cancel()
        pendingPings.values.forEach { (d, _) -> d.cancel() }
        pendingPings.clear()
        val inst = ygg ?: return
        ygg = null
        try { inst.stop() } catch (e: Exception) { AppLogger.w(TAG, "stop: $e") }
        AppLogger.i(TAG, "Yggdrasil stopped")
    }

    fun setPeers(peers: List<String>) {
        ygg?.retryPeersNow()
        AppLogger.d(TAG, "retryPeersNow for ${peers.size} peers")
    }

    fun writePacket(packet: ByteArray) {
        try {
            ygg?.send(packet)
        } catch (e: Exception) {
            AppLogger.w(TAG, "send: $e")
        }
    }

    fun getAddress(): String = runCatching { ygg?.addressString }.getOrNull() ?: ""

    // -------------------------------------------------------------------------
    // Ping (ICMPv6 Echo via Yggdrasil overlay)
    // -------------------------------------------------------------------------

    /**
     * Send an ICMPv6 Echo Request to [destAddrStr] through the Yggdrasil overlay
     * and await the reply. Returns round-trip time in ms, or null on timeout.
     */
    suspend fun pingYgg(
        destAddrStr: String,
        timeoutMs: Long = PING_TIMEOUT_MS,
    ): Long? {
        val destBytes = parseYggSelfAddr(destAddrStr) ?: run {
            AppLogger.w(TAG, "pingYgg: cannot parse address '$destAddrStr'")
            return null
        }
        val ourAddrStr = getAddress()
        val ourBytes   = parseYggSelfAddr(ourAddrStr) ?: run {
            AppLogger.w(TAG, "pingYgg: our address not available")
            return null
        }
        val seq = (++pingSeq) and 0xFFFF
        val deferred = CompletableDeferred<Unit>()
        val sentAt = System.currentTimeMillis()
        pendingPings[seq] = deferred to sentAt
        val pkt = buildICMPv6Echo(ourBytes, destBytes, seq)
        AppLogger.d(TAG, "pingYgg → $destAddrStr seq=$seq")
        writePacket(pkt)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
            System.currentTimeMillis() - sentAt
        } catch (_: TimeoutCancellationException) {
            AppLogger.d(TAG, "pingYgg timeout seq=$seq")
            null
        } finally {
            pendingPings.remove(seq)
        }
    }

    // -------------------------------------------------------------------------
    // Read loop
    // -------------------------------------------------------------------------

    private fun readLoop(inst: Yggdrasil) {
        AppLogger.d(TAG, "readLoop started")
        while (scope.isActive && ygg != null) {
            try {
                val pkt = inst.recv() ?: continue
                if (pkt.isEmpty()) continue

                // 1. WireGuard protocol packets → AWG
                val serverAddr = wgServerAddr
                if (serverAddr != null && onWGPacket != null) {
                    val wgPayload = pkt.extractWGPayload(serverAddr)
                    if (wgPayload != null) {
                        onWGPacket.invoke(wgPayload)
                        continue
                    }
                }

                // 2. ICMPv6 Echo Reply → complete pending pings
                if (pkt.size >= 48
                    && (pkt[0].toInt() and 0xF0) == 0x60   // IPv6
                    && pkt[6] == 0x3a.toByte()              // next header = ICMPv6
                    && pkt[40] == 0x81.toByte()             // type = Echo Reply (129)
                ) {
                    val seq = ((pkt[46].toInt() and 0xFF) shl 8) or (pkt[47].toInt() and 0xFF)
                    val entry = pendingPings.remove(seq)
                    if (entry != null) {
                        AppLogger.d(TAG, "pingYgg reply seq=$seq rtt=${System.currentTimeMillis() - entry.second}ms")
                        entry.first.complete(Unit)
                        continue   // don't forward ping replies to TUN
                    }
                }

                // 3. Everything else → TUN
                onPacketOut(pkt)
            } catch (e: Exception) {
                if (scope.isActive) AppLogger.w(TAG, "recv: $e")
                break
            }
        }
        AppLogger.d(TAG, "readLoop exited")
    }

    // -------------------------------------------------------------------------
    // Peer polling
    // -------------------------------------------------------------------------

    private suspend fun pollPeers(inst: Yggdrasil) {
        var lastCount = -1
        while (scope.isActive && ygg != null) {
            delay(POLL_INTERVAL_MS)
            val addr  = runCatching { inst.addressString }.getOrDefault("")
            val json  = runCatching { inst.peersJSON ?: "[]" }.getOrDefault("[]")
            val peers = parsePeers(json)
            val count = peers.count { it.up }

            if (count != lastCount) {
                AppLogger.i(TAG, "Peer count changed: $lastCount → $count (addr=$addr)")
                lastCount = count
            }
            YggNetworkState.selfAddress.value = addr
            YggNetworkState.peers.value = peers
            val state = if (count > 0) LayerState.UP else LayerState.STARTING
            onStatusChange(state, addr, count)
        }
    }

    private fun parsePeers(json: String): List<YggNetworkState.PeerInfo> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // Yggdrasil mobile uses capitalized field names (URI, Up, Uptime, Latency …)
            val latencyNanos = o.optLong("Latency", -1L)
            YggNetworkState.PeerInfo(
                uri       = o.optString("URI", o.optString("uri", "?")),
                up        = o.optBoolean("Up", o.optBoolean("up", false)),
                inbound   = o.optBoolean("Inbound", o.optBoolean("inbound", false)),
                latencyMs = if (latencyNanos > 0) latencyNanos / 1_000_000.0 else -1.0,
                uptimeSec = o.optDouble("Uptime", o.optDouble("uptime", -1.0)),
                bytesSent  = o.optLong("Bytes_Sent",  o.optLong("bytes_sent",  0L)),
                bytesRecvd = o.optLong("Bytes_Recvd", o.optLong("bytes_recvd", 0L)),
                lastError  = o.optString("LastError",  o.optString("last_error",  "")),
            )
        }
    } catch (e: Exception) {
        AppLogger.w(TAG, "parsePeers: $e")
        emptyList()
    }

    // -------------------------------------------------------------------------

    private fun buildConfig(peers: List<String>, privateKey: String,
                            multicast: Boolean = false): JSONObject {
        val cfg = JSONObject()
        if (privateKey.isNotBlank()) cfg.put("PrivateKey", privateKey)
        cfg.put("Peers", JSONArray(peers))
        cfg.put("IfName", "none")
        cfg.put("IfMTU", 65535)
        // Multicast enables automatic peer discovery on the local network.
        // The ".*" regex matches all interfaces; Beacon=true advertises us,
        // Listen=true accepts incoming beacons from peers on the same LAN.
        if (multicast) {
            val mcIface = JSONObject().apply {
                put("Regex",    ".*")
                put("Beacon",   true)
                put("Listen",   true)
                put("Port",     0)      // random ephemeral port
                put("Priority", 0)
                put("Password", "")
            }
            cfg.put("MulticastInterfaces", JSONArray().put(mcIface))
        } else {
            cfg.put("MulticastInterfaces", JSONArray())
        }
        return cfg
    }
}
