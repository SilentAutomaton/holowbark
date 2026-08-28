package net.holowbark.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PacketCodecTest {

    private val ourAddr = InetAddress.getByName("200:1111::1").address
    private val serverAddr = InetAddress.getByName("200:2222::2").address
    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    /**
     * The internet checksum of a datagram that already carries a correct checksum
     * is zero. Recomputing it is the only way to tell a real checksum from a
     * plausible-looking constant.
     */
    private fun checksumOver(packet: ByteArray, offset: Int, nextHeader: Int): Int {
        var sum = 0L
        for (i in 8 until 40 step 2) sum += packet.u16(i)   // src + dst addresses
        sum += (packet.size - offset).toLong()
        sum += nextHeader.toLong()
        var i = offset
        while (i + 1 < packet.size) { sum += packet.u16(i); i += 2 }
        if ((packet.size - offset) % 2 != 0) sum += (packet.last().toLong() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum and 0xFFFF).toInt()
    }

    @Test
    fun buildIPv6UDP_writesTheHeaderFields() {
        val packet = buildIPv6UDP(ourAddr, serverAddr, 51820, 44555, payload)
        assertEquals(6, packet.ipVersion())
        assertEquals(IP_PROTO_UDP, packet.ipv6NextHeader())
        assertEquals(IPV6_HEADER_LEN + UDP_HEADER_LEN + payload.size, packet.size)
        assertEquals(UDP_HEADER_LEN + payload.size, packet.u16(4))   // IPv6 payload length
        assertEquals(51820, packet.u16(IPV6_HEADER_LEN))
        assertEquals(44555, packet.u16(IPV6_HEADER_LEN + 2))
    }

    @Test
    fun buildIPv6UDP_checksumVerifies() {
        val packet = buildIPv6UDP(ourAddr, serverAddr, 51820, 44555, payload)
        assertEquals(0xFFFF, checksumOver(packet, IPV6_HEADER_LEN, IP_PROTO_UDP))
    }

    @Test
    fun buildIPv6UDP_neverWritesAZeroChecksum() {
        // RFC 8200 §8.1: zero is illegal over IPv6 and receivers must drop it.
        val packet = buildIPv6UDP(ourAddr, serverAddr, 51820, 44555, payload)
        assertTrue(packet.u16(IPV6_HEADER_LEN + 6) != 0)
    }

    @Test
    fun extractWGPayload_roundTripsWhatBuildIPv6UDPWrote() {
        val packet = buildIPv6UDP(serverAddr, ourAddr, 44555, WG_LOCAL_PORT, payload)
        assertArrayEquals(payload, packet.extractWGPayload(serverAddr))
    }

    @Test
    fun extractWGPayload_rejectsADifferentSender() {
        val packet = buildIPv6UDP(serverAddr, ourAddr, 44555, WG_LOCAL_PORT, payload)
        assertNull(packet.extractWGPayload(InetAddress.getByName("200:3333::3").address))
    }

    @Test
    fun extractWGPayload_rejectsANonUdpPacket() {
        val packet = buildIPv6UDP(serverAddr, ourAddr, 44555, WG_LOCAL_PORT, payload)
        packet[6] = IP_PROTO_ICMPV6.toByte()
        assertNull(packet.extractWGPayload(serverAddr))
    }

    @Test
    fun extractWGPayload_rejectsATruncatedPacket() {
        val packet = buildIPv6UDP(serverAddr, ourAddr, 44555, WG_LOCAL_PORT, payload)
        assertNull(packet.copyOf(40).extractWGPayload(serverAddr))
        assertNull(packet.copyOf(packet.size - 2).extractWGPayload(serverAddr))
    }

    @Test
    fun buildICMPv6Echo_isAnEchoRequestWithAVerifiableChecksum() {
        val packet = buildICMPv6Echo(ourAddr, serverAddr, seq = 0x1234)
        assertEquals(IP_PROTO_ICMPV6, packet.ipv6NextHeader())
        assertEquals(ICMPV6_ECHO_REQUEST, packet[IPV6_HEADER_LEN].toInt() and 0xFF)
        assertEquals(0x1234, packet.icmpv6EchoSeq())
        assertEquals(0xFFFF, checksumOver(packet, IPV6_HEADER_LEN, IP_PROTO_ICMPV6))
    }

    @Test
    fun isIcmpv6EchoReply_acceptsAReplyAndRejectsARequest() {
        val request = buildICMPv6Echo(ourAddr, serverAddr, seq = 7)
        assertTrue(!request.isIcmpv6EchoReply())
        request[IPV6_HEADER_LEN] = ICMPV6_ECHO_REPLY.toByte()
        assertTrue(request.isIcmpv6EchoReply())
        assertEquals(7, request.icmpv6EchoSeq())
    }

    @Test
    fun buildIPv4UdpReply_writesTheLengthAndAValidHeaderChecksum() {
        val src = byteArrayOf(198.toByte(), 18, 0, 53)
        val dst = byteArrayOf(10, 100, 0, 1)
        val packet = buildIPv4UdpReply(src, dst, DNS_PORT, 40000, payload)
        assertEquals(4, packet.ipVersion())
        assertEquals(IP_PROTO_UDP, packet.ipv4Protocol())
        assertEquals(packet.size, packet.u16(2))
        var sum = 0L
        for (i in 0 until 20 step 2) sum += packet.u16(i)
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, (sum and 0xFFFF).toInt())
    }

    @Test
    fun buildHandshakeTrigger_isAnIcmpEchoInsideTheAllowedIPs() {
        val packet = buildHandshakeTrigger()
        assertEquals(4, packet.ipVersion())
        assertEquals(IP_PROTO_ICMP, packet.ipv4Protocol())
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), packet.copyOfRange(16, 20))
    }

    @Test
    fun parseIpv6Bytes_acceptsBracketedBareAndSchemedForms() {
        val expected = InetAddress.getByName("200:4825:fd69::1").address
        assertArrayEquals(expected, parseIpv6Bytes("[200:4825:fd69::1]:44555"))
        assertArrayEquals(expected, parseIpv6Bytes("200:4825:fd69::1"))
        assertArrayEquals(expected, parseIpv6Bytes("quic://[200:4825:fd69::1]:65535"))
    }

    @Test
    fun parseIpv6Bytes_rejectsIpv4AndGarbage() {
        assertNull(parseIpv6Bytes("1.2.3.4:51820"))
        assertNull(parseIpv6Bytes("not an address"))
        assertNull(parseIpv6Bytes(""))
    }

    @Test
    fun parseEndpointPort_readsThePortOrFallsBack() {
        assertEquals(44555, parseEndpointPort("[200::1]:44555"))
        assertEquals(51820, parseEndpointPort("1.2.3.4:51820"))
        assertEquals(9999, parseEndpointPort("[200::1]", fallback = 9999))
    }

    @Test
    fun extractDnsName_readsTheFirstQuestion() {
        assertEquals("www.example.ygg", dnsQuery("www.example.ygg").let { extractDnsName(it) })
        assertEquals("host.ygg", extractDnsName(dnsQuery("HOST.YGG")))
    }

    @Test
    fun extractDnsName_stopsAtACompressionPointer() {
        val query = dnsQuery("www") + byteArrayOf(0xC0.toByte(), 0x0C)
        assertEquals("www", extractDnsName(query.copyOf(query.size - 1)))
    }

    @Test
    fun extractDnsName_returnsEmptyForATruncatedMessage() {
        assertEquals("", extractDnsName(ByteArray(12)))
        assertEquals("", extractDnsName(ByteArray(4)))
        // A length byte that runs past the end must not throw.
        assertEquals("", extractDnsName(ByteArray(12) + byteArrayOf(20, 'a'.code.toByte())))
    }

    @Test
    fun dnsTransactionId_readsTheFirstTwoBytes() {
        assertEquals(0xBEEF, byteArrayOf(0xBE.toByte(), 0xEF.toByte(), 0, 0).dnsTransactionId())
    }

    /** A DNS query message: 12-byte header, then the name as length-prefixed labels. */
    private fun dnsQuery(name: String): ByteArray {
        val header = ByteArray(12)
        val labels = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }
        return header + labels.toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
    }
}
