package club.taptappers.telly.ui

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import club.taptappers.telly.R
import club.taptappers.telly.data.Session
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/**
 * The conversation, one exchange at a time, just above the composer.
 *
 * You see your words the instant you send them, then telly saying what it's doing. It never pretends: until the reply
 * arrives it is reading your message (and says so, then admits when the model is slow). When the reply arrives it
 * says what changed, and while the playlist is saved and its notes and story are written, one quiet line says which
 * of those is happening. Tap to dismiss; it also leaves by itself once everything has settled.
 */
@Composable
fun ExchangeCard(ex: Exchange, session: Session?, haze: HazeState, onOpen: ((String) -> Unit)?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).glass(haze).border(1.dp, C.hairline, shape)
            .clickable(enabled = ex.phase != Phase.WORKING, onClickLabel = "Dismiss", onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .animateContentSize()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // Your words while telly works on them; once it has answered they live in the conversation thread, and the
        // card shrinks to the answer (it may stay a while as notes are written, so it should take little room).
        ex.said?.takeIf { ex.phase == Phase.WORKING }?.let { said ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    said, style = T.caption, color = C.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.glass).padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when (ex.phase) {
                    Phase.WORKING -> Dots()
                    Phase.DONE -> Icon(painterResource(R.drawable.ic_check), null, tint = C.text, modifier = Modifier.size(16.dp))
                    Phase.FAILED -> Box(Modifier.size(8.dp).clip(CircleShape).background(C.problem))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (ex.phase == Phase.WORKING) waitWords(ex) else (ex.reply ?: "Done."),
                style = T.caption, color = if (ex.phase == Phase.WORKING) C.text2 else C.text,
                modifier = Modifier.weight(1f),
            )
            if (ex.phase == Phase.DONE && (ex.added > 0 || ex.removed > 0) && session?.let { it.count != ex.added } != false) {
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(ex.added.takeIf { it > 0 }?.let { "+$it" }, ex.removed.takeIf { it > 0 }?.let { "−$it" }).joinToString(" "),
                    style = T.tabular, color = C.text2)
            }
            val target = ex.target
            if (ex.phase == Phase.DONE && onOpen != null && target != null) {
                Spacer(Modifier.width(10.dp))
                Text("Open", style = T.bodyStrong, color = C.text,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(target) }.padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
        val after = session?.inProgress
        if (ex.phase == Phase.DONE && after != null) {
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 30.dp)) {
                CircularProgressIndicator(Modifier.size(11.dp), color = C.text3, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(after, style = T.small, color = C.text3)
            }
        }
    }
}

/** What to say while waiting, by how long it has been (honest: a message is still being read until the reply comes). */
@Composable
private fun waitWords(ex: Exchange): String {
    val seconds by produceState(0L, ex.id) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - ex.startedAt) / 1000
            delay(1000)
        }
    }
    return when {
        !ex.slow && seconds < 8 -> "Reworking the playlist…"
        !ex.slow -> "Still on it…"
        seconds < 6 -> "Reading what you said…"
        seconds < 20 -> "Still on it…"
        else -> "The model is slow right now. Hang on."
    }
}

/** Three soft dots breathing in turn: the one moving thing on screen while telly works (still, with reduced motion). */
@Composable
private fun Dots() {
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "phase")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            // Read in the draw phase only: the dots animate without recomposing anything.
            Box(Modifier.size(4.5.dp).graphicsLayer {
                val d = ((phase - i + 3f) % 3f)
                alpha = if (still) 0.6f else (1f - (d.coerceAtMost(1.5f) / 1.5f)) * 0.75f + 0.25f
            }.clip(CircleShape).background(C.text))
        }
    }
}
