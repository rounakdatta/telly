package club.taptappers.telly

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import club.taptappers.telly.data.Brief
import club.taptappers.telly.data.CatalogInfo
import club.taptappers.telly.data.Day
import club.taptappers.telly.data.LibraryDay
import club.taptappers.telly.data.LineNote
import club.taptappers.telly.data.LogEntry
import club.taptappers.telly.data.LyricLine
import club.taptappers.telly.data.LyricsResult
import club.taptappers.telly.data.Playing
import club.taptappers.telly.data.SongRef
import club.taptappers.telly.data.Spot
import club.taptappers.telly.data.Trivia
import club.taptappers.telly.data.WordsStatus
import club.taptappers.telly.data.Remote
import club.taptappers.telly.data.Service
import club.taptappers.telly.data.Session
import club.taptappers.telly.data.Status
import club.taptappers.telly.data.Story
import club.taptappers.telly.data.Track
import club.taptappers.telly.ui.Ambient
import club.taptappers.telly.ui.Composer
import club.taptappers.telly.ui.Exchange
import club.taptappers.telly.ui.ExchangeCard
import club.taptappers.telly.ui.LibraryScreen
import club.taptappers.telly.ui.LyricsScreen
import club.taptappers.telly.ui.MiniPlayer
import club.taptappers.telly.ui.LocalEffects
import club.taptappers.telly.ui.Phase
import club.taptappers.telly.ui.PlaylistActions
import club.taptappers.telly.ui.PlaylistScreen
import club.taptappers.telly.ui.SettingsScreen
import club.taptappers.telly.ui.TellyTheme
import club.taptappers.telly.ui.TodayScreen
import club.taptappers.telly.ui.hourTint
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen, photographed at phone size with real-looking data, so the design can be looked at on every change
 * (CI uploads the PNGs). Artwork doesn't load here (no network), so covers show their placeholder tiles.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalEffects provides false) { TellyTheme { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val noop = PlaylistActions({}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {})

    @Composable
    private fun WithComposer(placeholder: String, ex: Exchange?, session: Session? = null, content: @Composable (HazeState) -> Unit) {
        val haze = HazeState()
        Box(Modifier.fillMaxSize()) {
            content(haze)
            Composer("", {}, placeholder, ex?.phase == Phase.WORKING, {}, {}, haze, Modifier.align(Alignment.BottomCenter)) {
                ex?.let { ExchangeCard(it, session, haze, onOpen = {}, onDismiss = {}, modifier = Modifier.padding(bottom = 10.dp)) }
            }
        }
    }

    @Composable
    private fun Canvas(day: Day?, ex: Exchange? = null) = WithComposer("What's today?", ex, day?.sessions?.firstOrNull()) { haze ->
        Ambient(hourTint(17))
        TodayScreen(day, true, null, haze, {}, {}, {}, {}, {}, {}, 96.dp)
    }

    @Test
    fun today_blank() = shoot("1-today-blank") {
        Canvas(Day("2026-11-16", "Monday, 16 November", true, "26°, partly cloudy",
            lensIdeas = listOf("20th-century history", "Something romantic", "Pop culture of the 2000s", "Indiranagar", "The Jazz Age")))
    }

    @Test
    fun today_reading() = shoot("2-today-reading") {
        Canvas(Day("2026-11-16", "Monday, 16 November", true, "26°, partly cloudy", lens = "20th-century history", sessions = listOf(walk)),
            Exchange("a run at 6:30 pm, about 40 minutes", Phase.WORKING))
    }

    @Test
    fun today_done() = shoot("3-today-done") {
        Canvas(Day("2026-11-16", "Monday, 16 November", true, "26°, partly cloudy", lens = "20th-century history", sessions = listOf(run, walk)),
            Exchange("a run at 6:30 pm, about 40 minutes", Phase.DONE, "Made a 40-minute run that climbs to a long peak.", target = run.id))
    }

    @Test
    fun playlist() = shoot("4-playlist") {
        WithComposer("Change this playlist…", null) { haze -> PlaylistScreen(run, null, false, setOf(run.tracks[3].id), haze, noop, 96.dp) }
    }

    @Test
    fun playlist_updating() = shoot("5-playlist-updating") {
        WithComposer("Change this playlist…", Exchange("more songs I know, and some Bengali in the middle", Phase.WORKING, target = run.id), run) { haze ->
            PlaylistScreen(run, null, true, emptySet(), haze, noop, 96.dp)
        }
    }

    @Test
    fun playlist_changed() = shoot("6-playlist-changed") {
        WithComposer("Change this playlist…", Exchange("Instrumental", Phase.DONE, "Instrumental only now.", added = 6, removed = 6, target = run.id),
            run.copy(notesState = "writing")) { haze ->
            PlaylistScreen(run.copy(brief = Brief(instrumental = true), notesState = "writing"), null, false, emptySet(), haze, noop, 96.dp)
        }
    }

    @Test
    fun library() = shoot("7-library") {
        LibraryScreen(listOf(LibraryDay("2026-11-15", "Sunday, 15 November", listOf(walk.copy(id = "a1"), run.copy(id = "a2"))),
            LibraryDay("2026-11-13", "Friday, 13 November", listOf(run.copy(id = "a3", title = "morning focus")))), true, {}, {}, {})
    }

    @Test
    fun first_run() = shoot("8-first-run") {
        SettingsScreen(true, "https://telly.taptappers.club", "", null, false, null, { _, _ -> }, null)
    }

    @Test
    fun settings() = shoot("9-settings") {
        SettingsScreen(false, "https://telly.taptappers.club", "secret-token-value", Status(
            catalog = CatalogInfo(30000, 5000, 1794830037), llm = true, today = "2026-11-16", place = "Bengaluru",
            ytm = Service(true, "you"), spotify = Service(true, "you")), false, null, { _, _ -> }, {})
    }

    @Test
    fun lyrics_singing() = shoot("10-lyrics-singing") {
        LyricsScreen(rhapsody, rhapsodyWords, null, null, null, {}, {}, {}, {}, null)
    }

    @Test
    fun lyrics_opening_note() = shoot("11-lyrics-opening") {
        LyricsScreen(rhapsody.copy(positionMs = 2_000), rhapsodyWords.copy(lines = rhapsodyWords.lines.map { it.copy(start = it.start + 9_000, end = it.end?.plus(9_000)) }),
            null, Spot(run.copy(tracks = run.tracks.mapIndexed { i, t ->
                if (i == 3) t.copy(title = "Bohemian Rhapsody", artist = "Queen", note = "You've played this 31 times; first in June 2019.") else t
            }), 3), null, {}, {}, {}, {}, null)
    }

    @Test
    fun lyrics_finding() = shoot("12-lyrics-finding") {
        LyricsScreen(rhapsody, null, null, null, null, {}, {}, {}, {}, null)
    }

    @Test
    fun lyrics_nothing_playing() = shoot("13-lyrics-nothing") {
        LyricsScreen(null, null, null, null, null, {}, {}, {}, {}, "6:30 pm run" to "https://music.youtube.com/watch?v=x&list=PLx")
    }

    @Test
    fun today_playing() = shoot("14-today-playing") {
        val haze = HazeState()
        Box(Modifier.fillMaxSize()) {
            Ambient(hourTint(17))
            TodayScreen(Day("2026-11-16", "Monday, 16 November", true, "26°, partly cloudy", sessions = listOf(run, walk)), true, null, haze,
                {}, {}, {}, {}, {}, {}, 162.dp)
            Composer("", {}, "What's today?", false, {}, {}, haze, Modifier.align(Alignment.BottomCenter)) {
                MiniPlayer(rhapsody, rhapsodyWords, null, {}, haze, Modifier.padding(bottom = 10.dp))
            }
        }
    }

    companion object {
        /** Paused 10.7 s in: the second line half sung (its words timed), a whisper beneath the first. */
        val rhapsody = Playing("com.google.android.apps.youtube.music", "Bohemian Rhapsody", "Queen", 354_320,
            playing = false, positionMs = 10_700, at = 0, speed = 1f, songId = "p121ce7db5300")

        val rhapsodyWords = LyricsResult(
            song = SongRef("p121ce7db5300", "Bohemian Rhapsody", "Queen"), kind = "synced", version = "v1",
            lines = listOf(
                LyricLine(663, "Is this the real life? Is this just fantasy?", listOf(663, 903, 1303, 1683, 2523, 4383, 4603, 4963, 5363), 6243),
                LyricLine(7663, "Caught in a landslide, no escape from reality", listOf(7663, 8343, 8643, 8863, 10623, 11103, 11783, 12183), 13803),
                LyricLine(15243, "Open your eyes, look up to the skies and see", listOf(15243, 15883, 17343, 18203, 18763, 18983, 19463, 19683, 21383, 21683), 21883),
                LyricLine(23500, "I'm just a poor boy, I need no sympathy", null, null),
                LyricLine(28800, "Because I'm easy come, easy go", null, null),
                LyricLine(31200, "Little high, little low", null, null),
            ),
            words = WordsStatus("ready", 42, 56),
            trivia = Trivia("ready", listOf("It was the first single from A Night at the Opera, Queen's fourth album."),
                listOf(LineNote(0, "It opens a cappella: Freddie's voice, layered into a choir.")), "https://genius.com/Queen-bohemian-rhapsody-lyrics"),
        )

        private fun tr(n: Int, title: String, artist: String, pool: String, note: String? = null, pinned: Boolean = false) =
            Track(n, "$artist|$title", title, artist, pool, "", whyOf(pool), pool.startsWith("new"), pinned, null, 230, note = note)

        private fun whyOf(pool: String) = when (pool) {
            "comfort" -> "A favourite: 45 plays"
            "forgotten" -> "Forgotten favourite: 20 plays, none since Sep 2024"
            "new_known" -> "New to you, from an artist you play a lot"
            else -> "Well known: 13 plays"
        }

        val run = Session(
            id = "1bc0ef5357", day = "2026-11-16", title = "6:30 pm run", activity = "run", start = "18:30", startLabel = "6:30 pm",
            minutes = 40, durationMin = 41, count = 8, summary = "Mostly familiar, 1 new, 2 forgotten favourites. It builds to a long peak.",
            tracks = listOf(
                tr(1, "Hosanna", "A.R. Rahman", "asked", "Rahman wrote it for Vinnaithaandi Varuvaayaa; the Hindi version came two years later.", pinned = true),
                tr(2, "Without Me", "Halsey", "comfort", "You've played this 45 times; first in March 2019."),
                tr(3, "Geet Gobindo", "Anupam Roy", "comfort"),
                tr(4, "Dil Ibaadat", "Pritam", "new_known", "Listen for KK's long held note going into the last chorus."),
                tr(5, "Cruel Summer", "Taylor Swift", "comfort", "Released in 2019, it became a single four years later."),
                tr(6, "Tomake Male", "Rupam Islam", "forgotten", "Not heard since September 2024: an old friend back."),
                tr(7, "Wolves", "Selena Gomez", "comfort"),
                tr(8, "Zack And Codeine", "Post Malone", "comfort", "You've played it 72 times. The run ends on home ground."),
            ),
            version = 4, canUndo = true, ytm = Remote("synced", "https://music.youtube.com/playlist?list=PLx", play = "https://music.youtube.com/watch?v=x&list=PLx"),
            spotify = Remote("synced", "https://open.spotify.com/playlist/x"), notesState = "ready",
            story = Story("ready", "Wind of Change — Scorpions",
                "Klaus Meine wrote it after the band played Moscow in 1989.\n\nWithin two years the Berlin Wall had fallen and the song became the sound of it.",
                "Wind of Change — Patrick Radden Keefe (podcast)", true),
            weather = "25°, overcast",
            log = listOf(LogEntry(0.0, "prompt", "going for a run at 6:30 pm, about 40 minutes", "Set up a 40-minute run that builds to a long peak."),
                LogEntry(0.0, "prompt", "add some Bengali songs in the middle", "Added a few Bengali songs into the middle of your run.")),
        )

        val walk = run.copy(id = "77aa001122", title = "post-dinner walk", activity = "walk", start = "21:30", startLabel = "9:30 pm",
            minutes = 30, durationMin = 31, count = 6, summary = "Mostly familiar, 2 new. It winds down.", weather = "22°, clear",
            tracks = run.tracks.reversed().take(6), log = emptyList())
    }
}
