package club.taptappers.telly.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    // A reply stays long enough to read, then gets out of the way.
    LaunchedEffect(st.note?.id) {
        if (st.note != null) {
            delay(if (st.note!!.problem) 9000 else 6000)
            model.clearNote()
        }
    }

    if (!st.configured) {
        SettingsScreen(true, model.server, model.token, st.status, st.statusLoading, st.statusError, model::connect, null)
        return
    }

    BackHandler(enabled = st.stack.size > 1) { model.back() }
    val haze = remember { HazeState() }
    val composerSpace = 96.dp

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = st.top,
            transitionSpec = {
                val forward = targetState !is Screen.Today
                (slideInHorizontally { if (forward) it / 6 else -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 8 else it / 8 } + fadeOut())
            },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Today -> Box(Modifier.fillMaxSize()) {
                    Ambient(hourTint(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
                    TodayScreen(
                        day = st.day, loaded = st.dayLoaded, offline = st.offline, haze = haze,
                        onOpen = { model.open(Screen.Playlist(it.id)) }, onPlay = openUrl,
                        onLibrary = { model.open(Screen.Library) }, onExample = { draft = it }, bottomSpace = composerSpace,
                    )
                }
                is Screen.Playlist -> {
                    val s = st.sessions[screen.id]
                    if (s != null) {
                        PlaylistScreen(s, st.working, haze, PlaylistActions(
                            onBack = model::back, onOpenUrl = openUrl,
                            onTweak = { model.tweak(s.id, it) }, onUndo = { model.undo(s.id) },
                            onSwap = { model.swap(s.id, it) }, onRemove = { model.remove(s.id, it) },
                            onPin = { i, on -> model.pin(s.id, i, on) }, onResync = { model.resync(s.id) },
                            onDelete = { model.delete(s.id) },
                        ), bottomSpace = composerSpace)
                    } else {
                        Ambient(hourTint(12))
                    }
                }
                Screen.Library -> LibraryScreen(st.library, st.libraryLoaded, onBack = model::back,
                    onOpen = { model.open(Screen.Playlist(it.id)) }, onSettings = { model.open(Screen.Settings) })
                Screen.Settings -> SettingsScreen(false, model.server, model.token, st.status, st.statusLoading, st.statusError,
                    model::connect, onBack = model::back)
            }
        }

        val top = st.top
        if (top == Screen.Today || top is Screen.Playlist) {
            Composer(
                text = draft, onText = { draft = it },
                placeholder = if (top is Screen.Playlist) "Change this playlist…" else "What's today?",
                sending = st.sending,
                onSend = { model.send(draft); draft = "" },
                onMic = mic, haze = haze, note = st.note, onNoteTap = model::clearNote,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
            )
        }
    }
}
