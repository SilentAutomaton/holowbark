package net.holowbark.peers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.holowbark.R
import net.holowbark.peers.models.CountryInfo
import net.holowbark.peers.models.Peer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import net.holowbark.AppLogger

/**
 * The list of Yggdrasil public peers, cached in Room.
 *
 * publicnodes.json is keyed by the *.md filename of the country page, not by peer
 * address — that is the shape the parser has to handle:
 * ```json
 * {
 *   "russia.md": {
 *     "tls://1.2.3.4:12345": { "up": true, "response_ms": 42, "last_seen": 1712000000 }
 *   },
 *   "germany.md": { }
 * }
 * ```
 * The region each file belongs to comes from a separate GitHub tree listing, and
 * the two are joined into a country key such as "europe/russia".
 *
 * On network failure the last saved snapshot is used, and failing that the peer
 * list bundled with the APK, so a first run with no connectivity still offers
 * something to connect through.
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

    // The region layout changes about once a year, so one fetch per process is plenty.
    private var regionMapCache: Map<String, String>? = null

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

    private suspend fun ensureCacheFresh(force: Boolean) {
        val latest = db.peerDao().getLatestCacheTime()
        val stale = latest == null || System.currentTimeMillis() - latest > CACHE_TTL_MS
        if (force || stale) fetchAndCache()
    }

    suspend fun fetchAndCache(): Int = withContext(Dispatchers.IO) {
        try {
            val regionMap = regionMapCache ?: buildRegionMap().also { regionMapCache = it }
            AppLogger.d(TAG, "Region map: ${regionMap.size} files")
            val nodesText = fetchUrl(NODES_URL)
            val peers = parsePeerNodes(nodesText, regionMap)
            AppLogger.d(TAG, "Parsed ${peers.size} peers from ${peers.map { it.country }.toSet().size} countries")
            if (peers.isNotEmpty()) {
                db.peerDao().deleteAll()
                db.peerDao().insertAll(peers)
                saveSnapshot(peers)
            }
            peers.size
        } catch (e: Exception) {
            AppLogger.w(TAG, "Network fetch failed, trying snapshot: $e")
            val snap = loadSnapshot()
            if (snap != null) {
                AppLogger.d(TAG, "Loaded snapshot: ${snap.size} peers")
                db.peerDao().deleteAll()
                db.peerDao().insertAll(snap)
                snap.size
            } else {
                AppLogger.w(TAG, "No snapshot available, loading bundled fallback")
                val fallback = loadFallbackPeers()
                AppLogger.d(TAG, "Bundled fallback: ${fallback.size} peers")
                db.peerDao().deleteAll()
                db.peerDao().insertAll(fallback)
                fallback.size
            }
        }
    }

    private fun saveSnapshot(peers: List<Peer>) {
        try {
            context.openFileOutput(SNAP_FILE, Context.MODE_PRIVATE).use { out ->
                out.write(json.encodeToString(peers).toByteArray(Charsets.UTF_8))
            }
            AppLogger.d(TAG, "Saved snapshot (${peers.size} peers)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to save snapshot: $e")
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
        return parsePeerNodes(text, emptyMap())
    }

    /**
     * Maps each country page filename to its region, from tree paths like
     * "europe/russia.md". Only direct children of a region directory count.
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
                AppLogger.w(TAG, "GitHub tree fetch failed: $e")
                emptyMap()
            }
        }

    private fun fetchUrl(url: String): String {
        val req = Request.Builder().url(url).build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            resp.body?.string() ?: error("Empty body for $url")
        }
    }
}
