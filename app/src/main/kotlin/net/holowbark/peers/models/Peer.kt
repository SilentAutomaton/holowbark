package net.holowbark.peers.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * A single Yggdrasil public peer, stored in Room.
 *
 * [address] is the full peer URI, e.g. `tls://1.2.3.4:12345`.
 * [country] is the region/slug key, e.g. `europe/russia`.
 * [up] is null when the peer was not measured recently: the list came from the
 * snapshot or the APK, or the crawler has not looked at it for a day.
 */
@Serializable
@Entity(
    tableName = "peers",
    indices = [Index("country"), Index("address", unique = true)]
)
data class Peer(
    @PrimaryKey val address: String,
    val ip: String?,         // resolved IPv4/IPv6 address, null if unresolved
    val country: String,     // e.g. "europe/russia"
    val up: Boolean?,
    val responseMs: Int?,    // last measured latency, null = unknown
    val lastSeen: Long?,     // epoch ms from publicnodes.json
    val cachedAt: Long,      // System.currentTimeMillis() when stored
)

data class CountryInfo(
    val countryKey: String,  // e.g. "europe/russia"
    val totalPeers: Int,
    val upPeers: Int?,       // null when no peer in the country is measured
) {
    val regionSlug: String get() = countryKey.substringBefore('/')
    val displayName: String get() = countryDisplayName(countryKey)
}

/** "europe/united-kingdom" → "United kingdom". */
fun countryDisplayName(countryKey: String): String = countryKey
    .substringAfter('/')
    .replace('-', ' ')
    .replaceFirstChar { it.uppercaseChar() }

/**
 * The peers "add all" and first-run seeding take. A check from this device
 * outranks the crawler, which sees the peer from its own network, not ours.
 * An unmeasured list gives no reason to leave any peer out.
 */
fun peersToSelect(peers: List<Peer>, probes: Map<String, Int>): List<Peer> {
    val measured = peers.any { it.up != null }
    return peers.filter { peer ->
        probes[peer.address]?.let { it >= 0 } ?: (!measured || peer.up == true)
    }
}

// ponytail: only the names known to differ between CLDR and public-peers; a new
// country page with its own spelling needs a line here.
private val ISO_SLUG_OVERRIDES = mapOf(
    "HK" to "hong-kong",
    "TR" to "turkey",
)

/** The country key whose page matches an ISO 3166 code, in any region. */
fun countryKeyForIso(iso: String, keys: List<String>): String? {
    val code = iso.uppercase()
    val slug = ISO_SLUG_OVERRIDES[code]
        ?: Locale("", code).getDisplayCountry(Locale.ENGLISH).lowercase().replace(' ', '-')
    return keys.firstOrNull { it.substringAfter('/') == slug }
}
