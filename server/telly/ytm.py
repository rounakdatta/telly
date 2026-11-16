"""YouTube Music over innertube, signed in with a browser session's cookies (what ytmusicapi calls browser auth).

Google rotates some session cookies on every few responses; the client folds each Set-Cookie back into its jar and
hands the jar to `on_rotate`, so the stored session keeps up instead of going stale.
"""
import hashlib
import http.cookies
import json
import re
import threading
import time
import urllib.error
import urllib.request
from difflib import SequenceMatcher

from .textnorm import nartist, ntrack

ORIGIN = 'https://music.youtube.com'
UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36'
SONGS_FILTER = 'EgWKAQIIAWoMEA4QChADEAQQCRAF'
VARIANT = re.compile(r'(?i)(remix|\bmix\b|version|\blive\b|lo-?fi|slowed|reverb|sped up|acoustic|unplugged|cover|karaoke|instrumental|8d)')


class AuthError(Exception):
    """The session is signed out or refused: the stored cookies need replacing."""


def cookies_from_header(header):
    out = {}
    for part in (header or '').split(';'):
        if '=' in part:
            k, v = part.strip().split('=', 1)
            if k:
                out[k] = v
    return out


def art(url, px=544):
    """A square artwork URL at a given size (lh3 URLs take their size as a suffix)."""
    if not url:
        return None
    if 'googleusercontent.com' in url or 'ggpht.com' in url:
        return re.sub(r'=w\d+-h\d+.*$', '', url) + f'=w{px}-h{px}-l90-rj'
    return url


def _runs(o):
    return ''.join(r.get('text', '') for r in (o or {}).get('runs', []))


def _walk(o, key):
    """Yield every value stored under `key` anywhere inside a JSON tree."""
    stack = [o]
    while stack:
        x = stack.pop()
        if isinstance(x, dict):
            for k, v in x.items():
                if k == key:
                    yield v
                elif isinstance(v, (dict, list)):
                    stack.append(v)
        elif isinstance(x, list):
            stack.extend(reversed(x))


def _thumb(r):
    th = (((r.get('thumbnail') or {}).get('musicThumbnailRenderer') or {}).get('thumbnail') or {}).get('thumbnails') or []
    return art(th[-1]['url']) if th else None


def _duration_s(text):
    if not text or ':' not in text or not text.replace(':', '').isdigit():
        return None
    s = 0
    for p in text.split(':'):
        s = s * 60 + int(p)
    return s


def parse_rows(r):
    """One musicResponsiveListItemRenderer -> {videoId, title, artists, album, duration_s, thumb, setVideoId}."""
    vid = ((r.get('playlistItemData') or {}).get('videoId'))
    if not vid:
        try:
            vid = r['overlay']['musicItemThumbnailOverlayRenderer']['content']['musicPlayButtonRenderer']['playNavigationEndpoint']['watchEndpoint']['videoId']
        except (KeyError, TypeError):
            vid = None
    cols = [((c.get('musicResponsiveListItemFlexColumnRenderer') or {}).get('text') or {}).get('runs', []) for c in r.get('flexColumns', [])]
    title = ''.join(x.get('text', '') for x in (cols[0] if cols else []))
    artists, album, dur = [], None, None
    for run in (cols[1] if len(cols) > 1 else []):
        be = (run.get('navigationEndpoint') or {}).get('browseEndpoint') or {}
        pt = be.get('browseEndpointContextSupportedConfigs', {}).get('browseEndpointContextMusicConfig', {}).get('pageType', '')
        if pt == 'MUSIC_PAGE_TYPE_ARTIST':
            artists.append(run['text'])
        elif pt == 'MUSIC_PAGE_TYPE_ALBUM':
            album = run['text']
        elif _duration_s(run.get('text', '')):
            dur = _duration_s(run['text'])
    for fc in r.get('fixedColumns', []) or []:
        t = _runs((fc.get('musicResponsiveListItemFixedColumnRenderer') or {}).get('text'))
        if _duration_s(t):
            dur = _duration_s(t)
    return {'videoId': vid, 'title': title, 'artists': artists, 'album': album, 'duration_s': dur, 'thumb': _thumb(r),
            'setVideoId': (r.get('playlistItemData') or {}).get('playlistSetVideoId')}


def _sim(a, b):
    return SequenceMatcher(None, a, b).ratio()


