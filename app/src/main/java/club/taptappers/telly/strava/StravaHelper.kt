package club.taptappers.telly.strava

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private const val REDIRECT_URI = "http://localhost/exchange_token"
        private const val SCOPE = "activity:read_all"
        private const val HEVY_DEVICE_NAME = "Hevy"

        // Refresh if the token expires within this many seconds of "now". One
        // minute is plenty — a single API call won't outlive that window.
        private const val REFRESH_SKEW_SEC = 60L

        private val CODE_REGEX = Regex("[?&]code=([^&\\s#]+)")
    }
}
