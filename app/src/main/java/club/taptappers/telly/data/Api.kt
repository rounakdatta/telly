package club.taptappers.telly.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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

/**
 * The server, from the phone. Every response is read AND decoded off the main thread (a day's JSON is tens of
 * kilobytes, and decoding it on the main thread every few seconds while something syncs was a source of jank), and
 * every type names its serializer outright, so the release build's shrinker can't break a lookup it can't see.
 */
class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // a message goes through the language model; the server gives up first
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun day(day: String = "today"): Day = get("/api/day/$day", Day.serializer())
    suspend fun session(id: String): Session = get("/api/session/$id", Session.serializer())
    suspend fun library(before: String? = null): Library = get("/api/library" + (before?.let { "?before=$it" } ?: ""), Library.serializer())
    suspend fun status(): Status = get("/api/status", Status.serializer())

    suspend fun prompt(text: String, focus: String?, day: String = "today"): PromptResult =
        post("/api/day/$day/prompt", buildJsonObject { put("text", text); focus?.let { put("focus", it) } }, PromptResult.serializer())

    suspend fun setLens(text: String, day: String = "today"): Day =
        post("/api/day/$day/lens", buildJsonObject { put("text", text) }, Day.serializer())

    suspend fun tweak(id: String, tweak: String): Session =
        post("/api/session/$id/tweak", buildJsonObject { put("tweak", tweak) }, Session.serializer())
    suspend fun edit(id: String, op: String, index: Int, trackId: String): Session =
        post("/api/session/$id/edit", buildJsonObject { put("op", op); put("index", index); put("id", trackId) }, Session.serializer())
    suspend fun undo(id: String): Session = post("/api/session/$id/undo", JsonObject(emptyMap()), Session.serializer())
    suspend fun resync(id: String): Session = post("/api/session/$id/sync", JsonObject(emptyMap()), Session.serializer())
    suspend fun delete(id: String) {
        call { url -> Request.Builder().url(url("/api/session/$id")).delete().build() }
    }

    private suspend fun <T> get(path: String, s: KSerializer<T>): T =
        decode(call { url -> Request.Builder().url(url(path)).get().build() }, s)

    private suspend fun <T> post(path: String, body: JsonObject, s: KSerializer<T>): T =
        decode(call { url -> Request.Builder().url(url(path)).post(body.toString().toRequestBody(jsonType)).build() }, s)

    private suspend fun <T> decode(body: String, s: KSerializer<T>): T = withContext(Dispatchers.Default) {
        try {
            json.decodeFromString(s, body)
        } catch (e: SerializationException) {
            throw TellyError("The server sent something this version of telly doesn't understand.")
        } catch (e: IllegalArgumentException) {
            throw TellyError("The server sent something this version of telly doesn't understand.")
        }
    }

    /**
     * Every failure becomes a TellyError worded for a person, including the ones that happen before anything is sent:
     * a malformed address or a token with a stray character must show a message, never crash the app on every launch.
     */
    private suspend fun call(build: ((String) -> String) -> Request): String = withContext(Dispatchers.IO) {
        val base = prefs.server.trim().trimEnd('/')
        if (!base.startsWith("https://")) throw TellyError("The server address must start with https://. Check it in Settings.")
        val authed = try {
            build { path -> base + path }.newBuilder().header("Authorization", "Bearer ${prefs.token}").build()
        } catch (e: IllegalArgumentException) {
            throw TellyError("That server address or token isn't valid. Check them in Settings.")
        }
        try {
            http.newCall(authed).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> body
                    r.code == 401 -> throw TellyError("The server didn't accept the token. Check it in Settings.")
                    r.code == 429 -> throw TellyError(errorOf(body) ?: "That's a lot of requests. Give it a few minutes.")
                    r.code == 503 -> throw TellyError(errorOf(body) ?: "The server isn't ready yet.")
                    else -> throw TellyError(errorOf(body) ?: "The server answered ${r.code}.")
                }
            }
        } catch (e: TellyError) {
            throw e
        } catch (e: UnknownHostException) {
            throw TellyError("Can't find the server. Check your connection.")
        } catch (e: ConnectException) {
            throw TellyError("Can't reach the server. Check your connection.")
        } catch (e: SocketTimeoutException) {
            throw TellyError("The server is taking too long. Try again in a moment.")
        } catch (e: IOException) {
            throw TellyError("No connection to the server right now.")
        } catch (e: IllegalArgumentException) {
            throw TellyError("That server address or token isn't valid. Check them in Settings.")
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

    /** The last day seen, so opening the app shows it at once, and still shows it offline. Read and written off the
     *  main thread. */
    suspend fun loadDay(): Day? = withContext(Dispatchers.Default) {
        p.getString("day", null)?.let { runCatching { json.decodeFromString(Day.serializer(), it) }.getOrNull() }
    }

    suspend fun saveDay(d: Day) = withContext(Dispatchers.Default) {
        p.edit().putString("day", json.encodeToString(Day.serializer(), d)).apply()
    }

    companion object {
        const val DEFAULT_SERVER = "https://telly.taptappers.club"
    }
}
