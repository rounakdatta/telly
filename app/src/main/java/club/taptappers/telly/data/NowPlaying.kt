package club.taptappers.telly.data

import android.os.SystemClock
import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.abs

/**
 * What the phone's player is doing, as LeZeppLean's helper app last reported it (telly asks the server; it doesn't read
 * the player itself: that takes Android's notification access, and Google blocks sideloading a new app that asks for
 * it). `positionMs` is where the song was when the server answered.
 */
@Serializable
data class PhoneView(
    val version: Int = -1,
    val app: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val playing: Boolean = false,
    /** Playing, or about to (buffering): the player's music. */
    val active: Boolean = false,
    /** The helper went quiet while the music played (Android holds it back with the screen off): taken to play on. */
    val stale: Boolean = false,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val songId: String? = null,
    /** How long since the helper last spoke. */
    val ageMs: Long? = null,
)

/** The song on the phone, and where in it as of `at` (elapsedRealtime). */
data class Playing(
    val app: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    /** Sounding now: the position runs forward. */
    val playing: Boolean,
    val positionMs: Long,
    val at: Long,
    val speed: Float,
    /** LeZeppLean's id for it, once it has worked out which song this is. */
    val songId: String?,
) {
    /** The position now, run forward from the last report: nothing is polled to move the words along. */
    fun position(now: Long = SystemClock.elapsedRealtime()): Long =
        if (!playing) positionMs else (positionMs + (now - at) * speed).toLong().coerceIn(0, durationMs ?: Long.MAX_VALUE)

    /** The same song from the same player: its lyrics are asked for again only when this changes. */
    val key: String get() = "$app|$title|$artist|${durationMs ?: 0}"

    /** The app that plays it, by name, for "Open in …". */
    val player: String
        get() = when {
            app.contains("spotify") -> "Spotify"
            app.contains("youtube.music") -> "YouTube Music"
            app.contains("youtube") -> "YouTube"
            else -> "the player"
        }

    companion object {
        /** A paused song stays on screen this long after the helper last spoke; then it's taken as put away. */
        private const val PAUSED_SHOWN_MS = 20 * 60 * 1000L
        /** A helper gone quiet while its song played is believed this long (the watch lets go after 5 minutes too). */
        private const val STALE_SHOWN_MS = 5 * 60 * 1000L
        /** The answer took about this long to arrive: the position is moved on by it. */
        private const val TRANSIT_MS = 120L

        /** The phone's view as of now -> what to show, or null when nothing is (or was lately) playing. */
        fun of(v: PhoneView, receivedAt: Long = SystemClock.elapsedRealtime()): Playing? {
            val title = v.title?.takeIf { it.isNotBlank() } ?: return null
            if (!v.active && (v.ageMs ?: Long.MAX_VALUE) > PAUSED_SHOWN_MS) return null
            // A helper silent for minutes while "playing" has stopped (or been killed): the song isn't still going.
            if (v.stale && (v.ageMs ?: 0) > STALE_SHOWN_MS) return null
            return Playing(app = v.app.orEmpty(), title = title, artist = v.artist.orEmpty(), durationMs = v.durationMs?.takeIf { it > 0 },
                playing = v.playing, positionMs = v.positionMs.coerceAtLeast(0), at = receivedAt - TRANSIT_MS,
                speed = v.speed.takeIf { it > 0f } ?: 1f, songId = v.songId)
        }
    }
}

/** Where the song playing sits in telly's playlists. */
data class Spot(val session: Session, val index: Int) {
    val track: Track get() = session.tracks[index]
}

private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
private val DASH_TAIL = Regex("""\s+-\s+.*$""")
private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
private val MARKS = Regex("""\p{M}+""")
private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
private val NOT_NAMES = setOf("the", "and", "feat", "with", "los", "las", "les", "der", "die", "das")

/** A song's name however a player spells it: 'Kesariya (From "Brahmastra")', '… - Remastered 2011', 'feat. …'. */
fun songKey(title: String): String = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "")
    .replace(BRACKETS, "").replace(DASH_TAIL, "").replace(FEAT, "").replace(NOT_WORD, " ").trim()

private fun artistWords(artist: String): Set<String> = songKey(artist).split(' ').filter { it.length >= 3 && it !in NOT_NAMES }.toSet()

/**
 * The playlist and place of the song playing, looking in `sessions` in order (the open playlist first): the same
 * name, and an artist in common (or, when the player names the artist differently, the same length).
 */
fun locate(p: Playing?, sessions: List<Session>): Spot? {
    if (p == null) return null
    val name = songKey(p.title).ifEmpty { return null }
    val artists = artistWords(p.artist)
    val artist = songKey(p.artist)
    for (s in sessions) {
        s.tracks.forEachIndexed { i, t ->
            // "U2" or "MØ" have no word long enough to compare: then the whole name must match.
            val sameArtist = artistWords(t.artist).any { it in artists } || (artist.isNotEmpty() && songKey(t.artist) == artist)
            val sameLength = p.durationMs != null && t.durationS > 0 && abs(p.durationMs / 1000 - t.durationS) <= 3
            if (songKey(t.title) == name && (sameArtist || sameLength)) return Spot(s, i)
        }
    }
    return null
}
