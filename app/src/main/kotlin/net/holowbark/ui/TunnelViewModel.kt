package net.holowbark.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.holowbark.AppLogger
import net.holowbark.config.AwgConfig
import net.holowbark.config.parseAwgConf
import net.holowbark.config.toConfString
import net.holowbark.peers.PeerDatabase
import net.holowbark.peers.PeerRepository
import net.holowbark.peers.PeerSelection
import net.holowbark.peers.detectCountryIso
import net.holowbark.peers.models.CountryInfo
import net.holowbark.peers.models.countryKeyForIso
import net.holowbark.peers.models.peersToSelect
import net.holowbark.peers.probePeer
import net.holowbark.vpn.TunnelStatus
import net.holowbark.vpn.VpnState
import net.holowbark.vpn.YggNetworkState
import net.holowbark.vpn.PeerUriError
import net.holowbark.vpn.PeerUriException
import net.holowbark.vpn.Prefs
import net.holowbark.vpn.parsePeerUri
import net.holowbark.vpn.TunnelService
import net.holowbark.vpn.parseIpv6Bytes
import net.holowbark.vpn.parseSubnet
import java.net.Inet6Address

private const val TAG = "TunnelViewModel"
private const val RESTART_TEARDOWN_TIMEOUT_MS = 3_000L
// Enough to check a country in a few seconds without a burst of sockets.
private const val PROBE_PARALLELISM = 8
private const val INTERNET_PERMISSION = android.Manifest.permission.INTERNET
private const val PERMISSION_GRANTED = android.content.pm.PackageManager.PERMISSION_GRANTED

/** Yggdrasil keys BLAKE2b with the password, which caps it at the digest size. */
const val MULTICAST_PASSWORD_MAX = 64

/** One entry of the app list on the split tunneling screen. */
data class InstalledApp(val packageName: String, val label: String)

class TunnelViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs.of(app)
    val repo = PeerRepository(PeerDatabase.getInstance(app), app)

    // Prefs survive process death, so a stored status is only meaningful while the
    // service is still up in this process.
    private val _tunnelStatus = MutableStateFlow(
        if (TunnelService.isRunning) prefs.tunnelStatus() else TunnelStatus()
    )
    val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

    private val _awgConfig = MutableStateFlow<AwgConfig?>(null)
    val awgConfig: StateFlow<AwgConfig?> = _awgConfig.asStateFlow()

    /** Raw text of the imported .conf file; used for display so no fields are lost. */
    private val _rawConfText = MutableStateFlow<String?>(null)
    val rawConfText: StateFlow<String?> = _rawConfText.asStateFlow()

    private val _countries = MutableStateFlow<List<CountryInfo>>(emptyList())
    val countries: StateFlow<List<CountryInfo>> = _countries.asStateFlow()

    private val _selection = MutableStateFlow(PeerSelection())
    val selection: StateFlow<PeerSelection> = _selection.asStateFlow()

    /** Every peer the tunnel will dial, whoever chose it. */
    val selectedPeers: StateFlow<Set<String>> =
        _selection.map { it.all }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _autoPeerSearch = MutableStateFlow(prefs.autoPeerSearch)
    val autoPeerSearch: StateFlow<Boolean> = _autoPeerSearch.asStateFlow()

    /** Connect time from this device per peer address, -1 for no answer. */
    private val _probeResults = MutableStateFlow<Map<String, Int>>(emptyMap())
    val probeResults: StateFlow<Map<String, Int>> = _probeResults.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    /** Every imported config by name, as raw .conf text. */
    private val _awgConfs = MutableStateFlow(prefs.awgConfs)
    val awgConfs: StateFlow<Map<String, String>> = _awgConfs.asStateFlow()

    private val _awgConfName = MutableStateFlow(prefs.awgConfName)
    val awgConfName: StateFlow<String?> = _awgConfName.asStateFlow()

    /** The config the running tunnel was started with, or null while it is down. */
    private val _awgConfInUse = MutableStateFlow(storedConfInUse())
    val awgConfInUse: StateFlow<String?> = _awgConfInUse.asStateFlow()

    private val _isLoadingPeers = MutableStateFlow(false)
    val isLoadingPeers: StateFlow<Boolean> = _isLoadingPeers.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _yggDnsEnabled = MutableStateFlow(prefs.yggDnsEnabled)
    val yggDnsEnabled: StateFlow<Boolean> = _yggDnsEnabled.asStateFlow()

    private val _multicastEnabled = MutableStateFlow(prefs.multicastEnabled)
    val multicastEnabled: StateFlow<Boolean> = _multicastEnabled.asStateFlow()

    private val _multicastPassword = MutableStateFlow(prefs.multicastPassword)
    val multicastPassword: StateFlow<String> = _multicastPassword.asStateFlow()


    private val _oledTheme = MutableStateFlow(prefs.oledTheme)
    val oledTheme: StateFlow<Boolean> = _oledTheme.asStateFlow()

    private val _bypassedApps = MutableStateFlow(prefs.bypassedApps)
    val bypassedApps: StateFlow<Set<String>> = _bypassedApps.asStateFlow()

    private val _bypassedSubnets = MutableStateFlow(prefs.bypassedSubnets)
    val bypassedSubnets: StateFlow<Set<String>> = _bypassedSubnets.asStateFlow()

    /** True when the chosen apps are the only ones inside the tunnel. */
    private val _appsAllowList = MutableStateFlow(prefs.appsAllowList)
    val appsAllowList: StateFlow<Boolean> = _appsAllowList.asStateFlow()

    /** Every app that can use the network, or null while the list is being read. */
    private val _installedApps = MutableStateFlow<List<InstalledApp>?>(null)
    val installedApps: StateFlow<List<InstalledApp>?> = _installedApps.asStateFlow()

    /** The server's overlay key, once the tunnel has learned it. */
    private val _serverKey = MutableStateFlow(storedServerKey())
    val serverKey: StateFlow<String> = _serverKey.asStateFlow()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = TunnelStatus.fromIntent(intent) ?: return
            _tunnelStatus.value = status
            // The service rewrites the derived peers when a search finishes.
            _selection.value = _selection.value.copy(derived = prefs.derivedPeers)
            _awgConfInUse.value = storedConfInUse()
            // The key is learned while connecting, and this is the only signal the
            // UI gets that the tunnel made progress.
            _serverKey.value = storedServerKey()
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            statusReceiver,
            IntentFilter(TunnelService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loadSavedConfig()
        loadSavedPeers()
        refreshCountries()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(statusReceiver)
        super.onCleared()
    }

    /**
     * Keep an imported config under [name], replacing one of the same name, and
     * make it the active one. [rawText] is the original .conf file content and is
     * stored separately so the display can show all lines without round-trip loss.
     */
    fun saveAwgConfig(config: AwgConfig, rawText: String, name: String) {
        setAwgConfs(_awgConfs.value + (name to rawText))
        activateAwgConfig(config, rawText, name)
    }

    /** Takes effect at the next connect, like every other change to the tunnel. */
    fun selectAwgConf(name: String) {
        val raw = _awgConfs.value[name] ?: return
        val config = runCatching { parseAwgConf(raw) }
            .onFailure { AppLogger.w(TAG, "Saved config $name no longer parses: $it") }
            .getOrNull() ?: return
        activateAwgConfig(config, raw, name)
    }

    /** False when [newName] is blank or already taken, which is what the field reports. */
    fun renameAwgConf(oldName: String, newName: String): Boolean {
        val name = newName.trim()
        if (name == oldName) return true
        if (name.isEmpty() || name in _awgConfs.value) return false
        val raw = _awgConfs.value[oldName] ?: return false
        setAwgConfs(_awgConfs.value - oldName + (name to raw))
        if (_awgConfName.value == oldName) {
            _awgConfName.value = name
            prefs.awgConfName = name
        }
        if (prefs.awgConfInUse == oldName) {
            prefs.awgConfInUse = name
            _awgConfInUse.value = storedConfInUse()
        }
        return true
    }

    /**
     * Any config but the one the tunnel is running on. Deleting the selected one
     * selects the first that is left, or leaves the app with no server at all.
     */
    fun deleteAwgConf(name: String) {
        if (name == _awgConfInUse.value) return
        val rest = _awgConfs.value - name
        if (name == _awgConfName.value) {
            rest.keys.sorted().firstOrNull()?.let { selectAwgConf(it) }
            if (_awgConfName.value == name) clearAwgConfig()
        }
        setAwgConfs(rest)
    }

    private fun clearAwgConfig() {
        _awgConfig.value = null
        _rawConfText.value = null
        _awgConfName.value = null
        prefs.awgConf = null
        prefs.awgConfRaw = null
        prefs.awgConfName = null
        _serverKey.value = storedServerKey()
    }

    private fun activateAwgConfig(config: AwgConfig, rawText: String, name: String) {
        _awgConfig.value = config
        _rawConfText.value = rawText
        _awgConfName.value = name
        prefs.awgConf = config.toConfString()   // what the service is started with
        prefs.awgConfRaw = rawText              // what the Config screen shows
        prefs.awgConfName = name
        _serverKey.value = storedServerKey()
    }

    private fun setAwgConfs(confs: Map<String, String>) {
        _awgConfs.value = confs
        prefs.awgConfs = confs
    }

    private fun loadSavedConfig() {
        val stored = prefs.awgConf ?: return
        val config = runCatching { parseAwgConf(stored) }.getOrNull()
        _awgConfig.value = config
        val raw = prefs.awgConfRaw ?: stored
        _rawConfText.value = raw
        // A config imported before configs were kept by name becomes the first one.
        if (_awgConfName.value == null && config != null) {
            setAwgConfs(_awgConfs.value + (config.endpoint to raw))
            _awgConfName.value = config.endpoint
            prefs.awgConfName = config.endpoint
        }
    }

    fun connect() {
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, TunnelService.startIntent(
            context = app,
            peers   = _selection.value.all.toList(),
            awgConf = _awgConfig.value?.toConfString(),
            yggKey  = prefs.yggPrivateKey(),
            multicastPassword = prefs.activeMulticastPassword(),
        ))
    }

    fun resetYggKey() = prefs.clearYggPrivateKey()

    fun disconnect() {
        val app = getApplication<Application>()
        app.startService(TunnelService.stopIntent(app))
    }

    fun restartAwg() {
        val app = getApplication<Application>()
        app.startService(Intent(app, TunnelService::class.java).apply {
            action = TunnelService.ACTION_RESTART_AWG
        })
    }

    fun refreshCountries(force: Boolean = false) {
        viewModelScope.launch {
            _isLoadingPeers.value = true
            try {
                if (!prefs.peersSeeded) seedPeersFromCountry()
                _countries.value = repo.getCountries(forceRefresh = force)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load peers: ${e.message}"
            } finally {
                _isLoadingPeers.value = false
            }
        }
    }

    /**
     * On the first run, start with the peers of the country the phone is in, so
     * a new user can connect without learning what a peer is. Runs once: a user
     * who later removes them all does not want them back.
     */
    private suspend fun seedPeersFromCountry() {
        if (_selection.value.all.isEmpty() && !_autoPeerSearch.value) {
            val peers = countryPeers()
            AppLogger.i(TAG, "First run: ${peers.size} peers from the country")
            setSelection(_selection.value.withDerived(peers))
        }
        prefs.peersSeeded = true
    }

    /** The peers of the country the phone is in, as the first run picks them. */
    private suspend fun countryPeers(): List<String> {
        val iso = detectCountryIso(getApplication())
        val key = iso?.let { countryKeyForIso(it, repo.getCountries().map { c -> c.countryKey }) }
        if (key == null) {
            AppLogger.w(TAG, "No public peers for country $iso")
            return emptyList()
        }
        return peersToSelect(repo.getPeersForCountry(key), emptyMap()).map { it.address }
    }

    /**
     * Switching on empties the app's own choice, so the search fills it at the next
     * connect. Switching off puts back what a first launch would pick. Peers the
     * user added stay either way.
     */
    fun setAutoPeerSearch(enabled: Boolean) {
        if (enabled == _autoPeerSearch.value) return
        _autoPeerSearch.value = enabled
        prefs.autoPeerSearch = enabled
        viewModelScope.launch {
            val derived = if (enabled) emptyList() else countryPeers()
            setSelection(_selection.value.withDerived(derived))
            applySelectedPeers()
        }
    }

    fun selectPeers(addresses: Collection<String>) =
        setSelection(_selection.value.withManual(addresses))

    fun unselectPeers(addresses: Collection<String>) =
        setSelection(_selection.value.without(addresses))

    /**
     * Connect to each peer from this device. The crawler's verdict comes from its
     * own network; this one answers whether the peer is reachable from here.
     */
    fun probePeers(addresses: List<String>) {
        if (_isProbing.value) return
        viewModelScope.launch {
            _isProbing.value = true
            val limit = Semaphore(PROBE_PARALLELISM)
            addresses.map { address ->
                launch {
                    val ms = limit.withPermit { probePeer(address) } ?: return@launch
                    _probeResults.value = _probeResults.value + (address to ms)
                }
            }.forEach { it.join() }
            _isProbing.value = false
        }
    }

    fun togglePeer(address: String) {
        val current = _selection.value
        setSelection(
            if (address in current.all) current.without(listOf(address))
            else current.withManual(listOf(address))
        )
    }

    /**
     * Add a peer the user typed. Returns the parse error to show under the field,
     * or null once the peer is stored.
     */
    fun addCustomPeer(input: String): PeerUriError? {
        val uri = parsePeerUri(input).getOrElse {
            return (it as PeerUriException).error
        }
        val canonical = uri.toString()
        if (canonical !in _selection.value.manual) selectPeers(listOf(canonical))
        return null
    }

    /**
     * Restart the tunnel so a changed peer list takes effect. The service is given
     * time to tear down first: [connect] on a service that is still stopping is
     * rejected by the duplicate-start guard, leaving the tunnel down.
     */
    fun applySelectedPeers() {
        val state = _tunnelStatus.value.overall
        if (state != VpnState.CONNECTED && state != VpnState.CONNECTING) return
        viewModelScope.launch {
            disconnect()
            withTimeoutOrNull(RESTART_TEARDOWN_TIMEOUT_MS) {
                while (TunnelService.isRunning) delay(50)
            }
            connect()
        }
    }

    fun removePeer(address: String) = unselectPeers(listOf(address))

    fun clearError() { _errorMessage.value = null }

    fun toggleYggDns() {
        val enabled = !_yggDnsEnabled.value
        _yggDnsEnabled.value = enabled
        prefs.yggDnsEnabled = enabled
    }

    /** Refused while no password is set — see [setMulticastPassword]. */
    fun toggleMulticast() {
        val enabled = !_multicastEnabled.value
        if (enabled && _multicastPassword.value.isEmpty()) return
        _multicastEnabled.value = enabled
        prefs.multicastEnabled = enabled
    }

    fun setMulticastPassword(password: String) {
        val trimmed = password.take(MULTICAST_PASSWORD_MAX)
        _multicastPassword.value = trimmed
        prefs.multicastPassword = trimmed
        // Clearing the password takes discovery down with it, rather than leaving
        // it nominally on with nothing guarding it.
        if (trimmed.isEmpty() && _multicastEnabled.value) {
            _multicastEnabled.value = false
            prefs.multicastEnabled = false
        }
    }

    fun toggleOledTheme() {
        val enabled = !_oledTheme.value
        _oledTheme.value = enabled
        prefs.oledTheme = enabled
    }

    /** Ping the AWG server's Yggdrasil address through the overlay. */
    fun pingAwgServer() {
        if (YggNetworkState.pinging.value) return
        val endpoint = _awgConfig.value?.endpoint ?: return
        val addrBytes = parseIpv6Bytes(endpoint) ?: return
        val addr = runCatching { Inet6Address.getByAddress(addrBytes).hostAddress }
            .getOrNull() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            YggNetworkState.pinging.value = true
            YggNetworkState.pingMs.value = null
            YggNetworkState.pingMs.value = YggNetworkState.manager?.pingYgg(addr) ?: -1L
            YggNetworkState.pinging.value = false
        }
    }

    /**
     * Read the installed apps once per process. Names come from the package
     * manager on hundreds of entries, which is slow enough to block a frame.
     */
    fun loadInstalledApps() {
        if (_installedApps.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val pm = app.packageManager
            _installedApps.value = pm.getInstalledApplications(0)
                // An app with no internet permission cannot be affected by this
                // screen, and a package with no launcher — a provider, a system
                // service — is not one the user thinks of as an app at all.
                // Listing either only makes the real choices harder to find.
                .filter { pm.checkPermission(INTERNET_PERMISSION, it.packageName) == PERMISSION_GRANTED }
                .filter { it.packageName != app.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun toggleBypassedApp(packageName: String) {
        val current = _bypassedApps.value.toMutableSet()
        if (!current.remove(packageName)) current.add(packageName)
        _bypassedApps.value = current
        prefs.bypassedApps = current
    }

    fun setAppsAllowList(enabled: Boolean) {
        _appsAllowList.value = enabled
        prefs.appsAllowList = enabled
    }

    /** False when [text] is not a subnet, which is what the field reports. */
    fun addBypassedSubnet(text: String): Boolean {
        val route = parseSubnet(text) ?: return false
        val canonical = "${route.address.hostAddress}/${route.prefix}"
        _bypassedSubnets.value = _bypassedSubnets.value + canonical
        prefs.bypassedSubnets = _bypassedSubnets.value
        return true
    }

    fun removeBypassedSubnet(subnet: String) {
        _bypassedSubnets.value = _bypassedSubnets.value - subnet
        prefs.bypassedSubnets = _bypassedSubnets.value
    }

    /**
     * Ask the server to identify itself over the overlay. Needs no tunnel and no
     * session, so it separates an unreachable server from a broken tunnel.
     */
    fun probeServer() {
        if (YggNetworkState.probing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            YggNetworkState.probing.value = true
            YggNetworkState.probeFound.value = null
            YggNetworkState.probeFound.value = YggNetworkState.manager?.probeServer() ?: false
            YggNetworkState.probing.value = false
        }
    }

    private fun storedConfInUse(): String? {
        val state = _tunnelStatus.value.overall
        val tunnelDown = state == VpnState.IDLE || state == VpnState.DISCONNECTED
        return if (tunnelDown) null else prefs.awgConfInUse
    }

    private fun storedServerKey(): String =
        _awgConfig.value?.endpoint?.let { prefs.serverKey(it) }.orEmpty()

    /** Writes only the half that changed: the service owns the other while it searches. */
    private fun setSelection(selection: PeerSelection) {
        val old = _selection.value
        _selection.value = selection
        if (selection.manual != old.manual) prefs.manualPeers = selection.manual
        if (selection.derived != old.derived) prefs.derivedPeers = selection.derived
    }

    private fun loadSavedPeers() {
        _selection.value = PeerSelection(prefs.manualPeers, prefs.derivedPeers)
    }
}
