package club.taptappers.telly.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import club.taptappers.telly.R
import club.taptappers.telly.data.LyricLine
import club.taptappers.telly.data.LyricsResult
import club.taptappers.telly.data.Playing
import club.taptappers.telly.data.Spot
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/** The colour of what a friend leans over and says: warm, apart from the lyrics' white, never loud. */
private val Whisper = Color(0xFFF2CF8C)

/**
 * A row as drawn. A sung line carries its words and when each starts, and perhaps a whisper (what a Genius annotation
 * says about it, in a friend's words). A row with no words is a pause in the singing (`gap`, drawn as breathing dots)
 * or the song's opening; either may carry something to say (`say`).
 */
data class Shown(
    val start: Long,
    val end: Long,
    val text: String,
    val words: List<Pair<String, Long>>,
    val gap: Boolean = false,
    val timed: Boolean = false,
    val whisper: String? = null,
    val say: String? = null,
)

/**
 * Lines with their words placed in time. Where LeZeppLean timed the words against the recording, those times are
 * used. Where only the line's start is known, its words are spread across the line by length, so the line still
 * sweeps as it is sung (and takes the real times the moment they arrive). Long silences become gaps. `says` are
 * spoken in order: the first as the song opens, the rest in pauses long enough to read them.
 */
fun shape(lines: List<LyricLine>, durationMs: Long?, whispers: Map<Int, String> = emptyMap(), says: List<String> = emptyList()): List<Shown> {
    val out = mutableListOf<Shown>()
    val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
    if (sung.isEmpty()) return out
    var said = 0
    val nextSay = { says.getOrNull(said)?.also { said++ } }
    val first = sung.first().value.start
    val opening = nextSay()
    if (first >= 6000 || opening != null) out += Shown(0, first, "", emptyList(), gap = first >= 6000, say = opening)
    sung.forEachIndexed { k, (src, line) ->
        val next = sung.getOrNull(k + 1)?.value?.start
        val end = line.end ?: next ?: (durationMs ?: (line.start + 5000))
        val texts = line.text.split(' ').filter { it.isNotEmpty() }
        val starts = line.words?.takeIf { it.size == texts.size }
        val words = if (starts != null) texts.zip(starts) else {
            // spread across ~85% of the line: singers rarely sing right up to the next line's first word
            val span = ((minOf(end, next ?: end) - line.start) * 0.85).toLong().coerceAtLeast(400)
            val total = texts.sumOf { it.length + 1 }.coerceAtLeast(1)
            var acc = 0
            texts.map { w -> (w to (line.start + span * acc / total)).also { acc += w.length + 1 } }
        }
        out += Shown(line.start, end, line.text, words, timed = starts != null, whisper = whispers[src])
        if (next != null && next - end >= 5000) out += Shown(end, next, "", emptyList(), gap = true, say = if (next - end >= 9000) nextSay() else null)
    }
    return out
}

/** The index of the row being sung at `pos`, or -1 before the first. */
fun activeAt(shown: List<Shown>, pos: Long): Int {
    var lo = 0
    var hi = shown.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (shown[mid].start <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** The rows for a song: its lines, its Genius whispers, and what to say as it opens (telly's own note first). */
@Composable
fun rememberShown(playing: Playing?, lyrics: LyricsResult?, note: String?): List<Shown> =
    remember(lyrics?.version, lyrics?.lines, lyrics?.trivia, note, playing?.durationMs) {
        val t = lyrics?.trivia
        shape(lyrics?.lines.orEmpty(), playing?.durationMs, t?.lines.orEmpty().associate { it.line to it.text },
            listOfNotNull(note) + t?.about.orEmpty())
    }

@Composable
fun LyricsScreen(
    playing: Playing?,
    lyrics: LyricsResult?,
    error: String?,
    spot: Spot?,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
    /** Something to play when nothing is: today's latest playlist, as (its name, its YouTube Music link). */
    suggestion: Pair<String, String>?,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The position, every frame, run forward from the player's last report: nothing is polled. (An infinite-animation
    // frame, so tests and the system's animation policy can hold it.)
    val pos = remember { mutableLongStateOf(playing?.position() ?: 0L) }
    LaunchedEffect(playing) {
        while (true) withInfiniteAnimationFrameMillis { pos.longValue = playing?.position() ?: 0L }
    }
    val note = spot?.track?.note
    val shown = rememberShown(playing, lyrics, note)
    val active by remember(shown) { derivedStateOf { activeAt(shown, pos.longValue) } }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        // The cover, blurred into the room's light (Android 12+; earlier, a flat dark room).
        val art = playing?.art
        if (art != null && Build.VERSION.SDK_INT >= 31 && LocalEffects.current) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f }.blur(90.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to C.bg.copy(alpha = 0.35f), 0.6f to C.bg.copy(alpha = 0.7f), 1f to C.bg)))

        Column(Modifier.fillMaxSize().padding(top = top, bottom = bottom)) {
            Header(playing, spot, onClose)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    playing == null -> NothingPlaying(suggestion, onOpenUrl)
                    shown.isNotEmpty() -> Lines(shown, lyrics, active, { pos.longValue }, onSeek, onOpenUrl)
                    else -> Unsung(lyrics, error, note, onRetry)
                }
            }
            if (playing != null) Controls(playing, { pos.longValue }, onSeek, onPlayPause, onNext, onPrevious)
        }
    }
}

