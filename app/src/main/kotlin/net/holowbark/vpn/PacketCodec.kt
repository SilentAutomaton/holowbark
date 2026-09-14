package net.holowbark.vpn

import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builders and parsers for the raw packets Holowbark moves by hand.
 *
 * AmneziaWG runs on a channel-backed bind rather than a real socket, so its
 * WireGuard protocol frames arrive as plain byte arrays. They are wrapped in IPv6
 * UDP here and handed to the Yggdrasil overlay, and unwrapped again on the way
 * back. The same code builds the ICMPv6 pings and the split-DNS replies.
 */

/** Source port of outbound WG frames, and the expected destination port inbound. */
const val WG_LOCAL_PORT = 51820

private const val DNS_HEADER_LEN = 12
private const val IPV4_HEADER_LEN = 20

/** Wrap a raw WireGuard frame in an IPv6 UDP datagram between two overlay addresses. */
fun buildIPv6UDP(
    srcAddr: ByteArray,
    dstAddr: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
): ByteArray {
    val udpLen = UDP_HEADER_LEN + payload.size
    val buf = ByteBuffer.allocate(IPV6_HEADER_LEN + udpLen).order(ByteOrder.BIG_ENDIAN)
    buf.putInt(0x60000000)                  // version 6, traffic class 0, flow label 0
    buf.putShort(udpLen.toShort())
    buf.put(IP_PROTO_UDP.toByte())
    buf.put(64)                             // hop limit
    buf.put(srcAddr)
    buf.put(dstAddr)
    buf.putShort(srcPort.toShort())
    buf.putShort(dstPort.toShort())
    buf.putShort(udpLen.toShort())
    buf.putShort(0)                         // checksum, filled in below
    buf.put(payload)

    val bytes = buf.array()
    // RFC 8200 §8.1: unlike IPv4, a zero UDP checksum is illegal over IPv6.
    var sum = ipv6Checksum(srcAddr, dstAddr, bytes, IPV6_HEADER_LEN, udpLen, IP_PROTO_UDP)
    if (sum == 0) sum = 0xFFFF
    bytes.putU16(IPV6_HEADER_LEN + 6, sum)
    return bytes
}

/**
 * The UDP payload of an IPv6 packet from [expectedSrcAddr], or null when the
 * packet is not one — wrong version, wrong protocol, wrong sender, or truncated.
 */
fun ByteArray.extractWGPayload(expectedSrcAddr: ByteArray): ByteArray? {
    if (size < IPV6_HEADER_LEN + UDP_HEADER_LEN) return null
    if (!isIpv6() || ipv6NextHeader() != IP_PROTO_UDP) return null
    for (i in 0..15) if (this[8 + i] != expectedSrcAddr[i]) return null
    return udpPayload()
}

/** True when this IPv6 packet is addressed to [addr], which must be 16 bytes. */
fun ByteArray.isIpv6To(addr: ByteArray): Boolean {
    if (size < IPV6_HEADER_LEN || !isIpv6()) return false
    for (i in 0..15) if (this[24 + i] != addr[i]) return false
    return true
}

/** The UDP payload of an IPv6 datagram, or null when the length field does not fit. */
fun ByteArray.udpPayload(): ByteArray? {
    val start = IPV6_HEADER_LEN + UDP_HEADER_LEN
    if (size < start) return null
    val payloadLen = u16(IPV6_HEADER_LEN + 4) - UDP_HEADER_LEN
    if (payloadLen <= 0 || size < start + payloadLen) return null
    return copyOfRange(start, start + payloadLen)
}

/** Source port of an IPv6 UDP datagram. */
fun ByteArray.udpSrcPort(): Int = u16(IPV6_HEADER_LEN)

/**
 * The 16 address bytes of an IPv6 host, accepting either a bare address or an
 * endpoint such as `[200:…]:44555` or `quic://[200:…]:65535`. Null for anything
 * that is not IPv6.
 */
fun parseIpv6Bytes(hostOrEndpoint: String): ByteArray? = runCatching {
    val host = hostOrEndpoint
        .substringAfter("://")
        .substringBefore('%')
        .let { if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it }
    (InetAddress.getByName(host) as? Inet6Address)?.address
}.getOrNull()

/** The port of an endpoint such as `[200:…]:44555`, or [fallback] if absent. */
fun parseEndpointPort(endpoint: String, fallback: Int = 44555): Int =
    endpoint.substringAfterLast(':').toIntOrNull() ?: fallback

/**
 * An IPv6 ICMPv6 Echo Request. [seq] correlates the reply, which arrives on the
 * Yggdrasil read loop rather than through any socket.
 */
fun buildICMPv6Echo(srcAddr: ByteArray, dstAddr: ByteArray, seq: Int): ByteArray {
    val icmpLen = 16                        // 8-byte header + 8 zero bytes of data
    val buf = ByteBuffer.allocate(IPV6_HEADER_LEN + icmpLen).order(ByteOrder.BIG_ENDIAN)
    buf.putInt(0x60000000)
    buf.putShort(icmpLen.toShort())
    buf.put(IP_PROTO_ICMPV6.toByte())
    buf.put(64)
    buf.put(srcAddr)
    buf.put(dstAddr)
    buf.put(ICMPV6_ECHO_REQUEST.toByte())
    buf.put(0)                              // code
    buf.putShort(0)                         // checksum, filled in below
    buf.putShort(1)                         // identifier
    buf.putShort(seq.toShort())
    buf.put(ByteArray(8))

    val bytes = buf.array()
    val sum = ipv6Checksum(srcAddr, dstAddr, bytes, IPV6_HEADER_LEN, icmpLen, IP_PROTO_ICMPV6)
    bytes.putU16(IPV6_HEADER_LEN + 2, sum)
    return bytes
}

