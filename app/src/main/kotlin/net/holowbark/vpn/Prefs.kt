package net.holowbark.vpn

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's single SharedPreferences file. Every key is spelled here and nowhere
 * else, so a rename cannot leave one reader behind.
 *
 * The tunnel state keys mirror [TunnelStatus] and are written only by
 * [TunnelService]. They survive process death, so a reader must confirm
 * [TunnelService.isRunning] before trusting them.
 */
class Prefs(private val prefs: SharedPreferences) {
    companion object {
        private const val FILE = "holowbark"

        // Written by TunnelService, read by the UI and the Quick Settings tile.
        private const val VPN_STATE   = "vpn_state"
        private const val YGG_LAYER   = "ygg_layer"
        private const val YGG_ADDRESS = "ygg_address"
        private const val YGG_PEER_COUNT = "ygg_peer_count"
        private const val AWG_LAYER   = "awg_layer"

        // User configuration.
        private const val YGG_PRIVATE_KEY  = "ygg_private_key"
        private const val YGG_DNS_ENABLED  = "ygg_dns_enabled"
        private const val YGG_MULTICAST     = "ygg_multicast"
        private const val YGG_MULTICAST_PASSWORD = "ygg_multicast_password"
        private const val AUTO_RECOVER      = "auto_recover"
        private const val AWG_CONF         = "awg_conf"
        private const val AWG_CONF_RAW     = "awg_conf_raw"
        private const val SELECTED_PEERS   = "selected_peers"

        fun of(context: Context): Prefs =
            Prefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }

    fun vpnState(): VpnState = prefs.enum(VPN_STATE, VpnState.IDLE)

    /**
     * The last broadcast status, or an empty one when nothing usable is stored.
     * CONNECTING is treated as absent — it is transient and the next broadcast
     * will replace it within a second.
     */
    fun tunnelStatus(): TunnelStatus {
        val overall = vpnState()
        if (overall == VpnState.IDLE || overall == VpnState.CONNECTING) return TunnelStatus()
        return TunnelStatus(
            overall    = overall,
            ygg        = prefs.enum(YGG_LAYER, LayerState.IDLE),
            yggAddress = prefs.getString(YGG_ADDRESS, "").orEmpty(),
            yggPeers   = prefs.getInt(YGG_PEER_COUNT, 0),
            awg        = prefs.enum(AWG_LAYER, LayerState.IDLE),
        )
    }

    fun saveTunnelStatus(s: TunnelStatus) = prefs.edit()
        .putString(VPN_STATE,      s.overall.name)
        .putString(YGG_LAYER,      s.ygg.name)
        .putString(YGG_ADDRESS,    s.yggAddress)
        .putInt   (YGG_PEER_COUNT, s.yggPeers)
        .putString(AWG_LAYER,      s.awg.name)
        .apply()

    var awgConf: String?
        get() = prefs.getString(AWG_CONF, null)
        set(v) = prefs.edit().putString(AWG_CONF, v).apply()

    var awgConfRaw: String?
        get() = prefs.getString(AWG_CONF_RAW, null)
        set(v) = prefs.edit().putString(AWG_CONF_RAW, v).apply()

    var selectedPeers: Set<String>
        get() = prefs.getStringSet(SELECTED_PEERS, null) ?: emptySet()
        set(v) = prefs.edit().putStringSet(SELECTED_PEERS, v).apply()

    var yggDnsEnabled: Boolean
        get() = prefs.getBoolean(YGG_DNS_ENABLED, false)
        set(v) = prefs.edit().putBoolean(YGG_DNS_ENABLED, v).apply()

    /** Discover peers on the local network as well as dialling the configured ones. */
    var multicastEnabled: Boolean
        get() = prefs.getBoolean(YGG_MULTICAST, false)
        set(v) = prefs.edit().putBoolean(YGG_MULTICAST, v).apply()

    /**
     * Shared secret for local discovery. Yggdrasil keys a BLAKE2b hash with it and
     * drops beacons that do not match, so it decides *whose* devices we will peer
     * with. Without one, any Yggdrasil node on the same Wi-Fi can peer with us and
     * learn our overlay address — and AllowedPublicKeys does not apply to peers
     * found this way.
     */
    var multicastPassword: String
        get() = prefs.getString(YGG_MULTICAST_PASSWORD, "").orEmpty()
        set(v) = prefs.edit().putString(YGG_MULTICAST_PASSWORD, v).apply()

    /** The password to run with, or empty when discovery is off or unusable. */
    fun activeMulticastPassword(): String =
        if (multicastEnabled) multicastPassword else ""

    /** Restart the overlay by itself when the server stops answering through it. */
    var autoRecoverEnabled: Boolean
        get() = prefs.getBoolean(AUTO_RECOVER, false)
        set(v) = prefs.edit().putBoolean(AUTO_RECOVER, v).apply()

    /**
     * The node's Yggdrasil identity, as the 128 hex chars (seed + public key) that
     * yggdrasil-go expects. Generated once and kept, so the overlay address — and
     * therefore the server's allow-list — stays stable across restarts.
     */
    fun yggPrivateKey(): String {
        prefs.getString(YGG_PRIVATE_KEY, null)
            ?.takeIf { it.length == 128 }
            ?.let { return it }
        val hex = generateYggKey()
        prefs.edit().putString(YGG_PRIVATE_KEY, hex).apply()
        return hex
    }

    fun clearYggPrivateKey() = prefs.edit().remove(YGG_PRIVATE_KEY).apply()
}

private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T =
    getString(key, null)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

private fun generateYggKey(): String {
    val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    // RFC 8410 DER layout: the PKCS#8 seed starts at byte 16, the X.509 public key at byte 12.
    val seed = pair.private.encoded.sliceArray(16..47)
    val publicKey = pair.public.encoded.sliceArray(12..43)
    return (seed + publicKey).joinToString("") { "%02x".format(it) }
}
