package com.example.speedmeter.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// Mirrors schema.sql. Room builds the real tables from these classes; the CHECK constraints in
// schema.sql are enforced in code (validatePlan, the billing states) until a migration adds them.

@Entity(tableName = "daily_usage")
data class DailyUsageEntity(
    @PrimaryKey val day: String,
    @ColumnInfo(name = "utc_offset_min") val utcOffsetMin: Int,
    @ColumnInfo(name = "mobile_rx") val mobileRx: Long,
    @ColumnInfo(name = "mobile_tx") val mobileTx: Long,
    @ColumnInfo(name = "wifi_rx") val wifiRx: Long,
    @ColumnInfo(name = "wifi_tx") val wifiTx: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "counter_checkpoint")
data class CounterCheckpointEntity(
    @PrimaryKey val counter: String,            // "total" or "mobile"
    @ColumnInfo(name = "last_rx") val lastRx: Long,
    @ColumnInfo(name = "last_tx") val lastTx: Long,
    @ColumnInfo(name = "boot_id") val bootId: String,
    @ColumnInfo(name = "read_at") val readAt: Long,
)

/** One row, id is always 1. capBytes null means no cap. counts: "mobile" or "mobile_wifi". */
@Entity(tableName = "data_plan")
data class DataPlanEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "cycle_day") val cycleDay: Int,
    @ColumnInfo(name = "cap_bytes") val capBytes: Long?,
    val counts: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Which cap alerts already fired this cycle, so each fires once. threshold is 80 or 100. */
@Entity(tableName = "alert_log", primaryKeys = ["cycle_start", "threshold"])
data class AlertLogEntity(
    @ColumnInfo(name = "cycle_start") val cycleStart: String,
    val threshold: Int,
    @ColumnInfo(name = "fired_at") val firedAt: Long,
)

/** Cache only. Google Play is the source of truth. state: none, pending or purchased. */
@Entity(tableName = "entitlement_cache")
data class EntitlementEntity(
    @PrimaryKey @ColumnInfo(name = "product_id") val productId: String,
    val state: String,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long,
)

data class CycleTotals(val mobile: Long, val wifi: Long)

@Dao
interface UsageDao {
    @Query("SELECT * FROM counter_checkpoint WHERE counter = :counter")
    suspend fun checkpoint(counter: String): CounterCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: CounterCheckpointEntity)

    @Query(
        "INSERT OR IGNORE INTO daily_usage " +
            "(day, utc_offset_min, mobile_rx, mobile_tx, wifi_rx, wifi_tx, created_at, updated_at) " +
            "VALUES (:day, :offset, 0, 0, 0, 0, :now, :now)"
    )
    suspend fun ensureDay(day: String, offset: Int, now: Long)

    @Query(
        "UPDATE daily_usage SET mobile_rx = mobile_rx + :mrx, mobile_tx = mobile_tx + :mtx, " +
            "wifi_rx = wifi_rx + :wrx, wifi_tx = wifi_tx + :wtx, updated_at = :now WHERE day = :day"
    )
    suspend fun addToDay(day: String, mrx: Long, mtx: Long, wrx: Long, wtx: Long, now: Long)

    @Query("SELECT * FROM daily_usage WHERE day = :day")
    suspend fun getDay(day: String): DailyUsageEntity?

    @Query("SELECT * FROM daily_usage WHERE day = :day")
    fun observeDay(day: String): Flow<DailyUsageEntity?>

    @Query("SELECT * FROM daily_usage ORDER BY day DESC LIMIT :count")
    fun observeRecent(count: Int): Flow<List<DailyUsageEntity>>

    /** Totals for a cycle: days from [start] (inclusive) to [end] (exclusive), as yyyy-MM-dd strings. */
    @Query(
        "SELECT COALESCE(SUM(mobile_rx + mobile_tx), 0) AS mobile, " +
            "COALESCE(SUM(wifi_rx + wifi_tx), 0) AS wifi " +
            "FROM daily_usage WHERE day >= :start AND day < :end"
    )
    suspend fun cycleTotals(start: String, end: String): CycleTotals

    @Query("DELETE FROM daily_usage WHERE day < :cutoff")
    suspend fun pruneBefore(cutoff: String): Int
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM data_plan WHERE id = 1")
    suspend fun getPlan(): DataPlanEntity?

    @Query("SELECT * FROM data_plan WHERE id = 1")
    fun observePlan(): Flow<DataPlanEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(plan: DataPlanEntity)

    /** Returns the new row id, or -1 if this alert was already logged for the cycle. Fire only on != -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markFired(entry: AlertLogEntity): Long

    @Query("DELETE FROM alert_log WHERE cycle_start < :cutoff")
    suspend fun pruneAlerts(cutoff: String): Int
}

@Dao
interface EntitlementDao {
    @Query("SELECT * FROM entitlement_cache WHERE product_id = :productId")
    suspend fun get(productId: String): EntitlementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: EntitlementEntity)
}

/** Version 1 to 2: data plan, cap alert log and entitlement cache. Column definitions must match the entities exactly. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS data_plan (id INTEGER NOT NULL, cycle_day INTEGER NOT NULL, " +
                "cap_bytes INTEGER, counts TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS alert_log (cycle_start TEXT NOT NULL, threshold INTEGER NOT NULL, " +
                "fired_at INTEGER NOT NULL, PRIMARY KEY(cycle_start, threshold))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS entitlement_cache (product_id TEXT NOT NULL, state TEXT NOT NULL, " +
                "verified_at INTEGER NOT NULL, PRIMARY KEY(product_id))"
        )
    }
}

@Database(
    entities = [
        DailyUsageEntity::class, CounterCheckpointEntity::class,
        DataPlanEntity::class, AlertLogEntity::class, EntitlementEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun planDao(): PlanDao
    abstract fun entitlementDao(): EntitlementDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "speedmeter.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
