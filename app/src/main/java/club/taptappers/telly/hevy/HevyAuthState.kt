package club.taptappers.telly.hevy

import org.json.JSONObject
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class HevyAuthStatus {
    /** Either developer API key OR auth tokens are missing — first-time setup. */
    data object NotConfigured : HevyAuthStatus()

    /** A verify-credentials call is in flight. */
    data object Verifying : HevyAuthStatus()

    /** Telly holds working credentials for the user. */
    data class Authorized(val username: String?) : HevyAuthStatus()

    data class Error(val message: String) : HevyAuthStatus()
}

@Singleton
class HevyAuthState @Inject constructor(
    private val secrets: HevySecrets,
    private val helper: HevyHelper
) {
    private val _status = MutableStateFlow<HevyAuthStatus>(deriveStatus())
    val status: StateFlow<HevyAuthStatus> = _status.asStateFlow()

    private fun deriveStatus(): HevyAuthStatus = when {
        !secrets.isAuthorized() -> HevyAuthStatus.NotConfigured
        else -> HevyAuthStatus.Authorized(secrets.username)
    }

    fun refresh() {
        _status.value = deriveStatus()
    }

    fun saveDevApiKey(key: String) {
        secrets.devApiKey = key.trim()
        _status.value = deriveStatus()
    }

    /**
     * Accepts either:
     * - the raw value of the `auth2.0-token` cookie (URL-encoded JSON), or
     * - the already-decoded JSON object verbatim.
     *
     * Parses out access_token / refresh_token / expires_at, persists, then
     * verifies via `/account` and caches the username for display. Returns
     * null on success, or a human-readable error message on failure (which
     * also lands as `HevyAuthStatus.Error`).
     */
    suspend fun saveAuthCookieAndVerify(input: String): String? {
        _status.value = HevyAuthStatus.Verifying

        val parsed = parseCookieValue(input.trim())
        if (parsed == null) {
            val msg = "Couldn't parse auth2.0-token. Paste the cookie value (URL-encoded JSON) or the decoded JSON directly."
            _status.value = HevyAuthStatus.Error(msg)
            return msg
        }
        secrets.persistTokens(
            access = parsed.access,
            refresh = parsed.refresh,
            expiresAtIso = parsed.expiresAt
        )

        val username = helper.verifyAndCacheUsername()
        return if (username != null) {
            _status.value = HevyAuthStatus.Authorized(username)
            null
        } else {
            // Verification failed — wipe so we don't pretend we're authorized.
            secrets.clearTokens()
            val msg = "Tokens saved but /account verification failed. Re-extract the auth2.0-token cookie from app.hevyapp.com."
            _status.value = HevyAuthStatus.Error(msg)
            msg
        }
    }

    fun signOut() {
        helper.signOut()
        _status.value = deriveStatus()
    }

    fun clearAll() {
        helper.clearAll()
        _status.value = deriveStatus()
    }

    private data class ParsedCookie(
        val access: String,
        val refresh: String,
        val expiresAt: String
    )

    private fun parseCookieValue(raw: String): ParsedCookie? {
        if (raw.isEmpty()) return null
        // The cookie value is URL-encoded JSON. Decode if it looks encoded.
        val candidate = if (raw.startsWith("{")) raw
        else try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { return null }

        return try {
            val json = JSONObject(candidate)
            ParsedCookie(
                access = json.getString("access_token"),
                refresh = json.getString("refresh_token"),
                expiresAt = json.getString("expires_at")
            )
        } catch (_: Exception) {
            null
        }
    }
}
