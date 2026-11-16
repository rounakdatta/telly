# telly

telly tells tales. Each day starts as a blank canvas. You say what the day holds, like "a run at 6 pm, 40 minutes" or
"a walk after dinner", and telly makes the music for each part of it. Every playlist comes from your own listening
history, and each one carries a story about one of its songs. Keep changing them, as often as you like:

- **Type a change:** "more songs I know", "some Bengali in the middle", "swap the third one", "play Hosanna first".
- **Tap a chip:** More familiar, More new, Calmer, More energy, Shorter, Longer or Reshuffle.

Every change makes a new version, so any change can be undone.

The playlists live in your YouTube Music (and in Spotify, once it is connected). telly writes and rewrites them in the
background, and Play opens them in the app you already listen with.

## How it fits together

```
app/      Android app (Kotlin, Compose). The canvas, the playlists and their liner notes, earlier days.
server/   The engine, as an HTTP API (Python, standard library only, SQLite). Runs on the homelab.
tools/    bake_catalog.py: turns a music-taste workspace into the catalogue the server reads.
```

**The server holds three things on its volume (`/data`):**

- `catalog.db`: the songs you know (plays, skips, likes, world, sound), the new songs lined up for you, and how your
  worlds mix by hour and by activity. It is built offline by `tools/bake_catalog.py` and uploaded with
  `PUT /admin/catalog`.
- `telly.db`: every day's canvas, each playlist and every version of it, plus the song-match cache.
- The music-service sign-ins, set through `POST /admin/ytm` (cookies from a signed-in music.youtube.com) and
  `POST /admin/spotify` (a Development Mode app's client id, secret and refresh token).

None of this goes into the repository or the image.

**How a message becomes a playlist:**

1. Claude, through an Anthropic-shaped gateway, turns your words into edits to a *brief*: activity, start time,
   minutes, share of familiar songs, energy arc, world tilts, wanted counts, and edits by song.
2. `server/telly/engine.py` builds the list from that brief:
   - Familiarity pools come from the measured flow curve, so favourites rest between appearances, longer in worlds
     that wear out fast.
   - New songs are scored by how often music like them has stuck for you.
   - Each activity has its own energy arc, and a strong familiar song opens and a favourite closes.
   - Earlier choices stay put unless they no longer fit.
3. The background worker (`server/telly/sync.py`) syncs the playlist:
   - It finds each song on YouTube Music and writes the playlist there, in place, so the link stays the same.
   - It fetches the artwork.
   - It writes the story, grounded in Wikipedia, and checks the "go deeper" pointer.

Without the gateway, telly still answers: simple rules read the common requests, and the chips always work.

## Run it

```bash
cd server
TELLY_TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))') \
TELLY_LLM_URL=https://your-gateway TELLY_LLM_KEY=... \
python3 -m telly --data ./data --port 8080
python3 -m unittest discover -s tests -v
```

The app asks for the server's address and token on first open. Every API route except `/livez` needs
`Authorization: Bearer <TELLY_TOKEN>`.

## Build and release

There is no local Android build: CI does it.

- **Android** (`.github/workflows/android.yml`):
  - On every PR it builds the APK and photographs every screen with Roborazzi. Both are uploaded as artifacts.
  - On `main` it publishes the APK as a release. The APK is debug-signed with the checked-in key, so each build
    installs over the last.
- **Server** (`.github/workflows/server.yml`):
  - Tests run on every change.
  - Branch pushes publish `ghcr.io/rounakdatta/telly-server:sha-<commit>`.
  - A `server-vX.Y.Z` tag publishes `:X.Y.Z`.

telly 1.x was a tale runner: scheduled Gmail, Strava, Hevy and Health Connect automations. It lives on at the git tag
`tales-final`.
