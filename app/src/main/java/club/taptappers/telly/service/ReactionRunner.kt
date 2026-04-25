package club.taptappers.telly.service

import android.util.Log
import club.taptappers.telly.data.model.Reaction
import club.taptappers.telly.health.HealthConnectHelper
import club.taptappers.telly.hevy.HevyHelper
import club.taptappers.telly.strava.StravaHelper
import club.taptappers.telly.strava.TcxBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks a [Reaction] chain over a payload built by [TaleExecutor]. Reactions
 * may augment the payload (Health Connect lookup) or transmit it (webhook).
 *
 * Failures inside a reaction are isolated — they're recorded in the returned
 * log string and the chain continues. This is deliberate: a flaky webhook
 * shouldn't lose a successful health-data augment, and vice versa.
 */
@Singleton
class ReactionRunner @Inject constructor(
    private val healthConnect: HealthConnectHelper,
    private val strava: StravaHelper,
    private val hevy: HevyHelper
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun run(
        payload: JSONObject,
        reactions: List<Reaction>
    ): String {
        if (reactions.isEmpty()) return ""

        val notes = mutableListOf<String>()
        for (reaction in reactions) {
            val note = try {
                when (reaction) {
                    is Reaction.GetHealthDataForWorkout -> "Health: ${augmentWithHealthData(payload)}"
                    is Reaction.StravaTransform -> "Transform: ${applyStravaTransform(payload)}"
                    is Reaction.SyncBiometricsToHevy -> "HevySync: ${applySyncBiometricsToHevy(payload)}"
                    is Reaction.Webhook -> "Webhook: ${postWebhook(reaction.url, payload)}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reaction ${reaction.typeKey} failed", e)
                "${reaction.typeKey} error: ${e.message}"
            }
            notes.add(note)
        }
        return notes.joinToString(" | ")
    }

    /**
     * Reads the workout window from the payload's Strava activity, queries
     * Health Connect, and writes the result under `data.health`. Returns a
     * short summary suitable for the tale log.
     */
    private suspend fun augmentWithHealthData(payload: JSONObject): String {
        val data = payload.optJSONObject("data")
            ?: return "skipped (no data section)"
        val activity = data.optJSONObject("activity")
            ?: return "skipped (no activity in payload)"
        val startStr = activity.optString("start_date").takeIf { it.isNotBlank() }
            ?: return "skipped (no start_date)"
        val elapsed = activity.optLong("elapsed_time", 0L)
        if (elapsed <= 0L) return "skipped (no elapsed_time)"

        val start = try {
            Instant.parse(startStr)
        } catch (e: Exception) {
            return "skipped (unparsable start_date '$startStr')"
        }
        val end = start.plusSeconds(elapsed)

        val health = healthConnect.readWorkoutData(start, end)
        data.put("health", health)

        val errorNote = health.optString("error", "")
        if (errorNote.isNotBlank()) return errorNote
        val hrCount = health.optJSONObject("heartRate")?.optInt("count", 0) ?: 0
        return "appended ($hrCount HR samples)"
    }

    /**
     * Builds a TCX representation of the workout (Hevy description + HC HR
     * samples + HC active calories), uploads it to Strava as a new activity,
     * fixes the sport_type post-upload (TCX always lands as "Workout"), and
     * writes the resulting Strava activity_id back into `data.transform` so
     * downstream reactions (e.g., the webhook) can see it.
     */
    private suspend fun applyStravaTransform(payload: JSONObject): String {
        val data = payload.optJSONObject("data")
            ?: return "skipped (no data section)"
        val activity = data.optJSONObject("activity")
            ?: return "skipped (no activity in payload)"

        val originalId = activity.optLong("id", 0L)
        if (originalId <= 0L) return "skipped (no original activity id)"

        val startStr = activity.optString("start_date").takeIf { it.isNotBlank() }
            ?: return "skipped (no start_date)"
        val elapsed = activity.optLong("elapsed_time", 0L)
        if (elapsed <= 0L) return "skipped (no elapsed_time)"

        val name = activity.optString("name").takeIf { it.isNotBlank() } ?: "Workout"
        val sportType = activity.optString("sport_type").takeIf { it.isNotBlank() }
        val origDescription = activity.optString("description", "")

        // Pull HR samples + calorie totals out of the augmented health block
        // (set by the GetHealthDataForWorkout reaction). Optional — we still
        // upload the activity even with no health data, just without HR.
        val health = data.optJSONObject("health")
        val hrSamples: List<TcxBuilder.HrSample> = health
            ?.optJSONObject("heartRate")
            ?.optJSONArray("samples")
            ?.let { arr ->
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val time = s.optString("time").takeIf { it.isNotBlank() } ?: continue
                        val bpm = s.optLong("bpm", 0L)
                        if (bpm > 0L) add(TcxBuilder.HrSample(time, bpm))
                    }
                }
            } ?: emptyList()

        // Prefer active calories (energy spent above resting) over total —
        // matches the kcal Hevy/Strava typically display for workouts.
        val calories: Int = health
            ?.optJSONObject("calories")
            ?.optJSONObject("active")
            ?.optDouble("kcal", 0.0)
            ?.toInt()
            ?: 0

        val photoUrl = extractPrimaryPhotoUrl(activity)
        val description = composeDescription(origDescription, health, photoUrl)

        val tcx = TcxBuilder.build(
            startIso = startStr,
            elapsedSeconds = elapsed,
            notes = description,
            calories = calories,
            hrSamples = hrSamples
        )

        val externalId = "telly-$originalId"

        return when (val outcome = strava.uploadActivity(
            tcxContent = tcx,
            name = name,
            description = description,
            externalId = externalId
        )) {
            is StravaHelper.UploadOutcome.Created -> {
                if (sportType != null && sportType != "Workout") {
                    runCatching { strava.updateActivity(outcome.activityId, sportType = sportType) }
                        .onFailure { e -> Log.w(TAG, "sport_type update failed", e) }
                }
                writeTransformResult(data, "created", outcome.activityId, externalId)
                "uploaded as ${outcome.activityId} (${hrSamples.size} HR, $calories kcal)"
            }
            is StravaHelper.UploadOutcome.Duplicate -> {
                writeTransformResult(data, "duplicate", outcome.activityId, externalId)
                "already uploaded as ${outcome.activityId}"
            }
            is StravaHelper.UploadOutcome.Pending -> {
                writeTransformResult(data, "pending", null, externalId, uploadId = outcome.uploadId)
                "pending (upload ${outcome.uploadId})"
            }
        }
    }

    /**
     * Pairs with the [club.taptappers.telly.data.model.ActionType.HEVY_LAST_WORKOUT]
     * action. Reads the V1 + V2 representations of the workout from the
     * payload (placed there by the action), plus the HC-augmented HR/calorie
     * data. Idempotent: if the V2 workout already has populated biometrics,
     * we skip — the chain has run before for this same workout.
     *
     * On success: posts a new Hevy workout with `share_to_strava: true`,
     * deletes the old Hevy workout, and writes `data.hevy_sync` for the
     * webhook + future Strava-cleanup reaction. The duplicate Strava activity
     * is *intentionally* left alone here — we'll clean it up in a separate
     * reaction once we've decided how to identify it.
     */
    private suspend fun applySyncBiometricsToHevy(payload: JSONObject): String {
        if (!hevy.isAuthorized()) return "skipped (Hevy not authorized)"
        val data = payload.optJSONObject("data") ?: return "skipped (no data)"

        val workoutId = data.optString("workoutId").takeIf { it.isNotBlank() }
            ?: return "skipped (no workoutId — wrong action type?)"
        val v1 = data.optJSONObject("hevy_v1") ?: return "skipped (no hevy_v1)"
        val v2 = data.optJSONObject("hevy_v2") ?: return "skipped (no hevy_v2)"

        // Idempotency: if the workout we're about to enrich already has HR
        // samples, we already ran. Skip without churning a new POST + delete.
        val existingHr = v2.optJSONObject("biometrics")
            ?.optJSONArray("heart_rate_samples")
        if (existingHr != null && existingHr.length() > 0) {
            val sync = JSONObject()
                .put("status", "skipped_already_synced")
                .put("workout_id", workoutId)
                .put("existing_hr_samples", existingHr.length())
            data.put("hevy_sync", sync)
            return "skipped — workout already has ${existingHr.length()} HR samples"
        }

        val health = data.optJSONObject("health")
        val hrSamples = buildHevyHrSamples(
            health?.optJSONObject("heartRate")?.optJSONArray("samples") ?: JSONArray()
        )
        val totalCalories = health
            ?.optJSONObject("calories")
            ?.optJSONObject("total")
            ?.optDouble("kcal", 0.0)
            ?: 0.0
        if (hrSamples.length() == 0 && totalCalories <= 0.0) {
            return "skipped (no HR or calories — run GetHealthDataForWorkout first)"
        }

        val hevyPayload = buildHevyPostPayload(v1, v2, hrSamples, totalCalories)
        val oldStartIso = v1.optString("start_time", "")

        // ---------------------------------------------------------------
        // Step 1: POST the new workout. If this throws, the old workout is
        // untouched — propagate so the chain logs the error and the user
        // can re-run.
        // ---------------------------------------------------------------
        val postResponse = hevy.postWorkoutV2(hevyPayload)

        // ---------------------------------------------------------------
        // Step 2: Extract the new workout id. If the response is shaped in
        // an unexpected way and we can't pin down the id, ABORT — never
        // delete an old workout we can't pair with a new one.
        // ---------------------------------------------------------------
        val newWorkoutId = extractNewWorkoutId(postResponse)
        if (newWorkoutId == null) {
            recordAbort(
                data, workoutId, oldStartIso,
                reason = "post_response_missing_id",
                detail = "Response keys: ${postResponse.keys().asSequence().toList()}"
            )
            return "ABORTED: POST returned 2xx but no workout id parsed; OLD HEVY WORKOUT NOT DELETED"
        }

        // ---------------------------------------------------------------
        // Step 3: INDEPENDENTLY verify the new workout exists on Hevy AND
        // carries the biometrics we just sent. A separate GET — never trust
        // the POST response alone, especially with an undocumented API.
        // We retry briefly to absorb any small server-side propagation lag.
        // ---------------------------------------------------------------
        val verifiedHrCount = verifyNewWorkoutHasBiometrics(newWorkoutId)
        if (verifiedHrCount == null) {
            recordAbort(
                data, workoutId, oldStartIso,
                reason = "verify_failed",
                detail = "GET /workout/$newWorkoutId did not show biometrics within retry window",
                newWorkoutId = newWorkoutId
            )
            return "ABORTED: new workout $newWorkoutId did not surface biometrics on verify; OLD HEVY WORKOUT NOT DELETED"
        }

        // ---------------------------------------------------------------
        // Step 4: Verified safe. Delete the old workout. If the delete itself
        // fails we still consider the sync successful — the user has a stray
        // duplicate to clean up but no data was lost.
        // ---------------------------------------------------------------
        val deleteResult = try {
            hevy.deleteWorkoutV2(workoutId)
            "deleted"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete old Hevy workout $workoutId", e)
            "delete_failed: ${e.message}"
        }

        data.put("hevy_sync", JSONObject()
            .put("status", "synced")
            .put("new_workout_id", newWorkoutId)
            .put("old_workout_id", workoutId)
            .put("old_workout_start_time", oldStartIso)
            .put("old_hevy_delete", deleteResult)
            .put("hr_samples_sent", hrSamples.length())
            .put("hr_samples_verified", verifiedHrCount)
            .put("total_calories", totalCalories))

        return "verified new $newWorkoutId ($verifiedHrCount HR on Hevy); old hevy: $deleteResult"
    }

    /**
     * Pulls the new workout id out of a `POST /v2/workout` response. We've
     * seen Hevy return it nested under `workout.id` and bare at top level
     * `id` across different API versions — accept either, fail closed if
     * neither is present.
     */
    private fun extractNewWorkoutId(response: JSONObject): String? {
        val nested = response.optJSONObject("workout")?.optString("id")?.takeIf { it.isNotBlank() }
        if (nested != null) return nested
        val flat = response.optString("id").takeIf { it.isNotBlank() }
        return flat
    }

    /**
     * Confirms the new workout actually exists on Hevy with biometrics.
     * Retries briefly because Hevy's POST→GET consistency can have a small
     * window of propagation lag for nested fields.
     *
     * Returns the verified HR sample count on success, null if we couldn't
     * confirm within the retry budget — caller MUST treat null as "do not
     * delete the old workout."
     */
    private suspend fun verifyNewWorkoutHasBiometrics(newWorkoutId: String): Int? {
        val attempts = listOf(0L, 1_000L, 2_000L, 4_000L) // total ~7s, four tries
        for ((i, delayMs) in attempts.withIndex()) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            try {
                val verified = hevy.getWorkoutV2(newWorkoutId)
                val hr = verified.optJSONObject("biometrics")
                    ?.optJSONArray("heart_rate_samples")
                if (hr != null && hr.length() > 0) {
                    if (i > 0) Log.d(TAG, "Hevy verify succeeded on attempt ${i + 1}")
                    return hr.length()
                }
                Log.d(TAG, "Hevy verify attempt ${i + 1}: no biometrics yet for $newWorkoutId")
            } catch (e: Exception) {
                // Transient — keep retrying within budget. Don't propagate;
                // the null return is the contract.
                Log.w(TAG, "Hevy verify attempt ${i + 1} threw", e)
            }
        }
        return null
    }

    private fun recordAbort(
        data: JSONObject,
        oldWorkoutId: String,
        oldStartIso: String,
        reason: String,
        detail: String?,
        newWorkoutId: String? = null
    ) {
        val obj = JSONObject()
            .put("status", "aborted")
            .put("abort_reason", reason)
            .put("old_workout_id", oldWorkoutId)
            .put("old_workout_start_time", oldStartIso)
            .put("note", "Old Hevy workout intentionally NOT deleted — verify manually before retry.")
        if (detail != null) obj.put("detail", detail)
        if (newWorkoutId != null) obj.put("new_workout_id_unverified", newWorkoutId)
        data.put("hevy_sync", obj)
    }

    /**
     * Builds the `PostWorkout` body Hevy V2 expects — top-level
     * `share_to_strava: true` plus a nested `workout` carrying biometrics, the
     * exercise list (V1 sets enriched with V2 rest/superset metadata), and the
     * media list pulled verbatim from the V2 response so photos ride along.
     *
     * Timestamps: Hevy V2 stores workout start/end as epoch SECONDS — we reuse
     * what the V2 GET returned so we don't drift across timezone arithmetic.
     */
    private fun buildHevyPostPayload(
        v1: JSONObject,
        v2: JSONObject,
        hrSamples: JSONArray,
        totalCalories: Double
    ): JSONObject {
        val title = v1.optString("title").takeIf { it.isNotBlank() }
            ?: v2.optString("name", "Workout")
        val description = v1.optString("description").takeIf { it.isNotBlank() }
            ?: v2.optString("description", "")
        val startSec = v2.optLong("start_time", 0L)
        val endSec = v2.optLong("end_time", 0L)

        val workout = JSONObject()
            .put("title", title)
            .put("description", description)
            .put("start_time", startSec)
            .put("end_time", endSec)
            .put("is_private", v1.optBoolean("is_private", false))
            .put("is_biometrics_public", true)
            .put("apple_watch", v2.optBoolean("apple_watch", false))
            .put("wearos_watch", v2.optBoolean("wearos_watch", false))
            .put("workout_id", UUID.randomUUID().toString())
            .put("routine_id", v1.optString("routine_id", v2.optString("routine_id", "")))
            .put("media", v2.optJSONArray("media") ?: JSONArray())
            .put("biometrics", JSONObject()
                .put("heart_rate_samples", hrSamples)
                .put("total_calories", totalCalories))
            .put("exercises", buildHevyExercises(v1, v2))

        // trainer_program_id can be null/missing — preserve only when present.
        if (v2.has("trainer_program_id") && !v2.isNull("trainer_program_id")) {
            workout.put("trainer_program_id", v2.optString("trainer_program_id"))
        }

        return JSONObject()
            .put("share_to_strava", true)
            .put("workout", workout)
    }

    private fun buildHevyExercises(v1: JSONObject, v2: JSONObject): JSONArray {
        val v1Exercises = v1.optJSONArray("exercises") ?: return JSONArray()
        val v2Exercises = v2.optJSONArray("exercises") ?: JSONArray()

        val out = JSONArray()
        for (i in 0 until v1Exercises.length()) {
            val v1Ex = v1Exercises.optJSONObject(i) ?: continue
            val templateId = v1Ex.optString("exercise_template_id")
            val title = v1Ex.optString("title")
            val v2Ex = findV2Exercise(v2Exercises, templateId, title)

            val ex = JSONObject()
                .put("exercise_template_id", templateId)
                .put("title", title)
                .put("notes", v2Ex?.optString("notes", "") ?: "")
                .put("rest_timer_seconds", v2Ex?.optInt("rest_seconds", 0) ?: 0)
                .put("volume_doubling_enabled", v2Ex?.optBoolean("volume_doubling_enabled", false) ?: false)
                .put("sets", v1Ex.optJSONArray("sets") ?: JSONArray())

            // superset_id may be null; preserve null distinctly (Hevy uses null
            // to indicate "not in a superset").
            if (v2Ex != null && v2Ex.has("superset_id") && !v2Ex.isNull("superset_id")) {
                ex.put("superset_id", v2Ex.optInt("superset_id"))
            } else {
                ex.put("superset_id", JSONObject.NULL)
            }
            out.put(ex)
        }
        return out
    }

    private fun findV2Exercise(
        v2Exercises: JSONArray,
        templateId: String,
        title: String
    ): JSONObject? {
        // Primary: exact template id match.
        for (i in 0 until v2Exercises.length()) {
            val ex = v2Exercises.optJSONObject(i) ?: continue
            if (ex.optString("exercise_template_id") == templateId) return ex
        }
        // Fallback: title match (defensive; shouldn't normally be needed).
        for (i in 0 until v2Exercises.length()) {
            val ex = v2Exercises.optJSONObject(i) ?: continue
            if (ex.optString("title") == title) return ex
        }
        return null
    }

    private fun buildHevyHrSamples(healthSamples: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until healthSamples.length()) {
            val s = healthSamples.optJSONObject(i) ?: continue
            val timeStr = s.optString("time")
            val bpm = s.optDouble("bpm", 0.0)
            if (timeStr.isBlank() || bpm <= 0.0) continue
            val timestampMs = try {
                Instant.parse(timeStr).toEpochMilli()
            } catch (_: Exception) {
                continue
            }
            out.put(JSONObject().put("bpm", bpm).put("timestamp_ms", timestampMs))
        }
        return out
    }

    private fun composeDescription(
        original: String,
        health: JSONObject?,
        photoUrl: String?
    ): String {
        val sections = mutableListOf<String>()
        if (original.isNotBlank()) sections.add(original)

        // Strava's photo upload API is partner-only; we can't attach the image
        // natively. Embedding the original Hevy CDN URL gets us the next best
        // thing — Strava auto-links it on the activity page so it's one click
        // away from the description.
        if (photoUrl != null) {
            sections.add("Photo: $photoUrl")
        }

        if (health != null) {
            val hr = health.optJSONObject("heartRate")
            val cal = health.optJSONObject("calories")
            val summary = buildString {
                val hrCount = hr?.optInt("count", 0) ?: 0
                if (hrCount > 0) {
                    val avg = hr?.optDouble("avg", 0.0) ?: 0.0
                    val max = hr?.optLong("max", 0L) ?: 0L
                    append("HR: avg ${avg.toInt()} bpm, max $max bpm ($hrCount samples)")
                }
                val active = cal?.optJSONObject("active")?.optDouble("kcal", 0.0) ?: 0.0
                val total = cal?.optJSONObject("total")?.optDouble("kcal", 0.0) ?: 0.0
                if (active > 0.0 || total > 0.0) {
                    if (isNotEmpty()) append(" · ")
                    append("Calories: ${active.toInt()} active / ${total.toInt()} total kcal")
                }
            }
            if (summary.isNotBlank()) sections.add("— Telly: $summary")
        }

        return sections.joinToString("\n\n")
    }

    /**
     * The Strava activity payload nests the photo URL at
     * `photos.primary.urls.{size}` where size is one of "100" / "600". Prefer
     * the larger; fall back to the smaller. Returns null when there's no
     * primary photo.
     */
    private fun extractPrimaryPhotoUrl(activity: JSONObject): String? {
        val urls = activity.optJSONObject("photos")
            ?.optJSONObject("primary")
            ?.optJSONObject("urls")
            ?: return null
        return urls.optString("600").takeIf { it.isNotBlank() }
            ?: urls.optString("100").takeIf { it.isNotBlank() }
    }

    private fun writeTransformResult(
        data: JSONObject,
        status: String,
        activityId: Long?,
        externalId: String,
        uploadId: Long? = null
    ) {
        val transform = JSONObject()
            .put("status", status)
            .put("external_id", externalId)
        if (activityId != null) transform.put("strava_activity_id", activityId)
        if (uploadId != null) transform.put("upload_id", uploadId)
        data.put("transform", transform)
    }

    private suspend fun postWebhook(url: String, payload: JSONObject): String =
        withContext(Dispatchers.IO) {
            try {
                val body = payload.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Telly/1.0 (Android)")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) "OK (${response.code})"
                    else "Failed (${response.code})"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Webhook failed", e)
                "Error: ${e.message}"
            }
        }

    companion object {
        private const val TAG = "ReactionRunner"
    }
}
