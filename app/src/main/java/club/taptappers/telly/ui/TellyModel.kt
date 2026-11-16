package club.taptappers.telly.ui

import android.app.Application
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
    val sending: Boolean = false,
    val working: String? = null,
    val note: Note? = null,
    val status: Status? = null,
    val statusLoading: Boolean = false,
    val statusError: String? = null,
) {
    val top: Screen get() = stack.last()
}

/** A line telly says back (what it did, or what went wrong), shown above the composer for a few seconds. */
data class Note(val text: String, val problem: Boolean = false, val id: Long = System.nanoTime())

class TellyModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val api = Api(prefs)
    private val _state = MutableStateFlow(UiState(configured = prefs.configured, day = prefs.cachedDay))
    val state: StateFlow<UiState> = _state
    private var poller: Job? = null
    private var foreground = true

    val server: String get() = prefs.server
    val token: String get() = prefs.token

    init {
        if (prefs.configured) refreshDay()
    }

    // -- navigation ---------------------------------------------------------------------------------------------------
    fun open(screen: Screen) {
        _state.update { it.copy(stack = it.stack + screen, note = null) }
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

    fun clearNote() = _state.update { it.copy(note = null) }

    // -- reading ------------------------------------------------------------------------------------------------------
    fun refreshDay() = viewModelScope.launch {
        try {
            val d = api.day()
            prefs.cachedDay = d
            absorb(d)
            _state.update { it.copy(day = d, dayLoaded = true, offline = null) }
        } catch (e: TellyError) {
            // A degraded path still shows the content: keep the last day, say less, not nothing.
            _state.update { it.copy(dayLoaded = it.day != null, offline = e.message) }
        }
        poll()
    }

    private fun refreshSession(id: String) = viewModelScope.launch {
        try {
            val s = api.session(id)
            _state.update { it.copy(sessions = it.sessions + (s.id to s), offline = null) }
        } catch (e: TellyError) {
            _state.update { it.copy(offline = e.message) }
        }
        poll()
    }

    fun loadLibrary() = viewModelScope.launch {
        try {
            val lib = api.library()
            lib.days.forEach { d -> d.sessions.forEach { s -> _state.update { st -> st.copy(sessions = st.sessions + (s.id to (st.sessions[s.id] ?: s))) } } }
            _state.update { it.copy(library = lib.days, libraryLoaded = true) }
        } catch (e: TellyError) {
            _state.update { it.copy(libraryLoaded = it.library.isNotEmpty(), note = Note(e.message ?: "", problem = true)) }
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

    private fun absorb(d: Day) {
        _state.update { st -> st.copy(sessions = st.sessions + d.sessions.associateBy { it.id }) }
    }

    /** While something is still being saved or written, look again every few seconds; stop when it settles. */
    private fun poll() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                delay(2500)
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
                        is Screen.Playlist -> api.session(top.id).let { s -> _state.update { it.copy(sessions = it.sessions + (s.id to s)) } }
                        else -> api.day().let { d -> prefs.cachedDay = d; absorb(d); _state.update { it.copy(day = d) } }
                    }
                } catch (_: TellyError) {
                }
            }
        }
    }

    // -- writing ------------------------------------------------------------------------------------------------------
    /** Say something to the canvas. On a playlist's page it's about that playlist. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _state.value.sending) return
        val focus = (_state.value.top as? Screen.Playlist)?.id
        val day = focus?.let { _state.value.sessions[it]?.day } ?: "today"
        _state.update { it.copy(sending = true, note = null) }
        viewModelScope.launch {
            try {
                val r = api.prompt(t, focus, day)
                if (r.day.today) prefs.cachedDay = r.day
                absorb(r.day)
                _state.update { st ->
                    st.copy(day = if (r.day.today) r.day else st.day, dayLoaded = true, sending = false, offline = null,
                        note = r.reply?.let { Note(it, problem = r.rough) })
                }
                focus?.let { refreshSession(it) }
            } catch (e: TellyError) {
                _state.update { it.copy(sending = false, note = Note(e.message ?: "Something went wrong.", problem = true)) }
            }
            poll()
        }
    }

    /** Today's subject to learn about ("" clears it). The stories are rewritten to lean on it. */
    fun setLens(text: String) {
        viewModelScope.launch {
            try {
                val d = api.setLens(text.trim())
                prefs.cachedDay = d
                absorb(d)
                _state.update { it.copy(day = d, note = Note(d.lens?.let { l -> "Today you're learning about $l. The stories will lean on it." }
                    ?: "No subject for today, then.")) }
            } catch (e: TellyError) {
                _state.update { it.copy(note = Note(e.message ?: "Couldn't set it.", problem = true)) }
            }
            poll()
        }
    }

    fun tweak(id: String, name: String) = mutate(id, name) { api.tweak(id, name) }
    fun swap(id: String, index: Int) = mutate(id, "swap$index") { api.edit(id, "swap", index) }
    fun remove(id: String, index: Int) = mutate(id, "remove$index") { api.edit(id, "remove", index) }
    fun pin(id: String, index: Int, on: Boolean) = mutate(id, "pin$index") { api.edit(id, if (on) "pin" else "unpin", index) }
    fun undo(id: String) = mutate(id, "undo") { api.undo(id) }
    fun resync(id: String) = mutate(id, "sync") { api.resync(id) }

    private fun mutate(id: String, what: String, call: suspend () -> Session) {
        if (_state.value.working != null) return
        _state.update { it.copy(working = what, note = null) }
        viewModelScope.launch {
            try {
                val s = call()
                _state.update { st ->
                    st.copy(sessions = st.sessions + (s.id to s), working = null,
                        day = st.day?.let { d -> d.copy(sessions = d.sessions.map { if (it.id == s.id) s else it }) })
                }
            } catch (e: TellyError) {
                _state.update { it.copy(working = null, note = Note(e.message ?: "Something went wrong.", problem = true)) }
            }
            poll()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                api.delete(id)
                _state.update { st ->
                    st.copy(sessions = st.sessions - id, stack = st.stack.filterNot { it == Screen.Playlist(id) }.ifEmpty { listOf(Screen.Today) },
                        day = st.day?.let { d -> d.copy(sessions = d.sessions.filterNot { it.id == id }) },
                        library = st.library.map { d -> d.copy(sessions = d.sessions.filterNot { it.id == id }) },
                        note = Note("Deleted, here and on YouTube Music."))
                }
            } catch (e: TellyError) {
                _state.update { it.copy(note = Note(e.message ?: "Couldn't delete it.", problem = true)) }
            }
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