const val ICMPV6_ECHO_REQUEST = 128
const val ICMPV6_ECHO_REPLY = 129

/** True when this is an ICMPv6 Echo Reply long enough to carry a sequence number. */
fun ByteArray.isIcmpv6EchoReply(): Boolean =
    size >= IPV6_HEADER_LEN + 8 &&
        isIpv6() &&
        ipv6NextHeader() == IP_PROTO_ICMPV6 &&
        (this[IPV6_HEADER_LEN].toInt() and 0xFF) == ICMPV6_ECHO_REPLY

/** Sequence number of an ICMPv6 Echo message. */
fun ByteArray.icmpv6EchoSeq(): Int = u16(IPV6_HEADER_LEN + 6)

/**
 * A minimal ICMP Echo Request inside the WireGuard AllowedIPs range. Feeding this
 * into the plaintext side of AWG makes it encrypt something, which is what
 * triggers the handshake — there is no other way to start one on demand.
 */
fun buildHandshakeTrigger(): ByteArray {
    val buf = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
    buf.put(0x45)                           // version 4, header length 5 words
    buf.put(0)                              // DSCP
    buf.putShort(28)                        // total length
    buf.putShort(0)                         // identification
    buf.putShort(0)                         // flags and fragment offset
    buf.put(64)                             // TTL
    buf.put(IP_PROTO_ICMP.toByte())
    buf.putShort(0)                         // header checksum, filled in below
    buf.put(byteArrayOf(10, 100, 0, 1))     // our TUN address
    buf.put(byteArrayOf(10, 0, 0, 1))       // the server's VPN address
    buf.put(8)                              // ICMP Echo Request
    buf.put(0)                              // code
    buf.putShort(0)                         // ICMP checksum, unchecked by the peer
    buf.putShort(1)                         // identifier
    buf.putShort(1)                         // sequence

    val bytes = buf.array()
    bytes.putU16(10, ipv4HeaderChecksum(bytes))
    return bytes
}

/**
 * An IPv4 UDP datagram, used for the split-DNS proxy's replies to the system
 * resolver. The UDP checksum is left at zero, which IPv4 permits.
 */
fun buildIPv4UdpReply(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
): ByteArray {
    val udpLen = UDP_HEADER_LEN + payload.size
    val totalLen = IPV4_HEADER_LEN + udpLen
    val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
    buf.put(0x45)
    buf.put(0)
    buf.putShort(totalLen.toShort())
    buf.putShort(0)
    buf.putShort(0x4000.toShort())          // don't fragment
    buf.put(64)
    buf.put(IP_PROTO_UDP.toByte())
    buf.putShort(0)                         // header checksum, filled in below
    buf.put(srcIp)
    buf.put(dstIp)
    buf.putShort(srcPort.toShort())
    buf.putShort(dstPort.toShort())
    buf.putShort(udpLen.toShort())
    buf.putShort(0)                         // UDP checksum, optional over IPv4
    buf.put(payload)

    val bytes = buf.array()
    bytes.putU16(10, ipv4HeaderChecksum(bytes))
    return bytes
}

/**
 * The first question name in a DNS message, lowercased and dotted, or "" when the
 * message is too short. A compression pointer ends the name early — the proxy only
 * needs the suffix, and a query's first name is never compressed in practice.
 */
fun extractDnsName(dnsPayload: ByteArray): String {
    if (dnsPayload.size <= DNS_HEADER_LEN) return ""
    val name = StringBuilder()
    var i = DNS_HEADER_LEN
    while (i < dnsPayload.size) {
        val len = dnsPayload[i].toInt() and 0xFF
        if (len == 0 || (len and 0xC0) == 0xC0) break
        i++
        if (i + len > dnsPayload.size) break
        if (name.isNotEmpty()) name.append('.')
        name.append(String(dnsPayload, i, len, Charsets.US_ASCII))
        i += len
    }
    return name.toString().lowercase()
}

/** Transaction id of a DNS message. */
fun ByteArray.dnsTransactionId(): Int = u16(0)

/**
 * The internet checksum over an IPv6 pseudo-header plus [len] bytes at [offset].
 * The only difference between the UDP and ICMPv6 variants is [nextHeader].
 */
private fun ipv6Checksum(
    src: ByteArray,
    dst: ByteArray,
    packet: ByteArray,
    offset: Int,
    len: Int,
    nextHeader: Int,
): Int {
    var sum = 0L
    for (i in 0..14 step 2) sum += src.u16(i)
    for (i in 0..14 step 2) sum += dst.u16(i)
    sum += len.toLong()
    sum += nextHeader.toLong()
    sum += onesComplementSum(packet, offset, len)
    return sum.foldCarry()
}

private fun ipv4HeaderChecksum(packet: ByteArray): Int =
    onesComplementSum(packet, 0, IPV4_HEADER_LEN).foldCarry()

private fun onesComplementSum(data: ByteArray, offset: Int, len: Int): Long {
    var sum = 0L
    var i = offset
    while (i + 1 < offset + len) {
        sum += data.u16(i)
        i += 2
    }
    if (len % 2 != 0) sum += (data[offset + len - 1].toLong() and 0xFF) shl 8
    return sum
}

private fun Long.foldCarry(): Int {
    var sum = this
    while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    return (sum.inv() and 0xFFFF).toInt()
}

private fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = (value and 0xFF).toByte()
}
