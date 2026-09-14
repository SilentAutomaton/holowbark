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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.holowbark.config.AwgConfig
import net.holowbark.config.parseAwgConf
import net.holowbark.config.toConfString
import net.holowbark.peers.PeerDatabase
import net.holowbark.peers.PeerRepository
import net.holowbark.peers.models.CountryInfo
import net.holowbark.peers.models.Peer
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

private const val RESTART_TEARDOWN_TIMEOUT_MS = 3_000L
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

    private val _currentCountryPeers = MutableStateFlow<List<Peer>>(emptyList())
    val currentCountryPeers: StateFlow<List<Peer>> = _currentCountryPeers.asStateFlow()

    private val _selectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeers: StateFlow<Set<String>> = _selectedPeers.asStateFlow()

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

    private val _autoRecoverEnabled = MutableStateFlow(prefs.autoRecoverEnabled)
    val autoRecoverEnabled: StateFlow<Boolean> = _autoRecoverEnabled.asStateFlow()

    private val _bypassedApps = MutableStateFlow(prefs.bypassedApps)
    val bypassedApps: StateFlow<Set<String>> = _bypassedApps.asStateFlow()

    private val _bypassedSubnets = MutableStateFlow(prefs.bypassedSubnets)
    val bypassedSubnets: StateFlow<Set<String>> = _bypassedSubnets.asStateFlow()

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
     * Save AWG config. [rawText] is the original .conf file content and is stored
     * separately so the display can show all lines without round-trip loss.
     */
    fun saveAwgConfig(config: AwgConfig, rawText: String) {
        _awgConfig.value = config
        _rawConfText.value = rawText
        prefs.awgConf = config.toConfString()   // what the service is started with
        prefs.awgConfRaw = rawText              // what the Config screen shows
    }

    private fun loadSavedConfig() {
        val stored = prefs.awgConf ?: return
        _awgConfig.value = runCatching { parseAwgConf(stored) }.getOrNull()
        _rawConfText.value = prefs.awgConfRaw ?: stored
    }

    fun connect() {
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, TunnelService.startIntent(
            context = app,
            peers   = _selectedPeers.value.toList(),
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
                _countries.value = repo.getCountries(forceRefresh = force)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load peers: ${e.message}"
            } finally {
                _isLoadingPeers.value = false
            }
        }
    }

    fun loadPeersForCountry(countryKey: String) {
        viewModelScope.launch {
            _currentCountryPeers.value = repo.getPeersForCountry(countryKey)
        }
    }

    fun togglePeer(address: String) {
        val current = _selectedPeers.value.toMutableSet()
        if (address in current) current.remove(address) else current.add(address)
        _selectedPeers.value = current
        savePeers(current)
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
        if (canonical !in _selectedPeers.value) togglePeer(canonical)
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

    fun removePeer(address: String) {
        val current = _selectedPeers.value.toMutableSet()
        if (current.remove(address)) {
            _selectedPeers.value = current
            savePeers(current)
        }
    }

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

    /** Read by the service on every watchdog tick, so this takes effect immediately. */
    fun toggleAutoRecover() {
        val enabled = !_autoRecoverEnabled.value
        _autoRecoverEnabled.value = enabled
        prefs.autoRecoverEnabled = enabled
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
                // screen, and listing it only makes the real choices harder to find.
                .filter { pm.checkPermission(INTERNET_PERMISSION, it.packageName) == PERMISSION_GRANTED }
                .filter { it.packageName != app.packageName }
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

    private fun storedServerKey(): String =
        _awgConfig.value?.endpoint?.let { prefs.serverKey(it) }.orEmpty()

    private fun savePeers(peers: Set<String>) { prefs.selectedPeers = peers }

    private fun loadSavedPeers() {
        _selectedPeers.value = prefs.selectedPeers
    }
}
