package net.yggawg.mobile.peers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.yggawg.mobile.R
import net.yggawg.mobile.peers.models.CountryInfo
import net.yggawg.mobile.peers.models.Peer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Port of fetch.py.
 *
 * publicnodes.json structure (critical — missed in original port):
 * ```json
 * {
 *   "russia.md": {
 *     "tls://1.2.3.4:12345": { "up": true, "response_ms": 42, "last_seen": 1712... },
 *     ...
 *   },
 *   "germany.md": { ... }
 * }
 * ```
 * Keys at the top level are *.md filenames, NOT peer addresses.
 *
 * Region mapping (from GitHub tree API):
 *   "russia.md"  → region "europe"  → country key "europe/russia"
 *   "germany.md" → region "europe"  → country key "europe/germany"
 *
 * Fallback chain on network failure:
 *   1. Network fetch (publicnodes.json + GitHub region map)
 *   2. Latest saved snapshot (up to 3 rolling slots in filesDir)
 *   3. Bundled res/raw/fallback_peers.json
 */
class PeerRepository(private val db: PeerDatabase, private val context: Context) {

    companion object {
        private const val TAG = "PeerRepository"
        private const val NODES_URL =
            "https://publicpeers.neilalexander.dev/publicnodes.json"
        private const val GITHUB_TREE_URL =
            "https://api.github.com/repos/yggdrasil-network/public-peers/git/trees/master?recursive=1"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour

        private const val SNAPSHOT_COUNT = 3
        private const val PREFS_SNAP_IDX = "peers_snap_idx"

        // Parses peer address: tls://host:port or tls://[ipv6]:port
        // Groups: (1) = bracketed IPv6, (2) = plain host, (3) = port
        private val HOST_RE = Pattern.compile(
            """(?:tcp|tls|quic|wss?)://(?:\[([^\]]+)]|([^/:?\s]+)):(\d+)"""
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Holowbark/1.0")
                .build()
            chain.proceed(req)
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    suspend fun getCountries(forceRefresh: Boolean = false): List<CountryInfo> {
        ensureCacheFresh(forceRefresh)
        return db.peerDao().getCountrySummaries().map {
            CountryInfo(it.country, it.totalPeers, it.upPeers)
        }
    }

    suspend fun getPeersForCountry(countryKey: String, forceRefresh: Boolean = false): List<Peer> {
        ensureCacheFresh(forceRefresh)
        return db.peerDao().getByCountry(countryKey)
    }

    suspend fun getUpPeers(countryKey: String): List<Peer> =
        db.peerDao().getByCountry(countryKey).filter { it.up }

    // -------------------------------------------------------------------------
    // Cache
    // -------------------------------------------------------------------------

    private suspend fun ensureCacheFresh(force: Boolean) {
        val latest = db.peerDao().getLatestCacheTime()
        val stale = latest == null || System.currentTimeMillis() - latest > CACHE_TTL_MS
        if (force || stale) fetchAndCache()
    }

    suspend fun fetchAndCache(): Int = withContext(Dispatchers.IO) {
        try {
            val regionMap = buildRegionMap()
            Log.d(TAG, "Region map: ${regionMap.size} files")
            val nodesText = fetchUrl(NODES_URL)
            val peers = parseNodes(nodesText, regionMap)
            Log.d(TAG, "Parsed ${peers.size} peers from ${peers.map { it.country }.toSet().size} countries")
            if (peers.isNotEmpty()) {
                db.peerDao().deleteAll()
                db.peerDao().insertAll(peers)
                saveSnapshot(peers)
            }
            peers.size
        } catch (e: Exception) {
            Log.w(TAG, "Network fetch failed, trying snapshot: $e")
            val snap = loadLatestSnapshot()
            if (snap != null) {
                Log.d(TAG, "Loaded snapshot: ${snap.size} peers")
                db.peerDao().deleteAll()
                db.peerDao().insertAll(snap)
                snap.size
            } else {
                Log.w(TAG, "No snapshot available, loading bundled fallback")
                val fallback = loadFallbackPeers()
                Log.d(TAG, "Bundled fallback: ${fallback.size} peers")
                db.peerDao().deleteAll()
                db.peerDao().insertAll(fallback)
                fallback.size
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot: 3-slot rolling storage in filesDir
    // -------------------------------------------------------------------------

    private fun saveSnapshot(peers: List<Peer>) {
        val prefs = context.getSharedPreferences("yggawg", Context.MODE_PRIVATE)
        val idx = prefs.getInt(PREFS_SNAP_IDX, 0)
        val slot = idx % SNAPSHOT_COUNT
        try {
            context.openFileOutput("peers_snap_$slot.json", Context.MODE_PRIVATE).use { out ->
                out.write(json.encodeToString(peers).toByteArray(Charsets.UTF_8))
            }
            prefs.edit().putInt(PREFS_SNAP_IDX, idx + 1).apply()
            Log.d(TAG, "Saved snapshot slot $slot (${peers.size} peers)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save snapshot: $e")
        }
    }

    private fun loadLatestSnapshot(): List<Peer>? {
        val prefs = context.getSharedPreferences("yggawg", Context.MODE_PRIVATE)
        val idx = prefs.getInt(PREFS_SNAP_IDX, 0)
        if (idx == 0) return null
        for (i in 0 until SNAPSHOT_COUNT) {
            val slot = ((idx - 1 - i) % SNAPSHOT_COUNT + SNAPSHOT_COUNT) % SNAPSHOT_COUNT
            runCatching {
                context.openFileInput("peers_snap_$slot.json").use { inp ->
                    json.decodeFromString<List<Peer>>(inp.readBytes().toString(Charsets.UTF_8))
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun loadFallbackPeers(): List<Peer> {
        val text = context.resources.openRawResource(R.raw.fallback_peers).use { inp ->
            inp.readBytes().toString(Charsets.UTF_8)
        }
        return parseNodes(text, emptyMap())
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /**
     * Build {filename → region} from GitHub tree.
     * Tree contains paths like "europe/russia.md" → filename="russia.md", region="europe".
     * Only direct children of a region directory (path.count('/') == 1) are included.
     */
    private suspend fun buildRegionMap(): Map<String, String> =
        withContext(Dispatchers.IO) {
            try {
                val text = fetchUrl(GITHUB_TREE_URL)
                val root = json.parseToJsonElement(text).jsonObject
                val tree = root["tree"]?.jsonArray ?: return@withContext emptyMap()

                buildMap {
                    for (item in tree) {
                        val path = item.jsonObject["path"]?.jsonPrimitive?.content ?: continue
                        // Only match "region/country.md" (exactly one slash, ends with .md)
                        if (!path.endsWith(".md") || path.count { it == '/' } != 1) continue
                        val region   = path.substringBefore('/')
                        val filename = path.substringAfter('/')
                        put(filename, region)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub tree fetch failed: $e")
                emptyMap()
            }
        }

    /**
     * Parse publicnodes.json.
     *
     * Top-level keys are *.md filenames, NOT peer addresses.
     * Values are maps of {peer_address → {up, response_ms, last_seen}}.
     */
    private fun parseNodes(nodesText: String, regionMap: Map<String, String>): List<Peer> {
        val root = try {
            json.parseToJsonElement(nodesText).jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse nodes JSON: $e")
            return emptyList()
        }

        val now  = System.currentTimeMillis()
        val peers = mutableListOf<Peer>()

        for ((filename, peersEl) in root) {
            // filename is like "russia.md"
            if (!filename.endsWith(".md")) continue
            val slug      = filename.removeSuffix(".md")            // "russia"
            val region    = regionMap.getOrDefault(filename, "other")
            val countryKey = "$region/$slug"                        // "europe/russia"

            val peersMap = peersEl.jsonObject
            for ((address, infoEl) in peersMap) {
                val addr = address.trim()
                val (host, port) = parseHostPort(addr) ?: continue
                val info = infoEl.jsonObject

                val up          = info["up"]?.jsonPrimitive?.booleanOrNull ?: false
                val responseMs  = info["response_ms"]?.jsonPrimitive?.intOrNull
                val lastSeen    = info["last_seen"]?.jsonPrimitive?.longOrNull?.let { it * 1000L }

                peers += Peer(
                    address    = addr,
                    host       = host,
                    port       = port,
                    ip         = null,   // DNS resolution deferred
                    country    = countryKey,
                    up         = up,
                    responseMs = responseMs,
                    lastSeen   = lastSeen,
                    cachedAt   = now,
                )
            }
        }
        return peers
    }

    /** Extract (host, port) from "tls://host:port" or "tls://[ipv6]:port". */
    private fun parseHostPort(address: String): Pair<String, String>? {
        val m = HOST_RE.matcher(address)
        if (!m.find()) return null
        val host = m.group(1) ?: m.group(2) ?: return null  // IPv6 or plain host
        val port = m.group(3) ?: return null
        return host to port
    }

    private fun fetchUrl(url: String): String {
        val req = Request.Builder().url(url).build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            resp.body?.string() ?: error("Empty body for $url")
        }
    }
}