@Composable
private fun Header(p: Playing?, spot: Spot?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(p, 52.dp, 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p?.title ?: "Nothing playing", style = T.bodyStrong, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) Text(p.artist, style = T.caption, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Where it sits in one of today's playlists, when it does.
            spot?.let { Text("${it.index + 1} of ${it.session.count} · ${it.session.name}", style = T.small, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        GlassCircle(R.drawable.ic_down, "Close lyrics", onClose)
    }
}

@Composable
private fun Cover(p: Playing?, size: Dp, corner: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(placeholderBrush(p?.title ?: "telly"))) {
        p?.art?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

/** Nothing playing: say how to start, and offer today's playlist when there is one. */
@Composable
private fun NothingPlaying(suggestion: Pair<String, String>?, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
        Text("Play something, and its words show here as they're sung.", style = T.headline, color = C.text)
        Spacer(Modifier.height(10.dp))
        Text("From YouTube Music or Spotify, while telly is open.", style = T.body, color = C.text2)
        suggestion?.let { (name, url) ->
            Spacer(Modifier.height(24.dp))
            PrimaryPill("Play $name", R.drawable.ic_ytmusic, onClick = { onOpenUrl(url) })
        }
    }
}

/**
 * A song with no lines to show (yet): finding them is a calm wait, not an alarm; a song without lyrics still gets
 * what there is to say about it, which is most of the point for an instrumental.
 */
@Composable
private fun Unsung(l: LyricsResult?, error: String?, note: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        val says = listOfNotNull(note) + l?.trivia?.about.orEmpty()
        when {
            error != null && l == null -> {
                Text(error, style = T.body, color = C.text2)
                Spacer(Modifier.height(12.dp))
                Text("Try again", style = T.bodyStrong, color = C.text,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onRetry).padding(vertical = 6.dp))
            }
            l == null || l.kind == "pending" -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Breathing()
                Spacer(Modifier.width(12.dp))
                Text("Finding the words…", style = T.body, color = C.text3)
            }
            l?.kind == "plain" -> Text("There are words for this one, but none timed to it yet.", style = T.body, color = C.text2)
            else -> Text(if (says.isEmpty()) "No words to this one. Just listen." else "No words to this one.", style = T.headline, color = C.text2)
        }
        says.forEach { s ->
            Spacer(Modifier.height(18.dp))
            Said(s, bright = true)
        }
    }
}

@Composable
private fun Lines(shown: List<Shown>, l: LyricsResult?, active: Int, pos: () -> Long, onSeek: (Long) -> Unit, onOpenUrl: (String) -> Unit) {
    val view = LocalView.current
    val still = reducedMotion()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val list = rememberLazyListState()
        // Following the song: the line being sung sits a quarter of the way down. A finger on the list takes over for
        // a few seconds, then the song takes back the lead.
        val touched = remember { mutableLongStateOf(0L) }
        val follow = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput) touched.longValue = SystemClock.elapsedRealtime()
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(active, shown) {
            if (active >= 0 && SystemClock.elapsedRealtime() - touched.longValue > 3500) {
                if (still) list.scrollToItem(active) else list.animateScrollToItem(active)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(follow), state = list,
            contentPadding = PaddingValues(top = h * 0.24f, bottom = h * 0.62f, start = 24.dp, end = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(shown, key = { i, s -> "${s.start}-$i" }) { i, s ->
                val state = when {
                    i == active -> 0
                    i < active -> -1
                    else -> 1
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                        onClickLabel = "Play from here") {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        touched.longValue = 0L
                        onSeek(s.start)
                    }) {
                    when {
                        s.text.isNotEmpty() -> Line(s, state, pos, still)
                        s.gap -> Gap(s, state, pos)
                    }
                    // What a friend says: in a pause, or as the song opens.
                    s.say?.let { Box(Modifier.padding(top = if (s.gap) 12.dp else 0.dp)) { Said(it, bright = state == 0) } }
                    // A whisper arrives as its line is sung and stays with it; lines still to come keep theirs to themselves.
                    s.whisper?.let { w ->
                        AnimatedVisibility(visible = state <= 0, enter = fadeIn(tween(500)) + expandVertically(tween(320))) {
                            Whispered(w, bright = state == 0)
                        }
                    }
                }
            }
            item(key = "credits") { Credits(l, onOpenUrl) }
        }
    }
}

