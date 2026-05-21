package com.focusguard.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────
// ENTITIES
// ─────────────────────────────────────────

/** One row per blocked app-feature combination */
@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,        // e.g. "com.instagram.android"
    val featureKey: String,         // e.g. "reels", "explore", "shorts", "fyp", "keyword"
    val featureLabel: String,       // Human-readable: "Reels"
    val pattern: String? = null,    // For keywords or websites: "facebook.com", "porn"
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** Schedule: a named time window that activates a set of rules */
@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val startHour: Int,   // 0-23
    val startMin: Int,
    val endHour: Int,
    val endMin: Int,
    val daysOfWeek: String,  // comma-separated: "1,2,3,4,5" (Mon-Fri)
    val isEnabled: Boolean = true,
    val wifiSsid: String? = null,    // optional WiFi trigger
    val locationLabel: String? = null
)

/** Aggregate daily usage per package */
@Entity(tableName = "usage_logs", indices = [Index(value = ["packageName", "date"])])
data class UsageLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val date: String,            // "2024-03-15"
    val totalMinutes: Int = 0,
    val blockedAttempts: Int = 0
)

// ─────────────────────────────────────────
// DAOs
// ─────────────────────────────────────────

@Dao
interface BlockRuleDao {
    @Query("SELECT * FROM block_rules ORDER BY packageName, featureKey")
    fun observeAll(): Flow<List<BlockRule>>

    @Query("SELECT * FROM block_rules WHERE packageName = :pkg AND isEnabled = 1")
    suspend fun getEnabledForPackage(pkg: String): List<BlockRule>

    @Query("SELECT * FROM block_rules WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<BlockRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BlockRule)

    @Query("UPDATE block_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)

    @Delete
    suspend fun delete(rule: BlockRule)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules")
    fun observeAll(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE isEnabled = 1")
    suspend fun getEnabled(): List<Schedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: Schedule)

    @Query("UPDATE schedules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)

    @Delete
    suspend fun delete(s: Schedule)
}

@Dao
interface UsageLogDao {
    @Query("SELECT * FROM usage_logs WHERE date = :date ORDER BY totalMinutes DESC")
    fun observeForDate(date: String): Flow<List<UsageLog>>

    @Query("SELECT SUM(totalMinutes) FROM usage_logs WHERE date = :date")
    suspend fun totalMinutesForDate(date: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: UsageLog)

    @Query("""
        UPDATE usage_logs SET totalMinutes = totalMinutes + :delta
        WHERE packageName = :pkg AND date = :date
    """)
    suspend fun incrementMinutes(pkg: String, date: String, delta: Int)

    @Query("""
        UPDATE usage_logs SET blockedAttempts = blockedAttempts + 1
        WHERE packageName = :pkg AND date = :date
    """)
    suspend fun incrementBlocked(pkg: String, date: String)
}

// ─────────────────────────────────────────
// DATABASE
// ─────────────────────────────────────────

@Database(
    entities = [BlockRule::class, Schedule::class, UsageLog::class],
    version = 2,
    exportSchema = false
)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun blockRuleDao(): BlockRuleDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun usageLogDao(): UsageLogDao

    companion object {
        @Volatile private var INSTANCE: FocusDatabase? = null

        fun get(context: Context): FocusDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(
                context.applicationContext,
                FocusDatabase::class.java,
                "focusguard.db"
            ).fallbackToDestructiveMigration()
             .build().also { INSTANCE = it }
        }
    }
}
