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
 */
data class PeerUri(val scheme: String, val host: String, val port: Int, val query: String?) {
    /** The canonical form, which is what gets stored and handed to Yggdrasil. */
    override fun toString(): String {
        val hostPart = if (':' in host) "[$host]" else host
        return "$scheme://$hostPart:$port" + (query?.let { "?$it" } ?: "")
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
}

val PEER_SCHEMES = listOf("tcp", "tls", "quic", "ws", "wss", "socks")

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
    val authority = rest.substringBefore('?')
    // Yggdrasil carries ?key=, ?password= and ?sni= for authenticated and TLS
    // peerings, so the query has to survive verbatim.
    val query = rest.substringAfter('?', "").ifEmpty { null }

    val host: String
    val portPart: String
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return failure(PeerUriError.NO_HOST)
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
    if (host.isEmpty()) return failure(PeerUriError.NO_HOST)
    if (portPart.isEmpty()) return failure(PeerUriError.NO_PORT)

    val port = portPart.toIntOrNull() ?: return failure(PeerUriError.BAD_PORT)
    if (port !in 1..65535) return failure(PeerUriError.BAD_PORT)

    return Result.success(PeerUri(scheme, host, port, query))
}

private fun failure(error: PeerUriError): Result<PeerUri> =
    Result.failure(PeerUriException(error))

class PeerUriException(val error: PeerUriError) : Exception(error.message)
