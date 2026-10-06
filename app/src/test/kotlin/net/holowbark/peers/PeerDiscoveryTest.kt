package net.holowbark.peers

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.holowbark.peers.models.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PeerDiscoveryTest {

    private fun peer(address: String, country: String = "europe/germany", up: Boolean? = null, ms: Int? = null) =
        Peer(address, ip = null, country = country, up = up, responseMs = ms, lastSeen = null, cachedAt = 0L)

    private fun answering(vararg answers: Pair<String, Int>): suspend (String) -> Int? {
        val table = answers.toMap()
        return { table[it] }
    }

    private fun hostsOf(addresses: List<String>) = addresses.map(::hostOf).toSet()

    @Test
    fun discoveryCandidates_homeCountryThenUpThenFastest() {
        val all = listOf(
            peer("tls://slow:1", "europe/germany", up = true, ms = 90),
            peer("tls://fast:1", "europe/germany", up = true, ms = 10),
            peer("tls://down:1", "europe/germany", up = false),
            peer("tls://home:1", "europe/russia", up = false),
        )
        val order = discoveryCandidates(all, "europe/russia", emptySet())
        assertEquals(listOf("tls://home:1", "tls://fast:1", "tls://slow:1", "tls://down:1"), order)
    }

    @Test
    fun discoveryCandidates_homeCountryPeerBeatsAForeignPeerKeptFromBefore() {
        val all = listOf(
            peer("tls://kept-abroad:1", "europe/germany", up = true, ms = 5),
            peer("tls://at-home:1", "europe/russia", up = false),
        )
        val order = discoveryCandidates(all, "europe/russia", previous = setOf("tls://kept-abroad:1"))
        assertEquals("tls://at-home:1", order.first())
    }

    @Test
    fun discoveryCandidates_insideACountryTheKeptPeersComeFirst() {
        val all = listOf(
            peer("tls://fresh:1", "europe/russia", up = true, ms = 5),
            peer("tls://kept:1", "europe/russia", up = false),
        )
        val order = discoveryCandidates(all, "europe/russia", previous = setOf("tls://kept:1"))
        assertEquals(listOf("tls://kept:1", "tls://fresh:1"), order)
    }

    @Test
    fun discoveryCandidates_dropsQuicAndSocks() {
        val all = listOf(
            peer("quic://a:1"), peer("socks://127.0.0.1:1080/b:2"), peer("tcp://c:1"), peer("ws://d:1"),
        )
        assertEquals(setOf("tcp://c:1", "ws://d:1"), discoveryCandidates(all, null, emptySet()).toSet())
    }

    @Test
    fun answeringPeers_someAnswer_emitsOnlyThem() = runBlocking {
        val found = answeringPeers(
            listOf("tls://a:1", "tls://b:1", "tls://c:1", "tls://d:1"),
            probe = answering("tls://b:1" to 80, "tls://d:1" to 20),
        ).toList()
        assertEquals(setOf("tls://b:1", "tls://d:1"), found.toSet())
    }

    @Test
    fun answeringPeers_nobodyAnswers_emitsNothing() = runBlocking {
        val found = answeringPeers(listOf("tls://a:1", "tls://b:1"), probe = answering()).toList()
        assertTrue(found.isEmpty())
    }

    @Test
    fun answeringPeers_negativeTime_isNoAnswer() = runBlocking {
        assertTrue(answeringPeers(listOf("tls://a:1"), probe = { -1 }).toList().isEmpty())
    }

    @Test
    fun answeringPeers_takeStopsTheSearchAtTheCount() = runBlocking {
        val candidates = (1..20).map { "tls://h$it:1" }
        val found = answeringPeers(candidates, probe = { 5 }).take(3).toList()
        assertEquals(3, found.size)
    }

    @Test
    fun answeringPeers_oneHostOnSeveralPorts_countsOnce() = runBlocking {
        val found = answeringPeers(
            listOf("tls://h:1", "tcp://h:2", "ws://h:3", "tls://i:1"),
            probe = { 5 },
        ).toList()
        assertEquals(setOf("h", "i"), hostsOf(found))
        assertEquals(2, found.size)
    }

    @Test
    fun answeringPeers_hostsAlreadyDialed_areNotProbedOrEmitted() = runBlocking {
        val probed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val found = answeringPeers(
            listOf("tls://dialed:1", "tcp://dialed:2", "tls://new:1"),
            skipHosts = setOf("dialed"),
            probe = { probed += it; 5 },
        ).toList()
        assertEquals(listOf("tls://new:1"), found)
        assertEquals(listOf("tls://new:1"), probed.toList())
    }

    // The bundled list has no notion of a whitelist. When only some hosts answer,
    // as under one, the search must land on exactly those, with nothing named in code.
    @Test
    fun answeringPeers_bundledListWhereOnlySomeHostsAnswer_findsOnlyThose() = runBlocking {
        val text = File("src/main/res/raw/fallback_peers.json").readText()
        val all = parsePeerNodes(text, emptyMap())
        val reachable = setOf(
            "des.8px.sk", "ygg-ke.8px.sk", "ip4.fvm.mywire.org",
            "ygg2.mk16.de", "88.210.10.78", "vpn.itrus.su", "kursk.cleverfox.org",
        )
        val candidates = discoveryCandidates(all, "europe/russia", emptySet())
        assertTrue(candidates.size > 100)

        val found = answeringPeers(candidates) { address ->
            if (hostOf(address) in reachable) 30 else null
        }.toList()

        assertTrue(found.isNotEmpty())
        assertTrue(hostsOf(found).all { it in reachable })
        assertEquals(found.size, hostsOf(found).size)
    }
}
