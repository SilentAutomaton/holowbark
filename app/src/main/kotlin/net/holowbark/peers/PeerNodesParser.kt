package net.holowbark.peers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.holowbark.AppLogger
import net.holowbark.peers.models.Peer

private const val TAG = "PeerNodesParser"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Read publicnodes.json into peers.
 *
 * The document is keyed by the country page filename, and each value maps a peer
 * URI to its last measurement:
 * ```json
 * { "russia.md": { "tls://1.2.3.4:12345": { "up": true, "response_ms": 42 } } }
 * ```
 * [regionMap] supplies the region each filename sits under, which is joined with
 * the filename into a country key like "europe/russia". A filename the map does
 * not know lands under "other".
 *
 * Returns an empty list rather than throwing: a malformed document should fall
 * through to the cached snapshot, not crash the peer browser.
 */
fun parsePeerNodes(
    nodesText: String,
    regionMap: Map<String, String>,
    now: Long = System.currentTimeMillis(),
): List<Peer> {
    val root = try {
        json.parseToJsonElement(nodesText).jsonObject
    } catch (e: Exception) {
        AppLogger.e(TAG, "Failed to parse nodes JSON: $e")
        return emptyList()
    }

    return root.entries
        .filter { it.key.endsWith(".md") }
        .flatMap { (filename, peersForFile) ->
            val country = "${regionMap[filename] ?: "other"}/${filename.removeSuffix(".md")}"
            peersForFile.jsonObject.map { (uri, measurement) ->
                val info = measurement.jsonObject
                Peer(
                    address = uri.trim(),
                    ip = null,
                    country = country,
                    up = info["up"]?.jsonPrimitive?.booleanOrNull ?: false,
                    responseMs = info["response_ms"]?.jsonPrimitive?.intOrNull,
                    // publicnodes.json reports seconds; everything else here is millis.
                    lastSeen = info["last_seen"]?.jsonPrimitive?.longOrNull?.times(1000L),
                    cachedAt = now,
                )
            }
        }
}
