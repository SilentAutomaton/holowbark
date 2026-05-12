package net.yggawg.mobile.vpn

import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utilities for bridging AmneziaWG ↔ Yggdrasil at the Kotlin layer.
 *
 * AWG uses chanBind (no real sockets). Its WireGuard protocol packets are
 * exposed via RecvWGPacket / SendWGPacket. Kotlin wraps them in IPv6 UDP
 * frames and routes through the Yggdrasil overlay.
 *
 * Local WG port constant used as src port in outbound packets and as the
 * expected dst port when filtering inbound Yggdrasil packets.
 */
const val WG_LOCAL_PORT = 51820

/**
 * Wrap [payload] (a raw WireGuard protocol packet) in an IPv6 UDP frame
 * addressed from our Yggdrasil address to the AWG server's Yggdrasil address.
 */
fun buildIPv6UDP(
    srcAddr: ByteArray,   // 16 bytes
    dstAddr: ByteArray,   // 16 bytes
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
): ByteArray {
    val udpLen = 8 + payload.size
    val buf = ByteBuffer.allocate(40 + udpLen).order(ByteOrder.BIG_ENDIAN)
    // IPv6 header (40 bytes)
    buf.putInt(0x60000000)             // version=6, TC=0, flow=0
    buf.putShort(udpLen.toShort())     // payload length
    buf.put(0x11.toByte())             // next header = UDP
    buf.put(64.toByte())               // hop limit
    buf.put(srcAddr)
    buf.put(dstAddr)
    // UDP header (8 bytes) — checksum computed below
    buf.putShort(srcPort.toShort())
    buf.putShort(dstPort.toShort())
    buf.putShort(udpLen.toShort())
    buf.putShort(0)                    // checksum placeholder
    buf.put(payload)
    val bytes = buf.array()
    // RFC 2460 §8.1: UDP over IPv6 checksum is mandatory (0 is illegal).
    val cksum = udpv6Checksum(srcAddr, dstAddr, bytes, udpOffset = 40, udpLen = udpLen)
    bytes[40 + 6] = (cksum ushr 8).toByte()
    bytes[40 + 7] = (cksum and 0xFF).toByte()
    return bytes
}

private fun udpv6Checksum(
    src: ByteArray, dst: ByteArray,
    pkt: ByteArray, udpOffset: Int, udpLen: Int,
): Int {
    var sum = 0L
    // IPv6 pseudo-header: src(16) + dst(16) + UDP length(4) + zeros(3) + next-header(1)
    for (i in 0..14 step 2) sum += ((src[i].toLong() and 0xFF) shl 8) or (src[i + 1].toLong() and 0xFF)
    for (i in 0..14 step 2) sum += ((dst[i].toLong() and 0xFF) shl 8) or (dst[i + 1].toLong() and 0xFF)
    sum += udpLen.toLong()
    sum += 17L   // next-header = UDP
    // UDP header + data
    var i = udpOffset
    while (i + 1 < udpOffset + udpLen) {
        sum += ((pkt[i].toLong() and 0xFF) shl 8) or (pkt[i + 1].toLong() and 0xFF)
        i += 2
    }
    if (udpLen % 2 != 0) sum += (pkt[udpOffset + udpLen - 1].toLong() and 0xFF) shl 8
    while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    val result = (sum.inv() and 0xFFFF).toInt()
    // RFC 2460: if computed checksum is 0, transmit as 0xFFFF
    return if (result == 0) 0xFFFF else result
}

/**
 * Parse an IPv6 packet received from Yggdrasil.
 * Returns the UDP payload if the packet:
 *   - is IPv6
 *   - next header is UDP (0x11)
 *   - source address matches [expectedSrcAddr]
 * Returns null otherwise.
 */
fun ByteArray.extractWGPayload(expectedSrcAddr: ByteArray): ByteArray? {
    if (size < 48) return null                          // need at least IPv6(40)+UDP(8)
    if ((this[0].toInt() and 0xF0) != 0x60) return null // not IPv6
    if (this[6] != 0x11.toByte()) return null           // next header != UDP
    // Source address is bytes 8–23
    for (i in 0..15) {
        if (this[8 + i] != expectedSrcAddr[i]) return null
    }
    // UDP payload starts at byte 48 (40 IPv6 + 8 UDP header)
    val udpLen = ((this[44].toInt() and 0xFF) shl 8) or (this[45].toInt() and 0xFF)
    val payloadLen = udpLen - 8
    if (payloadLen <= 0 || size < 48 + payloadLen) return null
    return copyOfRange(48, 48 + payloadLen)
}

/**
 * Parse the AWG server Yggdrasil address (IPv6) from an endpoint string like
 * "[200:4825:fd69:6d41:5475:a08a:8885:9542]:44555" → 16-byte array.
 * Returns null if the address is not an IPv6 address.
 */
fun parseYggAddrBytes(endpoint: String): ByteArray? {
    return try {
        val host = endpoint.substringAfter("://")
            .substringBefore('%')
            .let {
                if (it.startsWith("[")) it.substringAfter("[").substringBefore("]")
                else it.substringBefore(':')
            }
        val addr = InetAddress.getByName(host)
        if (addr is Inet6Address) addr.address else null
    } catch (_: Exception) { null }
}

