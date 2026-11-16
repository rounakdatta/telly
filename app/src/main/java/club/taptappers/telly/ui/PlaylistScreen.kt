@file:OptIn(ExperimentalMaterial3Api::class)

package club.taptappers.telly.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import club.taptappers.telly.R
import club.taptappers.telly.data.Session
import club.taptappers.telly.data.Track
import dev.chrisbanes.haze.HazeState

val TWEAKS = listOf("familiar" to "More familiar", "new" to "More new", "calmer" to "Calmer", "energy" to "More energy",
    "instrumental" to "Instrumental", "shorter" to "Shorter", "longer" to "Longer", "reshuffle" to "Reshuffle")

class PlaylistActions(
    val onBack: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onTweak: (String, String) -> Unit,
    val onUndo: () -> Unit,
    val onSwap: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onPin: (Int, Boolean) -> Unit,
    val onResync: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
fun PlaylistScreen(s: Session, working: String?, updating: Boolean, fresh: Set<String>, haze: HazeState, actions: PlaylistActions,
                   bottomSpace: androidx.compose.ui.unit.Dp,
                   /** The song of this playlist the phone is playing (its index), and whether it is sounding. */
                   playing: Pair<Int, Boolean>? = null) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 40 } }
    var sheet by remember { mutableStateOf<Int?>(null) }
    val accent = hourTint((s.start?.substringBefore(":")?.toIntOrNull()) ?: 12)
    // While a change is being made the list steps back a little, so it's plain the old list is on its way out.
    val listAlpha by animateFloatAsState(if (updating) 0.45f else 1f, tween(260), label = "updating")

    Box(Modifier.fillMaxSize()) {
        Ambient(accent, art = s.tracks.firstOrNull { it.thumb != null }?.thumb)
        LazyColumn(
            Modifier.fillMaxSize().glassSource(haze), state = list,
            contentPadding = PaddingValues(top = top + 60.dp, bottom = bottomSpace + 28.dp),
        ) {
            item(key = "hero") { Hero(s) }
            item(key = "actions") { PlayRow(s, working, actions) }
            item(key = "tweaks") { Tweaks(s, working, actions) }
            item(key = "progress") {
                Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 20.dp)) {
                    if (updating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent, trackColor = Color.Transparent)
                }
            }
            itemsIndexed(s.tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, accent, fresh = t.id in fresh, playing = playing?.takeIf { it.first == i }?.second, onClick = { sheet = i },
                    modifier = Modifier.animateItem().graphicsLayer { alpha = listAlpha })
            }
            item(key = "notes") { LinerNotes(s) }
            item(key = "log") { Conversation(s) }
        }
        TopBar(s.name, scrolled, haze, actions)
    }

    sheet?.let { i ->
        s.tracks.getOrNull(i)?.let { t -> TrackSheet(t, i, s, actions, onDismiss = { sheet = null }) }
    }
}

@Composable
private fun TopBar(title: String, scrolled: Boolean, haze: HazeState, actions: PlaylistActions) {
    var menu by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier.fillMaxWidth().then(if (scrolled) Modifier.glass(haze) else Modifier)
            .padding(top = top).height(56.dp).padding(horizontal = 12.dp),
    ) {
        GlassCircle(R.drawable.ic_back, "Back", actions.onBack, Modifier.align(Alignment.CenterStart))
        if (scrolled) {
            Text(title, style = T.bodyStrong, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp))
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            GlassCircle(R.drawable.ic_more, "More", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Save to the services again", style = T.body, color = C.text) },
                    onClick = { menu = false; actions.onResync() })
                DropdownMenuItem(text = { Text("Delete this playlist", style = T.body, color = C.text2) },
                    onClick = { menu = false; actions.onDelete() })
            }
        }
    }
}

