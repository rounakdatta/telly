package club.taptappers.telly.strava

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strava OAuth + API client.
 *
 * Auth flow: user enters their own Strava app's `client_id` / `client_secret`
 * (registered at https://www.strava.com/settings/api with callback domain
 * `localhost`). They open [authorizationUrl] in a browser, authorize, copy
 * the resulting URL/code from their browser, and submit it back via
 * [exchangeAuthorizationCode]. Tokens are persisted in [StravaSecrets].
 *
 * Tokens are refreshed transparently before each API call when within the
 * skew window; [tokenMutex] serializes refreshes so concurrent action runs
 * don't burn extra refresh attempts.
 */
@Singleton
class StravaHelper @Inject constructor(
    private val secrets: StravaSecrets
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()

    fun isConfigured(): Boolean = secrets.hasCredentials()
    fun isAuthorized(): Boolean = secrets.isAuthorized()
    fun athleteName(): String? = secrets.athleteName

    /** URL the user should open in a browser to authorize Telly. */
    fun authorizationUrl(): String? {
        val clientId = secrets.clientId?.takeIf { it.isNotBlank() } ?: return null
        return AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("approval_prompt", "auto")
            .addQueryParameter("scope", SCOPE)
            .build()
            .toString()
    }

    /**
     * Accepts either a bare authorization code or the full redirect URL the
     * browser landed on. On success persists tokens and returns null; on
     * failure returns a human-readable error and leaves tokens unchanged.
     */
    suspend fun exchangeAuthorizationCode(input: String): String? = withContext(Dispatchers.IO) {
        val code = extractCode(input)
            ?: return@withContext "Couldn't find a `code` in: $input"
        val clientId = secrets.clientId?.takeIf { it.isNotBlank() }
            ?: return@withContext "Client ID is not configured"
        val clientSecret = secrets.clientSecret?.takeIf { it.isNotBlank() }
            ?: return@withContext "Client Secret is not configured"

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext "Strava ${response.code}: ${responseBody.take(300)}"
                }
                val json = JSONObject(responseBody)
                val access = json.getString("access_token")
                val refresh = json.getString("refresh_token")
                val expiresAt = json.getLong("expires_at")
                val athleteName = json.optJSONObject("athlete")?.let { athlete ->
                    val first = athlete.optString("firstname", "").trim()
                    val last = athlete.optString("lastname", "").trim()
                    listOf(first, last)
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                        .takeIf { it.isNotEmpty() }
                }
                secrets.persistTokens(access, refresh, expiresAt, athleteName)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authorization-code exchange failed", e)
            "Network error: ${e.message}"
        }
    }

    /**
     * Fetches the most recent Strava activity tagged with `device_name == "Hevy"`,
     * then re-fetches the full detail payload by id (the list endpoint omits a
     * lot of fields; the detail endpoint is the source of truth). Returns null
     * if no Hevy activity exists in the recent window.
     */
    suspend fun getLastHevyActivity(): JSONObject? = withContext(Dispatchers.IO) {
        val token = validAccessToken()

        val listUrl = LIST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "30")
            .build()
        val listRequest = Request.Builder()
            .url(listUrl)
            .header("Authorization", "Bearer $token")
            .build()

        val activityId: Long = httpClient.newCall(listRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("Strava list ${response.code}: ${responseBody.take(300)}")
            }
            val arr = JSONArray(responseBody)
            var found: Long? = null
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optString("device_name") == HEVY_DEVICE_NAME) {
                    found = item.optLong("id")
                    break
                }
            }
            found
        } ?: return@withContext null

        val detailRequest = Request.Builder()
            .url("$DETAIL_URL_PREFIX$activityId")
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(detailRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("Strava detail ${response.code}: ${responseBody.take(300)}")
            }
            JSONObject(responseBody)
        }
    }

    /**
     * Result of a successful TCX upload + processing.
     *
     * - [Created]: a fresh activity was created from this upload.
     * - [Duplicate]: Strava recognized this external_id as a previous upload
     *   and returned the existing [activityId] without creating a new one.
     *   Idempotent re-runs land here.
     * - [Pending]: the upload was accepted but Strava is still processing it.
     *   Treated as a non-fatal "try again later" — the caller can record the
     *   upload_id and we'll pick up the activity on the next chain run.
     */
    sealed class UploadOutcome {
        data class Created(val activityId: Long) : UploadOutcome()
        data class Duplicate(val activityId: Long) : UploadOutcome()
        data class Pending(val uploadId: Long) : UploadOutcome()
    }

    /**
     * Uploads a TCX file as a new activity. Polls the upload-status endpoint
     * until the activity is processed (or the timeout fires). Strava
     * deduplicates by [externalId] — re-uploading the same external_id
     * returns [UploadOutcome.Duplicate] with the original activity_id.
     */
    suspend fun uploadActivity(
        tcxContent: String,
        name: String,
        description: String,
        externalId: String,
        pollTimeoutMs: Long = 60_000L
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val token = validAccessToken()

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("data_type", "tcx")
            .addFormDataPart("name", name)
            .addFormDataPart("description", description)
            .addFormDataPart("external_id", externalId)
            .addFormDataPart(
                name = "file",
                filename = "telly-$externalId.tcx",
                body = tcxContent.toRequestBody("application/xml".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()

        val initial = httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Upload ${resp.code}: ${body.take(300)}")
            }
            JSONObject(body)
        }

        // Strava reports duplicate detection as an "error" string on the
        // initial response, with the existing activity_id sometimes embedded.
        // We treat this as success.
        classifyUpload(initial)?.let { return@withContext it }

        val uploadId = initial.getLong("id")
        pollUpload(uploadId, token, pollTimeoutMs)
    }

    private suspend fun pollUpload(
        uploadId: Long,
        token: String,
        timeoutMs: Long
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var delayMs = 1_000L
        while (System.currentTimeMillis() < deadline) {
            delay(delayMs)
            // Linear backoff: 1s, 2s, 3s, capped at 5s. Strava processing is
            // usually done within a few seconds.
            delayMs = (delayMs + 1_000L).coerceAtMost(5_000L)

            val pollRequest = Request.Builder()
                .url("$UPLOAD_URL/$uploadId")
                .header("Authorization", "Bearer $token")
                .build()

            val json = httpClient.newCall(pollRequest).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("Upload poll ${resp.code}: ${body.take(300)}")
                }
                JSONObject(body)
            }

            classifyUpload(json)?.let { return@withContext it }
        }
        UploadOutcome.Pending(uploadId)
    }

    /**
     * Maps an upload-status JSON to an outcome, or null if it's still being
     * processed and we should keep polling.
     */
    private fun classifyUpload(json: JSONObject): UploadOutcome? {
        val activityIdRaw = if (json.isNull("activity_id")) 0L else json.optLong("activity_id", 0L)
        val error = json.optString("error", "").takeIf { it.isNotBlank() && it != "null" }

        if (error != null) {
            // Strava signals duplicates with a message like:
            //   "duplicate of activity 18253002807"
            // The activity_id field is sometimes populated, sometimes not.
            if (error.contains("duplicate", ignoreCase = true)) {
                val parsed = DUPLICATE_ID_REGEX.find(error)?.groupValues?.getOrNull(1)?.toLongOrNull()
                val finalId = parsed ?: activityIdRaw.takeIf { it > 0L }
                if (finalId != null) return UploadOutcome.Duplicate(finalId)
                // Duplicate but no id we can recover — treat as a hard error so
                // the caller logs it, since silently dropping would mask issues.
                throw RuntimeException("Duplicate upload but no activity_id returned: $error")
            }
            throw RuntimeException("Upload error: $error")
        }

        if (activityIdRaw > 0L) return UploadOutcome.Created(activityIdRaw)
        return null
    }

    /**
     * PUTs an UpdatableActivity. Strava only accepts the documented subset of
     * fields here — sport_type is the one we need most, since TCX uploads land
     * as generic "Workout" and we want WeightTraining (or whatever the
     * original Hevy activity was).
     */
    suspend fun updateActivity(
        activityId: Long,
        sportType: String? = null,
        name: String? = null,
        description: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val body = FormBody.Builder().apply {
            if (sportType != null) add("sport_type", sportType)
            if (name != null) add("name", name)
            if (description != null) add("description", description)
        }.build()

        val request = Request.Builder()
            .url("$ACTIVITY_URL_PREFIX$activityId")
            .header("Authorization", "Bearer $token")
            .put(body)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw RuntimeException("Update ${resp.code}: ${errBody.take(300)}")
            }
        }
    }

    fun signOut() {
        secrets.clearTokens()
    }

    fun clearCredentials() {
        secrets.clearAll()
    }

    /**
     * Returns an access token that is valid for at least [REFRESH_SKEW_SEC]
     * more seconds, refreshing if necessary. Throws if the user hasn't yet
     * completed the authorization-code exchange.
     */
    private suspend fun validAccessToken(): String = tokenMutex.withLock {
        val current = secrets.accessToken
            ?: throw IllegalStateException("Strava is not authorized")
        val nowSec = System.currentTimeMillis() / 1000
        if (secrets.expiresAtSec - nowSec >= REFRESH_SKEW_SEC) {
            current
        } else {
            refreshTokens()
        }
    }

    private fun refreshTokens(): String {
        val clientId = secrets.clientId
            ?: throw IllegalStateException("Client ID not configured")
        val clientSecret = secrets.clientSecret
            ?: throw IllegalStateException("Client Secret not configured")
        val refresh = secrets.refreshToken
            ?: throw IllegalStateException("No refresh token; reauthorization required")

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 400/401 on refresh means the refresh_token is dead (user
                // revoked at strava.com, or Strava invalidated it). Clear so
                // we don't keep retrying with a token that will never work.
                if (response.code == 400 || response.code == 401) {
                    secrets.clearTokens()
                }
                throw RuntimeException("Token refresh ${response.code}: ${responseBody.take(300)}")
            }
            val json = JSONObject(responseBody)
            // Parse everything before persisting — a partial JSON parse must not
            // half-write tokens. Refresh response can rotate the refresh_token.
            val newAccess = json.getString("access_token")
            val newRefresh = json.getString("refresh_token")
            val newExpiresAt = json.getLong("expires_at")
            secrets.persistTokens(newAccess, newRefresh, newExpiresAt)
            return newAccess
        }
    }

    private fun extractCode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        // Bare code (no URL syntax)
        if ('?' !in trimmed && '=' !in trimmed && '/' !in trimmed && ' ' !in trimmed) {
            return trimmed
        }
        return CODE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
    }

    companion object {
        private const val TAG = "StravaHelper"
        private const val AUTHORIZE_URL = "https://www.strava.com/oauth/authorize"
        private const val TOKEN_URL = "https://www.strava.com/api/v3/oauth/token"
        private const val LIST_URL = "https://www.strava.com/api/v3/athlete/activities"
        private const val DETAIL_URL_PREFIX = "https://www.strava.com/api/v3/activities/"
        private const val ACTIVITY_URL_PREFIX = "https://www.strava.com/api/v3/activities/"
        private const val UPLOAD_URL = "https://www.strava.com/api/v3/uploads"
        private const val REDIRECT_URI = "http://localhost/exchange_token"
        private const val SCOPE = "activity:read_all,activity:write"
        private const val HEVY_DEVICE_NAME = "Hevy"

        private val DUPLICATE_ID_REGEX = Regex("activity (\\d+)")

        // Refresh if the token expires within this many seconds of "now". One
        // minute is plenty — a single API call won't outlive that window.
        private const val REFRESH_SKEW_SEC = 60L

        private val CODE_REGEX = Regex("[?&]code=([^&\\s#]+)")
    }
}
