package com.telly.data.repository

import com.telly.data.db.TaleDao
import com.telly.data.db.TaleLogDao
import com.telly.data.model.Tale
import com.telly.data.model.TaleLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaleRepository @Inject constructor(
    private val taleDao: TaleDao,
    private val taleLogDao: TaleLogDao
) {
    fun getAllTales(): Flow<List<Tale>> = taleDao.getAllTales()

    suspend fun getTaleById(id: String): Tale? = taleDao.getTaleById(id)

    suspend fun getEnabledTales(): List<Tale> = taleDao.getEnabledTales()

    suspend fun insertTale(tale: Tale) = taleDao.insertTale(tale)

    suspend fun updateTale(tale: Tale) = taleDao.updateTale(tale)

    suspend fun deleteTale(tale: Tale) = taleDao.deleteTale(tale)

    suspend fun setTaleEnabled(id: String, enabled: Boolean) = taleDao.setEnabled(id, enabled)

    suspend fun updateLastRunAt(id: String, timestamp: Long) = taleDao.updateLastRunAt(id, timestamp)

    fun getLogsForTale(taleId: String): Flow<List<TaleLog>> = taleLogDao.getLogsForTale(taleId)

    fun getRecentLogs(limit: Int = 100): Flow<List<TaleLog>> = taleLogDao.getRecentLogs(limit)

    suspend fun insertLog(log: TaleLog) = taleLogDao.insertLog(log)

    suspend fun deleteLogsForTale(taleId: String) = taleLogDao.deleteLogsForTale(taleId)
}
