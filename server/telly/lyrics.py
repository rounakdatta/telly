"""What's playing on the phone, and its lyrics timed word by word, from LeZeppLean (on the same homelab).

LeZeppLean already does the hard part for the watch: given what the phone's player says (title, artist, length), it
finds the song's synced lyrics on LRCLIB for that very recording, then times every word against the recording itself
(vocals separated, wav2vec 2.0, forced alignment), once per song, kept. telly speaks to it the way LeZeppLean's own
phone helper does (POST /phone: it is the same phone, playing the same song), learns which song that is, and asks for
its lyrics (POST /lyrics). Lines come back with word starts only where the aligner is sure; the rest are timed line by
line. Songs in other scripts come back in Latin letters (what the watch shows), timed in those.
"""
import json
import urllib.error
import urllib.request

STATES = ('playing', 'paused', 'stopped', 'buffering')
DAY_MS = 6 * 3600 * 1000


class LyricsError(Exception):
    pass


def player_state(body):
    """The phone's report, checked and trimmed -> dict, or None when it names no song."""
    def text(k, n):
        v = body.get(k)
        return ' '.join(v.split())[:n] if isinstance(v, str) else ''

    def num(k, lo, hi):
        v = body.get(k)
        return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) and lo <= v <= hi else None

    title = text('title', 300)
    if not title:
        return None
    return {'app': text('app', 100), 'title': title, 'artist': text('artist', 300), 'album': text('album', 300) or None,
            'durationMs': num('durationMs', 1, DAY_MS), 'state': body.get('state') if body.get('state') in STATES else 'playing',
            'positionMs': num('positionMs', 0, DAY_MS) or 0.0, 'speed': num('speed', 0.1, 4) or 1.0, 'sentAt': num('sentAt', 1, 1e14)}


class Lyrics:
    def __init__(self, url, token):
        self.url, self.token = (url or '').rstrip('/'), token or ''

    @property
    def ready(self):
        return bool(self.url and self.token)

    def _call(self, method, path, body=None, timeout=12):
        req = urllib.request.Request(self.url + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                     headers={'Authorization': f'Bearer {self.token}', 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            raise LyricsError(f'{path}: HTTP {e.code}') from None
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            raise LyricsError(f'{path}: {e}') from None

    def now(self, state):
        """The phone's player state (player_state()) -> the song it is playing and its lyrics. The song is worked out
        a moment after a new one is reported: when it isn't yet, wait briefly for the change that names it, and
        otherwise say "pending" (the phone asks again)."""
        sent = self._call('POST', '/phone', state)
        song_id = sent.get('songId')
        if not song_id:
            view = self._call('GET', f"/phone/next?since={int(sent.get('version', -1))}&wait=4", timeout=9)
            if view.get('songId') and (view.get('title') or '').casefold() == state['title'].casefold():
                song_id = view['songId']
        if not song_id:
            return {'song': None, 'kind': 'pending', 'version': None, 'lines': [], 'words': {'status': 'pending'}}
        return self.lyrics(song_id)

    def lyrics(self, song_id):
        d = self._call('POST', '/lyrics', {'song': song_id})
        if not d.get('ok'):
            return {'song': None, 'kind': 'none', 'version': None, 'lines': [], 'words': {'status': 'unavailable'}}
        payload = d.get('lyrics') or {}
        lines = []
        for line in payload.get('lines') or []:
            start, text, words, end = (list(line) + [None] * 4)[:4]
            lines.append({'start': int(start or 0), 'text': text or '', 'words': words, 'end': end})
        song = d.get('song') or {}
        return {'song': {k: song.get(k) for k in ('id', 'title', 'artist', 'album', 'durationMs')}, 'kind': payload.get('kind', 'pending'),
                'version': payload.get('version'), 'lines': lines, 'words': d.get('words') or {'status': 'pending'}}
