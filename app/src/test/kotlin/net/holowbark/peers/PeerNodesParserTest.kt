package net.holowbark.peers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerNodesParserTest {

    private val nodes = """
        {
          "russia.md": {
            "tls://1.2.3.4:12345": { "up": true, "response_ms": 42, "last_seen": 1712000000, "updated": 1712000100 },
            "quic://[2a09:5302::1]:65535": { "up": false, "updated": 1712000100 },
            "tcp://[2a09:5302::2]:1": { "up": false, "response_ms": 847, "updated": 1712000100 },
            "tls://9.9.9.9:1": { "up": true, "response_ms": 10, "updated": 1700000000 }
          },
          "narnia.md": {
            "tcp://5.6.7.8:9999": { "up": true, "response_ms": 300, "last_seen": 1712000001 }
          },
          "README": { }
        }
    """.trimIndent()

    // An hour after the crawler's pass over the russia.md peers.
    private val now = 1712003700_000L

    private val regions = mapOf("russia.md" to "europe")

    @Test
    fun parse_joinsRegionAndFilenameIntoACountryKey() {
        val peers = parsePeerNodes(nodes, regions, now = now)
        assertEquals("europe/russia", peers.first { it.address.startsWith("tls://") }.country)
    }

    @Test
    fun parse_unknownFilenameFallsBackToOther() {
        val peers = parsePeerNodes(nodes, regions, now = now)
        assertEquals("other/narnia", peers.first { it.address == "tcp://5.6.7.8:9999" }.country)
    }

    @Test
    fun parse_skipsTopLevelKeysThatAreNotCountryPages() {
        val peers = parsePeerNodes(nodes, regions, now = now)
        assertEquals(5, peers.size)
    }

    @Test
    fun parse_carriesTheMeasurementFields() {
        val peer = parsePeerNodes(nodes, regions, now = now)
            .first { it.address == "tls://1.2.3.4:12345" }
        assertEquals(true, peer.up)
        assertEquals(42, peer.responseMs)
        assertEquals(now, peer.cachedAt)
    }

    @Test
    fun parse_convertsLastSeenFromSecondsToMillis() {
        val peer = parsePeerNodes(nodes, regions, now = now)
            .first { it.address == "tls://1.2.3.4:12345" }
        assertEquals(1712000000_000L, peer.lastSeen)
    }

    @Test
    fun parse_missingOptionalFieldsBecomeNullOrFalse() {
        val peer = parsePeerNodes(nodes, regions, now = now)
            .first { it.address.startsWith("quic://") }
        assertEquals(false, peer.up)
        assertNull(peer.responseMs)
        assertNull(peer.lastSeen)
    }

    @Test
    fun parse_staleUpdatedLeavesPeerUnmeasured() {
        val peer = parsePeerNodes(nodes, regions, now = now).first { it.address == "tls://9.9.9.9:1" }
        assertNull(peer.up)
        assertNull(peer.responseMs)
    }

    @Test
    fun parse_missingUpdatedLeavesPeerUnmeasured() {
        val peer = parsePeerNodes(nodes, regions, now = now).first { it.address == "tcp://5.6.7.8:9999" }
        assertNull(peer.up)
    }

    @Test
    fun parse_downPeerDropsResponseMs() {
        val peer = parsePeerNodes(nodes, regions, now = now).first { it.address == "tcp://[2a09:5302::2]:1" }
        assertEquals(false, peer.up)
        assertNull(peer.responseMs)
    }

    @Test
    fun parse_malformedJsonYieldsNoPeersInsteadOfThrowing() {
        assertEquals(emptyList<Any>(), parsePeerNodes("{ not json", regions))
        assertEquals(emptyList<Any>(), parsePeerNodes("", regions))
        assertEquals(emptyList<Any>(), parsePeerNodes("[1, 2, 3]", regions))
    }
}
