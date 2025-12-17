package club.taptappers.telly.service

import android.os.Build
import android.util.Log
import club.taptappers.telly.data.model.ActionType
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.model.TaleLog
import club.taptappers.telly.data.repository.TaleRepository
import club.taptappers.telly.gmail.GmailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaleExecutor @Inject constructor(
    private val repository: TaleRepository,
    private val gmailHelper: GmailHelper
) {
    companion object {
        private const val TAG = "TaleExecutor"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun execute(taleId: String): Boolean {
        val tale = repository.getTaleById(taleId)
        if (tale == null) {
            Log.e(TAG, "Tale $taleId not found")
            return false
        }

        if (!tale.isEnabled) {
            Log.d(TAG, "Tale $taleId is disabled, skipping")
            return false
        }

        return try {
            val timestamp = System.currentTimeMillis()
            val actionResult = executeAction(tale, timestamp)

            // Build result string for logging
            val resultSummary = when (actionResult) {
                is ActionResult.Simple -> actionResult.result
                is ActionResult.EmailJuggle -> actionResult.summary
            }

            // POST to webhook if configured
            val webhookResult = tale.webhookUrl?.let { url ->
                postToWebhook(url, tale, actionResult, timestamp)
            }

            // Build log message
            val logMessage = buildString {
                append(resultSummary)
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tale $taleId failed", e)

            val log = TaleLog(
                taleId = taleId,
                result = "Error: ${e.message}",
                success = false
            )
            repository.insertLog(log)
            false
        }
    }

    private suspend fun executeAction(tale: Tale, timestamp: Long): ActionResult {
        return when (tale.actionType) {
            ActionType.TIME -> {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                ActionResult.Simple(formatter.format(Date()))
            }
            ActionType.EMAIL_JUGGLE -> {
                executeEmailJuggle(tale, timestamp)
            }
        }
    }

    private suspend fun executeEmailJuggle(tale: Tale, timestamp: Long): ActionResult {
        val searchQuery = tale.searchQuery
        if (searchQuery.isNullOrBlank()) {
            return ActionResult.Simple("Error: No search query configured")
        }

        if (!gmailHelper.isSignedIn()) {
            return ActionResult.Simple("Error: Gmail not signed in")
        }

        // Calculate time window based on schedule type
        val windowMs = when (tale.scheduleType) {
            ScheduleType.ONCE -> 60 * 60 * 1000L // 1 hour for one-time
            ScheduleType.DAILY_AT -> 24 * 60 * 60 * 1000L // 24 hours for daily
            ScheduleType.INTERVAL -> {
                tale.scheduleValue?.toLongOrNull() ?: (60 * 60 * 1000L) // Default 1 hour
            }
        }

        val windowEndMs = timestamp
        val windowStartMs = timestamp - windowMs

        Log.d(TAG, "Email Juggle: searching '$searchQuery' from ${Date(windowStartMs)} to ${Date(windowEndMs)}")

        val emails = gmailHelper.fetchEmails(
            searchQuery = searchQuery,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs
        )

        val emailsJson = gmailHelper.emailResultsToJson(emails)

        return ActionResult.EmailJuggle(
            summary = "Found ${emails.size} emails",
            emailCount = emails.size,
            emailsJson = emailsJson
        )
    }

    sealed class ActionResult {
        data class Simple(val result: String) : ActionResult()
        data class EmailJuggle(
            val summary: String,
            val emailCount: Int,
            val emailsJson: JSONArray
        ) : ActionResult()
    }

    private suspend fun postToWebhook(
        url: String,
        tale: Tale,
        actionResult: ActionResult,
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
                    if (tale.searchQuery != null) {
                        put("searchQuery", tale.searchQuery)
                    }
                })
                put("timestamp", timestamp)
                put("timestamp_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).format(Date(timestamp)))
                put("data", when (actionResult) {
                    is ActionResult.Simple -> JSONObject().apply {
                        put("result", actionResult.result)
                    }
                    is ActionResult.EmailJuggle -> JSONObject().apply {
                        put("summary", actionResult.summary)
                        put("emailCount", actionResult.emailCount)
                        put("emails", actionResult.emailsJson)
                    }
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
}
