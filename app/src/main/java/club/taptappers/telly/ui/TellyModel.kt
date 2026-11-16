package club.taptappers.telly.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import club.taptappers.telly.data.Api
import club.taptappers.telly.data.Day
import club.taptappers.telly.data.LibraryDay
import club.taptappers.telly.data.Prefs
import club.taptappers.telly.data.Session
import club.taptappers.telly.data.Status
import club.taptappers.telly.data.TellyError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Today : Screen
    data class Playlist(val id: String) : Screen
    data object Library : Screen
    data object Settings : Screen
}

enum class Phase { WORKING, DONE, FAILED }

/**
 * One exchange with telly, shown above the composer: what you said (echoed the instant you send it), what telly is
 * doing (honestly: it is reading your words until the reply arrives), and then what changed. `said` is null for things
 * telly says on its own ("Deleted.").
 */
data class Exchange(
    val said: String?,
    val phase: Phase,
    val reply: String? = null,
    val added: Int = 0,
    val removed: Int = 0,
    /** The playlist this was about; on the canvas, the card offers to open it. */
    val target: String? = null,
    /** Words for the wait: a message goes through the language model, a chip doesn't. */
    val slow: Boolean = true,
    val startedAt: Long = SystemClock.elapsedRealtime(),
    val id: Long = System.nanoTime(),
)

data class UiState(
    val configured: Boolean,
    val stack: List<Screen> = listOf(Screen.Today),
    val day: Day? = null,
    /** "Not loaded" is not "empty": the blank-canvas invitation waits for this. */
    val dayLoaded: Boolean = false,
    val offline: String? = null,
    val sessions: Map<String, Session> = emptyMap(),
    val library: List<LibraryDay> = emptyList(),
    val libraryLoaded: Boolean = false,
    val libraryEnd: Boolean = false,
    val exchange: Exchange? = null,
    /** The playlist a change is being made to right now (its list dims and a progress line runs over it). */
    val updating: String? = null,
    /** Which chip or edit is in flight, so only that control spins. */
    val working: String? = null,
    /** Songs that just arrived in a playlist, so they can glow for a moment and then settle. */
    val fresh: Map<String, Set<String>> = emptyMap(),
    val status: Status? = null,
    val statusLoading: Boolean = false,
    val statusError: String? = null,
) {
    val top: Screen get() = stack.last()
    val sending: Boolean get() = exchange?.phase == Phase.WORKING
}

class TellyModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val api = Api(prefs)
    private val _state = MutableStateFlow(UiState(configured = prefs.configured))
    val state: StateFlow<UiState> = _state
    private var poller: Job? = null
    private var foreground = true

    val server: String get() = prefs.server
    val token: String get() = prefs.token

    init {
        viewModelScope.launch {
            prefs.loadDay()?.let { d -> _state.update { if (it.day == null) it.copy(day = d) else it } }
            if (prefs.configured) refreshDay()
        }
    }

    // -- navigation ---------------------------------------------------------------------------------------------------
    fun open(screen: Screen) {
        if (_state.value.top == screen) return // a double tap opens it once
        _state.update { it.copy(stack = it.stack + screen) }
        when (screen) {
            is Screen.Playlist -> refreshSession(screen.id)
            Screen.Library -> loadLibrary()
            Screen.Settings -> loadStatus()
            Screen.Today -> refreshDay()
        }
    }

    fun back() {
        _state.update { if (it.stack.size > 1) it.copy(stack = it.stack.dropLast(1)) else it }
        if (_state.value.top == Screen.Today) refreshDay()
    }

    fun setForeground(on: Boolean) {
        foreground = on
        if (on && prefs.configured) {
            when (val top = _state.value.top) {
                is Screen.Playlist -> refreshSession(top.id)
                else -> refreshDay()
            }
        }
    }

    fun dismissExchange(id: Long? = null) = _state.update { if (id == null || it.exchange?.id == id) it.copy(exchange = null) else it }

    // -- reading ------------------------------------------------------------------------------------------------------
    fun refreshDay() = viewModelScope.launch {
        try {
            putDay(api.day())
            _state.update { it.copy(dayLoaded = true, offline = null) }
        } catch (e: TellyError) {
            // A degraded path still shows the content: keep the last day, say less, not nothing.
            _state.update { it.copy(dayLoaded = it.day != null, offline = e.message) }
        }
        poll()
    }

    private fun refreshSession(id: String) = viewModelScope.launch {
        try {
            putSession(api.session(id))
            _state.update { it.copy(offline = null) }
        } catch (e: TellyError) {
            _state.update { it.copy(offline = e.message) }
        }
        poll()
    }

    /** Earlier days, a fortnight at a time; `more` continues from the oldest day shown. */
    fun loadLibrary(more: Boolean = false) = viewModelScope.launch {
        try {
            val before = if (more) _state.value.library.lastOrNull()?.day else null
            val lib = api.library(before)
            _state.update { st ->
                st.copy(sessions = lib.days.flatMap { it.sessions }.fold(st.sessions) { m, s -> if (s.id in m) m else m + (s.id to s) },
                    library = if (more) (st.library + lib.days).distinctBy { d -> d.day } else lib.days,
                    libraryLoaded = true, libraryEnd = lib.days.size < 14)
            }
        } catch (e: TellyError) {
            _state.update { it.copy(libraryLoaded = it.library.isNotEmpty(), exchange = Exchange(null, Phase.FAILED, e.message)) }
        }
    }

    fun loadStatus() = viewModelScope.launch {
        if (!prefs.configured) return@launch
        _state.update { it.copy(statusLoading = true, statusError = null) }
        try {
            val s = api.status()
            _state.update { it.copy(status = s, statusLoading = false) }
        } catch (e: TellyError) {
            _state.update { it.copy(statusLoading = false, statusError = e.message) }
        }
    }

    /** Take in a day. Nothing is replaced unless it actually changed, so a poll that finds nothing new redraws nothing. */
    private suspend fun putDay(d: Day) {
        if (_state.value.day == d) return
        _state.update { st ->
            st.copy(day = d, sessions = d.sessions.fold(st.sessions) { m, s -> if (m[s.id] == s) m else m + (s.id to s) })
        }
        if (d.today) prefs.saveDay(d)
    }

    private suspend fun putSession(s: Session) {
        if (_state.value.sessions[s.id] == s) return
        _state.update { st ->
            st.copy(sessions = st.sessions + (s.id to s), day = st.day?.let { d ->
                if (d.sessions.any { it.id == s.id }) d.copy(sessions = d.sessions.map { if (it.id == s.id) s else it }) else d
            })
        }
        // The canvas shown at a cold start is the cached day: keep it as fresh as what's on screen.
        _state.value.day?.takeIf { d -> d.today && d.sessions.any { it.id == s.id } }?.let { prefs.saveDay(it) }
    }

    /** A day's playlists from a reply. Today's replaces the canvas; an earlier day's only updates its own playlists (and
     *  its place in the library), so a change made to an old playlist shows wherever that playlist is shown. */
    private suspend fun absorb(d: Day) {
        if (d.today) return putDay(d)
        _state.update { st ->
            st.copy(sessions = d.sessions.fold(st.sessions) { m, s -> if (m[s.id] == s) m else m + (s.id to s) },
                library = st.library.map { ld -> if (ld.day == d.day) ld.copy(sessions = d.sessions) else ld })
        }
    }

    /** A playlist that no longer exists: off every list, and off the screen stack (back to where he came from). */
    private suspend fun forget(id: String) {
        _state.update { st ->
            st.copy(sessions = st.sessions - id, stack = st.stack.filterNot { it == Screen.Playlist(id) }.ifEmpty { listOf(Screen.Today) },
                day = st.day?.let { d -> d.copy(sessions = d.sessions.filterNot { it.id == id }) },
                library = st.library.map { d -> d.copy(sessions = d.sessions.filterNot { it.id == id }) })
        }
        _state.value.day?.takeIf { it.today }?.let { prefs.saveDay(it) }
    }

    /** While something is still being saved or written, look again every few seconds; stop when it settles. */
    private fun poll() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                val st = _state.value
                val settling = when (val top = st.top) {
                    is Screen.Playlist -> st.sessions[top.id]?.settling == true
                    Screen.Today -> st.day?.sessions?.any { it.settling } == true
                    else -> false
                }
                if (!settling) break
                if (!foreground) continue
                try {
                    when (val top = st.top) {
                        is Screen.Playlist -> putSession(api.session(top.id))
                        else -> putDay(api.day())
                    }
                } catch (_: TellyError) {
                }
            }
        }
    }

    // -- writing ------------------------------------------------------------------------------------------------------
    /** Say something to the canvas. On a playlist's page it's about that playlist. `onSent` hears whether it went
     *  through, so the words stay in the box when it didn't. */
    fun send(text: String, onSent: (Boolean) -> Unit = {}) {
        val t = text.trim()
        if (t.isEmpty() || _state.value.sending || _state.value.working != null) return
        val focus = (_state.value.top as? Screen.Playlist)?.id
        val day = focus?.let { _state.value.sessions[it]?.day } ?: "today"
        val before = snapshot()
        val ex = Exchange(t, Phase.WORKING, target = focus)
        _state.update { it.copy(exchange = ex, updating = focus) }
        viewModelScope.launch {
            try {
                val r = api.prompt(t, focus, day)
                absorb(r.day)
                // "Delete this one": the playlist on screen is gone, so its page closes and there's nothing to open.
                val gone = focus != null && r.focus == null && r.day.sessions.none { it.id == focus }
                if (gone) forget(focus!!)
                val target = if (gone) null else r.focus ?: focus
                if (target != null && r.day.sessions.none { it.id == target }) putSession(api.session(target))
                val now = target?.let { _state.value.sessions[it] }
                val (added, removed) = diff(before[target], now)
                markFresh(target, before[target], now)
                _state.update { st ->
                    st.copy(dayLoaded = true, offline = null, updating = null,
                        exchange = if (st.exchange?.id == ex.id) ex.copy(phase = Phase.DONE, reply = r.reply,
                            added = added, removed = removed, target = target) else st.exchange)
                }
                onSent(true)
            } catch (e: TellyError) {
                _state.update { st ->
                    st.copy(updating = null, exchange = if (st.exchange?.id == ex.id) ex.copy(phase = Phase.FAILED, reply = e.message) else st.exchange)
                }
                onSent(false)
            }
            poll()
        }
    }

    /** Today's subject to learn about ("" clears it). The stories are rewritten to lean on it. */
    fun setLens(text: String) {
        val ex = Exchange(if (text.isBlank()) "No subject today" else "Learn about $text", Phase.WORKING, slow = false)
        _state.update { it.copy(exchange = ex) }
        viewModelScope.launch {
            try {
                val d = api.setLens(text.trim())
                putDay(d)
                _state.update { it.copy(exchange = ex.copy(phase = Phase.DONE,
                    reply = d.lens?.let { l -> "Today's subject: $l. The stories will lean on it." } ?: "No subject for today, then.")) }
            } catch (e: TellyError) {
                _state.update { it.copy(exchange = ex.copy(phase = Phase.FAILED, reply = e.message)) }
            }
            poll()
        }
    }

    fun tweak(id: String, name: String, label: String) = mutate(id, name, label) { api.tweak(id, name) }
    fun swap(id: String, index: Int, trackId: String, title: String) =
        mutate(id, "swap$index", "Swap $title") { api.edit(id, "swap", index, trackId) }
    fun remove(id: String, index: Int, trackId: String, title: String) =
        mutate(id, "remove$index", "Remove $title") { api.edit(id, "remove", index, trackId) }
    fun pin(id: String, index: Int, trackId: String, title: String, on: Boolean) =
        mutate(id, "pin$index", if (on) "Keep $title in place" else "Let $title change") { api.edit(id, if (on) "pin" else "unpin", index, trackId) }
    fun undo(id: String) = mutate(id, "undo", "Undo") { api.undo(id) }
    fun resync(id: String) = mutate(id, "sync", "Save it to the services again") { api.resync(id) }

    private fun mutate(id: String, what: String, label: String, call: suspend () -> Session) {
        if (_state.value.working != null || _state.value.sending) return
        val before = _state.value.sessions[id]
        val ex = Exchange(label, Phase.WORKING, target = id, slow = false)
        _state.update { it.copy(working = what, updating = id, exchange = ex) }
        viewModelScope.launch {
            try {
                val s = call()
                putSession(s.copy(note = null))
                val (added, removed) = diff(before, s)
                markFresh(id, before, s)
                _state.update { st ->
                    st.copy(working = null, updating = null, exchange = if (st.exchange?.id == ex.id)
                        ex.copy(phase = Phase.DONE, reply = s.note ?: s.log.lastOrNull()?.reply, added = added, removed = removed) else st.exchange)
                }
            } catch (e: TellyError) {
                _state.update { st -> st.copy(working = null, updating = null,
                    exchange = if (st.exchange?.id == ex.id) ex.copy(phase = Phase.FAILED, reply = e.message) else st.exchange) }
            }
            poll()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                api.delete(id)
                forget(id)
                _state.update { it.copy(exchange = Exchange(null, Phase.DONE, "Deleted, here and on the music services.")) }
            } catch (e: TellyError) {
                _state.update { it.copy(exchange = Exchange(null, Phase.FAILED, e.message ?: "Couldn't delete it.")) }
            }
        }
    }

    // -- what changed ----------------------------------------------------------------------------------------------------
    private fun snapshot(): Map<String?, Session?> = _state.value.sessions.mapKeys { it.key as String? }

    private fun diff(before: Session?, after: Session?): Pair<Int, Int> {
        if (after == null) return 0 to 0
        val a = after.tracks.map { it.id }.toSet()
        val b = before?.tracks?.map { it.id }?.toSet() ?: emptySet()
        return (a - b).size to (b - a).size
    }

    /** Songs that just arrived glow for a few seconds; a new playlist's songs don't (all of them are new). */
    private fun markFresh(id: String?, before: Session?, after: Session?) {
        if (id == null || before == null || after == null) return
        val fresh = after.tracks.map { it.id }.toSet() - before.tracks.map { it.id }.toSet()
        if (fresh.isEmpty()) return
        _state.update { it.copy(fresh = it.fresh + (id to fresh)) }
        viewModelScope.launch {
            delay(6000)
            _state.update { st -> if (st.fresh[id] == fresh) st.copy(fresh = st.fresh - id) else st }
        }
    }

    // -- settings -----------------------------------------------------------------------------------------------------
    fun connect(server: String, token: String) {
        prefs.server = server.ifBlank { Prefs.DEFAULT_SERVER }
        prefs.token = token
        _state.update { it.copy(statusLoading = true, statusError = null) }
        viewModelScope.launch {
            try {
                val s = api.status()
                _state.update { it.copy(configured = true, status = s, statusLoading = false) }
                refreshDay()
            } catch (e: TellyError) {
                _state.update { it.copy(statusLoading = false, statusError = e.message) }
            }
        }
    }
}
