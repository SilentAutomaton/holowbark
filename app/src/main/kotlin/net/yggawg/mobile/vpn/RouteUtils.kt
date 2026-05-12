package net.yggawg.mobile.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android equivalent of rules.py routing logic.
 *
 * On desktop: `ip route replace <peer_ip> via <gateway>` bypasses AWG for Yggdrasil peers.
 * On Android: we can't add "exclude" routes directly in VpnService.Builder (excludeRoute()
 * requires API 33). Instead we split the broad route into sub-routes that cover everything
 * EXCEPT the excluded /32 (or /128) peer addresses.
 *
 * Algorithm: for each excluded IP, split the overlapping covering route into two halves,
 * keep the half that doesn't contain the excluded IP, and recurse into the other half.
 * This produces at most 32 routes per excluded IPv4 and 128 per IPv6.
 *
 * Example: exclude 1.2.3.4/32 from 0.0.0.0/0 produces:
 *   128.0.0.0/1, 64.0.0.0/2, 0.0.0.0/3, ... (31 routes covering all except 1.2.3.4)
 */

data class Route(val address: InetAddress, val prefix: Int)

/**
 * Build VPN routes that cover [baseRoutes] minus [excludedIPs].
 *
 * [baseRoutes]   — the initial set of routes (e.g. 0.0.0.0/0 + ::/0 + 200::/7).
 * [excludedIPs]  — peer IPs that must NOT be routed through the VPN.
 *
 * Returns routes to pass to VpnService.Builder.addRoute().
 */
fun buildRoutesExcluding(
    baseRoutes: List<Route>,
    excludedIPs: Set<InetAddress>,
): List<Route> {
    var routes = baseRoutes.toMutableList()
    for (excluded in excludedIPs) {
        routes = splitOutIP(routes, excluded)
    }
    return routes
}

/**
 * Remove [ip] from the route set by splitting the smallest enclosing route.
 */
private fun splitOutIP(routes: MutableList<Route>, ip: InetAddress): MutableList<Route> {
    // Find the most-specific route that contains ip
    val enclosing = routes
        .filter { routeContains(it, ip) }
        .maxByOrNull { it.prefix }
        ?: return routes   // ip not covered by any route — nothing to do

    if (enclosing.prefix == maxPrefix(ip)) {
        // Already an exact /32 or /128 — just remove it
        routes.remove(enclosing)
        return routes
    }

    routes.remove(enclosing)
    // Split enclosing into two halves; add the one that does NOT contain ip
    val (left, right) = splitRoute(enclosing)
    if (routeContains(left, ip)) {
        routes.add(right)
        routes.addAll(splitOut(left, ip))
    } else {
        routes.add(left)
        routes.addAll(splitOut(right, ip))
    }
    return routes
}

/** Recursively split [route] until [ip] is isolated and excluded. */
private fun splitOut(route: Route, ip: InetAddress): List<Route> {
    if (route.prefix == maxPrefix(ip)) return emptyList()   // exact match — exclude
    val (left, right) = splitRoute(route)
    return if (routeContains(left, ip)) {
        listOf(right) + splitOut(left, ip)
    } else {
        listOf(left) + splitOut(right, ip)
    }
}

/** Split a route into two /n+1 halves. */
private fun splitRoute(route: Route): Pair<Route, Route> {
    val newPrefix = route.prefix + 1
    val addrBytes  = route.address.address.copyOf()
    // Left half: keep address as-is with newPrefix
    val left = Route(InetAddress.getByAddress(addrBytes), newPrefix)
    // Right half: flip the (newPrefix)th bit
    val rightBytes = addrBytes.copyOf()
    val byteIdx = (newPrefix - 1) / 8
    val bitIdx  = 7 - (newPrefix - 1) % 8
    rightBytes[byteIdx] = (rightBytes[byteIdx].toInt() or (1 shl bitIdx)).toByte()
    val right = Route(InetAddress.getByAddress(rightBytes), newPrefix)
    return left to right
}

/** True if [route] contains [ip]. */
private fun routeContains(route: Route, ip: InetAddress): Boolean {
    val routeAddr = route.address.address
    val ipAddr    = ip.address
    if (routeAddr.size != ipAddr.size) return false
    val fullBytes = route.prefix / 8
    val remBits   = route.prefix % 8
    for (i in 0 until fullBytes) {
        if (routeAddr[i] != ipAddr[i]) return false
    }
    if (remBits > 0 && fullBytes < routeAddr.size) {
        val mask = (0xFF shl (8 - remBits)) and 0xFF
        if ((routeAddr[fullBytes].toInt() and mask) != (ipAddr[fullBytes].toInt() and mask)) return false
    }
    return true
}

private fun maxPrefix(ip: InetAddress) = if (ip is Inet6Address) 128 else 32

// ─── Parsing helpers ──────────────────────────────────────────────────────────

/**
 * Parse AWG AllowedIPs string (comma-separated CIDRs) into [Route] list.
 * Skips invalid entries silently.
 */
fun parseAllowedIPs(allowedIPs: String): List<Route> =
    allowedIPs.split(",").mapNotNull { it.trim().parseCidr() }

/** Parse "a.b.c.d/prefix" or "ipv6/prefix" into [Route], or null on failure. */
private fun String.parseCidr(): Route? {
    val slash = lastIndexOf('/')
    if (slash < 0) return null
    val ip     = runCatching { InetAddress.getByName(substring(0, slash)) }.getOrNull() ?: return null
    val prefix = substring(slash + 1).toIntOrNull() ?: return null
    val max    = if (ip is Inet6Address) 128 else 32
    if (prefix < 0 || prefix > max) return null
    // Mask address to canonical network address
    val masked = maskAddress(ip.address, prefix)
    return Route(InetAddress.getByAddress(masked), prefix)
}

private fun maskAddress(bytes: ByteArray, prefix: Int): ByteArray {
    val out = bytes.copyOf()
    val fullBytes = prefix / 8
    val remBits   = prefix % 8
    if (remBits > 0 && fullBytes < out.size) {
        val mask = (0xFF shl (8 - remBits)) and 0xFF
        out[fullBytes] = (out[fullBytes].toInt() and mask).toByte()
    }
    for (i in fullBytes + (if (remBits > 0) 1 else 0) until out.size) {
        out[i] = 0
    }
    return out
}
