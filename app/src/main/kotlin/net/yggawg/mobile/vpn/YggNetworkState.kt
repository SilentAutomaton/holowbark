package net.yggawg.mobile.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton shared between YggdrasilManager (writer) and the UI (reader).
 * Avoids the need for a bound service connection.
 */
object YggNetworkState {

    data class PeerInfo(
        val uri: String,
        val up: Boolean,
        val inbound: Boolean,
        val latencyMs: Double,   // < 0 if unknown
        val uptimeSec: Double,   // < 0 if unknown
        val bytesSent: Long,
        val bytesRecvd: Long,
        val lastError: String,
    )

    /** Live list of Yggdrasil peers; updated every 5 s by YggdrasilManager. */
    val peers = MutableStateFlow<List<PeerInfo>>(emptyList())

    /** Our own Yggdrasil address string (set after Yggdrasil starts). */
    val selfAddress = MutableStateFlow("")

    /**
     * Ping result:
     *   null  = idle / no result yet
     *   -1L   = timed out
     *   >= 0  = round-trip ms
     */
    val pingMs = MutableStateFlow<Long?>(null)
    val pinging = MutableStateFlow(false)

    fun reset() {
        peers.value = emptyList()
        selfAddress.value = ""
        pingMs.value = null
        pinging.value = false
    }
}

/** Singleton handle to the running YggdrasilManager; null when VPN is stopped. */
object YggServiceAccess {
    @Volatile var manager: YggdrasilManager? = null
}
