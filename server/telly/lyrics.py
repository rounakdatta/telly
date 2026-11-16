"""What's playing on the phone, and its lyrics timed word by word, from LeZeppLean (on the same homelab).

LeZeppLean's helper app on the phone already reads the music player (Android shows it only to an app with notification
access, and Google blocks sideloading new apps that ask for it) and tells LeZeppLean what plays and where, the moment
that changes. LeZeppLean works out which song that is, finds its synced lyrics on LRCLIB for that very recording, and
times every word against the recording itself (vocals separated, wav2vec 2.0, forced alignment), once per song, kept.
telly only reads: what the phone plays (GET /phone/next, which waits for a change) and a song's lyrics (POST /lyrics).
Lines come back with word starts only where the aligner is sure; the rest are timed line by line. Songs in other
scripts come back in Latin letters (what the watch shows), timed in those.
"""
import json
import urllib.error
import urllib.request

VIEW = ('version', 'app', 'title', 'artist', 'durationMs', 'playing', 'active', 'stale', 'positionMs', 'speed', 'songId', 'ageMs')


class LyricsError(Exception):
    pass


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

    def phone(self, since=-1, wait=0.0):
        """What the phone's player is doing, as LeZeppLean's helper last said, with the position as of now. When the
        phone already has version `since`, this waits (up to `wait` s, at most 8) for the next change."""
        v = self._call('GET', f'/phone/next?since={int(since)}&wait={max(0.0, min(float(wait), 8.0))}', timeout=14)
        return {k: v.get(k) for k in VIEW}

    def lyrics(self, song_id):
        # plain: a song with only untimed words still has its words (LeZeppLean 0.4+ sends them to whoever asks)
        d = self._call('POST', '/lyrics', {'song': song_id, 'plain': True})
        if not d.get('ok'):
            return {'song': None, 'kind': 'none', 'version': None, 'lines': [], 'words': {'status': 'unavailable'}}
        payload = d.get('lyrics') or {}
        lines = []
        for line in payload.get('lines') or []:
            start, text, words, end = (list(line) + [None] * 4)[:4]
            lines.append({'start': int(start or 0), 'text': text or '', 'words': words, 'end': end})
        song = d.get('song') or {}
        return {'song': {k: song.get(k) for k in ('id', 'title', 'artist', 'album', 'durationMs')}, 'kind': payload.get('kind', 'pending'),
                'version': payload.get('version'), 'lines': lines, 'words': d.get('words') or {'status': 'pending'},
                'plain': [str(x) for x in (d.get('plain') or [])][:400], 'source': d.get('source')}