@Composable
private fun Hero(s: Session) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Mosaic(s.tracks, 212.dp, 20.dp)
        Spacer(Modifier.height(22.dp))
        Text(s.name, style = T.title, color = C.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        val time = s.startLabel?.takeIf { !s.title.contains(it, ignoreCase = true) }
        Text(listOfNotNull(time, "${s.durationMin} min", "${s.count} songs", s.weather).joinToString("  ·  "),
            style = T.tabular, color = C.text2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(s.summary, style = T.caption, color = C.text3, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlayRow(s: Session, working: String?, actions: PlaylistActions) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, start = 20.dp, end = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ytm = s.ytm.play ?: s.ytm.url
            if (ytm != null) {
                PrimaryPill("Play", R.drawable.ic_ytmusic, onClick = { actions.onOpenUrl(ytm) })
            } else if (s.ytm.state in setOf("pending", "syncing")) {
                SavingPill()
            }
            // Spotify only once it is there: a button for a service that isn't connected is chrome for nothing.
            s.spotify.url?.let { url -> GlassPill("Spotify", R.drawable.ic_spotify, onClick = { actions.onOpenUrl(url) }) }
        }
        // Said where it matters, beside the Play button, and only when something is actually wrong.
        val problem = when (s.ytm.state) {
            "signed-out" -> "YouTube Music needs signing in again on the server."
            "error" -> "Couldn't save to YouTube Music. " + (s.ytm.error ?: "")
            else -> s.ytm.error?.takeIf { s.ytm.state == "synced" }
        }
        if (problem != null) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(problem.trim(), style = T.caption, color = C.text3, textAlign = TextAlign.Center)
                if (s.ytm.state in setOf("error", "signed-out")) {
                    Spacer(Modifier.width(8.dp))
                    Text("Retry", style = T.caption, color = C.text, modifier = Modifier.clickable(role = Role.Button) { actions.onResync() })
                }
            }
        }
    }
}

@Composable
private fun SavingPill() {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.glass).border(1.dp, C.hairline, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp).semantics { contentDescription = "Saving to YouTube Music" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = C.text2, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Saving to YouTube Music", style = T.bodyStrong, color = C.text2, maxLines = 1)
    }
}

