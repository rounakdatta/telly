package com.telly.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.telly.data.model.Tale
import kotlinx.coroutines.flow.Flow

@Dao
interface TaleDao {
    @Query("SELECT * FROM tales ORDER BY createdAt DESC")
    fun getAllTales(): Flow<List<Tale>>

    @Query("SELECT * FROM tales WHERE id = :id")
    suspend fun getTaleById(id: String): Tale?

    @Query("SELECT * FROM tales WHERE isEnabled = 1")
    suspend fun getEnabledTales(): List<Tale>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTale(tale: Tale)

    @Update
    suspend fun updateTale(tale: Tale)

    @Delete
    suspend fun deleteTale(tale: Tale)

    @Query("UPDATE tales SET lastRunAt = :timestamp WHERE id = :id")
    suspend fun updateLastRunAt(id: String, timestamp: Long)

    @Query("UPDATE tales SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
