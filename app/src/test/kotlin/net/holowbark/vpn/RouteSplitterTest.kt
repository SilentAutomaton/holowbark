package net.holowbark.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * A wrong split sends Yggdrasil peer traffic into the tunnel that depends on those
 * peers, which deadlocks the overlay with no error anywhere — so the invariants
 * are checked rather than the exact route list.
 */
class RouteSplitterTest {

    private val ipv4Default = listOf(Route(InetAddress.getByName("0.0.0.0"), 0))
    private val ipv6Default = listOf(Route(InetAddress.getByName("::"), 0))

    private fun covers(routes: List<Route>, ip: InetAddress): Boolean =
        routes.any { route ->
            val r = route.address.address
            val a = ip.address
            if (r.size != a.size) return@any false
            (0 until route.prefix).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                (r[bit / 8].toInt() and mask) == (a[bit / 8].toInt() and mask)
            }
        }

    @Test
    fun excludeIpv4_singleAddress_producesOneRoutePerPrefixBit() {
        val excluded = InetAddress.getByName("1.2.3.4")
        val routes = buildRoutesExcluding(ipv4Default, setOf(hostRoute(excluded)))
        assertEquals(32, routes.size)
    }

    @Test
    fun excludeIpv4_singleAddress_doesNotCoverIt() {
        val excluded = InetAddress.getByName("1.2.3.4")
        val routes = buildRoutesExcluding(ipv4Default, setOf(hostRoute(excluded)))
        assertFalse(covers(routes, excluded))
    }

    @Test
    fun excludeIpv4_singleAddress_stillCoversEveryOtherAddress() {
        val routes = buildRoutesExcluding(ipv4Default, setOf(hostRoute(InetAddress.getByName("1.2.3.4"))))
        listOf("0.0.0.0", "1.2.3.3", "1.2.3.5", "1.2.4.4", "8.8.8.8", "255.255.255.255")
            .forEach { assertTrue(it, covers(routes, InetAddress.getByName(it))) }
    }

    @Test
    fun excludeIpv6_singleAddress_producesOneRoutePerPrefixBit() {
        val excluded = InetAddress.getByName("2a09:5302:ffff::132a")
        val routes = buildRoutesExcluding(ipv6Default, setOf(hostRoute(excluded)))
        assertEquals(128, routes.size)
        assertFalse(covers(routes, excluded))
        assertTrue(covers(routes, InetAddress.getByName("2a09:5302:ffff::132b")))
    }

    @Test
    fun excludeTwoAddresses_omitsBothAndKeepsTheRest() {
        val first = InetAddress.getByName("1.2.3.4")
        val second = InetAddress.getByName("200.100.50.25")
        val routes = buildRoutesExcluding(ipv4Default, setOf(hostRoute(first), hostRoute(second)))
        assertFalse(covers(routes, first))
        assertFalse(covers(routes, second))
        assertTrue(covers(routes, InetAddress.getByName("1.2.3.5")))
        assertTrue(covers(routes, InetAddress.getByName("200.100.50.26")))
    }

    @Test
    fun excludeTwoAddresses_orderDoesNotMatter() {
        val a = InetAddress.getByName("1.2.3.4")
        val b = InetAddress.getByName("9.9.9.9")
        val forward = buildRoutesExcluding(ipv4Default, linkedSetOf(hostRoute(a), hostRoute(b))).toSet()
        val reverse = buildRoutesExcluding(ipv4Default, linkedSetOf(hostRoute(b), hostRoute(a))).toSet()
        assertEquals(forward, reverse)
    }

    @Test
    fun excludeAddressOutsideBaseRoutes_changesNothing() {
        val base = listOf(Route(InetAddress.getByName("10.0.0.0"), 8))
        val routes = buildRoutesExcluding(base, setOf(hostRoute(InetAddress.getByName("192.168.1.1"))))
        assertEquals(base, routes)
    }

    @Test
    fun excludeAddressFromDifferentFamily_changesNothing() {
        val routes = buildRoutesExcluding(ipv4Default, setOf(hostRoute(InetAddress.getByName("200::1"))))
        assertEquals(ipv4Default, routes)
    }

    @Test
    fun excludeSubnet_wholePrefix_leavesItUncoveredAndKeepsNeighbours() {
        val routes = buildRoutesExcluding(ipv4Default, setOf(parseSubnet("10.0.0.0/8")!!))
        assertEquals(8, routes.size)
        assertFalse(covers(routes, InetAddress.getByName("10.1.2.3")))
        assertTrue(covers(routes, InetAddress.getByName("11.0.0.1")))
        assertTrue(covers(routes, InetAddress.getByName("9.255.255.255")))
    }

    @Test
    fun parseSubnet_hostBitsSet_masksThemOff() {
        assertEquals(
            Route(InetAddress.getByName("10.0.0.0"), 8),
            parseSubnet("10.1.2.3/8"),
        )
    }

    @Test
    fun parseSubnet_ipv6_isAccepted() {
        assertEquals(Route(InetAddress.getByName("200::"), 7), parseSubnet("200::/7"))
    }

    @Test
    fun parseSubnet_spacesAroundTheSlash_areIgnored() {
        assertEquals(Route(InetAddress.getByName("10.0.0.0"), 8), parseSubnet(" 10.0.0.0 / 8 "))
    }

    @Test
    fun parseSubnet_prefixOutOfRange_returnsNull() {
        assertNull(parseSubnet("10.0.0.0/33"))
    }

    @Test
    fun parseSubnet_withoutPrefix_returnsNull() {
        assertNull(parseSubnet("10.0.0.0"))
    }

    @Test
    fun parseSubnet_hostname_returnsNull() {
        assertNull(parseSubnet("example.com/24"))
    }
}