def best_match(want, rows):
    """Pick the search row that is the song asked for, or None.
    Indian film credits differ between services (composer on one, singers on the other), so a near-exact title at the
    top of the results is enough; otherwise the artist or the duration has to agree too."""
    best, best_s = None, 0.0
    want_s = (want.get('duration_ms') or 0) / 1000 or None
    for rank, s in enumerate(rows[:5]):
        if not s.get('videoId'):
            continue
        ts = _sim(ntrack(want['title']), ntrack(s['title']))
        names = [x.strip() for a in s['artists'] for x in a.replace(' & ', ',').split(',') if x.strip()]
        as_ = max([_sim(nartist(want['artist']), nartist(a)) for a in names] or [0])
        dur_ok = bool(want_s and s.get('duration_s') and abs(want_s - s['duration_s']) <= 10)
        ok = (ts >= 0.88 and rank <= 1) or (ts >= 0.75 and as_ >= 0.5) or (ts >= 0.8 and dur_ok)
        variant = bool(VARIANT.search(s['title'])) and not VARIANT.search(want['title'])
        score = 0.6 * ts + 0.3 * as_ + 0.1 * dur_ok - 0.05 * rank - (0.4 if variant else 0)
        if ok and score > best_s:
            best, best_s = s, score
    return best


class YTM:
    def __init__(self, cookies, on_rotate=None):
        self.cookies = dict(cookies)
        self.on_rotate = on_rotate
        self.lock = threading.Lock()
        self.version = '1.' + time.strftime('%Y%m%d', time.gmtime(time.time() - 7 * 86400)) + '.01.00'

    # -- transport -------------------------------------------------------------------------------------------
    def _auth(self):
        sapisid = self.cookies.get('SAPISID') or self.cookies.get('__Secure-3PAPISID')
        if not sapisid:
            raise AuthError('no SAPISID cookie: sign in again')
        ts = int(time.time())
        return f"SAPISIDHASH {ts}_{hashlib.sha1(f'{ts} {sapisid} {ORIGIN}'.encode()).hexdigest()}"

    def _absorb(self, set_cookies):
        changed = False
        for line in set_cookies:
            c = http.cookies.SimpleCookie()
            try:
                c.load(line)
            except http.cookies.CookieError:
                continue
            for name, m in c.items():
                # Only rotate cookies the session already has: Set-Cookie attributes Python doesn't know ('Priority=HIGH')
                # parse as cookies of their own and must not join the jar.
                if name not in self.cookies or m['max-age'] == '0' or m.value in ('', 'EXPIRED'):
                    continue
                if self.cookies.get(name) != m.value:
                    self.cookies[name] = m.value
                    changed = True
        if changed and self.on_rotate:
            self.on_rotate(dict(self.cookies))

    def call(self, endpoint, body, timeout=30):
        data = json.dumps({'context': {'client': {'clientName': 'WEB_REMIX', 'clientVersion': self.version, 'hl': 'en', 'gl': 'IN'}, 'user': {}},
                           **body}).encode()
        with self.lock:
            headers = {'user-agent': UA, 'content-type': 'application/json', 'accept': '*/*', 'origin': ORIGIN, 'x-origin': ORIGIN,
                       'referer': ORIGIN + '/', 'cookie': '; '.join(f'{k}={v}' for k, v in self.cookies.items()),
                       'authorization': self._auth(), 'x-goog-authuser': '0',
                       'x-youtube-client-name': '67', 'x-youtube-client-version': self.version}
        req = urllib.request.Request(f'{ORIGIN}/youtubei/v1/{endpoint}?prettyPrint=false', data=data, method='POST', headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                with self.lock:
                    self._absorb(r.headers.get_all('Set-Cookie') or [])
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            with self.lock:
                self._absorb(e.headers.get_all('Set-Cookie') or [])
            if e.code in (401, 403):
                raise AuthError(f'{endpoint}: HTTP {e.code}') from None
            raise

    # -- account ---------------------------------------------------------------------------------------------
    def whoami(self):
        """The signed-in account's name, or AuthError."""
        d = self.call('account/account_menu', {})
        logged = any(p.get('key') == 'logged_in' and p.get('value') == '1'
                     for s in (d.get('responseContext') or {}).get('serviceTrackingParams', []) for p in s.get('params', []))
        names = [_runs(h.get('accountName')) for h in _walk(d, 'activeAccountHeaderRenderer')]
        if not logged and not names:
            raise AuthError('signed out')
        return names[0] if names else 'signed in'

    # -- search ----------------------------------------------------------------------------------------------
    def search_songs(self, query):
        d = self.call('search', {'query': query, 'params': SONGS_FILTER})
        return [parse_rows(r) for r in _walk(d, 'musicResponsiveListItemRenderer')]

    def resolve(self, want):
        """{'title','artist','duration_ms'?} -> search row or None."""
        return best_match(want, self.search_songs(f"{want['artist']} {want['title']}"))

    # -- playlists -------------------------------------------------------------------------------------------
    def create(self, title, description, video_ids, privacy='PRIVATE'):
        d = self.call('playlist/create', {'title': title[:150], 'description': (description or '')[:5000],
                                          'privacyStatus': privacy, 'videoIds': list(video_ids)})
        pid = d.get('playlistId')
        if not pid:
            raise RuntimeError('playlist/create returned no id: ' + json.dumps(d)[:300])
        return pid

    def items(self, playlist_id):
        """The playlist's own rows (never the 'suggestions' shelf YouTube Music appends below them)."""
        d = self.call('browse', {'browseId': 'VL' + playlist_id})
        rows = []
        for shelf in _walk(d, 'musicPlaylistShelfRenderer'):
            for it in shelf.get('contents', []):
                r = it.get('musicResponsiveListItemRenderer')
                if r:
                    rows.append(parse_rows(r))
            cont = next(iter(_walk(shelf, 'continuationCommand')), None)
            while cont and cont.get('token'):
                more = self.call('browse', {'continuation': cont['token']})
                items = next(iter(_walk(more, 'continuationItems')), []) or []
                rows += [parse_rows(i['musicResponsiveListItemRenderer']) for i in items if 'musicResponsiveListItemRenderer' in i]
                cont = next(iter(_walk(items, 'continuationCommand')), None)
            break
        return rows

    def exists(self, playlist_id):
        """Is this playlist still there? (Someone may have deleted it in YouTube Music.)"""
        try:
            d = self.call('browse', {'browseId': 'VL' + playlist_id})
        except AuthError:
            raise
        except Exception:
            return True  # can't tell: assume it is, and let the caller report the error rather than make a duplicate
        return any(True for _ in _walk(d, 'musicPlaylistShelfRenderer')) or any(True for _ in _walk(d, 'musicEditablePlaylistDetailHeaderRenderer'))

    def edit(self, playlist_id, actions):
        d = self.call('browse/edit_playlist', {'playlistId': playlist_id, 'actions': actions})
        if d.get('status') not in (None, 'STATUS_SUCCEEDED'):
            raise RuntimeError('edit_playlist: ' + json.dumps(d)[:300])
        return d

    def set_meta(self, playlist_id, title=None, description=None):
        acts = []
        if title is not None:
            acts.append({'action': 'ACTION_SET_PLAYLIST_NAME', 'playlistName': title[:150]})
        if description is not None:
            acts.append({'action': 'ACTION_SET_PLAYLIST_DESCRIPTION', 'playlistDescription': description[:5000]})
        if acts:
            self.edit(playlist_id, acts)

    def settled_items(self, playlist_id, expect, tries=5):
        """Reads lag writes by about a second (a new playlist browses as empty), so read until the count is the one
        just written, or give up and return the last read."""
        rows = self.items(playlist_id)
        for _ in range(tries):
            if len(rows) == expect:
                break
            time.sleep(1.0)
            rows = self.items(playlist_id)
        return rows

    def replace(self, playlist_id, video_ids, current_count=None):
        """Make the playlist hold exactly these songs, in this order. Returns its rows afterwards.
        `current_count` is how many songs it held after the last sync, so a lagging read isn't taken for an empty list."""
        want = list(dict.fromkeys(video_ids))
        current = self.settled_items(playlist_id, current_count) if current_count else self.items(playlist_id)
        if [r['videoId'] for r in current] == want:
            return current
        if current:
            self.edit(playlist_id, [{'action': 'ACTION_REMOVE_VIDEO', 'removedVideoId': r['videoId'], 'setVideoId': r['setVideoId']}
                                    for r in current if r.get('setVideoId')])
        if want:
            self.edit(playlist_id, [{'action': 'ACTION_ADD_VIDEO', 'addedVideoId': v, 'dedupeOption': 'DEDUPE_OPTION_SKIP'} for v in want])
        return self.settled_items(playlist_id, len(want))

    def delete(self, playlist_id):
        self.call('playlist/delete', {'playlistId': playlist_id})


def playlist_url(playlist_id):
    return f'https://music.youtube.com/playlist?list={playlist_id}'
