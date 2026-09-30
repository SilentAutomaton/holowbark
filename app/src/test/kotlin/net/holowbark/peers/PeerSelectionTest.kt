package net.holowbark.peers

import net.holowbark.peers.models.Peer
import net.holowbark.peers.models.countryKeyForIso
import net.holowbark.peers.models.peersToSelect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerSelectionTest {

    private fun peer(address: String, up: Boolean?) =
        Peer(address, ip = null, country = "europe/russia", up = up, responseMs = null, lastSeen = null, cachedAt = 0L)

    private val keys = listOf("europe/russia", "asia/hong-kong", "europe/turkey", "north-america/united-states")

    @Test
    fun peersToSelect_measuredList_takesOnlyUpPeers() {
        val peers = listOf(peer("tls://a:1", true), peer("tls://b:1", false))
        assertEquals(listOf("tls://a:1"), peersToSelect(peers, emptyMap()).map { it.address })
    }

    @Test
    fun peersToSelect_unmeasuredList_takesAll() {
        val peers = listOf(peer("tls://a:1", null), peer("tls://b:1", null))
        assertEquals(2, peersToSelect(peers, emptyMap()).size)
    }

    @Test
    fun peersToSelect_probeOverridesCrawler() {
        val peers = listOf(peer("tls://a:1", true), peer("tls://b:1", false))
        val probes = mapOf("tls://a:1" to -1, "tls://b:1" to 40)
        assertEquals(listOf("tls://b:1"), peersToSelect(peers, probes).map { it.address })
    }

    @Test
    fun countryKeyForIso_matchesSlugAcrossRegions() {
        assertEquals("north-america/united-states", countryKeyForIso("us", keys))
        assertEquals("europe/russia", countryKeyForIso("RU", keys))
    }

    @Test
    fun countryKeyForIso_usesOverrideForHongKong() {
        assertEquals("asia/hong-kong", countryKeyForIso("HK", keys))
    }

    @Test
    fun countryKeyForIso_unknownCountryGivesNull() {
        assertNull(countryKeyForIso("BR", keys))
    }
}
