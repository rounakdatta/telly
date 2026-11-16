package club.taptappers.telly

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import club.taptappers.telly.data.CatalogInfo
import club.taptappers.telly.data.Day
import club.taptappers.telly.data.LibraryDay
import club.taptappers.telly.data.LogEntry
import club.taptappers.telly.data.Remote
import club.taptappers.telly.data.Service
import club.taptappers.telly.data.Session
import club.taptappers.telly.data.Status
import club.taptappers.telly.data.Story
import club.taptappers.telly.data.Track
import club.taptappers.telly.ui.Ambient
import club.taptappers.telly.ui.Composer
import club.taptappers.telly.ui.LibraryScreen
import club.taptappers.telly.ui.LocalEffects
import club.taptappers.telly.ui.Note
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

    private val noop = PlaylistActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {})

    @Composable
    private fun Canvas(day: Day?, loaded: Boolean = true, note: Note? = null, draft: String = "") {
        val haze = HazeState()
        Box(Modifier.fillMaxSize()) {
            Ambient(hourTint(17))
            TodayScreen(day, loaded, null, haze, {}, {}, {}, {}, {}, {}, 96.dp)
            Composer(draft, {}, "What's today?", false, {}, {}, haze, note, {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test
    fun today_blank() = shoot("1-today-blank") {
        Canvas(Day("2026-11-16", "Sunday, 16 November", true, "26°, partly cloudy",
            lensIdeas = listOf("20th-century history", "Something romantic", "Pop culture of the 2000s", "Indiranagar", "The Jazz Age")))
    }

    @Test
    fun today_with_playlists() = shoot("2-today-canvas") {
        Canvas(Day("2026-11-16", "Sunday, 16 November", true, "26°, partly cloudy", lens = "20th-century history", sessions = listOf(run, walk)),
            note = Note("Added a few Bengali songs into the middle of your run."), draft = "")
    }

    @Test
    fun playlist() = shoot("3-playlist") {
        val haze = HazeState()
        Box(Modifier.fillMaxSize()) {
            PlaylistScreen(run, null, haze, noop, 96.dp)
            Composer("", {}, "Change this playlist…", false, {}, {}, haze, null, {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test
    fun playlist_saving() = shoot("4-playlist-saving") {
        val haze = HazeState()
        Box(Modifier.fillMaxSize()) {
            PlaylistScreen(walk.copy(ytm = Remote("syncing"), story = Story("writing"), version = 1), "calmer", haze, noop, 96.dp)
            Composer("swap the third song", {}, "Change this playlist…", true, {}, {}, haze, null, {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test
    fun library() = shoot("5-library") {
        LibraryScreen(listOf(LibraryDay("2026-11-15", "Saturday, 15 November", listOf(walk.copy(id = "a1"), run.copy(id = "a2"))),
            LibraryDay("2026-11-13", "Thursday, 13 November", listOf(run.copy(id = "a3", title = "morning focus")))), true, {}, {}, {})
    }

    @Test
    fun first_run() = shoot("6-first-run") {
        SettingsScreen(true, "https://telly.taptappers.club", "", null, false, null, { _, _ -> }, null)
    }

    @Test
    fun settings() = shoot("7-settings") {
        SettingsScreen(false, "https://telly.taptappers.club", "secret-token-value", Status(
            catalog = CatalogInfo(36044, 5808, 1794830037), llm = true, today = "2026-11-16", place = "Bengaluru",
            ytm = Service(true, "Rounak Datta"), spotify = Service(false, error = "not connected")), false, null, { _, _ -> }, {})
    }

    companion object {
        private fun tr(n: Int, title: String, artist: String, pool: String, pinned: Boolean = false) =
            Track(n, "$artist|$title", title, artist, pool, "", whyOf(pool), pool.startsWith("new"), pinned, null, 230)

        private fun whyOf(pool: String) = when (pool) {
            "comfort" -> "A favourite: 45 plays"
            "forgotten" -> "Forgotten favourite: 20 plays, none since Sep 2024"
            "new_known" -> "New to you, from an artist you play a lot"
            else -> "Well known: 13 plays"
        }

        val run = Session(
            id = "1bc0ef5357", day = "2026-11-16", title = "6:30 pm run", activity = "run", start = "18:30", startLabel = "6:30 pm",
            minutes = 40, durationMin = 41, count = 10, summary = "Mostly songs you know, 1 new, 2 forgotten favourites; climbing to a long peak.",
            tracks = listOf(
                tr(1, "Hosanna", "A.R. Rahman", "asked", pinned = true), tr(2, "Without Me", "Halsey", "comfort"),
                tr(3, "Geet Gobindo", "Anupam Roy", "comfort"), tr(4, "Din Bari Jay", "Anupam Roy", "familiar"),
                tr(5, "You & Me", "James TW", "comfort"), tr(6, "Cruel Summer", "Taylor Swift", "comfort"),
                tr(7, "Tomake Male", "Rupam Islam", "forgotten"), tr(8, "Dil Ibaadat", "Pritam", "new_known"),
                tr(9, "Wolves", "Selena Gomez", "comfort"), tr(10, "Zack And Codeine", "Post Malone", "comfort"),
            ),
            version = 4, ytm = Remote("synced", "https://music.youtube.com/playlist?list=PLx"), spotify = Remote("off"),
            story = Story("ready", "Roses (Imanbek Remix) — SAINt JHN",
                "Four years before it soundtracked your run, \"Roses\" was a modest 2016 track by the Guyanese-American rapper SAINt JHN.\n\nThen a nineteen-year-old Kazakh producer named Imanbek, working from Aqtobe, pitched the vocals up and rebuilt the beat.",
                "Song Machine — John Seabrook (book)", true),
            weather = "25°, overcast",
            log = listOf(LogEntry(0.0, "prompt", "going for a run at 6:30 pm, about 40 minutes", "Set up a 40-minute run that builds to a long peak."),
                LogEntry(0.0, "prompt", "add some Bengali songs in the middle", "Added a few Bengali songs into the middle of your run.")),
        )

        val walk = run.copy(id = "77aa001122", title = "post-dinner walk", activity = "walk", start = "21:30", startLabel = "9:30 pm",
            minutes = 30, durationMin = 31, count = 8, summary = "Mostly songs you know, 2 new; gliding down.", weather = "22°, clear",
            tracks = run.tracks.reversed().take(8), log = emptyList())
    }
}
