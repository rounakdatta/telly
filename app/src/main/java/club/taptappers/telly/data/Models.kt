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
    val ytm: Remote = Remote(),
    val spotify: Remote = Remote(),
    val story: Story = Story(),
    val weather: String? = null,
    val log: List<LogEntry> = emptyList(),
    val created: Double = 0.0,
    val updated: Double = 0.0,
) {
    /** The title as shown: the planner writes them lowercase ("post-dinner walk"); a title starts with a capital. */
    val name: String get() = title.replaceFirstChar { it.uppercaseChar() }

    /** Still settling: a playlist being written to a service, or a story being written. */
    val settling: Boolean
        get() = ytm.state in BUSY || spotify.state in BUSY || story.state in setOf("pending", "writing")

    companion object {
        val BUSY = setOf("pending", "syncing")
    }
}

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
data class Remote(val state: String = "pending", val url: String? = null, val error: String? = null)

@Serializable
data class Story(
    val state: String = "pending",
    val title: String? = null,
    val body: String? = null,
    val deeper: String? = null,
    @SerialName("deeper_checked") val deeperChecked: Boolean = false,
)

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
)

@Serializable
data class CatalogInfo(val songs: Int = 0, val new: Int = 0, @SerialName("built_at") val builtAt: Long = 0)

@Serializable
data class Service(val ok: Boolean = false, val account: String? = null, val error: String? = null)
