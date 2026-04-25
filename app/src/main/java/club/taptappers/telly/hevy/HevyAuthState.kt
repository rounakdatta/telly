package club.taptappers.telly.hevy

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
     * Called from [club.taptappers.telly.hevy.HevyWebLoginActivity] once the
     * WebView captures the `auth2.0-token` cookie set by Hevy's web login
     * flow. Parses the cookie, persists the tokens atomically, and verifies
     * via `/account`. Returns null on success, or a human-readable error
     * (also surfaced as [HevyAuthStatus.Error]).
     *
     * Wraps everything in a try/catch — the only terminal states allowed
     * are [HevyAuthStatus.Authorized] or [HevyAuthStatus.Error]. The
     * spinner must never be left dangling.
     */
    suspend fun handleWebLoginCookie(rawCookieValue: String): String? {
        _status.value = HevyAuthStatus.Verifying
        return try {
            val tokens = helper.parseAuthCookieValue(rawCookieValue)
            if (tokens == null) {
                val msg = "Couldn't parse auth2.0-token from web login. Try logging in again."
                _status.value = HevyAuthStatus.Error(msg)
                return msg
            }
            helper.persistTokens(tokens)
            val username = helper.verifyAndCacheUsername()
            if (username != null) {
                _status.value = HevyAuthStatus.Authorized(username)
                null
            } else {
                secrets.clearTokens()
                val msg = "Cookie captured but /account verification failed. Try logging in again."
                _status.value = HevyAuthStatus.Error(msg)
                msg
            }
        } catch (e: Exception) {
            secrets.clearTokens()
            val msg = "Web-login crash: ${e.javaClass.simpleName}: ${e.message}"
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
}
