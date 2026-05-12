package net.yggawg.mobile.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.yggawg.mobile.AppLogger
import net.yggawg.mobile.MainActivity
import net.yggawg.mobile.R
import net.yggawg.mobile.HolowbarkApp
import net.yggawg.mobile.config.AwgConfig
import net.yggawg.mobile.config.parseAwgConf
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class YggVpnService : VpnService() {

    companion object {
        private const val TAG = "YggVpnService"
        private const val NOTIF_ID = 1

        const val ACTION_START       = "net.yggawg.mobile.START_VPN"
        const val ACTION_STOP        = "net.yggawg.mobile.STOP_VPN"
        const val ACTION_STATUS      = "net.yggawg.mobile.VPN_STATUS"
        const val ACTION_RESTART_AWG = "net.yggawg.mobile.RESTART_AWG"

        // Extras for ACTION_START intent
        const val EXTRA_AWG_CONF   = "awg_conf"
        const val EXTRA_YGG_PEERS  = "ygg_peers"      // ArrayList<String> of peer addresses
        const val EXTRA_YGG_KEY    = "ygg_key"

        // Community Yggdrasil DNS resolvers (Revertron). Support ICANN, ALFIS, OpenNIC, ad blocking.
        // All in 200::/7 — routed through Yggdrasil overlay automatically.
        val YGG_DNS_SERVERS = listOf(
            "308:62:45:62::",   // Amsterdam
            "308:84:68:55::",   // Frankfurt
            "308:25:40:bd::",   // Bratislava
            "308:c8:48:45::",   // Buffalo
        )
        const val EXTRA_MULTICAST  = "ygg_multicast"  // Boolean — enable LAN multicast discovery
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var ygg: YggdrasilManager? = null
    private var awg: AwgManager? = null
    private var router: PacketRouter? = null

    // Manages deferred AWG start (ping wait) + bridge loop; cancelled/restarted on restartAwg()
    private var awgLifecycleScope: CoroutineScope? = null

    // Saved for restartAwg() so we don't need to re-parse the intent
    private var savedAwgConfig: AwgConfig? = null
    private var savedAwgServerAddrBytes: ByteArray? = null
    private var savedAwgServerPort: Int = 44555

    @Volatile private var status = TunnelStatus()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP        -> { stopVpn(); START_NOT_STICKY }
            ACTION_RESTART_AWG -> { restartAwg(); START_STICKY }
            else -> {
                val awgConfText = intent?.getStringExtra(EXTRA_AWG_CONF)
                @Suppress("UNCHECKED_CAST")
                val yggPeers   = intent?.getStringArrayListExtra(EXTRA_YGG_PEERS) ?: arrayListOf()
                val yggKey     = intent?.getStringExtra(EXTRA_YGG_KEY) ?: ""
                val multicast  = intent?.getBooleanExtra(EXTRA_MULTICAST, false) ?: false
                val awgConfig  = awgConfText?.let { runCatching { parseAwgConf(it) }.getOrNull() }
                startVpn(awgConfig, yggPeers, yggKey, multicast)
                START_STICKY
            }
        }
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    // -------------------------------------------------------------------------
    // VPN start / stop
    // -------------------------------------------------------------------------

    private fun startVpn(awgConfig: AwgConfig?, peers: List<String>, yggKey: String,
                          multicast: Boolean = false) {
        if (tunFd != null) {
            AppLogger.w(TAG, "VPN already running — ignoring duplicate start")
            return
        }
        AppLogger.i(TAG, "startVpn peers=${peers.size} awg=${awgConfig?.endpoint} multicast=$multicast")
        updateStatus { copy(overall = VpnState.CONNECTING, ygg = LayerState.STARTING,
                           awg = if (awgConfig != null) LayerState.STARTING else LayerState.IDLE) }

        val peerIps: Set<InetAddress> = peers.flatMap { parsePeerHosts(it) }.toSet()

        // Start Yggdrasil before establish() so we get the real overlay address
        // (derived from the private key) to assign to the TUN interface.
        // All callbacks use nullable vars (router?, awg?) so starting before those
        // are initialised is safe — packets are dropped until the router is ready,
        // which is fine during the brief setup window.
        val awgServerAddrBytes = awgConfig?.let { parseYggAddrBytes(it.endpoint) }
        val awgServerPort      = awgConfig?.let { parseEndpointPort(it.endpoint) } ?: 44555

        val awgMgr = AwgManager(
            onPacketOut    = { router?.writeToTun(it) },
            onStatusChange = { state -> updateStatus { copy(awg = state) } },
        )
        val yggMgr = YggdrasilManager(
            onPacketOut = { router?.writeToTun(it) },
            onWGPacket  = if (awgServerAddrBytes != null) { wgPkt ->
                awgMgr.sendWGPacket(wgPkt)
            } else null,
            onStatusChange = { state, addr, count ->
                updateStatus { copy(ygg = state, yggAddress = addr, yggPeers = count) }
            },
        )
        if (awgServerAddrBytes != null) {
            yggMgr.wgServerAddr = awgServerAddrBytes
            val addrHex = awgServerAddrBytes.joinToString(":") { "%02x".format(it) }
            AppLogger.i(TAG, "WG bridge: server=${awgConfig!!.endpoint} port=$awgServerPort addrBytes=[$addrHex]")
        } else {
            AppLogger.w(TAG, "AWG endpoint is not a Yggdrasil address — WG bridge disabled")
        }
        yggMgr.start(peers, yggKey, multicast)

        // The overlay address is deterministic from the private key — available immediately
        // after startJSON(), no need to wait for peer connections.
        val yggAddress = yggMgr.getAddress().ifEmpty { "200::" }
        AppLogger.i(TAG, "Yggdrasil address: $yggAddress")

        val physicalHasIPv6: Boolean = hasPhysicalIPv6()
        AppLogger.i(TAG, "physicalHasIPv6=$physicalHasIPv6")

        // Separate exclusions by address family.
        // IPv6 peer exclusions only make sense when the physical network has IPv6.
        val ipv4Exclusions: Set<Inet4Address> = peerIps.filterIsInstance<Inet4Address>().toSet()
        val ipv6Exclusions: Set<Inet6Address> = if (physicalHasIPv6) {
            peerIps.filterIsInstance<Inet6Address>().toSet()
        } else {
            val skipped = peerIps.filterIsInstance<Inet6Address>()
            if (skipped.isNotEmpty()) {
                AppLogger.w(TAG, "Physical network has no IPv6 — ${skipped.size} IPv6 peer(s) will " +
                                 "be unreachable: ${skipped.joinToString { it.hostAddress ?: "?" }}")
            }
            emptySet()
        }

        val builder = Builder()
            .setSession("Holowbark")
            .setMtu(1500)

        // Add WG client address from config (e.g. "10.9.0.2/32")
        awgConfig?.address?.let { addr ->
            val slash = addr.indexOf('/')
            val ip     = if (slash >= 0) addr.substring(0, slash) else addr
            val prefix = if (slash >= 0) addr.substring(slash + 1).toIntOrNull() ?: 32 else 32
            runCatching { builder.addAddress(ip, prefix) }
                .onFailure { AppLogger.w(TAG, "addAddress $addr: $it") }
        } ?: run {
            builder.addAddress("10.100.0.1", 32)
        }
        // Use the real Yggdrasil address so replies to user-initiated connections
        // are routed correctly through the overlay back to this node.
        builder.addAddress(yggAddress, 7)
        // Tricks Android's DNS resolver into issuing AAAA queries even when there is no
        // global IPv6 on the physical network. Without this single /128 host route the
        // resolver skips AAAA lookups entirely, making Yggdrasil service names unresolvable.
        // See android.googlesource.com/.../bionic/libc/dns/net/getaddrinfo.c#1935
        builder.addRoute("2000::", 128)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: catch-all routes + excludeRoute per IP (no route-count explosion).
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            (ipv4Exclusions + ipv6Exclusions).forEach { ip ->
                val prefix = if (ip is Inet4Address) 32 else 128
                runCatching { builder.excludeRoute(IpPrefix(ip, prefix)) }
                    .onFailure { AppLogger.w(TAG, "excludeRoute $ip: $it") }
            }
            AppLogger.i(TAG, "Routes: catch-all + ${ipv4Exclusions.size} IPv4 + ${ipv6Exclusions.size} IPv6 exclusions (API 33+)")
        } else {
            // API < 33: excludeRoute unavailable — use route-splitting.
            // IPv4: max 32 routes per excluded IP.
            val ipv4Base = listOf(Route(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0))
            val ipv4Routes = buildRoutesExcluding(ipv4Base, ipv4Exclusions)
            ipv4Routes.forEach { r ->
                runCatching { builder.addRoute(r.address.hostAddress ?: return@forEach, r.prefix) }
                    .onFailure { AppLogger.w(TAG, "addRoute ${r.address.hostAddress}/${r.prefix}: $it") }
            }
            // IPv6: route-splitting only when physical IPv6 is available (max 128 routes per IP).
            if (ipv6Exclusions.isNotEmpty()) {
                val ipv6Base = listOf(Route(InetAddress.getByAddress(ByteArray(16)), 0))
                val ipv6Routes = buildRoutesExcluding(ipv6Base, ipv6Exclusions)
                ipv6Routes.forEach { r ->
                    runCatching { builder.addRoute(r.address.hostAddress ?: return@forEach, r.prefix) }
                        .onFailure { AppLogger.w(TAG, "addRoute [${r.address.hostAddress}]/${r.prefix}: $it") }
                }
                AppLogger.i(TAG, "Routes: split IPv4 (${ipv4Routes.size}) + split IPv6 (${ipv6Routes.size}), excl ${ipv4Exclusions.size}+${ipv6Exclusions.size} (API<33)")
            } else {
                builder.addRoute("::", 0)
                AppLogger.i(TAG, "Routes: split IPv4 (${ipv4Routes.size}) + ::/0, excl ${ipv4Exclusions.size} IPv4 (API<33, no phys IPv6)")
            }
        }

        // AWG-provided DNS (private resolver behind the tunnel)
        awgConfig?.dns?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach {
                runCatching { builder.addDnsServer(it) }
                    .onFailure { AppLogger.w(TAG, "addDnsServer $it: $it") }
            }
        // Yggdrasil community DNS resolvers — only added when enabled by the user.
        // These are in 200::/7 and go through the Yggdrasil overlay automatically.
        val yggDnsEnabled = getSharedPreferences("yggawg", android.content.Context.MODE_PRIVATE)
            .getBoolean("ygg_dns_enabled", false)
        if (yggDnsEnabled) {
            YGG_DNS_SERVERS.forEach { dns ->
                runCatching { builder.addDnsServer(dns) }
                    .onFailure { AppLogger.w(TAG, "addDnsServer Ygg $dns: $it") }
            }
            AppLogger.i(TAG, "Yggdrasil DNS enabled (${YGG_DNS_SERVERS.size} resolvers)")
        }
        val fd = builder.establish() ?: run {
            AppLogger.e(TAG, "establish() returned null — VPN permission not granted")
            updateStatus { copy(overall = VpnState.ERROR) }
            return
        }
        tunFd = fd

        val routerObj = PacketRouter(tunFd = fd, ygg = yggMgr, awg = awgMgr)
        ygg    = yggMgr
        awg    = awgMgr
        router = routerObj
        YggServiceAccess.manager = yggMgr

        routerObj.start()

        if (awgConfig != null) {
            if (awgServerAddrBytes != null) {
                // Defer AWG start: wait for Yggdrasil peers, then ping server, then start tunnel
                savedAwgConfig          = awgConfig
                savedAwgServerAddrBytes = awgServerAddrBytes
                savedAwgServerPort      = awgServerPort
                launchAwgLifecycle(awgConfig, awgMgr, yggMgr, awgServerAddrBytes, awgServerPort)
            } else {
                // Non-Yggdrasil endpoint: start AWG immediately (no bridge needed)
                awgMgr.start(awgConfig)
            }
        }

        startForeground(NOTIF_ID, buildNotification(TunnelStatus()))
        AppLogger.i(TAG, "VPN started, waiting for peer connections")
    }

    /**
     * Launch (or re-launch) the AWG lifecycle coroutine:
     *   1. Wait until at least one Yggdrasil peer is UP
     *   2. Ping the AWG server through Yggdrasil (retry every 5 s on failure)
     *   3. Start the AWG backend
     *   4. Run the AWG→Ygg bridge loop
     */
    private fun launchAwgLifecycle(
        awgConfig: AwgConfig,
        awgMgr: AwgManager,
        yggMgr: YggdrasilManager,
        serverAddrBytes: ByteArray,
        serverPort: Int,
    ) {
        awgLifecycleScope?.cancel()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        awgLifecycleScope = scope

        scope.launch {
            // 1. Wait for at least one Yggdrasil peer to be UP
            AppLogger.i(TAG, "AWG: waiting for Yggdrasil peer…")
            YggNetworkState.peers.first { it.any { p -> p.up } }
            if (!isActive) return@launch

            // 2. Ping AWG server with retries until reachable
            val addrStr = runCatching {
                Inet6Address.getByAddress(serverAddrBytes).hostAddress
            }.getOrNull() ?: run {
                AppLogger.e(TAG, "AWG: cannot format server address")
                updateStatus { copy(awg = LayerState.ERROR) }
                return@launch
            }

            var attempt = 0
            while (isActive) {
                attempt++
                AppLogger.i(TAG, "AWG: pinging $addrStr (attempt $attempt)…")
                val ms = yggMgr.pingYgg(addrStr, timeoutMs = 5000L)
                if (ms != null) {
                    AppLogger.i(TAG, "AWG server reachable in ${ms}ms — starting tunnel")
                    break
                }
                AppLogger.w(TAG, "AWG server unreachable, retrying in 5s")
                delay(5_000)
            }
            if (!isActive) return@launch

            // 3. Start AWG backend
            awgMgr.start(awgConfig)
            // Give the Go runtime a moment to fully initialise the WG device
            delay(300)

            // 4. Bridge loop: AWG outbound WG packets → encapsulate in IPv6 UDP → Yggdrasil
            AppLogger.i(TAG, "AWG→Ygg bridge started, ourAddr=${yggMgr.getAddress()} serverAddr=$addrStr:$serverPort")

            // Trigger WG handshake; repeat every 3 s in a separate job until the first
            // WG packet arrives (handshake initiated) so we don't hang forever on a single trigger.
            val triggerJob = launch {
                while (isActive) {
                    AppLogger.d(TAG, "AWG bridge: sending handshake trigger packet")
                    awgMgr.writePacket(buildDummyIPv4())
                    delay(3_000)
                }
            }
            var wgPktCount = 0
            while (isActive) {
                val wgPkt = awgMgr.recvWGPacket()
                if (wgPkt == null) {
                    AppLogger.w(TAG, "AWG bridge: recvWGPacket returned null — exiting bridge loop")
                    break
                }
                if (wgPktCount == 0) triggerJob.cancel()   // handshake initiated — stop sending triggers
                wgPktCount++
                val ourAddrStr   = yggMgr.getAddress()
                val ourAddrBytes = parseYggSelfAddr(ourAddrStr)
                if (ourAddrBytes == null) {
                    AppLogger.w(TAG, "AWG bridge: our Ygg address not available yet, skipping pkt #$wgPktCount")
                    continue
                }
                val ipPkt = buildIPv6UDP(
                    srcAddr = ourAddrBytes,
                    dstAddr = serverAddrBytes,
                    srcPort = WG_LOCAL_PORT,
                    dstPort = serverPort,
                    payload = wgPkt,
                )
                yggMgr.writePacket(ipPkt)
            }
            triggerJob.cancel()
            AppLogger.i(TAG, "AWG→Ygg bridge exited after $wgPktCount packet(s)")
        }
    }

    private fun stopVpn() {
        AppLogger.i(TAG, "stopVpn")
        YggServiceAccess.manager = null
        YggNetworkState.reset()
        awgLifecycleScope?.cancel(); awgLifecycleScope = null
        savedAwgConfig = null; savedAwgServerAddrBytes = null
        router?.stop(); awg?.stop(); ygg?.stop(); tunFd?.close()
        router = null; awg = null; ygg = null; tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        status = TunnelStatus(overall = VpnState.DISCONNECTED)
        broadcastStatus()
    }

    /** Tear down and restart only the AWG layer (Yggdrasil keeps running). */
    private fun restartAwg() {
        val config    = savedAwgConfig          ?: run { AppLogger.w(TAG, "restartAwg: no config"); return }
        val addrBytes = savedAwgServerAddrBytes ?: run { AppLogger.w(TAG, "restartAwg: no addr"); return }
        val yggMgr    = ygg                    ?: run { AppLogger.w(TAG, "restartAwg: Ygg not running"); return }
        val awgMgr    = awg                    ?: run { AppLogger.w(TAG, "restartAwg: AWG manager missing"); return }

        AppLogger.i(TAG, "restartAwg: tearing down AWG lifecycle")
        awgLifecycleScope?.cancel(); awgLifecycleScope = null
        awgMgr.stop()
        updateStatus { copy(awg = LayerState.STARTING) }
        launchAwgLifecycle(config, awgMgr, yggMgr, addrBytes, savedAwgServerPort)
    }

    fun updatePeers(newPeers: List<String>) {
        ygg?.setPeers(newPeers)
    }

    // -------------------------------------------------------------------------
    // Status helpers
    // -------------------------------------------------------------------------

    @Synchronized
    private fun updateStatus(block: TunnelStatus.() -> TunnelStatus) {
        val s = status.block()
        val overall = when {
            s.ygg == LayerState.ERROR || s.awg == LayerState.ERROR -> VpnState.ERROR
            s.ygg == LayerState.UP && (s.awg == LayerState.UP || s.awg == LayerState.IDLE) -> VpnState.CONNECTED
            s.ygg == LayerState.STARTING || s.awg == LayerState.STARTING -> VpnState.CONNECTING
            else -> s.overall
        }
        status = s.copy(overall = overall)
        AppLogger.d(TAG, "updateStatus: ygg=${s.ygg} awg=${s.awg} → overall=$overall")
        broadcastStatus()
        if (overall == VpnState.CONNECTED || overall == VpnState.CONNECTING || overall == VpnState.ERROR) {
            val notif = buildNotification(status)
            val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            mgr.notify(NOTIF_ID, notif)
        }
    }

    private fun broadcastStatus() {
        val s = status
        getSharedPreferences("yggawg", android.content.Context.MODE_PRIVATE)
            .edit().putString("vpn_state", s.overall.name).apply()
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(TunnelStatus.EXTRA_OVERALL,     s.overall.name)
            putExtra(TunnelStatus.EXTRA_YGG,         s.ygg.name)
            putExtra(TunnelStatus.EXTRA_YGG_ADDRESS, s.yggAddress)
            putExtra(TunnelStatus.EXTRA_YGG_PEERS,   s.yggPeers)
            putExtra(TunnelStatus.EXTRA_AWG,         s.awg.name)
        })
        // Re-post notification on every broadcast so it reappears after being
        // swiped away (Android 14 allows dismissing FGS notifications).
        if (s.overall != VpnState.IDLE && s.overall != VpnState.DISCONNECTED) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(s))
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(s: TunnelStatus): Notification {
        val text = when (s.overall) {
            VpnState.CONNECTED  -> "Ygg: ${s.yggAddress} | peers: ${s.yggPeers}"
            VpnState.CONNECTING -> "Connecting…"
            VpnState.ERROR      -> "Error"
            else                -> "Connecting…"
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, YggVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, HolowbarkApp.VPN_NOTIF_CHANNEL)
            .setContentTitle("Holowbark")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (s.overall == VpnState.CONNECTED || s.overall == VpnState.CONNECTING) {
            builder.addAction(R.drawable.ic_vpn_key, "Disconnect", stopIntent)
        }
        return builder.build()
    }
}

