package club.taptappers.telly.hevy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hevy API client — V1 (documented, user-keyed) for listing workouts and V2
 * (undocumented, mobile-app-keyed) for everything else.
 *
 * Reverse-engineering credit: HevyHeart (https://github.com/iAm9001/HevyHeart).
 * The two `X-Api-Key` values + `Hevy-App-*` headers are baked into Hevy's
 * Android app and do not vary per user.
 */
@Singleton
class HevyHelper @Inject constructor(
    private val secrets: HevySecrets
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()

    fun isConfigured(): Boolean = secrets.isAuthorized()
    fun isAuthorized(): Boolean = secrets.isAuthorized()
    fun username(): String? = secrets.username

    // -------- V2 auth surface --------

    /**
     * Hits `/account` to verify the current access token works AND captures
     * the username (used as a friendly identifier in the UI). Returns the
     * username on success, or null on auth failure. Caller surfaces error.
     */
    suspend fun verifyAndCacheUsername(): String? = withContext(Dispatchers.IO) {
        try {
            val token = validAccessToken()
            val req = Request.Builder()
                .url("$BASE_URL/account")
                .header("Authorization", "Bearer $token")
                .header("X-Api-Key", APP_KEY_USER)
                .applyHevyAppHeaders()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "/account ${resp.code}: ${resp.body?.string()?.take(300)}")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val username = json.optString("username").takeIf { it.isNotBlank() }
                if (username != null) secrets.username = username
                username
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyAndCacheUsername failed", e)
            null
        }
    }

    /**
     * Returns an access token valid for at least [REFRESH_SKEW_SEC] more
     * seconds, refreshing if necessary. Throws if not authorized.
     */
    private suspend fun validAccessToken(): String = tokenMutex.withLock {
        val current = secrets.accessToken
            ?: throw IllegalStateException("Hevy not authorized — paste auth cookie JSON")
        val expiresAtIso = secrets.expiresAtIso
        val nowSec = System.currentTimeMillis() / 1000
        val expiresAtSec = parseIsoToEpochSec(expiresAtIso)
        if (expiresAtSec == null || expiresAtSec - nowSec < REFRESH_SKEW_SEC) {
            refreshTokens()
        } else {
            current
        }
    }

    private fun refreshTokens(): String {
        val accessToken = secrets.accessToken
            ?: throw IllegalStateException("No access token to refresh")
        val refresh = secrets.refreshToken
            ?: throw IllegalStateException("No refresh token; reauthorize with cookie")

        val body = JSONObject().put("refresh_token", refresh)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/auth/refresh_token")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Api-Key", APP_KEY_USER)
            .applyHevyAppHeaders()
            .post(body)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 400 || resp.code == 401) {
                    // Refresh token revoked / expired — clear so the UI surfaces re-auth.
                    secrets.clearTokens()
                }
                throw RuntimeException("Hevy refresh ${resp.code}: ${responseBody.take(300)}")
            }
            val json = JSONObject(responseBody)
            val newAccess = json.getString("access_token")
            val newRefresh = json.getString("refresh_token")
            val newExpiresIso = json.getString("expires_at")
            secrets.persistTokens(newAccess, newRefresh, newExpiresIso)
            return newAccess
        }
    }

    // -------- V1 surface (documented; user-keyed) --------

    /**
     * Lists the most recent workouts using the documented V1 endpoint (so we
     * don't depend on an undocumented "list" endpoint via V2). Caller is
     * responsible for picking the latest by `start_time`.
     */
    suspend fun listLatestWorkoutsV1(pageSize: Int = 5): JSONObject = withContext(Dispatchers.IO) {
        val devKey = secrets.devApiKey
            ?: throw IllegalStateException("Hevy developer API key not configured")

        val url = "$BASE_URL/v1/workouts".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("pageSize", pageSize.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .header("api-key", devKey)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Hevy V1 list ${resp.code}: ${body.take(300)}")
            }
            JSONObject(body)
        }
    }

    // -------- V2 workout surface --------

    /** Fetches the V2 detail (has `media` for photos and richer exercise fields). */
    suspend fun getWorkoutV2(workoutId: String): JSONObject = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val req = Request.Builder()
            .url("$BASE_URL/workout/$workoutId")
            .header("Authorization", "Bearer $token")
            .header("X-Api-Key", APP_KEY_DATA)
            .applyHevyAppHeaders()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Hevy V2 get ${resp.code}: ${body.take(300)}")
            }
            JSONObject(body)
        }
    }

    suspend fun deleteWorkoutV2(workoutId: String): Unit = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val req = Request.Builder()
            .url("$BASE_URL/workout/$workoutId")
            .header("Authorization", "Bearer $token")
            .header("X-Api-Key", APP_KEY_DATA)
            .applyHevyAppHeaders()
            .delete()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Hevy V2 delete ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
        }
    }

    /**
     * Posts a new workout. Body shape is HevyHeart's `PostWorkout` (top-level
     * `share_to_strava` + nested `workout`). Returns Hevy's response, which
     * the caller can mine for the new workout id.
     */
    suspend fun postWorkoutV2(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/v2/workout")
            .header("Authorization", "Bearer $token")
            .header("X-Api-Key", APP_KEY_DATA)
            .applyHevyAppHeaders()
            .post(body)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Hevy V2 post ${resp.code}: ${responseBody.take(300)}")
            }
            JSONObject(responseBody)
        }
    }

    fun signOut() {
        secrets.clearTokens()
    }

    fun clearAll() {
        secrets.clearAll()
    }

    private fun Request.Builder.applyHevyAppHeaders(): Request.Builder = this
        .header("Hevy-App-Version", APP_VERSION)
        .header("Hevy-App-Build", APP_BUILD)
        .header("Hevy-Platform", APP_PLATFORM)

    private fun parseIsoToEpochSec(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).epochSecond
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "HevyHelper"
        private const val BASE_URL = "https://api.hevyapp.com"

        // App-baked keys, identical for every Hevy user. Sourced from HevyHeart.
        private const val APP_KEY_USER = "with_great_power"          // /account, /auth/refresh_token
        private const val APP_KEY_DATA = "klean_kanteen_insulated"   // /workout/*, /v2/workout

        private const val APP_VERSION = "2.5.6"
        private const val APP_BUILD = "1819922"
        private const val APP_PLATFORM = "android 36"

        private const val REFRESH_SKEW_SEC = 60L
    }
}
