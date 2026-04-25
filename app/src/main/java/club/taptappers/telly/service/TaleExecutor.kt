package club.taptappers.telly.service

import android.os.Build
import android.util.Log
import club.taptappers.telly.data.model.ActionType
import club.taptappers.telly.data.model.Reaction
import club.taptappers.telly.data.model.ReactionCodec
import club.taptappers.telly.data.model.ScheduleType
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.model.TaleLog
import club.taptappers.telly.data.repository.TaleRepository
import club.taptappers.telly.gmail.GmailHelper
import club.taptappers.telly.hevy.HevyHelper
import club.taptappers.telly.strava.StravaHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaleExecutor @Inject constructor(
    private val repository: TaleRepository,
    private val gmailHelper: GmailHelper,
    private val stravaHelper: StravaHelper,
    private val hevyHelper: HevyHelper,
    private val reactionRunner: ReactionRunner
) {
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
            val resultSummary = summarize(actionResult)

            // Build the wire payload up front. Reactions augment it in place
            // and/or transmit it; we never re-serialize per reaction.
            val payload = buildPayload(tale, actionResult, timestamp)
            val reactions = effectiveReactions(tale)
            val reactionLog = reactionRunner.run(payload, reactions)

            val logMessage = if (reactionLog.isBlank()) resultSummary
            else "$resultSummary | $reactionLog"

            repository.insertLog(
                TaleLog(taleId = taleId, result = logMessage, success = true)
            )
            repository.updateLastRunAt(taleId, timestamp)

            Log.d(TAG, "Tale $taleId executed: $logMessage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tale $taleId failed", e)
            repository.insertLog(
                TaleLog(taleId = taleId, result = "Error: ${e.message}", success = false)
            )
            false
        }
    }

    /**
     * Returns the reactions to run, in order. Falls back to a single Webhook
     * synthesized from the legacy [Tale.webhookUrl] when [Tale.reactionsJson]
     * is null — keeps tales authored before the Reaction abstraction working
     * unchanged. An empty (non-null) reactionsJson means "no reactions".
     */
    private fun effectiveReactions(tale: Tale): List<Reaction> {
        val parsed = ReactionCodec.decodeOrNull(tale.reactionsJson)
        if (parsed != null) return parsed
        return if (!tale.webhookUrl.isNullOrBlank()) {
            listOf(Reaction.Webhook(tale.webhookUrl))
        } else {
            emptyList()
        }
    }

    private fun summarize(actionResult: ActionResult): String = when (actionResult) {
        is ActionResult.Simple -> actionResult.result
        is ActionResult.EmailJuggle -> actionResult.summary
        is ActionResult.Strava -> actionResult.summary
        is ActionResult.Hevy -> actionResult.summary
    }

    private fun buildPayload(
        tale: Tale,
        actionResult: ActionResult,
        timestamp: Long
    ): JSONObject {
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
        return JSONObject().apply {
            put("source", "telly")
            put("version", "1.0")
            put("device", Build.MODEL)
            put("tale", JSONObject().apply {
                put("id", tale.id)
                put("name", tale.name)
                put("action", tale.actionType.name)
                if (tale.searchQuery != null) put("searchQuery", tale.searchQuery)
            })
            put("timestamp", timestamp)
            put("timestamp_iso", isoFormatter.format(Date(timestamp)))
            put("data", when (actionResult) {
                is ActionResult.Simple -> JSONObject().put("result", actionResult.result)
                is ActionResult.EmailJuggle -> JSONObject()
                    .put("summary", actionResult.summary)
                    .put("emailCount", actionResult.emailCount)
                    .put("emails", actionResult.emailsJson)
                is ActionResult.Strava -> JSONObject()
                    .put("summary", actionResult.summary)
                    .put("activityId", actionResult.activityId)
                    .put("activity", actionResult.activityJson)
                is ActionResult.Hevy -> JSONObject()
                    .put("summary", actionResult.summary)
                    .put("workoutId", actionResult.workoutId)
                    // We synthesize a Strava-shaped `activity` object so the
                    // GetHealthDataForWorkout reaction can read start_date /
                    // elapsed_time from the same place regardless of source.
                    .put("activity", actionResult.activityShim)
                    .put("hevy_v1", actionResult.workoutV1Json)
                    .put("hevy_v2", actionResult.workoutV2Json)
            })
        }
    }

    private suspend fun executeAction(tale: Tale, timestamp: Long): ActionResult {
        return when (tale.actionType) {
            ActionType.TIME -> {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                ActionResult.Simple(formatter.format(Date()))
            }
            ActionType.EMAIL_JUGGLE -> executeEmailJuggle(tale, timestamp)
            ActionType.STRAVA_LAST_HEVY -> executeStravaLastHevy()
            ActionType.HEVY_LAST_WORKOUT -> executeHevyLastWorkout()
        }
    }

    /**
     * Pulls the most recent Hevy workout directly from Hevy (V1 list →
     * V2 detail). Synthesizes a Strava-shaped `activity` object on the side so
     * the GetHealthDataForWorkout reaction (which reads `start_date` /
     * `elapsed_time`) doesn't need to know the source.
     */
    private suspend fun executeHevyLastWorkout(): ActionResult {
        if (!hevyHelper.isAuthorized()) {
            return ActionResult.Simple("Error: Hevy not authorized")
        }
        return try {
            val list = hevyHelper.listLatestWorkoutsV1(pageSize = 1)
            val workouts = list.optJSONArray("workouts")
            if (workouts == null || workouts.length() == 0) {
                return ActionResult.Simple("No Hevy workouts found")
            }
            val v1 = workouts.getJSONObject(0)
            val workoutId = v1.optString("id")
                .takeIf { it.isNotBlank() }
                ?: return ActionResult.Simple("Hevy workout missing id")
            val v2 = hevyHelper.getWorkoutV2(workoutId)

            val title = v1.optString("title", v2.optString("name", "(unnamed)"))
            val activityShim = buildActivityShim(v1)

            ActionResult.Hevy(
                summary = "Got Hevy workout: $title",
                workoutId = workoutId,
                activityShim = activityShim,
                workoutV1Json = v1,
                workoutV2Json = v2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hevy action failed", e)
            ActionResult.Simple("Error: ${e.message}")
        }
    }

    /**
     * Constructs a minimal Strava-shaped `activity` from a Hevy V1 workout —
     * just enough for the existing GetHealthDataForWorkout reaction to compute
     * the workout window. V1 timestamps are ISO strings; we re-emit `start_date`
     * in the same UTC ISO-8601 form Strava uses.
     */
    private fun buildActivityShim(v1: JSONObject): JSONObject {
        val startStr = v1.optString("start_time", "")
        val endStr = v1.optString("end_time", "")
        val elapsed = if (startStr.isNotBlank() && endStr.isNotBlank()) {
            try {
                val start = Instant.parse(startStr)
                val end = Instant.parse(endStr)
                end.epochSecond - start.epochSecond
            } catch (_: Exception) { 0L }
        } else 0L

        return JSONObject()
            .put("id", v1.optString("id"))
            .put("name", v1.optString("title"))
            .put("description", v1.optString("description"))
            .put("start_date", startStr)
            .put("start_date_local", startStr)
            .put("elapsed_time", elapsed)
            .put("moving_time", elapsed)
            .put("sport_type", "WeightTraining")
            .put("type", "WeightTraining")
            .put("source", "hevy")
    }

    private suspend fun executeStravaLastHevy(): ActionResult {
        if (!stravaHelper.isAuthorized()) {
            return ActionResult.Simple("Error: Strava not authorized")
        }
        return try {
            val activity = stravaHelper.getLastHevyActivity()
            if (activity == null) {
                ActionResult.Simple("No Hevy activity found in recent activities")
            } else {
                val name = activity.optString("name", "(unnamed)")
                ActionResult.Strava(
                    summary = "Got Hevy activity: $name",
                    activityId = activity.optLong("id"),
                    activityJson = activity
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Strava action failed", e)
            ActionResult.Simple("Error: ${e.message}")
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

        // Time window derived from the schedule — the same shape as before
        // the Reaction refactor, so existing tales behave identically.
        val windowMs = when (tale.scheduleType) {
            ScheduleType.ONCE -> 60 * 60 * 1000L
            ScheduleType.DAILY_AT -> 24 * 60 * 60 * 1000L
            ScheduleType.INTERVAL -> tale.scheduleValue?.toLongOrNull() ?: (60 * 60 * 1000L)
        }
        val windowStartMs = timestamp - windowMs
        Log.d(TAG, "Email Juggle: '$searchQuery' from ${Date(windowStartMs)} to ${Date(timestamp)}")

        val emails = gmailHelper.fetchEmails(
            searchQuery = searchQuery,
            windowStartMs = windowStartMs,
            windowEndMs = timestamp
        )
        return ActionResult.EmailJuggle(
            summary = "Found ${emails.size} emails",
            emailCount = emails.size,
            emailsJson = gmailHelper.emailResultsToJson(emails)
        )
    }

    sealed class ActionResult {
        data class Simple(val result: String) : ActionResult()
        data class EmailJuggle(
            val summary: String,
            val emailCount: Int,
            val emailsJson: JSONArray
        ) : ActionResult()
        data class Strava(
            val summary: String,
            val activityId: Long,
            val activityJson: JSONObject
        ) : ActionResult()
        data class Hevy(
            val summary: String,
            val workoutId: String,
            /** Strava-shaped `activity` synthesized from V1 — for reaction reuse. */
            val activityShim: JSONObject,
            val workoutV1Json: JSONObject,
            val workoutV2Json: JSONObject
        ) : ActionResult()
    }

    companion object {
        private const val TAG = "TaleExecutor"
    }
}
