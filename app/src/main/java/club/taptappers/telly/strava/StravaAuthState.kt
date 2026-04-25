package club.taptappers.telly.strava

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class StravaAuthStatus {
    /** No client_id / client_secret saved yet — first-time setup. */
    data object NotConfigured : StravaAuthStatus()

    /** Credentials saved but no OAuth tokens — user needs to authorize. */
    data object NotSignedIn : StravaAuthStatus()

    /** A code-exchange or token refresh is in flight. */
    data object Authorizing : StravaAuthStatus()

    /** Telly holds working tokens for the user. */
    data class SignedIn(val athleteName: String?) : StravaAuthStatus()

    data class Error(val message: String) : StravaAuthStatus()
}

@Singleton
class StravaAuthState @Inject constructor(
    private val secrets: StravaSecrets,
    private val helper: StravaHelper
) {
    private val _status = MutableStateFlow<StravaAuthStatus>(deriveStatus())
    val status: StateFlow<StravaAuthStatus> = _status.asStateFlow()

    private fun deriveStatus(): StravaAuthStatus = when {
        !secrets.hasCredentials() -> StravaAuthStatus.NotConfigured
        !secrets.hasTokens() -> StravaAuthStatus.NotSignedIn
        else -> StravaAuthStatus.SignedIn(secrets.athleteName)
    }

    /** Re-read the secrets store (e.g., after a write from a different code path). */
    fun refresh() {
        _status.value = deriveStatus()
    }

    fun saveCredentials(clientId: String, clientSecret: String) {
        secrets.clientId = clientId.trim()
        secrets.clientSecret = clientSecret.trim()
        // Credentials changed → existing tokens (issued against old creds) are useless.
        secrets.clearTokens()
        _status.value = deriveStatus()
    }

    fun authorizationUrl(): String? = helper.authorizationUrl()

    suspend fun submitAuthorizationCode(input: String) {
        _status.value = StravaAuthStatus.Authorizing
        val error = helper.exchangeAuthorizationCode(input)
        _status.value = if (error == null) {
            StravaAuthStatus.SignedIn(secrets.athleteName)
        } else {
            StravaAuthStatus.Error(error)
        }
    }

    fun signOut() {
        helper.signOut()
        _status.value = deriveStatus()
    }

    fun clearAll() {
        helper.clearCredentials()
        _status.value = deriveStatus()
    }
}
