package com.telly.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telly.data.model.ActionType
import com.telly.data.model.TaleLog
import com.telly.data.repository.TaleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltWorker
class TaleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: TaleRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val taleId = inputData.getString(KEY_TALE_ID) ?: return Result.failure()

        val tale = repository.getTaleById(taleId)
        if (tale == null || !tale.isEnabled) {
            Log.d(TAG, "Tale $taleId not found or disabled")
            return Result.success()
        }

        return try {
            val result = executeAction(tale.actionType)

            // Log the result
            val log = TaleLog(
                taleId = taleId,
                result = result,
                success = true
            )
            repository.insertLog(log)

            // Update last run time
            repository.updateLastRunAt(taleId, System.currentTimeMillis())

            Log.d(TAG, "Tale $taleId executed: $result")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Tale $taleId failed", e)

            val log = TaleLog(
                taleId = taleId,
                result = "Error: ${e.message}",
                success = false
            )
            repository.insertLog(log)

            Result.failure()
        }
    }

    private fun executeAction(actionType: ActionType): String {
        return when (actionType) {
            ActionType.TIME -> {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                formatter.format(Date())
            }
        }
    }

    companion object {
        const val TAG = "TaleWorker"
        const val KEY_TALE_ID = "tale_id"
    }
}
