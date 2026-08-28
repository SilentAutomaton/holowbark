package net.holowbark.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerUriTest {

    private fun parse(s: String) = parsePeerUri(s).getOrThrow()

    private fun errorOf(s: String): PeerUriError {
        val e = parsePeerUri(s).exceptionOrNull()
        assertTrue("expected a failure for '$s'", e is PeerUriException)
        return (e as PeerUriException).error
    }

    @Test
    fun parse_acceptsEveryTransportYggdrasilPeersOver() {
        PEER_SCHEMES.forEach { scheme ->
            val uri = parse("$scheme://example.org:443")
            assertEquals(scheme, uri.scheme)
            assertEquals(443, uri.port)
        }
    }

    @Test
    fun parse_readsAnIpv4Host() {
        val uri = parse("tcp://37.186.113.100:1514")
        assertEquals("37.186.113.100", uri.host)
        assertEquals(1514, uri.port)
    }

    @Test
    fun parse_readsABracketedIpv6Host() {
        val uri = parse("quic://[2a09:5302:ffff::132a]:65535")
        assertEquals("2a09:5302:ffff::132a", uri.host)
        assertEquals(65535, uri.port)
    }

    @Test
    fun parse_keepsTheQueryVerbatim() {
        // Yggdrasil authenticates peerings with ?key= and ?password=; dropping the
        // query would turn a working peer into one that silently never connects.
        val uri = parse("tls://example.org:443?sni=other.example&password=hunter2")
        assertEquals("sni=other.example&password=hunter2", uri.query)
        assertEquals("tls://example.org:443?sni=other.example&password=hunter2", uri.toString())
    }

    @Test
    fun parse_normalisesSchemeCaseAndReBracketsIpv6() {
        assertEquals("tls://example.org:443", parse("TLS://example.org:443").toString())
        assertEquals("quic://[200::1]:1", parse("quic://[200::1]:1").toString())
    }

    @Test
    fun parse_stripsSurroundingWhitespaceAndATrailingComma() {
        val expected = "tls://example.org:443"
        assertEquals(expected, parse("  tls://example.org:443  ").toString())
        assertEquals(expected, parse("tls://example.org:443,").toString())
        assertEquals(expected, parse("  tls://example.org:443, ").toString())
    }

    @Test
    fun parse_rejectsInputWithNoTransport() {
        assertEquals(PeerUriError.NO_SCHEME, errorOf("example.org:443"))
        assertEquals(PeerUriError.NO_SCHEME, errorOf("://example.org:443"))
    }

    @Test
    fun parse_rejectsATransportYggdrasilDoesNotSpeak() {
        assertEquals(PeerUriError.UNKNOWN_SCHEME, errorOf("http://example.org:443"))
        assertEquals(PeerUriError.UNKNOWN_SCHEME, errorOf("udp://example.org:443"))
    }

    @Test
    fun parse_rejectsAMissingOrUnusablePort() {
        assertEquals(PeerUriError.NO_PORT, errorOf("tls://example.org"))
        assertEquals(PeerUriError.NO_PORT, errorOf("quic://[200::1]"))
        assertEquals(PeerUriError.BAD_PORT, errorOf("tls://example.org:notaport"))
        assertEquals(PeerUriError.BAD_PORT, errorOf("tls://example.org:0"))
        assertEquals(PeerUriError.BAD_PORT, errorOf("tls://example.org:70000"))
    }

    @Test
    fun parse_rejectsAMissingHost() {
        assertEquals(PeerUriError.NO_HOST, errorOf("tls://:443"))
        assertEquals(PeerUriError.NO_HOST, errorOf("quic://[200::1:443"))
    }

    @Test
    fun parse_rejectsEmptyInput() {
        assertEquals(PeerUriError.EMPTY, errorOf(""))
        assertEquals(PeerUriError.EMPTY, errorOf("   "))
    }

    @Test
    fun parse_acceptsEveryPeerInTheBundledFallbackList() {
        // The bundled list is what the app ships with, so the parser must not
        // reject a URI the peer browser would happily offer.
        listOf(
            "quic://37.186.113.100:1515",
            "tcp://37.186.113.100:1514",
            "tls://ygg-evn-1tls.wgos.org:443",
            "ws://[2a09:5302:ffff::132a]:80",
            "wss://example.org:443",
        ).forEach { assertTrue(it, parsePeerUri(it).isSuccess) }
    }
}
