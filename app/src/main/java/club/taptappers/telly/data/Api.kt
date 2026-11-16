package club.taptappers.telly.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/** A failure worded for the person holding the phone, not for a log. */
class TellyError(message: String) : Exception(message)

class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // a message goes through the language model; it can take a while
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun day(day: String = "today"): Day = get("/api/day/$day")
    suspend fun session(id: String): Session = get("/api/session/$id")
    suspend fun library(before: String? = null): Library = get("/api/library" + (before?.let { "?before=$it" } ?: ""))
    suspend fun status(): Status = get("/api/status")

    suspend fun prompt(text: String, focus: String?, day: String = "today"): PromptResult =
        post("/api/day/$day/prompt", buildJsonObject { put("text", text); focus?.let { put("focus", it) } })

    suspend fun tweak(id: String, tweak: String): Session = post("/api/session/$id/tweak", buildJsonObject { put("tweak", tweak) })
    suspend fun edit(id: String, op: String, index: Int): Session =
        post("/api/session/$id/edit", buildJsonObject { put("op", op); put("index", index) })
    suspend fun undo(id: String): Session = post("/api/session/$id/undo", JsonObject(emptyMap()))
    suspend fun resync(id: String): Session = post("/api/session/$id/sync", JsonObject(emptyMap()))
    suspend fun delete(id: String) {
        call(Request.Builder().url(url("/api/session/$id")).delete().build())
    }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(call(Request.Builder().url(url(path)).get().build()))

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T =
        json.decodeFromString(call(Request.Builder().url(url(path)).post(body.toString().toRequestBody(jsonType)).build()))

    private fun url(path: String) = prefs.server.trimEnd('/') + path

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        val authed = req.newBuilder().header("Authorization", "Bearer ${prefs.token}").build()
        try {
            http.newCall(authed).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> body
                    r.code == 401 -> throw TellyError("The server didn't accept the token. Check it in Settings.")
                    r.code == 503 -> throw TellyError(errorOf(body) ?: "The server isn't ready yet.")
                    else -> throw TellyError(errorOf(body) ?: "The server answered ${r.code}.")
                }
            }
        } catch (e: TellyError) {
            throw e
        } catch (e: UnknownHostException) {
            throw TellyError("Can't find the server. Is Tailscale on?")
        } catch (e: ConnectException) {
            throw TellyError("Can't reach the server. Is Tailscale on?")
        } catch (e: SocketTimeoutException) {
            throw TellyError("The server is taking too long. Try again in a moment.")
        } catch (e: IOException) {
            throw TellyError("No connection to the server right now.")
        }
    }

    private fun errorOf(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.let { (it as? JsonPrimitive)?.content }
    }.getOrNull()
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("telly", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", null) ?: DEFAULT_SERVER
        set(v) = p.edit().putString("server", v.trim()).apply()

    var token: String
        get() = p.getString("token", "").orEmpty()
        set(v) = p.edit().putString("token", v.trim()).apply()

    val configured: Boolean get() = token.isNotBlank()

    /** The last day seen, so opening the app shows it at once, and still shows it offline. */
    var cachedDay: Day?
        get() = p.getString("day", null)?.let { runCatching { json.decodeFromString<Day>(it) }.getOrNull() }
        set(v) = p.edit().putString("day", v?.let { json.encodeToString(Day.serializer(), it) }).apply()

    companion object {
        const val DEFAULT_SERVER = "https://telly.taptappers.club"
    }
}
