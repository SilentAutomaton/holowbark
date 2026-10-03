package net.holowbark.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OwnerFilterTest {

    private val local = InetAddress.getByName("10.9.0.2").address
    private val remote = InetAddress.getByName("1.1.1.1").address
    private val excludedUid = 10100
    private val otherUid = 10200

    private val forwarded = mutableListOf<ByteArray>()
    private var lookups = 0
    private var now = 0L

    private fun filter(ownerUid: Int, isAllowList: Boolean = false) = OwnerFilter(
        uids = setOf(excludedUid),
        isAllowList = isAllowList,
        lookup = { _, _, _ -> lookups++; ownerUid },
        forward = { forwarded.add(it) },
        executor = { it.run() },
        clock = { now },
    )

    private fun ipv4(protocol: Int, l4: ByteArray): ByteArray {
        val packet = ByteArray(IPV4_MIN_LEN + l4.size)
        packet[0] = 0x45
        packet[9] = protocol.toByte()
        local.copyInto(packet, 12)
        remote.copyInto(packet, 16)
        l4.copyInto(packet, IPV4_MIN_LEN)
        return packet
    }

    private fun ports(size: Int) = ByteArray(size).also {
        it[0] = 0xC3.toByte(); it[1] = 0x50   // 50000
        it[2] = 0x01; it[3] = 0xBB.toByte()   // 443
    }

    private fun tcp(flags: Int) = ipv4(IP_PROTO_TCP, ports(20).also { it[13] = flags.toByte() })
    private fun udp() = ipv4(IP_PROTO_UDP, ports(UDP_HEADER_LEN))

    @Test
    fun admit_synOfExcludedApp_isDropped() {
        filter(excludedUid).admit(tcp(0x02))
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun admit_synOfOtherApp_isForwarded() {
        filter(otherUid).admit(tcp(0x02))
        assertEquals(1, forwarded.size)
    }

    @Test
    fun admit_invalidUid_isDropped() {
        filter(OwnerFilter.INVALID_UID).admit(tcp(0x02))
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun admit_tcpWithoutSyn_isDroppedWithoutLookup() {
        filter(otherUid).admit(tcp(0x10))
        assertTrue(forwarded.isEmpty())
        assertEquals(0, lookups)
    }

    @Test
    fun admit_tcpAfterAdmittedSyn_usesTheVerdict() {
        val f = filter(otherUid)
        f.admit(tcp(0x02))
        f.admit(tcp(0x10))
        assertEquals(2, forwarded.size)
        assertEquals(1, lookups)
    }

    @Test
    fun admit_udpWithinTtl_looksUpOnce() {
        val f = filter(otherUid)
        f.admit(udp())
        f.admit(udp())
        assertEquals(2, forwarded.size)
        assertEquals(1, lookups)
    }

    @Test
    fun admit_udpAfterTtl_looksUpAgain() {
        val f = filter(otherUid)
        f.admit(udp())
        now += 11_000_000_000L
        f.admit(udp())
        assertEquals(2, lookups)
    }

    @Test
    fun admit_icmp_isDropped() {
        filter(otherUid).admit(ipv4(IP_PROTO_ICMP, ByteArray(8)))
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun admit_ipv4Fragment_isDropped() {
        filter(otherUid).admit(udp().also { it[6] = 0x20 })   // more fragments
        assertTrue(forwarded.isEmpty())
    }

    @Test
    fun admit_allowListWithListedApp_isForwarded() {
        filter(excludedUid, isAllowList = true).admit(udp())
        assertEquals(1, forwarded.size)
    }

    @Test
    fun admit_allowListWithUnlistedApp_isDropped() {
        filter(otherUid, isAllowList = true).admit(udp())
        assertTrue(forwarded.isEmpty())
    }
}
