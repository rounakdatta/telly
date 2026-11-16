"""The listener's catalogue: every song they have played (with plays, skips, likes, world and sound), the new songs
lined up for them, and how their worlds mix by hour and by activity. Built offline by tools/bake_catalog.py; read-only here."""
import math
import sqlite3
from collections import Counter, defaultdict

from .textnorm import nartist, ntrack

WORLDS = {
    'hindi': 'Hindi film & Hindi indie',
    'roots': 'Bengali, Pakistani & South Indian',
    'indie': 'Indie, folk & bedroom pop',
    'pop': 'Pop, joy & nostalgia',
    'rock': 'Rock canon & alt rock',
    'rap': 'Melodic rap & R&B',
    'night': 'Night-drive electronics, chill & ambient',
    'edm': 'EDM & dance',
    'sound': 'Soundtracks, scores & world',
}
ORIGINS = ['Hindi/Indian', 'Bengali', 'Punjabi', 'Pakistani', 'South Indian', 'Western/other']


class Catalog:
    def __init__(self, path):
        con = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
        con.row_factory = sqlite3.Row
        self.tracks = [dict(r) for r in con.execute('SELECT * FROM tracks')]
        self.candidates = [dict(r) for r in con.execute('SELECT * FROM candidates')]
        self.mix = defaultdict(dict)
        for r in con.execute('SELECT context, world, share FROM world_mix'):
            self.mix[r['context']][r['world']] = r['share']
        self.meta = {r['k']: r['v'] for r in con.execute('SELECT k, v FROM meta')}
        con.close()
        for t in self.tracks:
            t['kind'] = 'known'
        self.by_id = {t['id']: t for t in self.candidates}
        self.by_id.update({t['id']: t for t in self.tracks})
        plays = Counter()
        names = defaultdict(Counter)
        for t in self.tracks:
            plays[t['artist_key']] += t['plays'] or 0
            names[t['artist_key']][t['artist']] += 1
        self.artist_aff = {k: math.log1p(v) for k, v in plays.items()}
        self.artist_name = {k: c.most_common(1)[0][0] for k, c in names.items()}
        self.size = len(self.tracks)
        # Songs by normalised title, built once: matching a name against 42k titles on every request took ~1.3 s.
        self.by_title = defaultdict(list)
        for t in self.by_id.values():
            self.by_title[ntrack(t['title'])].append(t)

    def find(self, title, artist=None):
        """A song asked for by name: the catalogue entry that is surely it, or None."""
        tk, ak = ntrack(title), nartist(artist or '')
        if not tk:
            return None
        same = self.by_title.get(tk, [])
        exact = [t for t in same if not ak or nartist(t['artist']) == ak or ak in nartist(t['artist'])]
        if not exact and ak:
            exact = [t for t in same if ak.split(' ')[0] in nartist(t['artist'])] or (same if len(same) == 1 else [])
        if not exact:
            return None
        return max(exact, key=lambda t: (t.get('plays') or 0, t.get('playcount') or 0))

    def artist_keys(self, names):
        """Display names as the listener types them -> catalogue artist keys (prefix-tolerant)."""
        out = set()
        for n in names or []:
            k = nartist(n)
            if not k:
                continue
            if k in self.artist_aff:
                out.add(k)
                continue
            hits = [a for a in self.artist_aff if a.startswith(k) or k in a.split(' and ')]
            out.update(sorted(hits, key=lambda a: -self.artist_aff[a])[:3])
            if not hits:
                out.add(k)
        return out
