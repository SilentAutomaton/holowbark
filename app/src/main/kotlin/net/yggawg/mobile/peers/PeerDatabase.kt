package net.yggawg.mobile.peers

import androidx.room.*
import net.yggawg.mobile.peers.models.Peer

@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(peers: List<Peer>)

    @Query("SELECT * FROM peers WHERE country = :countryKey ORDER BY responseMs ASC")
    suspend fun getByCountry(countryKey: String): List<Peer>

    @Query("""
        SELECT country,
               COUNT(*) AS totalPeers,
               SUM(CASE WHEN up = 1 THEN 1 ELSE 0 END) AS upPeers
        FROM peers
        GROUP BY country
        ORDER BY country ASC
    """)
    suspend fun getCountrySummaries(): List<CountrySummaryRow>

    @Query("SELECT * FROM peers ORDER BY country, responseMs ASC")
    suspend fun getAll(): List<Peer>

    @Query("DELETE FROM peers")
    suspend fun deleteAll()

    /** Return the most recent cachedAt timestamp, or null if the table is empty. */
    @Query("SELECT MAX(cachedAt) FROM peers")
    suspend fun getLatestCacheTime(): Long?
}

/** Raw result of the country summary query. */
data class CountrySummaryRow(
    val country: String,
    val totalPeers: Int,
    val upPeers: Int,
)

@Database(entities = [Peer::class], version = 1, exportSchema = false)
abstract class PeerDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao

    companion object {
        @Volatile private var INSTANCE: PeerDatabase? = null

        fun getInstance(context: android.content.Context): PeerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PeerDatabase::class.java,
                    "peers.db"
                ).build().also { INSTANCE = it }
            }
    }
}
