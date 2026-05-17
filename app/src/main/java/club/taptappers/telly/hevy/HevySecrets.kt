package club.taptappers.telly.hevy

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device encrypted storage for Hevy credentials.
 *
 * Two pieces a user must provide one-time:
 * - **devApiKey** — issued via the Hevy account page. Required by the
 *   documented V1 endpoints (`/v1/workouts`).
 * - **accessToken** + **refreshToken** + **expiresAtIso** — extracted from the
 *   `auth2.0-token` cookie at https://app.hevyapp.com (browser DevTools →
 *   Application → Cookies). The Hevy username/password endpoint is broken
 *   per HevyHeart's research, so this manual paste is currently the only
 *   route. Token refresh via `/auth/refresh_token` keeps them current after.
 *
 * Note: Hevy's `expires_at` is an ISO 8601 string, not epoch seconds —
 * different from Strava's. We persist verbatim and parse on read.
 */
@Singleton
class HevySecrets @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var devApiKey: String?
        get() = prefs.getString(KEY_DEV_API_KEY, null)
        set(value) {
            prefs.edit().putString(KEY_DEV_API_KEY, value).apply()
        }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
        }

    var expiresAtIso: String?
        get() = prefs.getString(KEY_EXPIRES_AT_ISO, null)
        set(value) {
            prefs.edit().putString(KEY_EXPIRES_AT_ISO, value).apply()
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    /**
     * Workout IDs we've already examined and won't re-enrich, persisted across
     * runs. Two reasons a workout lands here:
     *   1. It already had biometrics natively when we first saw it (no work needed)
     *   2. We tried to enrich it but Health Connect had no HR data for that window
     *      (so re-trying would just keep failing the same way)
     *
     * Workouts we *successfully* enrich are NOT added here — the old Hevy id
     * is deleted anyway, and the new one carries biometrics so future scans
     * skip it naturally on the biometrics check. Workouts where enrichment
     * fails mid-flight are NOT added either, so the next run can retry.
     */
    val processedWorkoutIds: Set<String>
        get() = prefs.getString(KEY_PROCESSED_IDS, null)
            ?.let {
                try { JSONArray(it).let { arr ->
                    buildSet(arr.length()) {
                        for (i in 0 until arr.length()) add(arr.getString(i))
                    }
                } } catch (_: Exception) { emptySet() }
            } ?: emptySet()

    fun isProcessed(id: String): Boolean = id in processedWorkoutIds

    fun markProcessed(id: String) {
        if (id.isBlank()) return
        val current = processedWorkoutIds
        if (id in current) return
        persistProcessed(current + id)
    }

    fun markProcessedBulk(ids: Collection<String>) {
        val toAdd = ids.filter { it.isNotBlank() }
        if (toAdd.isEmpty()) return
        val current = processedWorkoutIds
        val merged = current + toAdd
        if (merged.size == current.size) return
        persistProcessed(merged)
    }

    private fun persistProcessed(ids: Set<String>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        prefs.edit().putString(KEY_PROCESSED_IDS, arr.toString()).commit()
    }

    fun hasDevApiKey(): Boolean = !devApiKey.isNullOrBlank()
    fun hasTokens(): Boolean = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
    fun isAuthorized(): Boolean = hasDevApiKey() && hasTokens()

    /** Atomic, synchronous write of the token bundle — same pattern as Strava. */
    fun persistTokens(
        access: String,
        refresh: String,
        expiresAtIso: String,
        username: String? = null
    ) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .putString(KEY_EXPIRES_AT_ISO, expiresAtIso)
        if (username != null) editor.putString(KEY_USERNAME, username)
        editor.commit()
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_ISO)
            .remove(KEY_USERNAME)
            .commit()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    fun clearProcessed() {
        prefs.edit().remove(KEY_PROCESSED_IDS).commit()
    }

    companion object {
        private const val FILE_NAME = "telly_hevy_secrets"
        private const val KEY_DEV_API_KEY = "dev_api_key"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_ISO = "expires_at_iso"
        private const val KEY_USERNAME = "username"
        private const val KEY_PROCESSED_IDS = "processed_workout_ids"
    }
}
