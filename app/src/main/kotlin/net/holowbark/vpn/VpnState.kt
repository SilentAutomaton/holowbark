package net.holowbark.vpn

import android.content.Intent

/** Overall tunnel lifecycle state, as the user sees it. */
enum class VpnState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/** State of one layer — Yggdrasil or AWG — independently of the other. */
enum class LayerState { IDLE, STARTING, UP, ERROR }

/**
 * The single snapshot [TunnelService] broadcasts to the UI and the Quick Settings
 * tile. Any new shared state belongs here rather than in a second channel.
 */
data class TunnelStatus(
    val overall: VpnState  = VpnState.IDLE,
    val ygg: LayerState    = LayerState.IDLE,
    val yggAddress: String = "",
    val yggPeers: Int      = 0,
    val awg: LayerState    = LayerState.IDLE,
) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_OVERALL,     overall.name)
        .putExtra(EXTRA_YGG,         ygg.name)
        .putExtra(EXTRA_YGG_ADDRESS, yggAddress)
        .putExtra(EXTRA_YGG_PEERS,   yggPeers)
        .putExtra(EXTRA_AWG,         awg.name)

    companion object {
        private const val EXTRA_OVERALL     = "overall"
        private const val EXTRA_YGG         = "ygg_state"
        private const val EXTRA_YGG_ADDRESS = "ygg_address"
        private const val EXTRA_YGG_PEERS   = "ygg_peer_count"
        private const val EXTRA_AWG         = "awg_state"

        /** Null when the intent carries no readable overall state. */
        fun fromIntent(intent: Intent): TunnelStatus? {
            val overall = intent.enum<VpnState>(EXTRA_OVERALL) ?: return null
            return TunnelStatus(
                overall    = overall,
                ygg        = intent.enum<LayerState>(EXTRA_YGG) ?: LayerState.IDLE,
                yggAddress = intent.getStringExtra(EXTRA_YGG_ADDRESS).orEmpty(),
                yggPeers   = intent.getIntExtra(EXTRA_YGG_PEERS, 0),
                awg        = intent.enum<LayerState>(EXTRA_AWG) ?: LayerState.IDLE,
            )
        }
    }
}

private inline fun <reified T : Enum<T>> Intent.enum(key: String): T? =
    getStringExtra(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
