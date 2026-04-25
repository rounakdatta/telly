package club.taptappers.telly.hevy

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
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

    companion object {
        private const val FILE_NAME = "telly_hevy_secrets"
        private const val KEY_DEV_API_KEY = "dev_api_key"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_ISO = "expires_at_iso"
        private const val KEY_USERNAME = "username"
    }
}
