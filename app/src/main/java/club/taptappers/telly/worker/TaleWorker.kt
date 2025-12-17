package club.taptappers.telly.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import club.taptappers.telly.data.model.ActionType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.model.TaleLog
import club.taptappers.telly.data.repository.TaleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@HiltWorker
class TaleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: TaleRepository,
    private val scheduler: TaleScheduler
) : CoroutineWorker(context, workerParams) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val taleId = inputData.getString(KEY_TALE_ID) ?: return Result.failure()
        val rescheduleInterval = inputData.getLong(TaleScheduler.KEY_RESCHEDULE_INTERVAL, 0L)

        val tale = repository.getTaleById(taleId)
        if (tale == null || !tale.isEnabled) {
            Log.d(TAG, "Tale $taleId not found or disabled")
            return Result.success()
        }

        return try {
            val timestamp = System.currentTimeMillis()
            val result = executeAction(tale.actionType)

            // POST to webhook if configured
            val webhookResult = tale.webhookUrl?.let { url ->
                postToWebhook(url, tale, result, timestamp)
            }

            // Build log message
            val logMessage = buildString {
                append(result)
                if (webhookResult != null) {
                    append(" | Webhook: $webhookResult")
                }
            }

            // Log the result
            val log = TaleLog(
                taleId = taleId,
                result = logMessage,
                success = true
            )
            repository.insertLog(log)

            // Update last run time
            repository.updateLastRunAt(taleId, timestamp)

            Log.d(TAG, "Tale $taleId executed: $logMessage")

            // Schedule next run for short intervals
            if (rescheduleInterval > 0 && tale.isEnabled) {
                scheduler.scheduleNextShortInterval(taleId, rescheduleInterval)
                Log.d(TAG, "Tale $taleId rescheduled for ${rescheduleInterval}ms")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Tale $taleId failed", e)

            val log = TaleLog(
                taleId = taleId,
                result = "Error: ${e.message}",
                success = false
            )
            repository.insertLog(log)

            // Still reschedule even on failure for short intervals
            if (rescheduleInterval > 0) {
                val tale2 = repository.getTaleById(taleId)
                if (tale2?.isEnabled == true) {
                    scheduler.scheduleNextShortInterval(taleId, rescheduleInterval)
                }
            }

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

    private suspend fun postToWebhook(
        url: String,
        tale: Tale,
        result: String,
        timestamp: Long
    ): String = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("source", "telly")
                put("version", "1.0")
                put("device", Build.MODEL)
                put("tale", JSONObject().apply {
                    put("id", tale.id)
                    put("name", tale.name)
                    put("action", tale.actionType.name)
                })
                put("timestamp", timestamp)
                put("timestamp_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).format(Date(timestamp)))
                put("data", JSONObject().apply {
                    put("result", result)
                })
            }

            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Telly/1.0 (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    "OK (${response.code})"
                } else {
                    "Failed (${response.code})"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webhook failed for ${tale.name}", e)
            "Error: ${e.message}"
        }
    }

    companion object {
        const val TAG = "TaleWorker"
        const val KEY_TALE_ID = "tale_id"
    }
}
