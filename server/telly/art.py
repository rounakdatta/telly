"""A song's cover, for the lyrics and the player: the phone's player isn't read by telly any more, so its artwork isn't
either. Apple's public search finds it by title and artist; it is looked up once per song and kept (a song Apple
doesn't have is kept as "none" for a fortnight; a failed lookup isn't kept at all)."""
import json
import time
import urllib.parse
import urllib.request
from difflib import SequenceMatcher

from .genius import first_artist
from .textnorm import nartist, ntrack

KEEP_NONE = 14 * 86400
_down = [0.0]  # when Apple may be asked again after a failed lookup: the first lyrics of a song never wait on it twice


def pick(results, title, artist):
    """The result that is this song (its title, by one of its artists) -> artwork URL at 600 px, or None."""
    want_t, want_a = ntrack(title), nartist(first_artist(artist))
    best, best_score = None, 0.0
    for r in results:
        ts = SequenceMatcher(None, want_t, ntrack(r.get('trackName') or '')).ratio()
        names = nartist(r.get('artistName') or '')
        a = 1.0 if want_a and f' {want_a} ' in f' {names} ' else SequenceMatcher(None, want_a, names).ratio()
        if ts >= 0.85 and a >= 0.6 and ts + a > best_score and r.get('artworkUrl100'):
            best, best_score = r, ts + a
    return best['artworkUrl100'].replace('100x100bb', '600x600bb') if best else None


def cover(store, title, artist, country='IN'):
    k = f'art:{nartist(first_artist(artist))}|{ntrack(title)}'
    got = store.get(k)
    if got and (got.get('url') or time.time() - got.get('ts', 0) < KEEP_NONE):
        return got.get('url')
    if _down[0] > time.time():
        return None
    q = urllib.parse.urlencode({'term': f'{ntrack(title)} {first_artist(artist)}', 'entity': 'song', 'limit': 8, 'country': country})
    try:
        with urllib.request.urlopen(urllib.request.Request(f'https://itunes.apple.com/search?{q}',
                                                           headers={'User-Agent': 'telly/2 (personal playlist app)'}), timeout=2.5) as r:
            results = json.loads(r.read()).get('results') or []
    except Exception:
        _down[0] = time.time() + 300
        return None  # not kept, and Apple isn't asked again for five minutes
    url = pick(results, title, artist)
    store.put(k, {'url': url, 'ts': time.time()})
    return url
