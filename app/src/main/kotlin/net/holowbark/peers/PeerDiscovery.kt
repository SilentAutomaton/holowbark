package net.holowbark.peers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.holowbark.peers.models.Peer
import net.holowbark.vpn.parsePeerUri

/** The peers a search starts the tunnel with: enough to connect now. */
const val DISCOVERY_WANT = 3
/** The peers the search tops the tunnel up to once it is running. */
const val DISCOVERY_MAX_PEERS = 20
private const val DISCOVERY_PARALLELISM = 32

/** A peer that answers a TCP connect, which is all a search can check. */
internal fun isProbeable(address: String): Boolean =
    parsePeerUri(address).getOrNull()?.scheme in TCP_SCHEMES

/**
 * The peers worth a connect, best first: the phone's own country, then the peers
 * [previous] searches kept, then the ones the crawler saw up, then the fastest.
 * One list, so a nearby peer is never behind a far one that happened to work
 * last time, and a search that stops at the first answers lands on the nearest.
 */
fun discoveryCandidates(all: List<Peer>, homeCountry: String?, previous: Set<String>): List<String> =
    all.filter { isProbeable(it.address) }
        .sortedWith(
            compareBy<Peer>(
                { it.country != homeCountry },
                { it.address !in previous },
                { it.up != true },
                { it.responseMs ?: Int.MAX_VALUE },
            )
        )
        .map { it.address }

/**
 * The candidates that answer, in the order they answer, one per host so that
 * losing a host loses one peer. Hosts in [skipHosts] are not probed again.
 * [probe] is the time to connect in ms, negative or null for no answer.
 *
 * Probes start in the order of [candidates], so the best ones are tried first.
 * The caller decides when it has enough by taking from the flow.
 */
fun answeringPeers(
    candidates: List<String>,
    skipHosts: Set<String> = emptySet(),
    probe: suspend (String) -> Int?,
): Flow<String> = flow {
    // Detached on purpose: a blocking connect cannot be cancelled, and a caller
    // that has enough must get its answer now, not when the slow ones time out.
    val scope = CoroutineScope(SupervisorJob())
    try {
        val hits = Channel<String>(Channel.UNLIMITED)
        val gate = Semaphore(DISCOVERY_PARALLELISM)
        scope.launch {
            candidates.filter { hostOf(it) !in skipHosts }.map { address ->
                launch {
                    gate.withPermit {
                        if ((probe(address) ?: -1) >= 0) hits.send(address)
                    }
                }
            }.joinAll()
            hits.close()
        }
        val hosts = skipHosts.toMutableSet()
        for (address in hits) {
            if (hosts.add(hostOf(address))) emit(address)
        }
    } finally {
        scope.cancel()
    }
}

fun hostOf(address: String): String =
    parsePeerUri(address).getOrNull()?.host ?: address
