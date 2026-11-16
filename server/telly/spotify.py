"""Spotify Web API for the listener's own playlists (a Development Mode app they own; the 2026 routes: /me/playlists,
/playlists/{id}/items, /me/library)."""
import base64
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from difflib import SequenceMatcher

from .textnorm import nartist, ntrack

API = 'https://api.spotify.com/v1'


class SpotifyError(Exception):
    pass


class AuthError(SpotifyError):
    pass


class Spotify:
    def __init__(self, client_id, client_secret, refresh_token, on_refresh=None):
        self.cid, self.secret, self.refresh_token = client_id, client_secret, refresh_token
        self.on_refresh = on_refresh
        self.token, self.expires = None, 0
        self.lock = threading.Lock()

    def _access(self):
        with self.lock:
            if self.token and time.time() < self.expires - 60:
                return self.token
            body = urllib.parse.urlencode({'grant_type': 'refresh_token', 'refresh_token': self.refresh_token}).encode()
            basic = base64.b64encode(f'{self.cid}:{self.secret}'.encode()).decode()
            req = urllib.request.Request('https://accounts.spotify.com/api/token', data=body, method='POST',
                                         headers={'Authorization': f'Basic {basic}', 'Content-Type': 'application/x-www-form-urlencoded'})
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    d = json.loads(r.read())
            except urllib.error.HTTPError as e:
                raise AuthError(f'token refresh: HTTP {e.code} {e.read()[:160]!r}') from None
            self.token, self.expires = d['access_token'], time.time() + int(d.get('expires_in', 3600))
            if d.get('refresh_token') and d['refresh_token'] != self.refresh_token:
                self.refresh_token = d['refresh_token']
                if self.on_refresh:
                    self.on_refresh(self.refresh_token)
            return self.token

    def call(self, method, path, body=None, query=None):
        url = API + path + ('?' + urllib.parse.urlencode(query) if query else '')
        for attempt in range(3):
            req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                         headers={'Authorization': f'Bearer {self._access()}', 'Content-Type': 'application/json'})
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    raw = r.read()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as e:
                if e.code == 429 and attempt < 2:
                    time.sleep(min(30, int(e.headers.get('Retry-After') or 5)))
                    continue
                if e.code == 401 and attempt < 2:
                    self.token = None
                    continue
                msg = e.read()[:200]
                if e.code in (401, 403):
                    raise AuthError(f'{method} {path}: HTTP {e.code} {msg!r}') from None
                raise SpotifyError(f'{method} {path}: HTTP {e.code} {msg!r}') from None

    def me(self):
        return self.call('GET', '/me')

    def search_track(self, title, artist, duration_ms=None):
        q = f'track:{ntrack(title) or title} artist:{artist}'
        d = self.call('GET', '/search', query={'q': q, 'type': 'track', 'limit': 5, 'market': 'from_token'}) or {}
        items = (d.get('tracks') or {}).get('items') or []
        if not items:
            d = self.call('GET', '/search', query={'q': f'{title} {artist}', 'type': 'track', 'limit': 5, 'market': 'from_token'}) or {}
            items = (d.get('tracks') or {}).get('items') or []
        best, best_s = None, 0.0
        for rank, it in enumerate(items):
            ts = SequenceMatcher(None, ntrack(title), ntrack(it.get('name', ''))).ratio()
            as_ = max([SequenceMatcher(None, nartist(artist), nartist(a.get('name', ''))).ratio() for a in it.get('artists', [])] or [0])
            dur_ok = bool(duration_ms and it.get('duration_ms') and abs(duration_ms - it['duration_ms']) <= 10_000)
            ok = (ts >= 0.88 and as_ >= 0.4) or (ts >= 0.75 and as_ >= 0.6) or (ts >= 0.8 and dur_ok)
            s = 0.6 * ts + 0.3 * as_ + 0.1 * dur_ok - 0.05 * rank
            if ok and s > best_s:
                best, best_s = it.get('uri'), s
        return best

    def create(self, name, description):
        d = self.call('POST', '/me/playlists', {'name': name[:100], 'description': _desc(description), 'public': False})
        return d['id']

    def replace(self, pid, uris):
        self.call('PUT', f'/playlists/{pid}/items', {'uris': list(dict.fromkeys(uris))[:100]})

    def set_meta(self, pid, name=None, description=None):
        body = {}
        if name is not None:
            body['name'] = name[:100]
        if description is not None:
            body['description'] = _desc(description)
        if body:
            self.call('PUT', f'/playlists/{pid}', body)

    def delete(self, pid):
        try:
            self.call('DELETE', '/me/library', query={'uris': f'spotify:playlist:{pid}'})
        except SpotifyError:
            self.call('DELETE', f'/playlists/{pid}/followers')


def _desc(s):
    s = ' '.join((s or '').split())
    return s if len(s) <= 300 else s[:297].rsplit(' ', 1)[0] + '…'


def playlist_url(pid):
    return f'https://open.spotify.com/playlist/{pid}'