/** Parse the port from an endpoint string like "[...]:44555" → 44555. */
fun parseEndpointPort(endpoint: String): Int {
    return try {
        endpoint.substringAfterLast(':').toInt()
    } catch (_: Exception) { 44555 }
}

/** Parse our own Yggdrasil address string to 16-byte array. */
fun parseYggSelfAddr(addrString: String): ByteArray? {
    return try {
        val addr = InetAddress.getByName(addrString)
        if (addr is Inet6Address) addr.address else null
    } catch (_: Exception) { null }
}

// ─── ICMPv6 Echo (ping) ──────────────────────────────────────────────────────

/**
 * Build a complete IPv6 + ICMPv6 Echo Request packet.
 * [seq] is a 16-bit sequence number used to correlate replies.
 */
fun buildICMPv6Echo(srcAddr: ByteArray, dstAddr: ByteArray, seq: Int): ByteArray {
    val data = ByteArray(8)               // 8 zero-bytes payload
    val icmpLen = 8 + data.size           // 8-byte ICMPv6 header + data
    val buf = ByteBuffer.allocate(40 + icmpLen).order(ByteOrder.BIG_ENDIAN)
    // IPv6 header
    buf.putInt(0x60000000)
    buf.putShort(icmpLen.toShort())
    buf.put(0x3a.toByte())                // next header = ICMPv6
    buf.put(64.toByte())
    buf.put(srcAddr)
    buf.put(dstAddr)
    // ICMPv6 Echo Request (type 128)
    val icmpOffset = 40
    buf.put(128.toByte())                 // type
    buf.put(0.toByte())                   // code
    buf.putShort(0)                       // checksum placeholder
    buf.putShort(1)                       // identifier
    buf.putShort(seq.toShort())
    buf.put(data)
    val bytes = buf.array()
    val cksum = icmpv6Checksum(srcAddr, dstAddr, bytes, icmpOffset, icmpLen)
    bytes[icmpOffset + 2] = (cksum ushr 8).toByte()
    bytes[icmpOffset + 3] = (cksum and 0xFF).toByte()
    return bytes
}

private fun icmpv6Checksum(
    src: ByteArray, dst: ByteArray,
    pkt: ByteArray, icmpOffset: Int, icmpLen: Int,
): Int {
    var sum = 0L
    // Pseudo-header: src(16) + dst(16) + upper-layer length(4) + zeros(3) + next-header(1)
    for (i in 0..14 step 2) sum += ((src[i].toLong() and 0xFF) shl 8) or (src[i + 1].toLong() and 0xFF)
    for (i in 0..14 step 2) sum += ((dst[i].toLong() and 0xFF) shl 8) or (dst[i + 1].toLong() and 0xFF)
    sum += icmpLen.toLong()
    sum += 58L                            // ICMPv6 next-header value
    // ICMPv6 body
    var i = icmpOffset
    while (i + 1 < icmpOffset + icmpLen) {
        sum += ((pkt[i].toLong() and 0xFF) shl 8) or (pkt[i + 1].toLong() and 0xFF)
        i += 2
    }
    if (icmpLen % 2 != 0) sum += (pkt[icmpOffset + icmpLen - 1].toLong() and 0xFF) shl 8
    while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
    return (sum.inv() and 0xFFFF).toInt()
}

// ─── Dummy IPv4 for WG handshake trigger ─────────────────────────────────────

/**
 * Build a minimal ICMPv6 Echo Request (ping) packet addressed to the AWG server's
 * allowed IP. This is fed into AWG's plaintext path to trigger the WireGuard
 * handshake without real user traffic.
 *
 * Uses a fixed destination from the AllowedIPs range (10.0.0.1) so AWG encrypts
 * and sends it, causing chanBind.Send() → bridge coroutine → Yggdrasil.
 */
fun buildDummyIPv4(): ByteArray {
    // Minimal ICMP Echo Request: 20-byte IPv4 header + 8-byte ICMP
    val buf = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
    // IPv4 header
    buf.put(0x45.toByte())           // version=4, IHL=5
    buf.put(0)                       // DSCP
    buf.putShort(28)                 // total length
    buf.putShort(0)                  // ID
    buf.putShort(0)                  // flags + fragment offset
    buf.put(64)                      // TTL
    buf.put(1)                       // protocol = ICMP
    buf.putShort(0)                  // checksum (0 = let stack handle)
    buf.put(byteArrayOf(10, 100, 0, 1))   // src: 10.100.0.1 (our TUN address)
    buf.put(byteArrayOf(10, 0, 0, 1))     // dst: 10.0.0.1 (server's VPN IP)
    // ICMP Echo Request
    buf.put(8)                       // type = Echo Request
    buf.put(0)                       // code
    buf.putShort(0)                  // checksum
    buf.putShort(1)                  // identifier
    buf.putShort(1)                  // sequence
    return buf.array()
}
