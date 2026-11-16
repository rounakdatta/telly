package club.taptappers.telly.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.Normalizer
import kotlin.math.abs

/**
 * Android shows the music players' media sessions only to apps the person has given notification access. This service
 * exists for that permission alone: telly never reads a notification.
 */
class NowPlayingListener : NotificationListenerService()

/** What the phone's player is playing, and where in it, as of `at` (elapsedRealtime). */
data class Playing(
    val app: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    /** Sounding now: the position runs forward. */
    val playing: Boolean,
    /** Starting or stalled: it will sound in a moment (the button offers pause), the position holds. */
    val buffering: Boolean,
    val positionMs: Long,
    val at: Long,
    val speed: Float,
    val art: Bitmap? = null,
) {
    /** The position now, run forward from the player's last report (no polling: the player says when it changes). */
    fun position(now: Long = SystemClock.elapsedRealtime()): Long =
        if (!playing) positionMs else (positionMs + (now - at) * speed).toLong().coerceIn(0, durationMs ?: Long.MAX_VALUE)

    /** The same song from the same player: its lyrics are asked for again only when this changes. */
    val key: String get() = "$app|$title|$artist|${durationMs ?: 0}"

    fun report(): PlayerState = PlayerState(
        app = app, title = title, artist = artist, album = album, durationMs = durationMs,
        state = when { playing -> "playing"; buffering -> "buffering"; else -> "paused" },
        positionMs = position(), speed = speed, sentAt = System.currentTimeMillis(),
    )
}

/**
 * Follows the phone's media sessions while telly is open (no background service, nothing running when it isn't):
 * which song is playing in YouTube Music or Spotify, where in it, and the controls to play, pause, skip and seek.
 */
class NowPlaying(private val context: Context) {
    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing
    private var manager: MediaSessionManager? = null
    private val component = ComponentName(context, NowPlayingListener::class.java)
    /** Every player with a session, each with the callback registered on that very controller (to take it off again). */
    private val followed = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    /** The system's order: the most recently active first. */
    private var order: List<MediaSession.Token> = emptyList()
    private var current: MediaController? = null

    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    fun start() {
        if (manager != null || !hasAccess()) return
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, component)
            manager = m
            follow(m.getActiveSessions(component))
        } catch (_: SecurityException) {
            runCatching { m.removeOnActiveSessionsChangedListener(sessionsChanged) } // access withdrawn mid-way
            manager = null
        }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        manager = null
        follow(emptyList())
    }

    /** Listen to every player, not just the one shown: the one to show changes without the list changing (Spotify
     *  starts while YouTube Music sits paused). */
    private fun follow(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        followed.keys.filter { it !in tokens }.forEach { t -> followed.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        list.forEach { c ->
            if (c.sessionToken !in followed) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = choose()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
                    override fun onSessionDestroyed() {
                        manager?.let { m -> runCatching { follow(m.getActiveSessions(component)) } }
                    }
                }
                c.registerCallback(cb)
                followed[c.sessionToken] = c to cb
            }
        }
        order = list.map { it.sessionToken }
        choose()
    }

    /** The player worth showing: one that is playing, else the one shown already, else the latest with a song. */
    private fun choose() {
        val live = order.mapNotNull { followed[it]?.first }.filter { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
        current = live.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: current?.let { c -> live.firstOrNull { it.sessionToken == c.sessionToken } }
            ?: live.firstOrNull()
        publish()
    }

    private fun publish() {
        val c = current
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || md == null || title.isNullOrBlank()) {
            _playing.value = null
            return
        }
        val ps = c.playbackState
        val prev = _playing.value
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val sameSong = prev != null && prev.title == title && prev.artist == artist && prev.app == c.packageName
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: if (sameSong) prev?.art else null
        _playing.value = Playing(
            app = c.packageName, title = title, artist = artist, album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            playing = ps?.state == PlaybackState.STATE_PLAYING, buffering = ps?.state == PlaybackState.STATE_BUFFERING,
            positionMs = ps?.position?.coerceAtLeast(0) ?: 0L, at = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f, art = art,
        )
    }

    fun playPause() {
        val c = current ?: return
        val s = c.playbackState?.state
        if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = current?.transportControls?.skipToNext()
    fun previous() = current?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = current?.transportControls?.seekTo(ms)
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

/** A song's name however a player spells it: 'Kesariya (From "Brahmastra")', '… - Remastered 2011', 'feat. …'. */
fun songKey(title: String): String = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "")
    .replace(BRACKETS, "").replace(DASH_TAIL, "").replace(FEAT, "").replace(NOT_WORD, " ").trim()

private fun artistWords(artist: String): Set<String> = songKey(artist).split(' ').filter { it.length >= 3 }.toSet()

/**
 * The playlist and place of the song playing, looking in `sessions` in order (the open playlist first): the same
 * name, and an artist in common (or, when the player names the artist differently, the same length).
 */
fun locate(p: Playing?, sessions: List<Session>): Spot? {
    if (p == null) return null
    val name = songKey(p.title).ifEmpty { return null }
    val artists = artistWords(p.artist)
    for (s in sessions) {
        s.tracks.forEachIndexed { i, t ->
            if (songKey(t.title) == name && (artists.isEmpty() || artistWords(t.artist).any { it in artists } ||
                    (p.durationMs != null && t.durationS > 0 && abs(p.durationMs / 1000 - t.durationS) <= 3))) return Spot(s, i)
        }
    }
    return null
}