private val LYRIC = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)

/**
 * One line. Being sung (state 0): each word lights as it is sung, its brightness rising across its own time. Before
 * and after: dimmed, and a touch smaller, so the eye rests on the one being sung.
 */
@Composable
private fun Line(s: Shown, state: Int, pos: () -> Long, still: Boolean) {
    val scale by animateFloatAsState(if (state == 0 || still) 1f else 0.95f, tween(380), label = "scale")
    val dim by animateFloatAsState(if (state < 0) 0.42f else if (state > 0) 0.30f else 1f, tween(380), label = "dim")
    val mod = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f) }
    if (state != 0) {
        Text(s.text, style = LYRIC, color = C.text.copy(alpha = dim), modifier = mod)
        return
    }
    // The line being sung, redrawn every frame (and only this line).
    val now = pos()
    val text = buildAnnotatedString {
        s.words.forEachIndexed { i, (w, start) ->
            val end = s.words.getOrNull(i + 1)?.second ?: s.end
            val p = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else if (now >= start) 1f else 0f
            withStyle(SpanStyle(color = C.text.copy(alpha = 0.36f + 0.64f * p))) { append(w) }
            if (i < s.words.size - 1) append(' ')
        }
    }
    Text(text, style = LYRIC, modifier = mod)
}

/** A stretch without singing: three dots that fill one after another as the voice comes back. */
@Composable
private fun Gap(s: Shown, state: Int, pos: () -> Long) {
    val p = if (state == 0) ((pos() - s.start).toFloat() / (s.end - s.start).coerceAtLeast(1)).coerceIn(0f, 1f) else if (state < 0) 1f else 0f
    Row(Modifier.padding(vertical = 8.dp).semantics { contentDescription = "Instrumental" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { i ->
            val f = ((p * 3f) - i).coerceIn(0f, 1f)
            Box(Modifier.size(11.dp).graphicsLayer { alpha = 0.3f + 0.7f * f; scaleX = 0.85f + 0.15f * f; scaleY = 0.85f + 0.15f * f }
                .clip(CircleShape).background(C.text))
        }
    }
}

/** Something to say about the song, in its opening or a pause: a full sentence, set to be read. */
@Composable
private fun Said(text: String, bright: Boolean) {
    val alpha by animateFloatAsState(if (bright) 0.92f else 0.5f, tween(380), label = "said")
    Text(text, style = T.reading, color = Whisper.copy(alpha = alpha))
}

/** What a Genius annotation says about the line just sung, as a friend would whisper it. */
@Composable
private fun Whispered(text: String, bright: Boolean) {
    val alpha by animateFloatAsState(if (bright) 0.9f else 0.45f, tween(380), label = "whisper")
    Row(Modifier.padding(top = 8.dp, end = 8.dp)) {
        Box(Modifier.padding(top = 9.dp).size(5.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(Whisper))
        Spacer(Modifier.width(10.dp))
        Text(text, style = T.body, color = Whisper.copy(alpha = alpha))
    }
}

/** Where the words, their timing and the notes came from. */
@Composable
private fun Credits(l: LyricsResult?, onOpenUrl: (String) -> Unit) {
    val url = l?.trivia?.takeIf { it.status == "ready" && (it.lines.isNotEmpty() || it.about.isNotEmpty()) }?.url
    Column(Modifier.padding(top = 28.dp)) {
        Text("Words from LRCLIB, timed to this recording by LeZeppLean.", style = T.small, color = C.text3)
        if (url != null) {
            Text("Notes from Genius annotations · Read them there", style = T.small, color = C.text3,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "Open on Genius") { onOpenUrl(url) }
                    .padding(vertical = 4.dp))
        }
    }
}

/** A calm "on its way": one soft dot breathing (still, with reduced motion). */
@Composable
private fun Breathing() {
    val still = reducedMotion()
    val a by produceState(0.6f, still) {
        if (still) return@produceState
        var t = 0L
        while (true) {
            withInfiniteAnimationFrameMillis { t = it }
            value = 0.35f + 0.5f * ((kotlin.math.sin(t / 420.0) + 1) / 2).toFloat()
        }
    }
    Box(Modifier.size(9.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(C.text))
}

@Composable
private fun Controls(p: Playing, pos: () -> Long, onSeek: (Long) -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    val dur = p.durationMs ?: 0L
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        // Progress, read in the draw phase only; a tap on it seeks there.
        Box(Modifier.fillMaxWidth().height(24.dp).pointerInput(dur) {
            if (dur > 0) detectTapGestures { o ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onSeek((o.x / size.width * dur).toLong().coerceIn(0, dur))
            }
        }.semantics { contentDescription = "Seek" }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.glass)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer {
                    scaleX = if (dur > 0) (pos().toFloat() / dur).coerceIn(0f, 1f) else 0f
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }.background(C.text.copy(alpha = 0.85f)))
            }
        }
        // Elapsed and remaining, redrawn once a second rather than every frame.
        val second by remember { derivedStateOf { pos() / 1000 } }
        Row(Modifier.fillMaxWidth()) {
            Text(clock(second), style = T.tabular, color = C.text3)
            Spacer(Modifier.weight(1f))
            if (dur > 0) Text("−" + clock((dur / 1000 - second).coerceAtLeast(0)), style = T.tabular, color = C.text3)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Transport(R.drawable.ic_prev, "Previous song", onPrevious, 52.dp, filled = false)
            Spacer(Modifier.width(28.dp))
            val on = p.playing || p.buffering
            Transport(if (on) R.drawable.ic_pause else R.drawable.ic_play, if (on) "Pause" else "Play", onPlayPause, 64.dp, filled = true)
            Spacer(Modifier.width(28.dp))
            Transport(R.drawable.ic_next, "Next song", onNext, 52.dp, filled = false)
        }
    }
}