@Composable
private fun Tweaks(s: Session, working: String?, actions: PlaylistActions) {
    LazyRow(
        Modifier.padding(top = 20.dp, bottom = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.canUndo) {
            item(key = "undo") { Chip("Undo", { actions.onUndo() }, icon = R.drawable.ic_undo, busy = working == "undo", enabled = working == null) }
        }
        TWEAKS.forEach { (key, label) ->
            item(key = key) {
                // Instrumental is a switch, not a nudge: it shows whether it is on, and a second tap brings vocals back.
                val on = key == "instrumental" && s.brief.instrumental
                Chip(label, { actions.onTweak(key, if (on) "Bring vocals back" else label) }, busy = working == key,
                    enabled = working == null, selected = on)
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, accent: Color, fresh: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, playing: Boolean? = null) {
    // A song that just arrived glows for a moment, then settles: what changed is visible without a word.
    val glow by animateFloatAsState(if (fresh) 1f else 0f, tween(if (fresh) 300 else 1600), label = "fresh")
    Row(
        modifier.fillMaxWidth().drawBehind { if (glow > 0f) drawRect(accent.copy(alpha = 0.16f * glow)) }
            .clickable(onClickLabel = "Options for ${t.title}", onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Art(t.thumb, t.id, 52.dp, 8.dp)
            // The song the phone is playing: bars over its cover, moving while it sounds.
            if (playing != null) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .semantics { contentDescription = if (playing) "Playing now" else "Paused here" }, contentAlignment = Alignment.Center) {
                    Bars(moving = playing)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = T.bodyStrong, color = if (playing != null) accent.copy(alpha = 0.98f).compositeOver(C.text) else C.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (t.pinned) {
                    Icon(painterResource(R.drawable.ic_pin), contentDescription = "Kept in place", tint = C.text3, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                }
                Spacer(Modifier.weight(1f))
                t.mark?.let { Text(it, style = T.small, color = if (t.new) accent.copy(alpha = 0.95f) else C.text3, modifier = Modifier.padding(start = 10.dp)) }
            }
            Text(t.artist, style = T.caption, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The friend leaning over: arrives a little after the playlist, so it fades in rather than pops.
            AnimatedVisibility(visible = t.note != null, enter = fadeIn(tween(500)) + expandVertically(tween(300))) {
                Text(t.note.orEmpty(), style = T.caption, color = C.text.copy(alpha = 0.6f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

/** Three bars rising and falling while the song sounds; at rest when it's paused (and with reduced motion). */
@Composable
fun Bars(moving: Boolean) {
    if (!moving || reducedMotion()) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            listOf(0.55f, 0.9f, 0.4f).forEach { h -> Box(Modifier.width(3.dp).height(16.dp * h).clip(RoundedCornerShape(1.5.dp)).background(Color.White)) }
        }
        return
    }
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0f, 0.37f, 0.71f).forEach { off ->
            // Read in the draw phase only: the bars move without recomposing the row.
            Box(Modifier.width(3.dp).height(16.dp).graphicsLayer {
                scaleY = 0.3f + 0.7f * abs(sin((phase + off) * PI.toFloat()))
                transformOrigin = TransformOrigin(0.5f, 1f)
            }.clip(RoundedCornerShape(1.5.dp)).background(Color.White))
        }
    }
}

@Composable
private fun LinerNotes(s: Session) {
    val st = s.story
    if (st.state == "none") return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 34.dp)) {
        Text("Liner notes", style = T.small, color = C.text3)
        Spacer(Modifier.height(10.dp))
        if (st.state == "ready" && st.body != null) {
            st.title?.let { Text(it, style = T.headline, color = C.text) }
            Spacer(Modifier.height(10.dp))
            st.body.split(Regex("\n\\s*\n")).forEach { para ->
                Text(para.trim(), style = T.reading, color = C.text.copy(alpha = 0.88f), modifier = Modifier.padding(bottom = 14.dp))
            }
            st.deeper?.let {
                Text("Go deeper: $it", style = T.caption.copy(fontStyle = FontStyle.Italic), color = C.text2)
                if (!st.deeperChecked) Text("Couldn't confirm this one exists; treat it as a lead.", style = T.small, color = C.text3, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            Text("Writing the liner notes…", style = T.caption, color = C.text3)
        }
    }
}

/** Everything said about this playlist, as the conversation it was: your words on the right, telly's on the left. */
@Composable
private fun Conversation(s: Session) {
    val log = s.log.filter { !it.said.isNullOrBlank() || !it.reply.isNullOrBlank() }.takeLast(8)
    if (log.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 30.dp)) {
        Text("Conversation", style = T.small, color = C.text3, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        log.forEach { e ->
            e.said?.let {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    Text(it, style = T.caption, color = C.text, modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                        .background(C.glass).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            e.reply?.let { Text(it, style = T.caption, color = C.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 40.dp)) }
        }
    }
}

@Composable
private fun TrackSheet(t: Track, i: Int, s: Session, actions: PlaylistActions, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = C.sheet, scrimColor = Color.Black.copy(alpha = 0.55f)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(t.thumb, t.id, 64.dp, 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = T.headline, color = C.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = T.caption, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            t.note?.let { Text(it, style = T.body, color = C.text.copy(alpha = 0.88f), modifier = Modifier.padding(top = 14.dp)) }
            if (t.why.isNotBlank()) Text(t.why, style = T.caption, color = C.text3, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(10.dp))
            val list = s.ytm.url?.substringAfter("list=", "")?.takeIf { it.isNotEmpty() }
            if (t.ytm != null) {
                SheetRow(R.drawable.ic_play, "Play from here") {
                    onDismiss(); actions.onOpenUrl("https://music.youtube.com/watch?v=${t.ytm}" + (list?.let { "&list=$it" } ?: ""))
                }
            }
            SheetRow(R.drawable.ic_swap, "Swap for another song") { onDismiss(); actions.onSwap(i) }
            SheetRow(R.drawable.ic_pin, if (t.pinned) "Let it change again" else "Keep it in place") { onDismiss(); actions.onPin(i, !t.pinned) }
            SheetRow(R.drawable.ic_remove, "Remove") { onDismiss(); actions.onRemove(i) }
        }
    }
}

@Composable
private fun SheetRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = C.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = T.body, color = C.text)
    }
}
