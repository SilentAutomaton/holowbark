package net.holowbark.peers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerNodesParserTest {

    private val nodes = """
        {
          "russia.md": {
            "tls://1.2.3.4:12345": { "up": true, "response_ms": 42, "last_seen": 1712000000 },
            "quic://[2a09:5302::1]:65535": { "up": false }
          },
          "narnia.md": {
            "tcp://5.6.7.8:9999": { "up": true, "response_ms": 300, "last_seen": 1712000001 }
          },
          "README": { }
        }
    """.trimIndent()

    private val regions = mapOf("russia.md" to "europe")

    @Test
    fun parse_joinsRegionAndFilenameIntoACountryKey() {
        val peers = parsePeerNodes(nodes, regions, now = 1000L)
        assertEquals("europe/russia", peers.first { it.address.startsWith("tls://") }.country)
    }

    @Test
    fun parse_unknownFilenameFallsBackToOther() {
        val peers = parsePeerNodes(nodes, regions, now = 1000L)
        assertEquals("other/narnia", peers.first { it.address.startsWith("tcp://") }.country)
    }

    @Test
    fun parse_skipsTopLevelKeysThatAreNotCountryPages() {
        val peers = parsePeerNodes(nodes, regions, now = 1000L)
        assertEquals(3, peers.size)
    }

    @Test
    fun parse_carriesTheMeasurementFields() {
        val peer = parsePeerNodes(nodes, regions, now = 1000L)
            .first { it.address == "tls://1.2.3.4:12345" }
        assertTrue(peer.up)
        assertEquals(42, peer.responseMs)
        assertEquals(1000L, peer.cachedAt)
    }

    @Test
    fun parse_convertsLastSeenFromSecondsToMillis() {
        val peer = parsePeerNodes(nodes, regions, now = 1000L)
            .first { it.address == "tls://1.2.3.4:12345" }
        assertEquals(1712000000_000L, peer.lastSeen)
    }

    @Test
    fun parse_missingOptionalFieldsBecomeNullOrFalse() {
        val peer = parsePeerNodes(nodes, regions, now = 1000L)
            .first { it.address.startsWith("quic://") }
        assertEquals(false, peer.up)
        assertNull(peer.responseMs)
        assertNull(peer.lastSeen)
    }

    @Test
    fun parse_malformedJsonYieldsNoPeersInsteadOfThrowing() {
        assertEquals(emptyList<Any>(), parsePeerNodes("{ not json", regions))
        assertEquals(emptyList<Any>(), parsePeerNodes("", regions))
        assertEquals(emptyList<Any>(), parsePeerNodes("[1, 2, 3]", regions))
    }
}
