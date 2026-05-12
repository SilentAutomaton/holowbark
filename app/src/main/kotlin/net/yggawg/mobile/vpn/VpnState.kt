package net.yggawg.mobile.vpn

/** Overall VPN lifecycle state. */
enum class VpnState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/** Per-layer state for Yggdrasil and AWG independently. */
enum class LayerState { IDLE, STARTING, UP, ERROR }

/**
 * Full snapshot broadcast from YggVpnService to the UI.
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
