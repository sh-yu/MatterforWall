package dev.wallpad.bridge.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "captured_packets")
data class CapturedPacketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampMicros: Long,
    val direction: String,
    val bytes: ByteArray,
    val syntheticTestData: Boolean,
)

@Dao
interface CapturedPacketDao {
    @Insert suspend fun insert(packet: CapturedPacketEntity)
    @Query("SELECT * FROM captured_packets WHERE sessionId = :sessionId ORDER BY timestampMicros")
    fun observeSession(sessionId: String): Flow<List<CapturedPacketEntity>>
    @Query("DELETE FROM captured_packets WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Database(entities = [CapturedPacketEntity::class], version = 1, exportSchema = true)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun packetDao(): CapturedPacketDao
}

