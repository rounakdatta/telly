package club.taptappers.telly.strava

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device encrypted storage for Strava OAuth credentials and tokens.
 * Backed by EncryptedSharedPreferences — values are encrypted at rest with
 * AES-256 GCM under a key held in the Android Keystore. Nothing here is ever
 * sent off device; the Helper reads these to call Strava directly.
 */
@Singleton
class StravaSecrets @Inject constructor(
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

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ID, value).apply()
        }

    var clientSecret: String?
        get() = prefs.getString(KEY_CLIENT_SECRET, null)
        set(value) {
            prefs.edit().putString(KEY_CLIENT_SECRET, value).apply()
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

    /** Epoch seconds — Strava's `expires_at` is in seconds, not millis. */
    var expiresAtSec: Long
        get() = prefs.getLong(KEY_EXPIRES_AT_SEC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_EXPIRES_AT_SEC, value).apply()
        }

    var athleteName: String?
        get() = prefs.getString(KEY_ATHLETE_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_ATHLETE_NAME, value).apply()
        }

    fun hasCredentials(): Boolean = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    fun hasTokens(): Boolean = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    fun isAuthorized(): Boolean = hasCredentials() && hasTokens()

    /**
     * Atomic, synchronous write of the token bundle. Strava can rotate the
     * refresh_token on every refresh, so we must persist all four values as
     * one unit — a process kill mid-write must never leave us with a new
     * access_token paired with the old refresh_token.
     */
    fun persistTokens(
        access: String,
        refresh: String,
        expiresAtSec: Long,
        athleteName: String? = null
    ) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .putLong(KEY_EXPIRES_AT_SEC, expiresAtSec)
        if (athleteName != null) {
            editor.putString(KEY_ATHLETE_NAME, athleteName)
        }
        editor.commit()
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT_SEC)
            .remove(KEY_ATHLETE_NAME)
            .commit()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val FILE_NAME = "telly_strava_secrets"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT_SEC = "expires_at_sec"
        private const val KEY_ATHLETE_NAME = "athlete_name"
    }
}
