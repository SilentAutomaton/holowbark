package net.holowbark.vpn

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Route sets that cover everything except a few individual addresses.
 *
 * Before API 33 there is no VpnService.Builder.excludeRoute, so the only way to
 * keep Yggdrasil peer traffic out of the tunnel is to replace the catch-all route
 * with the sub-routes that cover everything else. Splitting the enclosing route in
 * half and keeping the half without the excluded address, repeatedly, costs at most
 * 32 routes per excluded IPv4 address and 128 per IPv6 one.
 *
 * Excluding 1.2.3.4/32 from 0.0.0.0/0 yields 128.0.0.0/1, 64.0.0.0/2, 0.0.0.0/3 …
 */

data class Route(val address: InetAddress, val prefix: Int)

/** [baseRoutes] minus [excludedIPs], as routes for VpnService.Builder.addRoute. */
fun buildRoutesExcluding(
    baseRoutes: List<Route>,
    excludedIPs: Set<InetAddress>,
): List<Route> = excludedIPs.fold(baseRoutes) { routes, ip -> routes.excluding(ip) }

private fun List<Route>.excluding(ip: InetAddress): List<Route> {
    // The most specific covering route is the only one that needs splitting.
    val enclosing = filter { it.contains(ip) }.maxByOrNull { it.prefix } ?: return this
    return filterNot { it == enclosing } + enclosing.splitAround(ip)
}

/** [route] broken into the sub-routes that cover it except for [ip]. */
private fun Route.splitAround(ip: InetAddress): List<Route> {
    if (prefix == ip.maxPrefix()) return emptyList()   // the route is the excluded address
    val (left, right) = halves()
    val (containing, other) = if (left.contains(ip)) left to right else right to left
    return listOf(other) + containing.splitAround(ip)
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

private fun Route.contains(ip: InetAddress): Boolean {
    val routeBytes = address.address
    val ipBytes = ip.address
    if (routeBytes.size != ipBytes.size) return false
    val wholeBytes = prefix / 8
    for (i in 0 until wholeBytes) if (routeBytes[i] != ipBytes[i]) return false
    val remainingBits = prefix % 8
    if (remainingBits == 0 || wholeBytes >= routeBytes.size) return true
    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    return (routeBytes[wholeBytes].toInt() and mask) == (ipBytes[wholeBytes].toInt() and mask)
}

private fun InetAddress.maxPrefix() = if (this is Inet6Address) 128 else 32
