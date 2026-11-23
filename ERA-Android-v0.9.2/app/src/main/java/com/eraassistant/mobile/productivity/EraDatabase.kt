package com.eraassistant.mobile.productivity

import android.content.Context
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

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val encryptedTitle: String,
    val encryptedNote: String,
    val triggerAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val enabled: Boolean = true,
    val triggeredAtEpochMs: Long? = null,
    val calendarEventId: Long? = null,
    val calendarSyncedAtEpochMs: Long? = null
)

@Entity(tableName = "managed_memory")
data class ManagedMemoryEntity(
    @PrimaryKey val id: String,
    val category: String,
    val encryptedTitle: String,
    val encryptedContent: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "client_crm_records")
data class ClientCrmEntity(
    @PrimaryKey val id: String,
    val encryptedClientLabel: String,
    val encryptedProjectTitle: String,
    val encryptedProjectSummary: String,
    val encryptedSourceUrl: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val followUpAtEpochMs: Long?
)

@Dao
interface ClientCrmDao {
    @Query("SELECT * FROM client_crm_records ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ClientCrmEntity>>
    @Query("SELECT * FROM client_crm_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ClientCrmEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ClientCrmEntity)
    @Query("UPDATE client_crm_records SET status = :status, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long): Int
    @Query("DELETE FROM client_crm_records WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerAtEpochMs ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE enabled = 1 AND triggeredAtEpochMs IS NULL ORDER BY triggerAtEpochMs ASC")
    suspend fun getPending(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE reminders SET triggeredAtEpochMs = :nowEpochMs WHERE id = :id AND enabled = 1 AND triggeredAtEpochMs IS NULL AND triggerAtEpochMs <= :nowEpochMs")
    suspend fun claimIfDue(id: String, nowEpochMs: Long): Int

    @Query("UPDATE reminders SET calendarEventId = :eventId, calendarSyncedAtEpochMs = :syncedAtEpochMs WHERE id = :id")
    suspend fun markCalendarSynced(id: String, eventId: Long, syncedAtEpochMs: Long)

    @Query("DELETE FROM reminders WHERE triggeredAtEpochMs IS NOT NULL AND triggeredAtEpochMs < :beforeEpochMs")
    suspend fun pruneTriggered(beforeEpochMs: Long): Int
}

@Dao
interface ManagedMemoryDao {
    @Query("SELECT * FROM managed_memory ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ManagedMemoryEntity>>

    @Query("SELECT * FROM managed_memory WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ManagedMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ManagedMemoryEntity)

    @Query("DELETE FROM managed_memory WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM managed_memory")
    suspend fun deleteAll(): Int
}

@Database(
    entities = [ReminderEntity::class, ManagedMemoryEntity::class, ClientCrmEntity::class],
    version = 2,
    exportSchema = false
)
abstract class EraDatabase : RoomDatabase() {
    abstract fun reminders(): ReminderDao
    abstract fun managedMemory(): ManagedMemoryDao
    abstract fun clientCrm(): ClientCrmDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS client_crm_records (id TEXT NOT NULL, encryptedClientLabel TEXT NOT NULL, encryptedProjectTitle TEXT NOT NULL, encryptedProjectSummary TEXT NOT NULL, encryptedSourceUrl TEXT NOT NULL, status TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, followUpAtEpochMs INTEGER, PRIMARY KEY(id))")
            }
        }
        @Volatile private var instance: EraDatabase? = null

        fun get(context: Context): EraDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EraDatabase::class.java,
                "era_productivity_secure.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
