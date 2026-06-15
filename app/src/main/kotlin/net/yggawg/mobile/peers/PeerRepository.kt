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
 *   2. Latest saved snapshot (peers_snap.json in filesDir)
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
        private const val SNAP_FILE = "peers_snap.json"
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

    // ponytail: cached in memory for the lifetime of this instance; region map is static
    private var regionMapCache: Map<String, String>? = null

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
            val regionMap = regionMapCache ?: buildRegionMap().also { regionMapCache = it }
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
            val snap = loadSnapshot()
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
    // Snapshot: single file in filesDir
    // -------------------------------------------------------------------------

    private fun saveSnapshot(peers: List<Peer>) {
        try {
            context.openFileOutput(SNAP_FILE, Context.MODE_PRIVATE).use { out ->
                out.write(json.encodeToString(peers).toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "Saved snapshot (${peers.size} peers)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save snapshot: $e")
        }
    }

    private fun loadSnapshot(): List<Peer>? = runCatching {
        context.openFileInput(SNAP_FILE).use { inp ->
            json.decodeFromString<List<Peer>>(inp.readBytes().toString(Charsets.UTF_8))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

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
            if (!filename.endsWith(".md")) continue
            val slug       = filename.removeSuffix(".md")
            val region     = regionMap.getOrDefault(filename, "other")
            val countryKey = "$region/$slug"

            val peersMap = peersEl.jsonObject
            for ((address, infoEl) in peersMap) {
                val addr = address.trim()
                val info = infoEl.jsonObject

                val up         = info["up"]?.jsonPrimitive?.booleanOrNull ?: false
                val responseMs = info["response_ms"]?.jsonPrimitive?.intOrNull
                val lastSeen   = info["last_seen"]?.jsonPrimitive?.longOrNull?.let { it * 1000L }

                peers += Peer(
                    address    = addr,
                    ip         = null,
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

    private fun fetchUrl(url: String): String {
        val req = Request.Builder().url(url).build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            resp.body?.string() ?: error("Empty body for $url")
        }
    }
}
