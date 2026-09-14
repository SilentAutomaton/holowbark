package net.holowbark.vpn

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Route sets that cover everything except a few addresses or subnets.
 *
 * Before API 33 there is no VpnService.Builder.excludeRoute, so the only way to
 * keep traffic out of the tunnel — Yggdrasil peers, or a subnet the user wants left
 * on the local network — is to replace the catch-all route with the sub-routes that
 * cover everything else. Splitting the enclosing route in half and keeping the half
 * without the excluded one, repeatedly, costs at most 32 routes per excluded IPv4
 * address and 128 per IPv6 one.
 *
 * Excluding 1.2.3.4/32 from 0.0.0.0/0 yields 128.0.0.0/1, 64.0.0.0/2, 0.0.0.0/3 …
 */

data class Route(val address: InetAddress, val prefix: Int)

/** The single-host route for [ip], which is how one address is excluded. */
fun hostRoute(ip: InetAddress) = Route(ip, ip.maxPrefix())

/**
 * A subnet written as `10.0.0.0/8`, or null when the text is not one. Host bits are
 * masked off, so the result is what the route will actually cover.
 */
fun parseSubnet(text: String): Route? {
    val parts = text.split('/').map { it.trim() }
    if (parts.size != 2) return null
    // getByName resolves hostnames, which would mean a DNS lookup on whatever the
    // user typed. Numeric text is the only kind it must be given here.
    if (!NUMERIC_ADDRESS.matches(parts[0])) return null
    val address = runCatching { InetAddress.getByName(parts[0]) }.getOrNull() ?: return null
    val prefix = parts[1].toIntOrNull() ?: return null
    if (prefix !in 0..address.maxPrefix()) return null
    return Route(address.masked(prefix), prefix)
}

/** [baseRoutes] minus [excluded], as routes for VpnService.Builder.addRoute. */
fun buildRoutesExcluding(
    baseRoutes: List<Route>,
    excluded: Set<Route>,
): List<Route> = excluded.fold(baseRoutes) { routes, route -> routes.excluding(route) }

private val NUMERIC_ADDRESS = Regex("[0-9A-Fa-f.:]+")

private fun List<Route>.excluding(excluded: Route): List<Route> {
    // The most specific covering route is the only one that needs splitting.
    val enclosing = filter { it.contains(excluded) }.maxByOrNull { it.prefix } ?: return this
    return filterNot { it == enclosing } + enclosing.splitAround(excluded)
}

/** [route] broken into the sub-routes that cover it except for [excluded]. */
private fun Route.splitAround(excluded: Route): List<Route> {
    if (prefix >= excluded.prefix) return emptyList()   // the route is the excluded one
    val (left, right) = halves()
    val (containing, other) = if (left.contains(excluded)) left to right else right to left
    return listOf(other) + containing.splitAround(excluded)
}

/** The two /n+1 routes this route is made of. */
private fun Route.halves(): Pair<Route, Route> {
    val childPrefix = prefix + 1
    val lowerBytes = address.address
    val upperBytes = lowerBytes.copyOf()
    val byteIndex = (childPrefix - 1) / 8
    val bitIndex = 7 - (childPrefix - 1) % 8
    upperBytes[byteIndex] = (upperBytes[byteIndex].toInt() or (1 shl bitIndex)).toByte()
    return Route(InetAddress.getByAddress(lowerBytes), childPrefix) to
        Route(InetAddress.getByAddress(upperBytes), childPrefix)
}

private fun Route.contains(other: Route): Boolean {
    val routeBytes = address.address
    val otherBytes = other.address.address
    if (routeBytes.size != otherBytes.size) return false
    if (prefix > other.prefix) return false
    val wholeBytes = prefix / 8
    for (i in 0 until wholeBytes) if (routeBytes[i] != otherBytes[i]) return false
    val remainingBits = prefix % 8
    if (remainingBits == 0 || wholeBytes >= routeBytes.size) return true
    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    return (routeBytes[wholeBytes].toInt() and mask) == (otherBytes[wholeBytes].toInt() and mask)
}

private fun InetAddress.masked(prefix: Int): InetAddress {
    val bytes = address
    for (i in bytes.indices) {
        val keptBits = (prefix - i * 8).coerceIn(0, 8)
        val mask = (0xFF shl (8 - keptBits)) and 0xFF
        bytes[i] = (bytes[i].toInt() and mask).toByte()
    }
    return InetAddress.getByAddress(bytes)
}

private fun InetAddress.maxPrefix() = if (this is Inet6Address) 128 else 32
