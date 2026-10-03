package net.holowbark.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.holowbark.AppLogger
import net.holowbark.MainActivity
import net.holowbark.R
import net.holowbark.HolowbarkApp
import net.holowbark.config.AwgConfig
import net.holowbark.config.parseAwgConf
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class TunnelService : VpnService() {
    companion object {
        private const val TAG = "TunnelService"
        private const val NOTIF_ID = 1
        private const val PEER_DNS_TIMEOUT_SECONDS = 3L

        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val WATCHDOG_INTERVAL_IDLE_MS = 240_000L
        private const val WATCHDOG_PING_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_FAILURES_BEFORE_RECOVERY = 3
        private const val RECOVERY_COOLDOWN_MS = 120_000L

        /** How often the idle policy looks at what the tunnel is carrying. */
        private const val IDLE_TICK_MS = 30_000L
        /** Traffic in one tick below which the tunnel counts as unused. Background
         *  sync alone keeps a pocketed phone above zero, so the line has to sit
         *  above chatter and well below anything a person is waiting for. */
        private const val IDLE_QUIET_BYTES = 8L * 1024
        /** Consecutive quiet ticks, screen off, before the radio locks are let go. */
        private const val IDLE_QUIET_TICKS = 4
        /** Traffic after a release that means an app wants the tunnel back. */
        private const val WAKE_BYTES = 32L * 1024
        /** How long the overlay is given to redial before the wake probe judges it. */
        private const val WAKE_PROBE_DELAY_MS = 5_000L
        private const val WAKE_PROBE_ATTEMPTS = 2

        /** True while the VPN is actually up in this process. Prefs alone can go
         *  stale after process death/reboot — always check this alongside them. */
        @Volatile var isRunning = false
            private set

        const val ACTION_START       = "net.holowbark.START_VPN"
        const val ACTION_STOP        = "net.holowbark.STOP_VPN"
        const val ACTION_STATUS      = "net.holowbark.VPN_STATUS"
        const val ACTION_RESTART_AWG = "net.holowbark.RESTART_AWG"

        const val EXTRA_AWG_CONF   = "awg_conf"
        const val EXTRA_YGG_PEERS  = "peer_uris"      // ArrayList<String>
        const val EXTRA_YGG_KEY    = "ygg_key"

        // Shared secret for LAN discovery; empty means discovery is off.
        const val EXTRA_MULTICAST_PASSWORD = "ygg_multicast_password"

        // Community resolvers run by Revertron, serving .ygg alongside ICANN, ALFIS
        // and OpenNIC. All are inside 200::/7, so they route over the overlay.
        val YGG_DNS_SERVERS = listOf(
            "308:62:45:62::",   // Amsterdam
            "308:84:68:55::",   // Frankfurt
            "308:25:40:bd::",   // Bratislava
            "308:c8:48:45::",   // Buffalo
        )

        fun startIntent(
            context: Context,
            peers: List<String>,
            awgConf: String?,
            yggKey: String,
            multicastPassword: String = "",
        ): Intent = Intent(context, TunnelService::class.java).apply {
            action = ACTION_START
            putStringArrayListExtra(EXTRA_YGG_PEERS, ArrayList(peers))
            putExtra(EXTRA_AWG_CONF, awgConf)
            putExtra(EXTRA_YGG_KEY, yggKey)
            putExtra(EXTRA_MULTICAST_PASSWORD, multicastPassword)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var ygg: YggdrasilManager? = null
    private var awg: AwgManager? = null
    private var router: PacketRouter? = null
    /** Uids of the apps the split names, for the router's owner filter. */
    private var splitUids: Set<Int> = emptySet()

    // Manages deferred AWG start (ping wait) + bridge loop; cancelled/restarted on restartAwg()
    private var awgLifecycleScope: CoroutineScope? = null

    // Saved for restartAwg() so we don't need to re-parse the intent
    private var savedAwgConfig: AwgConfig? = null
    private var savedAwgServerAddrBytes: ByteArray? = null
    private var savedAwgServerPort: Int = 44555

    // What the overlay was started with, so recovery can start it the same way.
    private var savedPeers: List<String> = emptyList()
    private var savedYggKey: String = ""
    private var savedMulticast: String = ""

    private var watchdogScope: CoroutineScope? = null

    /** Owns the idle countdown and the wake probe, which outlive neither the tunnel
     *  nor each other. */
    private var powerScope: CoroutineScope? = null
    private var idleJob: Job? = null
    @Volatile private var screenOn = true
    @Volatile private var idle = false
    /** The server's overlay address, kept for the watchdog and the wake probe. */
    private var serverAddress = ""

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    /** Every non-VPN network with internet the callback currently reports. Empty in
     *  airplane mode and out of coverage. `activeNetwork` cannot answer this: while
     *  the tunnel is up, it is the tunnel. */
    private val physicalNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var lastNotifText = ""
    private var dnsProxyInstance: SplitDnsProxy? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var status = TunnelStatus()
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastRecoveryAt = 0L
    @Volatile private var recoveryStep = 0

    // Lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP        -> { stopVpn(); START_NOT_STICKY }
            ACTION_RESTART_AWG -> { restartAwg(); START_STICKY }
            ACTION_START -> {
                Prefs.of(this).run { awgConfInUse = awgConfName }
                val awgConfig = intent.getStringExtra(EXTRA_AWG_CONF)
                    ?.let { runCatching { parseAwgConf(it) }.getOrNull() }
                startVpn(
                    awgConfig = awgConfig,
                    peers     = intent.getStringArrayListExtra(EXTRA_YGG_PEERS).orEmpty(),
                    yggKey    = intent.getStringExtra(EXTRA_YGG_KEY).orEmpty(),
                    multicastPassword = intent.getStringExtra(EXTRA_MULTICAST_PASSWORD).orEmpty(),
                )
                START_STICKY
            }
            // Granting VPN consent makes the system start this service on its
            // android.net.VpnService filter, and a sticky restart delivers a null
            // intent. Neither carries a peer list or a config: starting a tunnel
            // from one leaves an empty overlay that swallows the real start as a
            // duplicate.
            else -> START_STICKY
        }
    }

    override fun onRevoke() = stopVpn()

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // VPN start / stop

    private fun startVpn(
        awgConfig: AwgConfig?,
        peers: List<String>,
        yggKey: String,
        multicastPassword: String,
    ) {
        if (tunFd != null) {
            AppLogger.w(TAG, "VPN already running — ignoring duplicate start")
            return
        }
        AppLogger.i(TAG, "startVpn peers=${peers.size} awg=${awgConfig?.endpoint} " +
            "mtu=${awgConfig?.effectiveMtu ?: AwgConfig.DEFAULT_MTU} " +
            "discovery=${multicastPassword.isNotEmpty()}")
        updateStatus {
            copy(
                overall = VpnState.CONNECTING,
                ygg = LayerState.STARTING,
                awg = if (awgConfig != null) LayerState.STARTING else LayerState.IDLE,
            )
        }
        // API 26+ kills the process unless startForeground() lands within 5 s of
        // startForegroundService(), and the Go runtime below takes seconds to come up.
        startForeground(NOTIF_ID, buildNotification(status))

        // Peer hostnames need DNS, which is only reachable before the tunnel exists.
        // Resolve them while Yggdrasil starts rather than in series with it.
        val peerIpsFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            peers.toSet().parallelStream()
                .flatMap { parsePeerHosts(it).stream() }
                .collect(java.util.stream.Collectors.toSet<InetAddress>())
        }
        val preVpnDns = readSystemDns()

        val serverAddr = awgConfig?.let { parseIpv6Bytes(it.endpoint) }
        val serverPort = awgConfig?.let { parseEndpointPort(it.endpoint) } ?: 44555

        val awgMgr = AwgManager(
            onPacketOut = { router?.writeToTun(it) },
            onStatusChange = { state -> updateStatus { copy(awg = state) } },
        )
        val yggMgr = YggdrasilManager(
            onPacketOut = { router?.writeToTun(it) },
            onWGPacket = if (serverAddr != null) awgMgr::sendWGPacket else null,
            onStatusChange = { state, addr, count ->
                updateStatus { copy(ygg = state, yggAddress = addr, yggPeers = count) }
            },
            onServerKeyLearned = { key ->
                awgConfig?.let { Prefs.of(this).saveServerKey(it.endpoint, key) }
            },
        )
        if (serverAddr != null) {
            yggMgr.wgServerAddr = serverAddr
            yggMgr.serverKey = Prefs.of(this).serverKey(awgConfig!!.endpoint)
            AppLogger.i(TAG, "WG bridge: server=${awgConfig!!.endpoint} port=$serverPort")
        } else {
            AppLogger.w(TAG, "AWG endpoint is not a Yggdrasil address — WG bridge disabled")
        }

        // Started before establish() so the overlay address, which is derived from
        // the private key and available immediately, can be assigned to the TUN.
        // Callbacks read router/awg through nullable fields, so packets arriving
        // during this window are dropped rather than crashing.
        savedPeers = peers
        savedYggKey = yggKey
        savedMulticast = multicastPassword
        yggMgr.start(peers, yggKey, multicastPassword)
        val yggAddress = yggMgr.getAddress().ifEmpty { "200::" }
        AppLogger.i(TAG, "Yggdrasil address: $yggAddress")

        watchPhysicalNetwork()
        watchScreenState()

        val fd = buildTunnel(awgConfig, yggAddress, collectPeerIps(peerIpsFuture), preVpnDns, yggMgr)
            ?: run {
                AppLogger.e(TAG, "establish() returned null — VPN permission not granted")
                releaseResources()
                yggMgr.stop()
                updateStatus { copy(overall = VpnState.ERROR) }
                return
            }
        tunFd = fd
        isRunning = true

        powerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        router = PacketRouter(
            tunFd = fd,
            ygg = yggMgr,
            awg = awgMgr,
            dnsProxy = dnsProxyInstance,
            onWake = { onActivity("an app wants the tunnel") },
            appUids = splitUids,
            isAllowList = Prefs.of(this).appsAllowList,
            ownerLookup = ownerLookup(),
        )
        ygg = yggMgr
        awg = awgMgr
        YggNetworkState.manager = yggMgr
        dnsProxyInstance?.let { yggMgr.dnsProxy = it }
        router?.start()

        if (awgConfig != null) {
            if (serverAddr != null) {
                // Remember what restartAwg() needs so it does not re-parse the intent.
                savedAwgConfig = awgConfig
                savedAwgServerAddrBytes = serverAddr
                savedAwgServerPort = serverPort
                launchAwgLifecycle(awgConfig, awgMgr, yggMgr, serverAddr, serverPort)
            } else {
                // A plain internet endpoint needs no overlay bridge.
                awgMgr.start(awgConfig)
            }
        }

        acquireWifiLock()
        if (multicastPassword.isNotEmpty()) acquireMulticastLock()
        if (serverAddr != null) startWatchdog(serverAddr)
        // Starting with the screen already off is the normal case for a tile tap
        // from the lock screen, and the receiver only reports transitions.
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!screenOn) startIdleCountdown()
        AppLogger.i(TAG, "VPN started, waiting for peer connections")
    }

    /**
     * Build and establish the TUN interface. Null when the user has not granted
     * VPN permission, which is the only way establish() fails.
     */
    private fun buildTunnel(
        awgConfig: AwgConfig?,
        yggAddress: String,
        peerIps: Set<InetAddress>,
        preVpnDns: InetAddress?,
        yggMgr: YggdrasilManager,
    ): ParcelFileDescriptor? {
        // Both layers must agree: a packet the Android TUN accepts but the AWG
        // device will not is simply dropped, with nothing logged either side.
        val mtu = awgConfig?.effectiveMtu ?: AwgConfig.DEFAULT_MTU
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(mtu)

        // The WG client address from the config, e.g. "10.9.0.2/32".
        val clientAddress = awgConfig?.address
        if (clientAddress != null) {
            val ip = clientAddress.substringBefore('/')
            val prefix = clientAddress.substringAfter('/', "32").toIntOrNull() ?: 32
            runCatching { builder.addAddress(ip, prefix) }
                .onFailure { AppLogger.w(TAG, "addAddress $clientAddress: $it") }
        } else {
            builder.addAddress("10.100.0.1", 32)
        }
        // Our real overlay address, so replies to connections we start come back here.
        builder.addAddress(yggAddress, 7)

        configureRoutes(builder, peerIps)
        configureDns(builder, awgConfig, preVpnDns, yggMgr)
        applyAppSplit(builder)
        return builder.establish()
    }

    private fun configureRoutes(builder: Builder, peerIps: Set<InetAddress>) {
        // A single /128 host route makes Android's resolver issue AAAA queries even
        // with no global IPv6 on the physical network. Without it, .ygg names never
        // resolve. See bionic/libc/dns/net/getaddrinfo.c, the "have IPv6" check.
        builder.addRoute("2000::", 128)

        // Excluding an IPv6 peer is pointless when the physical network cannot
        // reach IPv6 at all, and costs 128 routes below API 33.
        val physicalHasIPv6 = hasPhysicalIPv6()
        val bypassed = Prefs.of(this).bypassedSubnets.mapNotNull { text ->
            parseSubnet(text).also {
                if (it == null) AppLogger.w(TAG, "Not a subnet, ignored: $text")
            }
        }
        val ipv4Peers = peerIps.filterIsInstance<Inet4Address>()
        val ipv6Peers = peerIps.filterIsInstance<Inet6Address>()
        val ipv4Exclusions: Set<Route> = ipv4Peers.map(::hostRoute).toSet() +
            bypassed.filter { it.address is Inet4Address }
        val ipv6Exclusions: Set<Route> =
            (if (physicalHasIPv6) ipv6Peers.map(::hostRoute).toSet() else emptySet()) +
            bypassed.filter { it.address is Inet6Address }
        if (!physicalHasIPv6 && ipv6Peers.isNotEmpty()) {
            AppLogger.w(TAG, "No physical IPv6 — ${ipv6Peers.size} IPv6 peer(s) unreachable: " +
                ipv6Peers.joinToString { it.hostAddress ?: "?" })
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            (ipv4Exclusions + ipv6Exclusions).forEach { route ->
                runCatching { builder.excludeRoute(IpPrefix(route.address, route.prefix)) }
                    .onFailure { AppLogger.w(TAG, "excludeRoute $route: $it") }
            }
            AppLogger.i(TAG, "Routes: catch-all, excluding " +
                "${ipv4Exclusions.size} IPv4 + ${ipv6Exclusions.size} IPv6")
            return
        }

        // Below API 33 there is no excludeRoute, so the catch-all is replaced by the
        // sub-routes that cover everything but the exclusions. See RouteSplitter.
        val ipv4Routes = buildRoutesExcluding(
            listOf(Route(InetAddress.getByAddress(ByteArray(4)), 0)), ipv4Exclusions)
        ipv4Routes.forEach { builder.addRouteOrWarn(it) }
        if (ipv6Exclusions.isEmpty()) {
            builder.addRoute("::", 0)
            AppLogger.i(TAG, "Routes: ${ipv4Routes.size} IPv4 + ::/0, " +
                "excluding ${ipv4Exclusions.size} IPv4 (API < 33)")
            return
        }
        val ipv6Routes = buildRoutesExcluding(
            listOf(Route(InetAddress.getByAddress(ByteArray(16)), 0)), ipv6Exclusions)
        ipv6Routes.forEach { builder.addRouteOrWarn(it) }
        AppLogger.i(TAG, "Routes: ${ipv4Routes.size} IPv4 + ${ipv6Routes.size} IPv6, excluding " +
            "${ipv4Exclusions.size} + ${ipv6Exclusions.size} (API < 33)")
    }

    /**
     * Apply the app split. A package that has since been uninstalled throws, and
     * the only sane answer is to carry on without it.
     *
     * In allow-list mode this app is deliberately left out, so the overlay
     * transport keeps running outside the tunnel it carries.
     */
    private fun applyAppSplit(builder: Builder) {
        val prefs = Prefs.of(this)
        val apps = prefs.bypassedApps
        if (apps.isEmpty()) {
            // An empty allow list would be a tunnel no app can use, which reads as
            // a dead VPN rather than as the setting it is.
            if (prefs.appsAllowList) AppLogger.w(TAG, "Allow list is empty — every app stays in the tunnel")
            return
        }
        val allowList = prefs.appsAllowList
        val applied = apps.filter { pkg ->
            runCatching {
                if (allowList) builder.addAllowedApplication(pkg)
                else builder.addDisallowedApplication(pkg)
            }.onFailure { AppLogger.w(TAG, "app split $pkg: $it") }.isSuccess
        }
        splitUids = applied.mapNotNull { pkg ->
            runCatching { packageManager.getPackageUid(pkg, 0) }
                .onFailure { AppLogger.w(TAG, "uid of $pkg: $it") }.getOrNull()
        }.toSet()
        if (allowList) AppLogger.i(TAG, "Apps inside the tunnel: ${applied.size} of ${apps.size}")
        else AppLogger.i(TAG, "Apps outside the tunnel: ${applied.size} of ${apps.size}")
    }

    /**
     * The owner lookup that keeps split-out apps from binding to the TUN. Only
     * needed when the split names apps, and only possible from API 29.
     */
    private fun ownerLookup(): OwnerLookup? {
        if (splitUids.isEmpty()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLogger.w(TAG, "No owner lookup below API 29 — split-out apps can bind to the TUN")
            return null
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        return { protocol, local, remote -> cm.getConnectionOwnerUid(protocol, local, remote) }
    }

    private fun Builder.addRouteOrWarn(route: Route) {
        val host = route.address.hostAddress ?: return
        runCatching { addRoute(host, route.prefix) }
            .onFailure { AppLogger.w(TAG, "addRoute $host/${route.prefix}: $it") }
    }

    private fun configureDns(
        builder: Builder,
        awgConfig: AwgConfig?,
        preVpnDns: InetAddress?,
        yggMgr: YggdrasilManager,
    ) {
        val awgDns = awgConfig?.dns.orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val yggResolver = if (Prefs.of(this).yggDnsEnabled) {
            runCatching { InetAddress.getByName(YGG_DNS_SERVERS.first()) as Inet6Address }
                .onFailure { AppLogger.w(TAG, "Ygg DNS resolver unavailable: $it") }
                .getOrNull()
        } else null

        if (yggResolver == null) {
            awgDns.forEach { server ->
                runCatching { builder.addDnsServer(server) }
                    .onFailure { AppLogger.w(TAG, "addDnsServer $server: $it") }
            }
            return
        }

        // The system resolver is pointed at a local address the router intercepts,
        // so .ygg names can go to the overlay and everything else stays on the
        // config's DNS — or, failing that, whatever the network used before us.
        val upstream = awgDns.firstOrNull()
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?: preVpnDns
        dnsProxyInstance = SplitDnsProxy(
            upstreamDns = upstream,
            yggDnsResolver = yggResolver,
            yggMgr = yggMgr,
            protect = ::protect,
            writeToTun = { router?.writeToTun(it) },
        )
        runCatching { builder.addDnsServer(SplitDnsProxy.PROXY_ADDRESS) }
            .onFailure { AppLogger.w(TAG, "addDnsServer proxy: $it") }
        AppLogger.i(TAG, "Split DNS proxy enabled, upstream=$upstream")
    }

    /** The peer addresses resolved in the background, or none if DNS was too slow. */
    private fun collectPeerIps(
        future: java.util.concurrent.CompletableFuture<Set<InetAddress>>,
    ): Set<InetAddress> = try {
        future.get(PEER_DNS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: Exception) {
        AppLogger.w(TAG, "Peer DNS timed out — route exclusions may be incomplete")
        future.cancel(true)
        emptySet()
    }

    /** The network's DNS server, read before the tunnel replaces it. */
    private fun readSystemDns(): InetAddress? = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val links = cm?.getLinkProperties(cm.activeNetwork)
        links?.dnsServers?.firstOrNull { it is Inet4Address } ?: links?.dnsServers?.firstOrNull()
    } catch (e: Exception) {
        AppLogger.w(TAG, "pre-VPN DNS read failed: $e")
        null
    }

    /** Yggdrasil's own backoff is slow; a network change is a reason to redial now. */
    private fun watchPhysicalNetwork() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                physicalNetworks.add(network)
                AppLogger.i(TAG, "Physical network available — retrying Ygg peers")
                consecutiveFailures = 0
                ygg?.retryPeers()
                onActivity("a network came back")
            }
            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                AppLogger.d(TAG, "Physical network lost")
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                AppLogger.d(TAG, "Link properties changed — retrying Ygg peers")
                ygg?.retryPeers()
            }
        }
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
        netCallback = callback
    }

    /**
     * Nobody reads the peer list with the screen off, so poll it less often — and
     * a phone in a pocket has no use for a radio held out of power saving either.
     */
    private fun watchScreenState() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val off = intent.action == Intent.ACTION_SCREEN_OFF
                ygg?.slowPolling = off
                screenOn = !off
                if (off) startIdleCountdown() else onActivity("the screen came on")
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    /**
     * Android drops multicast for the app unless a lock is held, so local discovery
     * silently receives nothing without this — it fails by doing nothing at all,
     * which is why it is worth holding even though it costs battery.
     */
    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("holowbark:discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Wi-Fi power saving parks the radio between packets and stalls the overlay. */
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "holowbark:vpn")
        wifiLock?.acquire()
    }

    private fun releaseRadioLocks() {
        wifiLock?.let { runCatching { it.release() } }
        wifiLock = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    private fun releaseResources() {
        netCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        }
        netCallback = null
        physicalNetworks.clear()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        dnsProxyInstance?.stop()
        dnsProxyInstance = null
        releaseRadioLocks()
    }

    /**
     * Let the radio sleep once the screen has been off through
     * [IDLE_QUIET_TICKS] ticks carrying less than [IDLE_QUIET_BYTES] each. A track
     * playing into a pocketed phone moves far more than that and keeps the locks;
     * a push heartbeat does not and no longer counts as somebody using the tunnel.
     *
     * Releasing happens once per idle period. Cycling the lock is what tore the
     * overlay down in the app this policy was measured on.
     */
    private fun startIdleCountdown() {
        idleJob?.cancel()
        idleJob = powerScope?.launch {
            var quietTicks = 0
            var previousBytes = router?.outboundBytes ?: 0L
            while (isActive) {
                delay(IDLE_TICK_MS)
                val bytes = router?.outboundBytes ?: 0L
                val carried = bytes - previousBytes
                previousBytes = bytes
                quietTicks = if (!screenOn && carried < IDLE_QUIET_BYTES) quietTicks + 1 else 0
                if (quietTicks >= IDLE_QUIET_TICKS) {
                    enterIdle()
                    return@launch
                }
            }
        }
    }

    private fun enterIdle() {
        if (idle) return
        idle = true
        AppLogger.i(TAG, "Idle: releasing the radio locks until an app wants ${WAKE_BYTES / 1024} KB")
        releaseRadioLocks()
        router?.armIdleGate(WAKE_BYTES)
    }

    /**
     * Come back from idle. The packet that woke us is an app asking for the
     * network, which is also the moment to find out whether the path it needs
     * still exists: the overlay may have lost its peers to a NAT timeout while the
     * radio slept. The app's own retransmit carries the data once it does, so the
     * cost of the repair is latency, not a failed request.
     */
    private fun onActivity(reason: String) {
        if (screenOn) {
            idleJob?.cancel()
            idleJob = null
        }
        if (!idle) {
            if (!screenOn) startIdleCountdown()
            return
        }
        idle = false
        powerScope?.launch {
            AppLogger.i(TAG, "Wake ($reason): re-acquiring the radio locks and redialling")
            acquireWifiLock()
            if (savedMulticast.isNotEmpty()) acquireMulticastLock()
            consecutiveFailures = 0
            ygg?.retryPeers()
            probeAfterWake()
            if (!screenOn) startIdleCountdown()
        }
    }

    /**
     * Probe once the overlay has had a moment to redial, and once more before
     * believing the answer. The peers were told to redial a moment ago, and a
     * redial that is merely slow must not cost the tunnel a restart.
     */
    private suspend fun probeAfterWake() {
        if (serverAddress.isEmpty()) return
        repeat(WAKE_PROBE_ATTEMPTS) { attempt ->
            delay(WAKE_PROBE_DELAY_MS)
            if (ygg?.pingYgg(serverAddress, WATCHDOG_PING_TIMEOUT_MS) != null) {
                AppLogger.i(TAG, "Wake probe: the server still answers")
                return
            }
            AppLogger.d(TAG, "Wake probe: no answer (${attempt + 1}/$WAKE_PROBE_ATTEMPTS)")
        }
        AppLogger.w(TAG, "Wake probe: no answer — recovering now rather than at the next tick")
        recover()
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
                    awgMgr.writePacket(buildHandshakeTrigger())
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
                val ourAddrBytes = parseIpv6Bytes(yggMgr.getAddress())
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
        // stopSelf() below re-enters here through onDestroy(); the guard makes the
        // second pass a no-op.
        if (!isRunning && tunFd == null) return
        AppLogger.i(TAG, "stopVpn")
        isRunning = false
        YggNetworkState.manager = null
        YggNetworkState.reset()
        ygg?.dnsProxy = null
        awgLifecycleScope?.cancel(); awgLifecycleScope = null
        watchdogScope?.cancel(); watchdogScope = null
        idleJob = null
        powerScope?.cancel(); powerScope = null
        idle = false
        serverAddress = ""
        savedAwgConfig = null; savedAwgServerAddrBytes = null
        savedPeers = emptyList(); savedYggKey = ""
        releaseResources()
        router?.stop(); awg?.stop(); ygg?.stop(); tunFd?.close()
        router = null; awg = null; ygg = null; tunFd = null
        splitUids = emptySet()
        lastNotifText = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        status = TunnelStatus(overall = VpnState.DISCONNECTED)
        broadcastStatus()
    }

    /**
     * Watch for the server going unreachable through the overlay, and rebuild the
     * overlay when it does.
     *
     * The probe is skipped entirely whenever a packet has recently come out of the
     * tunnel: traffic already proves the path works, so an active tunnel costs one
     * comparison per interval and no packets at all. Only an idle tunnel is pinged,
     * and only while the tunnel claims to be connected.
     *
     * A plain [delay] is deliberate. It holds no wakelock and schedules no alarm, so
     * a sleeping device simply does not run the check — which is the correct
     * trade: an unreachable server matters when the user next uses the phone, not
     * at 04:00.
     */
    private fun startWatchdog(serverAddr: ByteArray) {
        watchdogScope?.cancel()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        watchdogScope = scope

        serverAddress = runCatching { Inet6Address.getByAddress(serverAddr).hostAddress }
            .getOrNull() ?: return

        scope.launch {
            while (isActive) {
                delay(if (ygg?.slowPolling == true) WATCHDOG_INTERVAL_IDLE_MS
                      else WATCHDOG_INTERVAL_MS)
                // Idle means nothing is waiting on the tunnel; the wake edge repairs
                // it when something is, and a probe here would only spend the radio.
                if (idle) continue
                if (status.overall != VpnState.CONNECTED) continue
                if (trafficSeenRecently()) {
                    consecutiveFailures = 0
                    continue
                }
                if (ygg?.pingYgg(serverAddress, WATCHDOG_PING_TIMEOUT_MS) != null) {
                    consecutiveFailures = 0
                    continue
                }
                consecutiveFailures++
                AppLogger.w(TAG, "Watchdog: server unreachable ($consecutiveFailures/$WATCHDOG_FAILURES_BEFORE_RECOVERY)")
                if (consecutiveFailures >= WATCHDOG_FAILURES_BEFORE_RECOVERY) recover()
            }
        }
    }

    private fun trafficSeenRecently(): Boolean {
        val last = awg?.lastPacketAt ?: return false
        return System.currentTimeMillis() - last < WATCHDOG_INTERVAL_MS
    }

    /**
     * Escalate one rung at a time. Redialling costs nothing, restarting the AWG
     * layer leaves the overlay alone, and only a repeated failure is worth rebuilding
     * the overlay for.
     */
    private suspend fun recover() {
        // Redialling into airplane mode fails on every rung of the ladder. The
        // network callback redials by itself when a network comes back.
        if (physicalNetworks.isEmpty()) {
            AppLogger.i(TAG, "Recovery: no physical network — waiting for one")
            consecutiveFailures = 0
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) return
        lastRecoveryAt = now
        consecutiveFailures = 0

        when (recoveryStep++ % 3) {
            0 -> {
                AppLogger.i(TAG, "Recovery: redialling Yggdrasil peers")
                ygg?.retryPeers()
            }
            1 -> {
                AppLogger.i(TAG, "Recovery: restarting the tunnel layer")
                restartAwg()
            }
            else -> {
                // A node that answers nodeinfo is reachable through the mesh, so the
                // overlay is not what is broken — restarting it would cost every peer
                // connection for nothing.
                if (ygg?.probeServer() == true) {
                    AppLogger.i(TAG, "Recovery: server answers in the mesh — restarting the tunnel layer")
                    restartAwg()
                } else {
                    AppLogger.i(TAG, "Recovery: restarting Yggdrasil")
                    restartYgg()
                }
            }
        }
    }

    /**
     * Stop and start the overlay without touching the TUN. The overlay address comes
     * from the private key, so it is unchanged by a restart — the interface, its
     * routes and the VPN permission all stay valid, and the user sees no prompt.
     */
    private fun restartYgg() {
        val yggMgr = ygg ?: return
        updateStatus { copy(ygg = LayerState.STARTING) }
        yggMgr.stop()
        yggMgr.start(savedPeers, savedYggKey, savedMulticast)
        restartAwg()
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

    // Status helpers

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
    }

    private fun broadcastStatus() {
        val s = status
        Prefs.of(this).saveTunnelStatus(s)
        sendBroadcast(s.putInto(Intent(ACTION_STATUS).setPackage(packageName)))
        // Re-post the notification only when its text changes, so a 30 s peer poll
        // that reports the same numbers costs nothing.
        if (s.overall != VpnState.IDLE && s.overall != VpnState.DISCONNECTED) {
            val text = notificationText(s)
            if (text != lastNotifText) {
                lastNotifText = text
                val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                mgr.notify(NOTIF_ID, buildNotification(s))
            }
        }
    }

    // Notification

    private fun notificationText(s: TunnelStatus): String = when (s.overall) {
        VpnState.CONNECTED -> "Ygg: ${s.yggAddress} | peers: ${s.yggPeers}"
        VpnState.ERROR     -> getString(R.string.notif_error)
        else               -> getString(R.string.notif_connecting)
    }

    private fun buildNotification(s: TunnelStatus): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, HolowbarkApp.VPN_NOTIF_CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText(s))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (s.overall == VpnState.CONNECTED || s.overall == VpnState.CONNECTING) {
            builder.addAction(R.drawable.ic_vpn_key, getString(R.string.notif_disconnect), stopIntent)
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
 *   "tls://hostname.example.com:443"     → [<all A/AAAA records>], or [] on failure
 *
 * Blocks on DNS for hostname peers, so callers must run it off the main thread.
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
