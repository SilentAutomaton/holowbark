package net.holowbark.peers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.holowbark.AppLogger
import net.holowbark.vpn.parsePeerUri
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "PeerProbe"
private const val PROBE_TIMEOUT_MS = 3_000

/** The transports that open a plain TCP connection to the peer itself. */
private val TCP_SCHEMES = setOf("tcp", "tls", "ws", "wss")

/**
 * Time a TCP connect to the peer from this device, in ms, or -1 when no
 * address it resolves to answers. Null for a peer this cannot check: QUIC is
 * UDP, and a SOCKS peer would only prove the proxy is up.
 */
suspend fun probePeer(address: String): Int? = withContext(Dispatchers.IO) {
    val uri = parsePeerUri(address).getOrNull()?.takeIf { it.scheme in TCP_SCHEMES }
        ?: return@withContext null
    // Every address the name resolves to, as Yggdrasil dials them: a network can
    // hand out an IPv6 address with no route beyond it, and the first address
    // alone would call a reachable peer dead.
    val targets = runCatching { InetAddress.getAllByName(uri.host).toList() }
        .onFailure { AppLogger.d(TAG, "$address: $it") }
        .getOrDefault(emptyList())
    targets.firstNotNullOfOrNull { target ->
        val start = System.nanoTime()
        runCatching {
            Socket().use { it.connect(InetSocketAddress(target, uri.port), PROBE_TIMEOUT_MS) }
            ((System.nanoTime() - start) / 1_000_000).toInt()
        }.onFailure { AppLogger.d(TAG, "$address via $target: $it") }.getOrNull()
    } ?: -1
}
