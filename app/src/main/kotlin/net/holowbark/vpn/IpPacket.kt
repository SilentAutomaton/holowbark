package net.holowbark.vpn

/**
 * Field accessors for raw IP packets, shared by everything that inspects one.
 * Offsets are from RFC 791 (IPv4) and RFC 8200 (IPv6); no bounds checking, so
 * callers must confirm the length first.
 */

const val IP_PROTO_ICMP   = 1
const val IP_PROTO_UDP    = 17
const val IP_PROTO_ICMPV6 = 58

const val DNS_PORT = 53

const val IPV6_HEADER_LEN = 40
const val UDP_HEADER_LEN  = 8

/** Big-endian 16-bit read — the byte order of every field in an IP header. */
fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

fun ByteArray.ipVersion(): Int = if (isEmpty()) 0 else (this[0].toInt() and 0xF0) ushr 4

fun ByteArray.isIpv4(): Boolean = ipVersion() == 4
fun ByteArray.isIpv6(): Boolean = ipVersion() == 6

/** IPv4 header length in bytes; the IHL field counts 32-bit words. */
fun ByteArray.ipv4HeaderLen(): Int = (this[0].toInt() and 0x0F) * 4

fun ByteArray.ipv4Protocol(): Int = this[9].toInt() and 0xFF
fun ByteArray.ipv6NextHeader(): Int = this[6].toInt() and 0xFF

/** True when this IPv6 address byte lies in the Yggdrasil overlay range 200::/7. */
fun Byte.isYggdrasilPrefix(): Boolean = (toInt() and 0xFE) == 0x02
