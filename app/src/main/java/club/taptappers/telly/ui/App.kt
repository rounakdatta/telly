package club.taptappers.telly.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import club.taptappers.telly.data.Playing
import club.taptappers.telly.data.locate
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun App(model: TellyModel) {
    val st by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var lensOpen by rememberSaveable { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
            draft = ""
            model.send(said)
        }
    }
    val mic: () -> Unit = {
        try {
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, if (st.top is Screen.Playlist) "How should it change?" else "What's today?"))
        } catch (_: ActivityNotFoundException) {
        }
    }

    // An exchange stays while telly works and while its playlist is still being saved or written about; once all of that
    // has settled it stays long enough to read, then gets out of the way. A failure stays longer.
    val ex = st.exchange
    val exSession = ex?.target?.let { st.sessions[it] }
    val settled = exSession?.inProgress == null
    LaunchedEffect(ex?.id, ex?.phase, settled) {
        if (ex == null || ex.phase == Phase.WORKING) return@LaunchedEffect
        delay(if (ex.phase == Phase.FAILED) 12_000 else if (settled) 7_000 else 60_000)
        model.dismissExchange(ex.id)
    }

    // The player app itself (YouTube Music, Spotify): telly can show what plays, but pausing and skipping happen there.
    val openPlayer: (String) -> Unit = { pkg ->
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity) }
    }

    if (!st.configured) {
        SettingsScreen(true, model.server, model.token, st.status, st.statusLoading, st.statusError, model::connect, null)
        return
    }

    BackHandler(enabled = st.stack.size > 1) { model.back() }
    val haze = remember { HazeState() }
    // The song playing, placed in one of telly's playlists: the one open (or open beneath the words) first, then today's.
    val openPlaylist = (st.stack.lastOrNull { it is Screen.Playlist } as? Screen.Playlist)?.id
    val spot = remember(st.playing?.key, st.sessions, st.day, openPlaylist) {
        val today = st.day?.sessions.orEmpty().sortedByDescending { it.created }
        locate(st.playing, (listOfNotNull(openPlaylist?.let { st.sessions[it] }) + today + st.sessions.values).distinctBy { it.id })
    }
    // Its cover: the one in its telly playlist (YouTube Music's), else Apple's, found with its lyrics.
    val art = spot?.track?.thumb ?: st.lyrics?.art
    val miniShown = st.playing != null && ex == null
    // Room under the lists for the composer and what sits on it (the player), measured rather than guessed.
    val density = LocalDensity.current
    var onComposer by remember { mutableStateOf(0.dp) }
    val composerSpace = 96.dp + onComposer

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = st.top,
            transitionSpec = {
                when {
                    // The words rise from the player below, and settle back into it.
                    targetState == Screen.Lyrics -> (slideInVertically(tween(320)) { it / 4 } + fadeIn(tween(240))) togetherWith fadeOut(tween(200))
                    initialState == Screen.Lyrics -> fadeIn(tween(240)) togetherWith (slideOutVertically(tween(280)) { it / 4 } + fadeOut(tween(220)))
                    else -> {
                        val forward = targetState !is Screen.Today
                        (slideInHorizontally { if (forward) it / 6 else -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 8 else it / 8 } + fadeOut())
                    }
                }
            },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Today -> Box(Modifier.fillMaxSize()) {
                    Ambient(hourTint(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
                    TodayScreen(
                        day = st.day, loaded = st.dayLoaded, offline = st.offline, haze = haze,
                        onOpen = { model.open(Screen.Playlist(it.id)) }, onPlay = openUrl,
                        onLibrary = { model.open(Screen.Library) }, onExample = { draft = it },
                        onLens = { lensOpen = true }, onLensIdea = { model.setLens(it) }, bottomSpace = composerSpace,
                    )
                }
                is Screen.Playlist -> {
                    val s = st.sessions[screen.id]
                    if (s != null) {
                        // While a message is being read the chips rest too: a tap would be dropped, so it shouldn't look tappable.
                        PlaylistScreen(s, st.working ?: "message".takeIf { st.sending }, updating = st.updating == s.id, fresh = st.fresh[s.id].orEmpty(), haze = haze,
                            playing = spot?.takeIf { it.session.id == s.id }?.let { it.index to (st.playing?.playing == true) },
                            actions = PlaylistActions(
                                onBack = model::back, onOpenUrl = openUrl,
                                onTweak = { key, label -> model.tweak(s.id, key, label) }, onUndo = { model.undo(s.id) },
                                onSwap = { i -> s.tracks.getOrNull(i)?.let { model.swap(s.id, i, it.id, it.title) } },
                                onRemove = { i -> s.tracks.getOrNull(i)?.let { model.remove(s.id, i, it.id, it.title) } },
                                onPin = { i, on -> s.tracks.getOrNull(i)?.let { model.pin(s.id, i, it.id, it.title, on) } },
                                onResync = { model.resync(s.id) }, onDelete = { model.delete(s.id) },
                                onRecompose = { model.recompose(s.id) }, onQuick = { model.quick(s.id) },
                            ), bottomSpace = composerSpace)
                    } else {
                        Ambient(hourTint(12))
                    }
                }
                Screen.Library -> LibraryScreen(st.library, st.libraryLoaded, onBack = model::back,
                    onOpen = { model.open(Screen.Playlist(it.id)) }, onSettings = { model.open(Screen.Settings) },
                    onMore = if (st.libraryEnd) null else ({ model.loadLibrary(more = true) }))
                Screen.Settings -> SettingsScreen(false, model.server, model.token, st.status, st.statusLoading, st.statusError,
                    model::connect, onBack = model::back)
                Screen.Lyrics -> LyricsScreen(
                    playing = st.playing, lyrics = st.lyrics, error = st.lyricsError, spot = spot, art = art,
                    onClose = model::back, onOpenPlayer = openPlayer, onRetry = model::retryLyrics, onOpenUrl = openUrl,
                    suggestion = st.day?.sessions?.filter { (it.ytm.play ?: it.ytm.url) != null }?.maxByOrNull { it.created }
                        ?.let { it.name to (it.ytm.play ?: it.ytm.url).orEmpty() },
                )
            }
        }

        if (lensOpen) {
            LensSheet(st.day?.lens, st.day?.lensIdeas.orEmpty(), onSet = { model.setLens(it) }, onDismiss = { lensOpen = false })
        }

        val top = st.top
        if (top == Screen.Today || top is Screen.Playlist) {
            Composer(
                text = draft, onText = { draft = it },
                placeholder = if (top is Screen.Playlist) "Change this playlist…" else "What's today?",
                sending = st.sending,
                // What was sent leaves the box at once (the exchange card shows it); if it fails, it comes back.
                onSend = { val said = draft; draft = ""; model.send(said) { ok -> if (!ok && draft.isEmpty()) draft = said } },
                onMic = mic, haze = haze,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
                above = {
                    AnimatedVisibility(visible = ex != null, enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
                        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 }) {
                        // Hold on to the last exchange while it animates out (a plain holder: no state write in composition).
                        val last = remember { arrayOfNulls<Exchange>(1) }
                        if (ex != null) last[0] = ex
                        (ex ?: last[0])?.let { e ->
                            ExchangeCard(e, e.target?.let { st.sessions[it] }, haze,
                                onOpen = if (top == Screen.Today) ({ id: String -> model.dismissExchange(); model.open(Screen.Playlist(id)) }) else null,
                                onDismiss = { model.dismissExchange(e.id) }, modifier = Modifier.padding(bottom = 10.dp))
                        }
                    }
                    Column(Modifier.onSizeChanged { onComposer = with(density) { it.height.toDp() } }) {
                        // What's playing, while nothing is being said (the exchange has the floor while it lasts).
                        AnimatedVisibility(visible = miniShown, enter = fadeIn(tween(220)) + expandVertically(tween(260)),
                            exit = fadeOut(tween(180)) + shrinkVertically(tween(220))) {
                            val lastPlaying = remember { arrayOfNulls<Playing>(1) }
                            st.playing?.let { lastPlaying[0] = it }
                            (st.playing ?: lastPlaying[0])?.let { p ->
                                MiniPlayer(p, st.lyrics, art, onOpen = { model.open(Screen.Lyrics) }, haze = haze,
                                    modifier = Modifier.padding(bottom = 10.dp))
                            }
                        }
                    }
                },
            )
        }
    }
}
