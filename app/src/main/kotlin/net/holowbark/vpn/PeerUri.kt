package net.holowbark.vpn

/**
 * A Yggdrasil peering URI, as the user types it and as `Peers` in the Yggdrasil
 * config expects it: `tls://example.org:443`, `quic://[2a09::1]:65535`.
 *
 * Parsing here is deliberately shallow. Yggdrasil validates every peer itself and
 * reports the verdict per peer in `PeerInfo.lastError`, which the Network screen
 * already shows — so this only has to catch what a person can see is wrong before
 * they leave the text field. A stricter copy of Yggdrasil's rules would be a second
 * thing to keep in sync with it.
 *
 * [path] is load-bearing for two schemes and meaningless for the rest:
 *  - `ws`/`wss` — the endpoint behind a reverse proxy. Yggdrasil cannot listen on
 *    `wss` at all, so a TLS WebSocket peer is always nginx or Caddy serving a real
 *    site on 443 and proxying one path through to a local `ws` listener.
 *  - `socks`/`sockstls` — the path *is* the target peer, dialled through the proxy
 *    named in the authority.
 */
data class PeerUri(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String? = null,
    val query: String? = null,
) {
    /** The canonical form, which is what gets stored and handed to Yggdrasil. */
    override fun toString(): String {
        val hostPart = if (':' in host) "[$host]" else host
        return "$scheme://$hostPart:$port" + path.orEmpty() + (query?.let { "?$it" } ?: "")
    }
}

/** What is wrong with a URI, in the words the text field shows the user. */
enum class PeerUriError(val message: String) {
    EMPTY("Enter a peer address"),
    NO_SCHEME("Start with a transport, for example tls://"),
    UNKNOWN_SCHEME("Unknown transport — use ${PEER_SCHEMES.joinToString(", ")}"),
    NO_HOST("Missing the host"),
    NO_PORT("Missing the port, for example tls://example.org:443"),
    BAD_PORT("Port must be between 1 and 65535"),
    NO_TARGET("socks:// needs a target — socks://127.0.0.1:1080/example.org:443"),
    BAD_TARGET("The target after / must be host:port"),
    PATH_NOT_ALLOWED("This transport takes no path — remove everything after the port"),
}

val PEER_SCHEMES = listOf("tcp", "tls", "quic", "ws", "wss", "socks", "sockstls")

/** Schemes where the path carries the peer to dial through the proxy. */
private val PROXY_SCHEMES = setOf("socks", "sockstls")

/** Schemes where a path addresses an endpoint behind a reverse proxy. */
private val PATH_SCHEMES = setOf("ws", "wss")

/**
 * Parse a peer URI, or return the reason it cannot be one.
 *
 * Leading and trailing whitespace and a trailing comma are stripped first, because
 * these arrive by paste far more often than by typing.
 */
fun parsePeerUri(input: String): Result<PeerUri> {
    val text = input.trim().removeSuffix(",").trim()
    if (text.isEmpty()) return failure(PeerUriError.EMPTY)

    val separator = text.indexOf("://")
    if (separator <= 0) return failure(PeerUriError.NO_SCHEME)
    val scheme = text.substring(0, separator).lowercase()
    if (scheme !in PEER_SCHEMES) return failure(PeerUriError.UNKNOWN_SCHEME)

    val rest = text.substring(separator + 3)
    val beforeQuery = rest.substringBefore('?')
    // Yggdrasil carries ?key=, ?password= and ?sni= for authenticated and TLS
    // peerings, so the query has to survive verbatim.
    val query = rest.substringAfter('?', "").ifEmpty { null }

    // A slash can only start the path: an IPv6 literal has none, and the query is
    // already split off above.
    val pathStart = beforeQuery.indexOf('/')
    val authority = if (pathStart >= 0) beforeQuery.substring(0, pathStart) else beforeQuery
    val path = if (pathStart >= 0) beforeQuery.substring(pathStart) else null

    val endpoint = parseHostPort(authority).getOrElse { return failure(it) }

    when {
        scheme in PROXY_SCHEMES -> {
            val target = path?.removePrefix("/").orEmpty()
            if (target.isEmpty()) return failure(PeerUriError.NO_TARGET)
            // The target is dialled by the proxy, so it must itself be host:port.
            parseHostPort(target).getOrElse { return failure(PeerUriError.BAD_TARGET) }
        }
        scheme !in PATH_SCHEMES && path != null -> return failure(PeerUriError.PATH_NOT_ALLOWED)
    }

    return Result.success(PeerUri(scheme, endpoint.first, endpoint.second, path, query))
}

/**
 * Split `host:port` or `[v6]:port`, reporting which half is wrong rather than
 * leaving the caller to guess after the fact.
 */
private fun parseHostPort(authority: String): HostPort {
    val host: String
    val portPart: String
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return HostPort.Bad(PeerUriError.NO_HOST)
        host = authority.substring(1, close)
        portPart = authority.substring(close + 1).removePrefix(":")
    } else if (':' in authority) {
        host = authority.substringBeforeLast(':')
        portPart = authority.substringAfterLast(':')
    } else {
        // No colon at all: the host is there and the port is what is missing.
        host = authority
        portPart = ""
    }
    if (host.isEmpty()) return HostPort.Bad(PeerUriError.NO_HOST)
    if (portPart.isEmpty()) return HostPort.Bad(PeerUriError.NO_PORT)
    val port = portPart.toIntOrNull() ?: return HostPort.Bad(PeerUriError.BAD_PORT)
    if (port !in 1..65535) return HostPort.Bad(PeerUriError.BAD_PORT)
    return HostPort.Ok(host, port)
}

private sealed interface HostPort {
    data class Ok(val host: String, val port: Int) : HostPort
    data class Bad(val error: PeerUriError) : HostPort
}

private inline fun HostPort.getOrElse(onBad: (PeerUriError) -> Nothing): Pair<String, Int> =
    when (this) {
        is HostPort.Ok -> host to port
        is HostPort.Bad -> onBad(error)
    }

private fun failure(error: PeerUriError): Result<PeerUri> =
    Result.failure(PeerUriException(error))

class PeerUriException(val error: PeerUriError) : Exception(error.message)
