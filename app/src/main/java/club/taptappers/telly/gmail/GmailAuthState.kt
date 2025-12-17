package club.taptappers.telly.gmail

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.gmail.GmailScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class GmailAuthStatus {
    data object NotSignedIn : GmailAuthStatus()
    data class SignedIn(val email: String) : GmailAuthStatus()
    data object SigningIn : GmailAuthStatus()
    data class Error(val message: String) : GmailAuthStatus()
}

@Singleton
class GmailAuthState @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gmailHelper: GmailHelper
) {
    private val _authStatus = MutableStateFlow<GmailAuthStatus>(GmailAuthStatus.NotSignedIn)
    val authStatus: StateFlow<GmailAuthStatus> = _authStatus.asStateFlow()

    init {
        checkCurrentAuthStatus()
    }

    fun checkCurrentAuthStatus() {
        val account = gmailHelper.getSignedInAccount()
        _authStatus.value = if (account != null &&
            GoogleSignIn.hasPermissions(account, Scope(GmailScopes.GMAIL_READONLY))) {
            GmailAuthStatus.SignedIn(account.email ?: "Unknown")
        } else {
            GmailAuthStatus.NotSignedIn
        }
    }

    fun getSignInIntent(): Intent {
        _authStatus.value = GmailAuthStatus.SigningIn
        return gmailHelper.getSignInClient().signInIntent
    }

    fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            _authStatus.value = GmailAuthStatus.SignedIn(account.email ?: "Unknown")
        } catch (e: ApiException) {
            val errorMsg = when (e.statusCode) {
                7 -> "NETWORK_ERROR (7): Check internet connection"
                8 -> "INTERNAL_ERROR (8): Google Play Services issue"
                10 -> "DEVELOPER_ERROR (10): SHA-1 fingerprint not configured in Google Cloud Console"
                12 -> "SIGN_IN_CANCELLED (12): User cancelled"
                12500 -> "SIGN_IN_CURRENTLY_IN_PROGRESS (12500)"
                12501 -> "SIGN_IN_CANCELLED (12501): User cancelled"
                12502 -> "SIGN_IN_FAILED (12502): Sign in attempt failed"
                else -> "Unknown error (${e.statusCode}): ${e.message}"
            }
            _authStatus.value = GmailAuthStatus.Error(errorMsg)
        }
    }

    fun signOut() {
        gmailHelper.getSignInClient().signOut().addOnCompleteListener {
            _authStatus.value = GmailAuthStatus.NotSignedIn
        }
    }
}
