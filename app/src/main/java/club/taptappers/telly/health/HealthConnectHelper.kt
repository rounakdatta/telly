package club.taptappers.telly.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over Health Connect for Telly's needs:
 *   - check SDK availability (built-in on Android 14+, requires the Health
 *     Connect provider app on older versions)
 *   - check / request a fixed permission set
 *   - read HR + calorie records for a given workout window
 *
 * No writes — Telly is strictly a consumer.
 */
@Singleton
class HealthConnectHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Permissions Telly needs in order to augment a workout payload. Both
     * Active and Total calorie record types are read because providers vary
     * in which they write — Hevy/Strava sources may write either or both.
     */
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    private val client: HealthConnectClient? by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect getOrCreate failed", e)
            null
        }
    }

    fun isAvailable(): Boolean = client != null

    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: return false
        return try {
            c.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions failed", e)
            false
        }
    }

    /**
     * Reads HR samples and calorie totals in `[start, end]`. Errors per metric
     * are caught and surfaced in the returned JSON rather than propagated, so a
     * partially successful read still augments the payload.
     */
    suspend fun readWorkoutData(start: Instant, end: Instant): JSONObject {
        val c = client ?: return JSONObject().apply {
            put("error", "Health Connect not available on this device")
        }
        if (!hasAllPermissions()) {
            return JSONObject().apply { put("error", "Missing Health Connect permissions") }
        }

        val window = JSONObject()
            .put("start", start.toString())
            .put("end", end.toString())
            .put("durationSeconds", end.epochSecond - start.epochSecond)

        val heartRate = try {
            readHeartRate(c, start, end)
        } catch (e: Exception) {
            Log.e(TAG, "HR read failed", e)
            JSONObject().put("error", "HR read failed: ${e.message}")
        }

        val calories = try {
            readCalories(c, start, end)
        } catch (e: Exception) {
            Log.e(TAG, "Calorie read failed", e)
            JSONObject().put("error", "Calorie read failed: ${e.message}")
        }

        return JSONObject()
            .put("window", window)
            .put("heartRate", heartRate)
            .put("calories", calories)
    }

    private suspend fun readHeartRate(
        c: HealthConnectClient,
        start: Instant,
        end: Instant
    ): JSONObject {
        val records = c.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records

        val samples = JSONArray()
        var sum = 0L
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        var count = 0

        for (record in records) {
            for (sample in record.samples) {
                samples.put(
                    JSONObject()
                        .put("time", sample.time.toString())
                        .put("bpm", sample.beatsPerMinute)
                )
                sum += sample.beatsPerMinute
                if (sample.beatsPerMinute < min) min = sample.beatsPerMinute
                if (sample.beatsPerMinute > max) max = sample.beatsPerMinute
                count++
            }
        }

        return JSONObject().apply {
            put("count", count)
            put("samples", samples)
            if (count > 0) {
                put("min", min)
                put("max", max)
                // Two-decimal average — JSON will encode as a number, no formatting noise
                put("avg", (sum.toDouble() / count * 100.0).toLong() / 100.0)
            }
        }
    }

    private suspend fun readCalories(
        c: HealthConnectClient,
        start: Instant,
        end: Instant
    ): JSONObject {
        val active = c.readRecords(
            ReadRecordsRequest(
                recordType = ActiveCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records
        val total = c.readRecords(
            ReadRecordsRequest(
                recordType = TotalCaloriesBurnedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records

        val activeKcal = active.sumOf { it.energy.inKilocalories }
        val totalKcal = total.sumOf { it.energy.inKilocalories }

        return JSONObject()
            .put("active", JSONObject().put("kcal", activeKcal).put("recordCount", active.size))
            .put("total", JSONObject().put("kcal", totalKcal).put("recordCount", total.size))
    }

    companion object {
        private const val TAG = "HealthConnectHelper"
    }
}
