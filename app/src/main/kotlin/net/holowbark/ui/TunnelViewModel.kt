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
import net.holowbark.vpn.Prefs
import net.holowbark.vpn.TunnelService
import net.holowbark.vpn.parseIpv6Bytes
import java.net.Inet6Address

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
        prefs.yggDnsEnabled = enabled
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

    private fun savePeers(peers: Set<String>) { prefs.selectedPeers = peers }

    private fun loadSavedPeers() {
        _selectedPeers.value = prefs.selectedPeers
    }
}
