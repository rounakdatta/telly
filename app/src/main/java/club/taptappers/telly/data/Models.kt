package club.taptappers.telly.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The server's JSON, field for field (see server/telly/canvas.py `render`). */

@Serializable
data class Day(
    val day: String,
    val label: String,
    val today: Boolean = false,
    val weather: String? = null,
    /** What he wants to learn about today; every story leans on it. */
    val lens: String? = null,
    @SerialName("lens_ideas") val lensIdeas: List<String> = emptyList(),
    val sessions: List<Session> = emptyList(),
)

@Serializable
data class Session(
    val id: String,
    val day: String,
    val title: String,
    val activity: String,
    val start: String? = null,
    @SerialName("start_label") val startLabel: String? = null,
    val minutes: Int,
    @SerialName("duration_min") val durationMin: Int,
    val count: Int,
    val summary: String,
    val blurb: String = "",
    val tracks: List<Track> = emptyList(),
    val version: Int = 1,
    @SerialName("can_undo") val canUndo: Boolean = false,
    /** What the server says about the last edit, when it is worth saying ("Nothing else fits, so it stays."). */
    val note: String? = null,
    val ytm: Remote = Remote(),
    val spotify: Remote = Remote(),
    val story: Story = Story(),
    @SerialName("notes_state") val notesState: String = "none",
    /** A mix being made (or remade) in the background: minutes of research, its steps shown as they happen. */
    val composing: Composing? = null,
    val brief: Brief = Brief(),
    val weather: String? = null,
    val log: List<LogEntry> = emptyList(),
    val created: Double = 0.0,
    val updated: Double = 0.0,
) {
    /** The title as shown: the planner writes them lowercase ("post-dinner walk"); a title starts with a capital. */
    val name: String get() = title.replaceFirstChar { it.uppercaseChar() }

    /** A mix written as one story (an opening, a paragraph per song, a landing) rather than a note per song. */
    val composed: Boolean get() = story.intro != null

    /** Still settling: a mix being made, a playlist being written to a service, or a story being written. */
    val settling: Boolean
        get() = composing?.active == true || ytm.state in BUSY || spotify.state in BUSY || story.state in WRITING || notesState in WRITING

    /** The one thing still happening in the background, in words, or null when it has all settled. */
    val inProgress: String?
        get() = when {
            composing?.active == true -> composing.progress.lastOrNull() ?: "Making your mix…"
            ytm.state in BUSY -> "Saving to YouTube Music…"
            spotify.state in BUSY -> "Saving to Spotify…"
            notesState in WRITING -> "Writing a note for each song…"
            story.state in WRITING -> "Writing the liner notes…"
            else -> null
        }

    companion object {
        val BUSY = setOf("pending", "syncing")
        val WRITING = setOf("pending", "writing")
    }
}

@Serializable
data class Brief(val instrumental: Boolean = false, val familiar: Double = 0.7)

@Serializable
data class Track(
    val n: Int,
    val id: String,
    val title: String,
    val artist: String,
    val pool: String? = null,
    val tag: String = "",
    val why: String = "",
    val new: Boolean = false,
    val pinned: Boolean = false,
    val thumb: String? = null,
    @SerialName("duration_s") val durationS: Int = 0,
    val ytm: String? = null,
    val spotify: String? = null,
    /** One line about the song, as an excited friend would say it; arrives a little after the playlist. */
    val note: String? = null,
) {
    /** Favourites and well-known songs are the normal case and carry no mark; only the exceptions do. */
    val mark: String?
        get() = when (pool) {
            "new_known" -> "new"
            "new_frontier" -> "new artist"
            "forgotten" -> "forgotten"
            "asked" -> "asked for"
            "warm" -> "getting to know"
            else -> null
        }
}

@Serializable
data class Remote(
    val state: String = "pending",
    val url: String? = null,
    /** Starts the first song with the whole playlist queued, in the YouTube Music app. */
    val play: String? = null,
    val error: String? = null,
)

@Serializable
data class Story(
    val state: String = "pending",
    val title: String? = null,
    val body: String? = null,
    val deeper: String? = null,
    @SerialName("deeper_checked") val deeperChecked: Boolean = false,
    /** A composed mix's story: what to read before pressing play, and the line it ends on. */
    val intro: String? = null,
    val outro: String? = null,
)

@Serializable
data class Composing(
    /** queued, working, or failed (then [error] says why, in words). */
    val state: String = "queued",
    /** The last few things it did ("Reading about Delia Derbyshire"), newest last. */
    val progress: List<String> = emptyList(),
    /** When it started (or was asked for), in epoch seconds. */
    val since: Double? = null,
    val error: String? = null,
) {
    val active: Boolean get() = state == "queued" || state == "working"
    val failed: Boolean get() = state == "failed"
}

@Serializable
data class LogEntry(val at: Double = 0.0, val source: String = "", val said: String? = null, val reply: String? = null)

@Serializable
data class PromptResult(val reply: String? = null, val focus: String? = null, val day: Day, val rough: Boolean = false)

@Serializable
data class Library(val days: List<LibraryDay> = emptyList())

@Serializable
data class LibraryDay(val day: String, val label: String, val sessions: List<Session> = emptyList())

@Serializable
data class Status(
    val catalog: CatalogInfo? = null,
    val llm: Boolean = false,
    val today: String = "",
    val place: String = "",
    val ytm: Service = Service(),
    val spotify: Service = Service(),
    /** The server can show lyrics for what's playing (it knows where LeZeppLean is). */
    val lyrics: Boolean = false,
)

@Serializable
data class CatalogInfo(val songs: Int = 0, val new: Int = 0, @SerialName("built_at") val builtAt: Long = 0)

@Serializable
data class Service(val ok: Boolean = false, val account: String? = null, val error: String? = null)
