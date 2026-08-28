package net.holowbark.vpn

/** Overall VPN lifecycle state. */
enum class VpnState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/** Per-layer state for Yggdrasil and AWG independently. */
enum class LayerState { IDLE, STARTING, UP, ERROR }

/**
 * Full snapshot broadcast from TunnelService to the UI.
 * Serialised as individual Intent extras.
 */
data class TunnelStatus(
    val overall: VpnState     = VpnState.IDLE,
    val ygg: LayerState       = LayerState.IDLE,
    val yggAddress: String    = "",       // Yggdrasil IPv6 address once UP
    val yggPeers: Int         = 0,        // number of active Yggdrasil peers
    val awg: LayerState       = LayerState.IDLE,
) {
    companion object {
        const val EXTRA_OVERALL      = "overall"
        const val EXTRA_YGG          = "ygg_layer"
        const val EXTRA_YGG_ADDRESS  = "ygg_address"
        const val EXTRA_YGG_PEERS    = "ygg_peer_count"
        const val EXTRA_AWG          = "awg_layer"

        fun fromPrefs(prefs: android.content.SharedPreferences): TunnelStatus {
            // Prefs go stale after process death/reboot — only meaningful while
            // the service is actually up in this process.
            if (!TunnelService.isRunning) return TunnelStatus()
            val overall = prefs.getString("vpn_state", null)
                ?.let { runCatching { VpnState.valueOf(it) }.getOrNull() }
                ?: return TunnelStatus()
            // Don't restore transient states — they'll be updated by the next broadcast
            if (overall == VpnState.CONNECTING) return TunnelStatus()
            return TunnelStatus(
                overall    = overall,
                ygg        = prefs.getString("ygg_layer", null)
                    ?.let { runCatching { LayerState.valueOf(it) }.getOrNull() } ?: LayerState.IDLE,
                yggAddress = prefs.getString("ygg_address", "") ?: "",
                yggPeers   = prefs.getInt("ygg_peers", 0),
                awg        = prefs.getString("awg_layer", null)
                    ?.let { runCatching { LayerState.valueOf(it) }.getOrNull() } ?: LayerState.IDLE,
            )
        }

        fun fromIntent(intent: android.content.Intent): TunnelStatus? {
            val overall = intent.getStringExtra(EXTRA_OVERALL)
                ?.let { runCatching { VpnState.valueOf(it) }.getOrNull() }
                ?: return null
            return TunnelStatus(
                overall    = overall,
                ygg        = intent.getStringExtra(EXTRA_YGG)
                    ?.let { runCatching { LayerState.valueOf(it) }.getOrNull() }
                    ?: LayerState.IDLE,
                yggAddress = intent.getStringExtra(EXTRA_YGG_ADDRESS) ?: "",
                yggPeers   = intent.getIntExtra(EXTRA_YGG_PEERS, 0),
                awg        = intent.getStringExtra(EXTRA_AWG)
                    ?.let { runCatching { LayerState.valueOf(it) }.getOrNull() }
                    ?: LayerState.IDLE,
            )
        }
    }
}
