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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
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
import club.taptappers.telly.data.LineNote
import club.taptappers.telly.data.LyricLine
import club.taptappers.telly.data.LyricsResult
import club.taptappers.telly.data.Playing
import club.taptappers.telly.data.Spot
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/** The colour of what a friend leans over and says: warm, apart from the lyrics' white, never loud. */
private val Whisper = Color(0xFFF2CF8C)

/**
 * A row as drawn. A sung line carries its words and when each starts. Lines a Genius annotation covers form a
 * `passage` (rows with the same number), and the passage's last line carries the `whisper`: what the annotation says,
 * in a friend's words. A row with no words is a pause in the singing (`gap`, drawn as breathing dots) or the song's
 * opening; either may carry something to say (`say`).
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
    val passage: Int? = null,
)

/**
 * Lines with their words placed in time. Where LeZeppLean timed the words against the recording, those times are
 * used. Where only the line's start is known, its words are spread across the line by length, so the line still
 * sweeps as it is sung (and takes the real times the moment they arrive). Long silences become gaps. `says` are
 * spoken in order: the first as the song opens, the rest in pauses long enough to read them.
 */
fun shape(lines: List<LyricLine>, durationMs: Long?, notes: List<LineNote> = emptyList(), says: List<String> = emptyList()): List<Shown> {
    val out = mutableListOf<Shown>()
    val rowOf = mutableMapOf<Int, Int>() // a sung line of the lyrics -> its row
    val sung = lines.withIndex().filter { it.value.text.isNotBlank() }
    if (sung.isEmpty()) return out
    var said = 0
    val nextSay = { says.getOrNull(said)?.also { said++ } }
    val first = sung.first().value.start
    val opening = nextSay()
    if (first >= 6000 || opening != null) out += Shown(0, first, "", emptyList(), gap = first >= 6000, say = opening)
    sung.forEachIndexed { k, (src, line) ->
        val next = sung.getOrNull(k + 1)?.value?.start
        // A line ends where the next line of the lyrics starts, sung or not (a blank one marks an instrumental break),
        // and the last one a few seconds on, not at the end of the outro.
        val following = lines.getOrNull(src + 1)?.start?.takeIf { it > line.start }
        val end = line.end ?: following ?: minOf(durationMs ?: Long.MAX_VALUE, line.start + 6000)
        val texts = line.text.split(' ').filter { it.isNotEmpty() }
        val starts = line.words?.takeIf { it.size == texts.size }
        val words = if (starts != null) texts.zip(starts) else {
            // spread across ~85% of the line: singers rarely sing right up to the next line's first word
            val span = ((end - line.start) * 0.85).toLong().coerceAtLeast(400)
            val total = texts.sumOf { it.length + 1 }.coerceAtLeast(1)
            var acc = 0
            texts.map { w -> (w to (line.start + span * acc / total)).also { acc += w.length + 1 } }
        }
        rowOf[src] = out.size
        out += Shown(line.start, end, line.text, words, timed = starts != null)
        if (next != null && next - end >= 5000) out += Shown(end, next, "", emptyList(), gap = true, say = if (next - end >= 9000) nextSay() else null)
    }
    // Each note over the rows of the lines it covers; it is said under the last of them.
    notes.forEachIndexed { k, n ->
        val rows = (n.line..maxOf(n.line, n.end ?: n.line)).mapNotNull { rowOf[it] }
        val a = rows.minOrNull() ?: return@forEachIndexed
        val b = rows.max()
        if ((a..b).any { out[it].passage != null }) return@forEachIndexed // passages never overlap
        for (r in a..b) out[r] = out[r].copy(passage = k, whisper = if (r == b) n.text else null)
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

/** The rows for a song: its lines, the passages Genius annotates, and what to say in its opening and its pauses (what
 *  Genius says about the song; telly's own note when Genius has nothing, so the same fact isn't said twice). */
@Composable
fun rememberShown(playing: Playing?, lyrics: LyricsResult?, note: String?): List<Shown> =
    remember(lyrics?.version, lyrics?.lines, lyrics?.trivia, note, playing?.durationMs) {
        val t = lyrics?.trivia
        shape(lyrics?.lines.orEmpty(), playing?.durationMs, t?.lines.orEmpty(), t?.about.orEmpty().ifEmpty { listOfNotNull(note) })
    }

@Composable
fun LyricsScreen(
    playing: Playing?,
    lyrics: LyricsResult?,
    error: String?,
    spot: Spot?,
    /** The song's cover: Apple's, or its YouTube Music art when it's in one of telly's playlists. */
    art: String?,
    onClose: () -> Unit,
    /** Opens the player app (by package): pausing and skipping happen there. */
    onOpenPlayer: (String) -> Unit,
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
        pos.longValue = playing?.position() ?: 0L
        val p = playing
        if (p == null || !p.playing) return@LaunchedEffect // paused: nothing moves, so no frame is asked for
        while (true) withInfiniteAnimationFrameMillis { pos.longValue = p.position() }
    }
    val note = spot?.track?.note
    val shown = rememberShown(playing, lyrics, note)
    val active by remember(shown) { derivedStateOf { activeAt(shown, pos.longValue) } }

    Box(Modifier.fillMaxSize()) {
        // The cover, blurred into the room's light (Android 12+; earlier, a flat dark room).
        Ambient(Color.Transparent, art = art.takeIf { playing != null })

        Column(Modifier.fillMaxSize().padding(top = top, bottom = bottom)) {
            Header(playing, spot, art, onClose)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    playing == null -> NothingPlaying(suggestion, onOpenUrl)
                    shown.isNotEmpty() -> Lines(shown, lyrics, active, { pos.longValue }, onOpenUrl)
                    else -> Unsung(lyrics, error, note, onRetry, onOpenUrl)
                }
            }
            if (playing != null) Progress(playing, { pos.longValue }, onOpenPlayer)
        }
    }
}

