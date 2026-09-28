package dev.handoff.app.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trusted_peers")
data class TrustedPeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    /** X.509 P-256 identity public key. Public material only; no secrets are stored here. */
    val publicKey: ByteArray,
    val pairedAtMs: Long,
) {
    override fun equals(other: Any?) = other is TrustedPeerEntity && other.peerId == peerId &&
        other.displayName == displayName && other.publicKey.contentEquals(publicKey) && other.pairedAtMs == pairedAtMs

    override fun hashCode() = peerId.hashCode()
}

@Entity(tableName = "logical_devices")
data class LogicalDeviceEntity(
    @PrimaryKey val logicalId: String,
    val displayName: String,
    val deviceType: String,
    val fingerprint: String?,
    /** This host's bonded-device address. Stays on this device. */
    val localAddress: String?,
    val multipoint: Boolean,
    val lastKnownOwner: String?,
    val ownershipGeneration: Long,
    /** JSON array of {hostId, alias}. */
    val hostMappingsJson: String,
)

@Entity(tableName = "transfer_records")
data class TransferRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logicalId: String,
    val deviceName: String,
    val trigger: String,
    val startedAtMs: Long,
    val outcome: String,
    val path: String?,
    val failure: String?,
    val detail: String?,
    val previousOwner: String?,
    val strategy: String?,
    val attempts: Int,
    val releaseMs: Long?,
    val connectMs: Long?,
    val totalMs: Long,
)

@Dao
interface TrustedPeerDao {
    @Query("SELECT * FROM trusted_peers ORDER BY displayName")
    fun observeAll(): Flow<List<TrustedPeerEntity>>

    @Upsert
    suspend fun upsert(entity: TrustedPeerEntity)

    @Query("DELETE FROM trusted_peers WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}

@Dao
interface LogicalDeviceDao {
    @Query("SELECT * FROM logical_devices ORDER BY displayName")
    fun observeAll(): Flow<List<LogicalDeviceEntity>>

    @Query("SELECT * FROM logical_devices WHERE logicalId = :logicalId")
    suspend fun get(logicalId: String): LogicalDeviceEntity?

    @Upsert
    suspend fun upsert(entity: LogicalDeviceEntity)

    @Query("DELETE FROM logical_devices WHERE logicalId = :logicalId")
    suspend fun delete(logicalId: String)

    @Transaction
    suspend fun rekey(from: String, to: String) {
        val existing = get(from) ?: return
        delete(from)
        delete(to)
        upsert(existing.copy(logicalId = to))
    }

    @Transaction
    suspend fun applyOwnership(logicalId: String, owner: String?, generation: Long): Boolean {
        val existing = get(logicalId) ?: return false
        if (generation < existing.ownershipGeneration) return false
        if (existing.lastKnownOwner == owner && existing.ownershipGeneration == generation) return false
        upsert(existing.copy(lastKnownOwner = owner, ownershipGeneration = generation))
        return true
    }
}

@Dao
interface TransferRecordDao {
    @Query("SELECT * FROM transfer_records ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferRecordEntity>>

    @androidx.room.Insert
    suspend fun insert(entity: TransferRecordEntity)

    @Query("DELETE FROM transfer_records WHERE id NOT IN (SELECT id FROM transfer_records ORDER BY startedAtMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(
    entities = [TrustedPeerEntity::class, LogicalDeviceEntity::class, TransferRecordEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HandoffDatabase : RoomDatabase() {
    abstract fun trustedPeers(): TrustedPeerDao
    abstract fun logicalDevices(): LogicalDeviceDao
    abstract fun transferRecords(): TransferRecordDao

    companion object {
        fun build(context: Context): HandoffDatabase =
            Room.databaseBuilder(context, HandoffDatabase::class.java, "handoff.db").build()
    }
}
