package net.holowbark.peers.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A single Yggdrasil public peer, stored in Room.
 *
 * [address] is the full peer URI, e.g. `tls://1.2.3.4:12345`.
 * [country] is the region/slug key, e.g. `europe/russia`.
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
    val up: Boolean,
    val responseMs: Int?,    // last measured latency, null = unknown
    val lastSeen: Long?,     // epoch ms from publicnodes.json
    val cachedAt: Long,      // System.currentTimeMillis() when stored
)

data class CountryInfo(
    val countryKey: String,  // e.g. "europe/russia"
    val totalPeers: Int,
    val upPeers: Int,
) {
    val regionSlug: String get() = countryKey.substringBefore('/')
    val displayName: String get() = countryDisplayName(countryKey)
}

/** "europe/united-kingdom" → "United kingdom". */
fun countryDisplayName(countryKey: String): String = countryKey
    .substringAfter('/')
    .replace('-', ' ')
    .replaceFirstChar { it.uppercaseChar() }
