package club.taptappers.telly.hevy

import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts a [WebView] pointed at https://hevy.com/login so the user can sign
 * in via Hevy's real web flow — including Google reCAPTCHA, which we cannot
 * mint a valid token for from native code. After login, Hevy's response sets
 * an `auth2.0-token` cookie on the hevy.com domain. We watch for that cookie
 * on every page-load event, parse it (URL-encoded JSON containing the same
 * `access_token` / `refresh_token` / `expires_at` fields the V2 API uses),
 * persist via [HevyAuthState], and finish the activity.
 *
 * The Hevy `HevyAuthState` is `@Singleton` so the persisted tokens are
 * immediately visible to the rest of the app — `MainActivity`'s injected
 * instance and ours are the same object.
 */
@AndroidEntryPoint
class HevyWebLoginActivity : ComponentActivity() {

    @Inject
    lateinit var hevyAuthState: HevyAuthState

    private lateinit var webView: WebView

    /**
     * Last cookie value we attempted to process. Prevents looping on a
     * malformed cookie that won't ever parse — without this, every
     * subsequent `onPageFinished` would re-trigger the same failed parse.
     */
    private var lastTriedCookieValue: String? = null

    /** Set once we've successfully finished — guards against double-finish. */
    private var done: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable cookie capture for the WebView's CookieManager BEFORE the
        // WebView starts loading — otherwise Set-Cookie headers are dropped.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        webView = WebView(this).apply {
            // reCAPTCHA needs both JS and DOM storage. Third-party cookies
            // are required because reCAPTCHA loads from google.com inside a
            // hevy.com page.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!done) tryExtractAndComplete()
                }
            }

            loadUrl(LOGIN_URL)
        }
        setContentView(webView)

        // In-WebView back navigation, then activity dismissal.
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    private fun tryExtractAndComplete() {
        val rawValue = readAuthCookieValue() ?: return
        if (rawValue == lastTriedCookieValue) return
        lastTriedCookieValue = rawValue

        lifecycleScope.launch {
            val err = hevyAuthState.handleWebLoginCookie(rawValue)
            if (err == null) {
                done = true
                Toast.makeText(this@HevyWebLoginActivity, "Signed in to Hevy", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Log.w(TAG, "handleWebLoginCookie returned error: $err")
                Toast.makeText(this@HevyWebLoginActivity, "Hevy: $err", Toast.LENGTH_LONG).show()
                // lastTriedCookieValue stays set — re-trying the same value
                // is pointless. If Hevy issues a new cookie on the next
                // navigation it'll have a different value and we'll try it.
            }
        }
    }

    private fun readAuthCookieValue(): String? {
        val cookies = CookieManager.getInstance().getCookie(HEVY_DOMAIN) ?: return null
        // CookieManager returns "name1=value1; name2=value2; ..."
        for (cookie in cookies.split("; ")) {
            if (cookie.startsWith(AUTH_COOKIE_PREFIX)) {
                val v = cookie.substring(AUTH_COOKIE_PREFIX.length)
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HevyWebLogin"
        private const val HEVY_DOMAIN = "https://hevy.com"
        private const val LOGIN_URL = "$HEVY_DOMAIN/login"
        private const val AUTH_COOKIE_PREFIX = "auth2.0-token="
    }
}
