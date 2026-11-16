package club.taptappers.telly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import club.taptappers.telly.R
import club.taptappers.telly.data.LibraryDay
import club.taptappers.telly.data.Session
import club.taptappers.telly.data.Status
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -- earlier days ---------------------------------------------------------------------------------------------------

@Composable
fun LibraryScreen(days: List<LibraryDay>, loaded: Boolean, onBack: () -> Unit, onOpen: (Session) -> Unit, onSettings: () -> Unit,
                  onMore: (() -> Unit)? = null) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        Ambient(hourTint(20))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + 24.dp)) {
            item(key = "bar") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassCircle(R.drawable.ic_back, "Back", onBack)
                    Spacer(Modifier.weight(1f))
                    GlassCircle(R.drawable.ic_settings, "Settings", onSettings)
                }
            }
            item(key = "title") {
                Text("Earlier", style = T.display, color = C.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp).semantics { heading() })
            }
            if (loaded && days.isEmpty()) {
                item(key = "empty") {
                    Text("Your past days collect here, every playlist with its story.", style = T.body, color = C.text2,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
                }
            }
            days.forEach { d ->
                item(key = "d-${d.day}") {
                    Text(d.label, style = T.headline, color = C.text, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 6.dp))
                }
                items(d.sessions, key = { it.id }) { s -> LibraryRow(s, onClick = { onOpen(s) }) }
            }
            if (onMore != null && days.isNotEmpty()) {
                item(key = "more") {
                    Chip("Show earlier days", onClick = onMore, modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp))
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(s: Session, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${s.name}", onClick = onClick).padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mosaic(s.tracks, 56.dp, 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = T.bodyStrong, color = C.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.durationMin} min · ${s.count} songs" + (s.story.title?.let { " · $it" } ?: ""), style = T.tabular, color = C.text2,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// -- connecting, and the state of things ----------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    firstRun: Boolean,
    server: String,
    token: String,
    status: Status?,
    loading: Boolean,
    error: String?,
    onConnect: (String, String) -> Unit,
    onBack: (() -> Unit)?,
) {
    var srv by rememberSaveable { mutableStateOf(server) }
    var tok by rememberSaveable { mutableStateOf(token) }
    var show by rememberSaveable { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
        Ambient(hourTint(9))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(top = top + 8.dp, bottom = 32.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) GlassCircle(R.drawable.ic_back, "Back", onBack)
            }
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(if (firstRun) "Hello" else "Settings", style = T.display, color = C.text, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
                Text(
                    "telly runs on your homelab. Paste its address and the token from Bitwarden (telly/token); it stays on this phone.",
                    style = T.body, color = C.text2,
                )
                Spacer(Modifier.height(24.dp))
                Field("Server", srv, { srv = it }, KeyboardType.Uri)
                Spacer(Modifier.height(10.dp))
                Field("Token", tok, { tok = it }, KeyboardType.Password, secret = !show, trailing = if (show) "Hide" else "Show", onTrailing = { show = !show })
                Spacer(Modifier.height(18.dp))
                PrimaryPill(if (firstRun) "Connect" else "Save and check", null, onClick = { onConnect(srv, tok) }, busy = loading,
                    enabled = tok.isNotBlank(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = T.caption, color = C.problem, modifier = Modifier.padding(top = 12.dp)) }
                status?.let { StatusCard(it) }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, type: KeyboardType, secret: Boolean = false,
                  trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(C.glass).border(1.dp, C.hairline, shape).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = T.small, color = C.text3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value, onValueChange = onValue, singleLine = true, textStyle = T.body.copy(color = C.text),
                cursorBrush = SolidColor(C.text), keyboardOptions = KeyboardOptions(keyboardType = type),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
            if (trailing != null && onTrailing != null) {
                Text(trailing, style = T.caption, color = C.text2, modifier = Modifier.clickable(role = Role.Button, onClick = onTrailing).padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(s: Status) {
    val n = NumberFormat.getIntegerInstance(Locale.getDefault())
    Column(Modifier.padding(top = 28.dp)) {
        Text("Connected", style = T.small, color = C.text3)
        StatusRow("YouTube Music", s.ytm.account?.let { "Signed in as $it" } ?: (s.ytm.error ?: "Not connected"), s.ytm.ok)
        StatusRow("Spotify", s.spotify.account?.let { "Signed in as $it" } ?: "Not connected yet", s.spotify.ok)
        StatusRow("Stories", if (s.llm) "Written by your Claude" else "Off: no language model configured", s.llm)
        StatusRow("Words", if (s.lyrics) "For what's playing, timed to every word (the LeZeppLean helper says what plays)"
            else "Off: the server has no lyrics service", s.lyrics)
        s.catalog?.let { c ->
            val built = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(c.builtAt * 1000))
            StatusRow("Your music", "${n.format(c.songs)} songs you know, ${n.format(c.new)} new ones lined up (from $built)", true)
        }
    }
}

@Composable
private fun StatusRow(name: String, detail: String, ok: Boolean, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (ok) C.text else C.text3))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = T.bodyStrong, color = C.text)
            Text(detail, style = T.caption, color = C.text2)
        }
        action?.let { (label, go) ->
            Text(label, style = T.bodyStrong, color = C.text,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = go).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}
