package club.taptappers.telly.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import club.taptappers.telly.data.model.TaleLog
import kotlinx.coroutines.flow.Flow

@Dao
interface TaleLogDao {
    @Query("SELECT * FROM tale_logs WHERE taleId = :taleId ORDER BY timestamp DESC")
    fun getLogsForTale(taleId: String): Flow<List<TaleLog>>

    @Query("SELECT * FROM tale_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<TaleLog>>

    @Insert
    suspend fun insertLog(log: TaleLog)

    @Query("DELETE FROM tale_logs WHERE taleId = :taleId")
    suspend fun deleteLogsForTale(taleId: String)

    @Query("DELETE FROM tale_logs WHERE timestamp < :before")
    suspend fun deleteOldLogs(before: Long)
}
