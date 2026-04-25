package club.taptappers.telly.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A post-action step. Reactions run in order after the action produces its
 * payload, with each step able to mutate the payload (`GetHealthDataForWorkout`)
 * or transmit it (`Webhook`). The list is the contract the user configures
 * per Tale; the runner walks it.
 *
 * Wire format on disk is a JSON array of objects, each tagged with `"type"`
 * plus type-specific fields. See [ReactionCodec].
 */
sealed class Reaction {
    abstract val typeKey: String

    /**
     * Reads `data.activity.start_date` and `data.activity.elapsed_time` from
     * the payload, queries Health Connect for HR + calorie samples in that
     * window, and writes the result to `data.health`.
     *
     * No-op if the payload doesn't carry an activity (e.g., wrong action type)
     * or Health Connect is unavailable / lacks permissions — failures are
     * captured in the tale log but never abort the chain.
     */
    data object GetHealthDataForWorkout : Reaction() {
        override val typeKey: String = TYPE_GET_HEALTH_DATA_FOR_WORKOUT
    }

    /**
     * Constructs a TCX file from the original Strava activity + Health Connect
     * samples in the payload, uploads it as a new Strava activity (preserving
     * the original — it stays alongside as Hevy left it), and writes the new
     * activity id back into `data.transform`. Idempotent across reruns via
     * Strava's external_id dedup.
     */
    data object StravaTransform : Reaction() {
        override val typeKey: String = TYPE_STRAVA_TRANSFORM
    }

    /**
     * Pairs with the [club.taptappers.telly.data.model.ActionType.HEVY_LAST_WORKOUT]
     * action. Recreates the user's most recent Hevy workout via Hevy's V2 API
     * with `heart_rate_samples` + `total_calories` populated from the augmented
     * `data.health` block, with `share_to_strava: true`. Hevy then performs the
     * partner-only sync to Strava on our behalf — the resulting Strava activity
     * has a native HR graph, native calorie total, and the photo if Hevy carried
     * `media[]` through. The original Hevy workout is deleted *after* the new
     * one is confirmed posted.
     *
     * No-op (with note in tale log) if the workout already has biometrics —
     * prevents the chain from churning when the action fires repeatedly.
     */
    data object SyncBiometricsToHevy : Reaction() {
        override val typeKey: String = TYPE_SYNC_BIOMETRICS_TO_HEVY
    }

    /** POST the current payload as JSON to [url]. */
    data class Webhook(val url: String) : Reaction() {
        override val typeKey: String = TYPE_WEBHOOK
    }

    companion object {
        const val TYPE_GET_HEALTH_DATA_FOR_WORKOUT: String = "GET_HEALTH_DATA_FOR_WORKOUT"
        const val TYPE_STRAVA_TRANSFORM: String = "STRAVA_TRANSFORM"
        const val TYPE_SYNC_BIOMETRICS_TO_HEVY: String = "SYNC_BIOMETRICS_TO_HEVY"
        const val TYPE_WEBHOOK: String = "WEBHOOK"
    }
}

object ReactionCodec {

    fun encode(reactions: List<Reaction>): String {
        val arr = JSONArray()
        for (reaction in reactions) {
            val obj = JSONObject().put("type", reaction.typeKey)
            when (reaction) {
                is Reaction.GetHealthDataForWorkout -> { /* no fields */ }
                is Reaction.StravaTransform -> { /* no fields */ }
                is Reaction.SyncBiometricsToHevy -> { /* no fields */ }
                is Reaction.Webhook -> obj.put("url", reaction.url)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Returns null when the input is null (caller-distinguishable from "explicit
     * empty list"). Returns empty list when the JSON is malformed — we'd rather
     * fall through to the legacy webhookUrl fallback than abort an action run.
     */
    fun decodeOrNull(json: String?): List<Reaction>? {
        if (json == null) return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                when (obj.optString("type")) {
                    Reaction.TYPE_GET_HEALTH_DATA_FOR_WORKOUT -> Reaction.GetHealthDataForWorkout
                    Reaction.TYPE_STRAVA_TRANSFORM -> Reaction.StravaTransform
                    Reaction.TYPE_SYNC_BIOMETRICS_TO_HEVY -> Reaction.SyncBiometricsToHevy
                    Reaction.TYPE_WEBHOOK -> {
                        val url = obj.optString("url").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        Reaction.Webhook(url)
                    }
                    else -> null // unknown type — forward-compatible: skip silently
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
