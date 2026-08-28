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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
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
import net.holowbark.vpn.TunnelService
import net.holowbark.vpn.parseYggAddrBytes
import java.security.SecureRandom

class TunnelViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("holowbark", Context.MODE_PRIVATE)
    private val db    = PeerDatabase.getInstance(app)
    val repo          = PeerRepository(db, app)

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private val _tunnelStatus = MutableStateFlow(TunnelStatus.fromPrefs(prefs))
    val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()

    /** Convenience derived flow — overall VPN state only. */
    val vpnState: StateFlow<VpnState> = _tunnelStatus
        .map { it.overall }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VpnState.IDLE)

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

    private val _yggDnsEnabled = MutableStateFlow(prefs.getBoolean("ygg_dns_enabled", false))
    val yggDnsEnabled: StateFlow<Boolean> = _yggDnsEnabled.asStateFlow()

    // -------------------------------------------------------------------------
    // Broadcast receiver
    // -------------------------------------------------------------------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = TunnelStatus.fromIntent(intent) ?: return
            _tunnelStatus.value = status
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
    }

    // -------------------------------------------------------------------------
    // AWG config
    // -------------------------------------------------------------------------

    /**
     * Save AWG config. [rawText] is the original .conf file content and is stored
     * separately so the display can show all lines without round-trip loss.
     */
    fun saveAwgConfig(config: AwgConfig, rawText: String) {
        _awgConfig.value = config
        _rawConfText.value = rawText
        prefs.edit()
            .putString("awg_conf", config.toConfString())   // used when starting VPN
            .putString("awg_conf_raw", rawText)              // used for display
            .apply()
    }

    private fun loadSavedConfig() {
        val parsed = prefs.getString("awg_conf", null) ?: return
        _awgConfig.value = runCatching { parseAwgConf(parsed) }.getOrNull()
        _rawConfText.value = prefs.getString("awg_conf_raw", parsed)
    }

    // -------------------------------------------------------------------------
    // VPN control
    // -------------------------------------------------------------------------

    fun connect() {
        val app = getApplication<Application>()
        val peers = _selectedPeers.value.toList()
        val intent = Intent(app, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
            putStringArrayListExtra(TunnelService.EXTRA_YGG_PEERS, ArrayList(peers))
            _awgConfig.value?.let { putExtra(TunnelService.EXTRA_AWG_CONF, it.toConfString()) }
            putExtra(TunnelService.EXTRA_YGG_KEY, getOrCreateYggKey())
        }
        ContextCompat.startForegroundService(app, intent)
    }

    fun getOrCreateYggKey(): String {
        val existing = prefs.getString("ygg_private_key", null)
        if (existing != null && existing.length == 128) return existing
        val hex = generateEd25519Hex()
        prefs.edit().putString("ygg_private_key", hex).apply()
        return hex
    }

    private fun generateEd25519Hex(): String {
        val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        // RFC 8410 DER: PKCS8 seed at offset 16 (32 bytes), X509 pubkey at offset 12 (32 bytes)
        val seed = kp.private.encoded.sliceArray(16..47)
        val pub  = kp.public.encoded.sliceArray(12..43)
        return (seed + pub).joinToString("") { "%02x".format(it) }
    }

    fun resetYggKey() {
        prefs.edit().remove("ygg_private_key").apply()
    }

    fun disconnect() {
        val app = getApplication<Application>()
        app.startService(Intent(app, TunnelService::class.java).apply {
            action = TunnelService.ACTION_STOP
        })
    }

    fun restartAwg() {
        val app = getApplication<Application>()
        app.startService(Intent(app, TunnelService::class.java).apply {
            action = TunnelService.ACTION_RESTART_AWG
        })
    }

    // -------------------------------------------------------------------------
    // Peer data
    // -------------------------------------------------------------------------

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

    fun applySelectedPeers() {
        if (_tunnelStatus.value.overall == VpnState.CONNECTED ||
            _tunnelStatus.value.overall == VpnState.CONNECTING) {
            disconnect()
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
        prefs.edit().putBoolean("ygg_dns_enabled", enabled).apply()
    }

    // -------------------------------------------------------------------------
    // Yggdrasil network
    // -------------------------------------------------------------------------

    /** Ping the AWG server's Yggdrasil address through the overlay. */
    fun pingAwgServer() {
        if (YggNetworkState.pinging.value) return
        val endpoint = _awgConfig.value?.endpoint ?: return
        // Extract IPv6 address from "[addr]:port" endpoint
        val addrBytes = parseYggAddrBytes(endpoint) ?: return
        val addrStr   = try {
            java.net.Inet6Address.getByAddress(addrBytes).hostAddress ?: return
        } catch (_: Exception) { return }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            YggNetworkState.pinging.value = true
            YggNetworkState.pingMs.value  = null
            val mgr = YggNetworkState.manager
            val ms  = mgr?.pingYgg(addrStr)
            YggNetworkState.pingMs.value  = ms ?: -1L
            YggNetworkState.pinging.value = false
        }
    }

    private fun savePeers(peers: Set<String>) {
        prefs.edit().putStringSet("selected_peers", peers).apply()
    }

    private fun loadSavedPeers() {
        val saved = prefs.getStringSet("selected_peers", null) ?: return
        _selectedPeers.value = saved
    }
}