/**
 * Return true if the active physical network has a globally routable IPv6 address.
 * Used to decide whether IPv6 peer exclusions are worth adding to VPN routes.
 *
 * Excluded address ranges (not globally routable):
 *   ::1/128       loopback
 *   fe80::/10     link-local
 *   fc00::/7      ULA (Unique Local Addresses — private, like RFC-1918 for IPv4)
 *   200::/7       Yggdrasil overlay
 */
internal fun VpnService.hasPhysicalIPv6(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val network = cm.activeNetwork ?: return false
    val lp = cm.getLinkProperties(network) ?: return false
    return lp.linkAddresses.any { la: LinkAddress ->
        val a = la.address
        a is Inet6Address
            && !a.isLinkLocalAddress
            && !a.isLoopbackAddress
            && (a.address[0].toInt() and 0xFE) != 0x02  // not Yggdrasil 200::/7
            && (a.address[0].toInt() and 0xFE) != 0xFC  // not ULA fc00::/7
    }
}

/**
 * Resolve all IP addresses for a Yggdrasil peer URI.
 * Returns every address (A + AAAA) so all are excluded from VPN routes.
 * For numeric IPs the result is a single-element list (no DNS call).
 * For hostnames, DNS is queried before the VPN tunnel is established.
 *
 *   "tcp://89.44.86.85:12345"            → [89.44.86.85]
 *   "quic://[2a09:5302:ffff::132a]:65535" → [2a09:5302:ffff::132a]
 *   "tls://hostname.example.com:443"     → [<all A/AAAA records>] or [] on failure
 */
internal fun parsePeerHosts(addr: String): List<InetAddress> {
    val hostPart = addr.substringAfter("://")
    val host = if (hostPart.startsWith("[")) {
        hostPart.removePrefix("[").substringBefore("]")
    } else {
        hostPart.substringBefore(':')
    }
    return runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
}