@Composable
private fun Header(p: Playing?, spot: Spot?, art: String?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (p != null) {
            Art(art, p.title, 52.dp, 10.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(p?.title ?: "Nothing playing", style = T.bodyStrong, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) Text(p.artist, style = T.caption, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Where it sits in one of today's playlists, when it does.
            spot?.let { Text("${it.index + 1} of ${it.session.count} · ${it.session.name}", style = T.small, color = C.text3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        GlassCircle(R.drawable.ic_down, "Close lyrics", onClose)
    }
}

/** Nothing playing: say how to start, and offer today's playlist when there is one. */
@Composable
private fun NothingPlaying(suggestion: Pair<String, String>?, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
        Text("Play something, and its words show here as they're sung.", style = T.headline, color = C.text)
        Spacer(Modifier.height(10.dp))
        Text("From YouTube Music or Spotify. The LeZeppLean helper on this phone tells telly what's playing.", style = T.body, color = C.text2)
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
private fun Unsung(l: LyricsResult?, error: String?, note: String?, onRetry: () -> Unit, onOpenUrl: (String) -> Unit) {
    val says = l?.trivia?.about.orEmpty().ifEmpty { listOfNotNull(note) }
    if (error == null && l != null && l.kind == "plain" && l.plain.any { it.isNotBlank() }) {
        PlainLines(l, says, onOpenUrl)
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when {
            error != null -> {
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
private fun Lines(shown: List<Shown>, l: LyricsResult?, active: Int, pos: () -> Long, onOpenUrl: (String) -> Unit) {
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
        // Each passage's first and last rows, so a row knows whether its passage is being sung, sung, or still to come.
        val passages = remember(shown) {
            shown.withIndex().filter { it.value.passage != null }.groupBy { it.value.passage!! }
                .mapValues { (_, rows) -> rows.first().index..rows.last().index }
        }
        val gapPx = with(LocalDensity.current) { 16.dp.toPx() }
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
                // A passage is sung while any of its lines is: its margin rule brightens, and its note arrives.
                val span = s.passage?.let { passages[it] }
                val passageState = when {
                    span == null -> state
                    active in span -> 0
                    active > span.last -> -1
                    else -> 1
                }
                Column(Modifier.fillMaxWidth().then(if (span != null) Modifier.passageRule(passageState, i == span.first, i == span.last, gapPx) else Modifier)) {
                    when {
                        s.text.isNotEmpty() -> Line(s, state, pos, still)
                        s.gap -> Gap(s, state, pos)
                    }
                    // What a friend says: in a pause, or as the song opens.
                    s.say?.let { Box(Modifier.padding(top = if (s.gap) 12.dp else 0.dp)) { Said(it, bright = state == 0) } }
                    // The note arrives as its passage begins and stays with it; passages still to come keep theirs.
                    s.whisper?.let { w ->
                        AnimatedVisibility(visible = passageState <= 0, enter = fadeIn(tween(500)) + expandVertically(tween(320))) {
                            Whispered(w, bright = passageState == 0)
                        }
                    }
                }
            }
            item(key = "credits") { Credits(l, onOpenUrl) }
        }
    }
}

private val PLAIN = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp)

/**
 * Words without times (none were found timed to this recording): the song's words, set to be read rather than sung
 * along to, with what's said about it first and Genius's notes beside the passages they're about. Nothing sweeps:
 * there is no timing to drive it, and a guessed one would mislead.
 */
@Composable
private fun PlainLines(l: LyricsResult, says: List<String>, onOpenUrl: (String) -> Unit) {
    val notes = l.trivia?.lines.orEmpty()
    val spans = notes.map { it.line..maxOf(it.line, it.end ?: it.line) }
    val gapPx = with(LocalDensity.current) { 10.dp.toPx() }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 30.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        says.forEachIndexed { i, s -> item(key = "say$i") { Box(Modifier.padding(bottom = 8.dp)) { Said(s, bright = true) } } }
        itemsIndexed(l.plain, key = { i, _ -> "p$i" }) { i, line ->
            val k = spans.indexOfFirst { i in it }
            Column(Modifier.fillMaxWidth().then(if (k >= 0) Modifier.passageRule(0, i == spans[k].first, i == spans[k].last, gapPx) else Modifier)) {
                if (line.isBlank()) Spacer(Modifier.height(8.dp)) else Text(line, style = PLAIN, color = C.text.copy(alpha = 0.88f))
                if (k >= 0 && i == spans[k].last) Whispered(notes[k].text, bright = true)
            }
        }
        item(key = "credits") { Credits(l, onOpenUrl) }
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

/** What a Genius annotation says about the passage just sung, as a friend would whisper it (inside the passage's rule). */
@Composable
private fun Whispered(text: String, bright: Boolean) {
    val alpha by animateFloatAsState(if (bright) 0.9f else 0.45f, tween(380), label = "whisper")
    Text(text, style = T.body, color = Whisper.copy(alpha = alpha), modifier = Modifier.padding(top = 8.dp, end = 8.dp))
}

/**
 * The margin rule beside the lines a note is about: a thin gold line in the left margin, like a reader's mark in a book.
 * Faint for a passage still to come (a note is waiting there), bright while it's sung, quiet once it has been. Rows of
 * one passage join up across the space between them.
 */
@Composable
private fun Modifier.passageRule(state: Int, first: Boolean, last: Boolean, gapPx: Float): Modifier {
    val alpha by animateFloatAsState(when (state) { 0 -> 0.85f; -1 -> 0.3f; else -> 0.16f }, tween(380), label = "rule")
    return drawBehind {
        val w = 2.5.dp.toPx()
        val top = if (first) 6.dp.toPx() else 0f
        val bottom = size.height - (if (last) 4.dp.toPx() else 0f) + (if (last) 0f else gapPx)
        // Square ends: rounded ones left a notch where two rows of a passage meet (a 2.5 dp rule needs no rounding).
        drawRect(Whisper.copy(alpha = alpha), topLeft = Offset(-14.dp.toPx(), top), size = Size(w, bottom - top))
    }
}

/** Where the words, their timing and the notes came from. */
@Composable
private fun Credits(l: LyricsResult?, onOpenUrl: (String) -> Unit) {
    val url = l?.trivia?.takeIf { it.status == "ready" && (it.lines.isNotEmpty() || it.about.isNotEmpty()) }?.url
    val from = when (l?.source) {
        "lrc.red" -> "lrc.red"
        "megalobiz" -> "Megalobiz"
        "simpmusic" -> "SimpMusic's listeners"
        else -> "LRCLIB"
    }
    Column(Modifier.padding(top = 28.dp)) {
        Text(if (l?.kind == "plain") "Words from $from, not timed to this recording yet." else "Words from $from, timed to this recording by LeZeppLean.",
            style = T.small, color = C.text3)
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

/** Where in the song (read-only: telly can't move the player) and the way to the player, to pause or skip there. */
@Composable
private fun Progress(p: Playing, pos: () -> Long, onOpenPlayer: (String) -> Unit) {
    val dur = p.durationMs ?: 0L
    // Elapsed and remaining (and what TalkBack reads), redrawn once a second rather than every frame.
    val second by remember { derivedStateOf { pos() / 1000 } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.glass).semantics {
            contentDescription = "Position in the song"
            if (dur > 0) progressBarRangeInfo = ProgressBarRangeInfo((second * 1000f / dur).coerceIn(0f, 1f), 0f..1f)
        }) {
            // Read in the draw phase only.
            Box(Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer {
                scaleX = if (dur > 0) (pos().toFloat() / dur).coerceIn(0f, 1f) else 0f
                transformOrigin = TransformOrigin(0f, 0.5f)
            }.background(C.text.copy(alpha = 0.85f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(clock(second), style = T.tabular, color = C.text3)
            Spacer(Modifier.weight(1f))
            if (dur > 0) Text("−" + clock((dur / 1000 - second).coerceAtLeast(0)), style = T.tabular, color = C.text3)
        }
        if (p.player != "the player") { // the players telly can open (declared in the manifest's <queries>)
            GlassPill("Open ${p.player}", if (p.app.contains("spotify")) R.drawable.ic_spotify else R.drawable.ic_ytmusic,
                onClick = { onOpenPlayer(p.app) }, modifier = Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally))
        }
    }
}

private fun clock(s: Long) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

/**
 * The song playing, just above the composer while telly is open: its line being sung right now under its name (or
 * the artist, between lines and for songs without words). Tap for the words.
 */
@Composable
fun MiniPlayer(p: Playing, lyrics: LyricsResult?, art: String?, onOpen: () -> Unit, haze: HazeState, modifier: Modifier = Modifier) {
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
        Art(art, p.title, 40.dp, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = T.bodyStrong, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line ?: p.artist, style = T.small, color = if (line != null) C.text else C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).semantics { contentDescription = if (p.playing) "Playing" else "Paused" }, contentAlignment = Alignment.Center) {
            Bars(moving = p.playing)
        }
    }
}