private fun clock(s: Long) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

@Composable
private fun Transport(icon: Int, label: String, onClick: () -> Unit, size: Dp, filled: Boolean) {
    val view = LocalView.current
    Box(Modifier.size(size).clip(CircleShape).background(if (filled) Color.White else Color.Transparent)
        .clickable(role = Role.Button, onClickLabel = label) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = if (filled) Color.Black else C.text, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * The song playing, just above the composer while telly is open: its line being sung right now under its name (or
 * the artist, between lines and for songs without words). Tap for the words; the button plays and pauses.
 */
@Composable
fun MiniPlayer(p: Playing, lyrics: LyricsResult?, onOpen: () -> Unit, onPlayPause: () -> Unit, haze: HazeState, modifier: Modifier = Modifier) {
    val shown = rememberShown(p, lyrics, null)
    // The line being sung, worked out when it changes rather than every frame: wait until the next line starts.
    val line by produceState<String?>(null, p, shown) {
        while (true) {
            val at = p.position()
            val i = activeAt(shown, at)
            value = shown.getOrNull(i)?.text?.takeIf { it.isNotBlank() }
            val next = shown.getOrNull(i + 1)?.start
            if (!p.playing || next == null) break
            delay(((next - at) / p.speed).toLong().coerceIn(40, 10_000))
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).glass(haze).border(1.dp, C.hairline, shape)
            .clickable(role = Role.Button, onClickLabel = "Show the words", onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(p, 40.dp, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = T.bodyStrong, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line ?: p.artist, style = T.small, color = if (line != null) C.text else C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val on = p.playing || p.buffering
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (on) "Pause" else "Play", onClick = onPlayPause),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(if (on) R.drawable.ic_pause else R.drawable.ic_play), if (on) "Pause" else "Play", tint = C.text, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The offer, once, at the bottom of the day: see the words of whatever's playing. Android files the permission under
 * notification access, so the card says what telly does with it. If he went to allow it and came back without, it
 * says what Android needs first (a sideloaded app's "restricted setting").
 */
@Composable
fun LyricsInvite(stuck: Boolean, onAllow: () -> Unit, onAppInfo: () -> Unit, onDecline: () -> Unit, haze: HazeState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).glass(haze).border(1.dp, C.hairline, shape).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("See the words of whatever's playing, lit as they're sung.", style = T.bodyStrong, color = C.text)
        Spacer(Modifier.height(4.dp))
        Text(
            if (stuck) "If Android greys the switch out as a restricted setting: open App info, its three-dot menu, Allow restricted settings. Then try again."
            else "Android calls this notification access. telly only looks at the music player, and only while it's open.",
            style = T.caption, color = C.text2,
        )
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (stuck) "Try again" else "Allow", style = T.bodyStrong, color = Color.Black,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(role = Role.Button, onClick = onAllow)
                    .padding(horizontal = 16.dp, vertical = 8.dp))
            if (stuck) {
                Spacer(Modifier.width(8.dp))
                Text("App info", style = T.bodyStrong, color = C.text,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onAppInfo).padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Not now", style = T.caption, color = C.text3, textAlign = TextAlign.End,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onDecline).padding(horizontal = 8.dp, vertical = 8.dp))
        }
    }
}
