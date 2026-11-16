@file:OptIn(ExperimentalLayoutApi::class)

package club.taptappers.telly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import club.taptappers.telly.R
import club.taptappers.telly.data.Day
import club.taptappers.telly.data.Session
import dev.chrisbanes.haze.HazeState

val EXAMPLES = listOf("A run at 6 pm, 40 minutes", "A walk after dinner", "Two hours of focus", "A long drive to Mysore")

@Composable
fun TodayScreen(
    day: Day?,
    loaded: Boolean,
    offline: String?,
    haze: HazeState,
    onOpen: (Session) -> Unit,
    onPlay: (String) -> Unit,
    onLibrary: () -> Unit,
    onExample: (String) -> Unit,
    bottomSpace: androidx.compose.ui.unit.Dp,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize().glassSource(haze),
        contentPadding = PaddingValues(top = top + 18.dp, bottom = bottomSpace + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { Header(day, offline, onLibrary) }
        when {
            day == null && !loaded -> Unit // not loaded yet: say nothing rather than flash an empty canvas
            day == null || day.sessions.isEmpty() -> item(key = "empty") { BlankCanvas(onExample) }
            else -> items(day.sessions, key = { it.id }) { s ->
                SessionCard(s, onOpen = { onOpen(s) }, onPlay = onPlay, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun Header(day: Day?, offline: String?, onLibrary: () -> Unit) {
    val label = day?.label ?: ""
    val weekday = label.substringBefore(", ", label)
    val date = label.substringAfter(", ", "")
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(weekday.ifEmpty { "Today" }, style = T.display, color = C.text, modifier = Modifier.semantics { heading() })
            val line = listOfNotNull(date.ifEmpty { null }, day?.weather).joinToString("  ·  ")
            if (line.isNotEmpty()) Text(line, style = T.caption, color = C.text2, modifier = Modifier.padding(top = 4.dp))
            if (offline != null) Text("Offline: showing what you had. $offline", style = T.caption, color = C.text3, modifier = Modifier.padding(top = 6.dp))
        }
        GlassCircle(R.drawable.ic_library, "Earlier days", onLibrary, Modifier.padding(top = 6.dp))
    }
}

/** First thing in the morning the canvas is blank. One line, and a few ways to start. */
@Composable
private fun BlankCanvas(onExample: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 56.dp)) {
        Text("What's today?", style = T.title, color = C.text)
        Spacer(Modifier.height(10.dp))
        Text(
            "Tell telly what the day holds and it makes the music for each part of it. Change anything, as often as you like.",
            style = T.body, color = C.text2,
        )
        Spacer(Modifier.height(22.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { Chip(it, onClick = { onExample(it) }) }
        }
    }
}

@Composable
fun SessionCard(s: Session, onOpen: () -> Unit, onPlay: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(shape).background(C.card).border(1.dp, C.hairline, shape)
            .clickable(role = Role.Button, onClickLabel = "Open ${s.title}", onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mosaic(s.tracks, 84.dp, 14.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = T.headline, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("${s.durationMin} min", "${s.count} songs", s.weather).joinToString(" · "),
                style = T.tabular, color = C.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(s.summary, style = T.caption, color = C.text3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(10.dp))
        PlayCircle(s.ytm.url, settling = s.ytm.state in setOf("pending", "syncing"), onPlay = onPlay)
    }
}
