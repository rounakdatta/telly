package club.taptappers.telly.data

import kotlinx.serialization.Serializable

/** The server's /api/now and /api/lyrics answers (server/telly/lyrics.py, server/telly/genius.py). */
@Serializable
data class LyricLine(val start: Long = 0, val text: String = "", val words: List<Long>? = null, val end: Long? = null)

/** How far the words are from being timed to the recording: pending (working on it), ready, or unavailable. */
@Serializable
data class WordsStatus(val status: String = "pending", val aligned: Int? = null, val of: Int? = null)

@Serializable
data class SongRef(val id: String? = null, val title: String? = null, val artist: String? = null)

/** A note from Genius placed on one line (its index in `lines`), in the words of a friend leaning over. */
@Serializable
data class LineNote(val line: Int = 0, val text: String = "")

@Serializable
data class Trivia(
    /** pending (being read and condensed) | ready | none */
    val status: String = "pending",
    /** A few things to say as the song starts or in its pauses. */
    val about: List<String> = emptyList(),
    val lines: List<LineNote> = emptyList(),
    val url: String? = null,
)

@Serializable
data class LyricsResult(
    val song: SongRef? = null,
    /** synced | plain | pending | none */
    val kind: String = "pending",
    val version: String? = null,
    val lines: List<LyricLine> = emptyList(),
    val words: WordsStatus = WordsStatus(),
    val trivia: Trivia? = null,
    /** The lines are the ones already on the phone (asked with ?known=): keep those. */
    val unchanged: Boolean = false,
    /** The song's cover (Apple's, 600 px), when it was found. */
    val art: String? = null,
) {
    /** Worth asking again: its lyrics are still being found, its words timed, or its notes read. */
    val settling: Boolean
        get() = kind == "pending" || (kind == "synced" && words.status == "pending") || trivia?.status == "pending"
}
