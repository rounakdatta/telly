"""Mark which songs in a baked catalogue are instrumental, from LRCLIB (the community lyrics database).

    python3 -I tools/enrich_vocals.py catalog.db [--cache ~/.cache/telly/lrclib.json]

The catalogue's audio features can't tell: their "instrumental" scores put The Rhythm of the Night and Billie Eilish's
everything i wanted among the instrumentals. LRCLIB marks tracks as instrumental and has the lyrics of the rest. On a
sample of 15 it was right every time.

Only plausible songs are looked up: titles that say so ('theme', 'piano', 'interlude', ...), songs the features call
instrumental-ish, and the night-drive and soundtrack worlds. That's about 2,000 lookups at a polite pace, cached, so a
rerun after a new bake costs almost nothing. Each song gets `vocals`: 0 = instrumental, 1 = has lyrics, NULL = unknown.
"""
import argparse
import json
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from difflib import SequenceMatcher

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'server'))
from telly.textnorm import nartist, ntrack  # noqa: E402

UA = {'User-Agent': 'telly/2.0 (personal playlist app; https://github.com/rounakdatta/telly)'}
TITLE = re.compile(r'(?i)\b(instrumental|theme|bgm|score|suite|interlude|intro|outro|prelude|overture|nocturne|sonata|concerto|'
                   r'etude|piano|flute|sitar|santoor|sarod|guitar|violin|cello|lofi|lo-fi|ambient)\b')


class Transient(Exception):
    """LRCLIB couldn't be asked (rate limited, timing out): that's no answer, so it is never cached."""


def plausible(title, ins, world, plays):
    return bool(TITLE.search(title or '')) or (ins is not None and ins >= 0.35) or (world in ('night', 'sound') and (plays or 0) >= 2)


def lookup(title, artist, duration_ms):
    q = urllib.parse.urlencode({'track_name': title, 'artist_name': artist})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(f'https://lrclib.net/api/search?{q}', headers=UA), timeout=20) as r:
                hits = json.loads(r.read())
            break
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(10 * (attempt + 1))
                continue
            return None
        except Exception:
            time.sleep(3)
    else:
        raise Transient(title)
    want_t, want_a = ntrack(title), nartist(artist)
    best, best_s = None, 0
    for h in hits[:20]:
        ts = SequenceMatcher(None, want_t, ntrack(h.get('trackName') or '')).ratio()
        as_ = SequenceMatcher(None, want_a, nartist(h.get('artistName') or '')).ratio()
        if ts < 0.8 or as_ < 0.55:
            continue
        s = ts + as_
        if duration_ms and h.get('duration') and abs(duration_ms / 1000 - h['duration']) <= 10:
            s += 0.3
        if s > best_s:
            best, best_s = h, s
    if not best:
        return None
    if best.get('instrumental'):
        return 0
    return 1 if (best.get('plainLyrics') or best.get('syncedLyrics') or '').strip() else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('catalog')
    ap.add_argument('--cache', default=os.path.expanduser('~/.cache/telly/lrclib.json'))
    a = ap.parse_args()
    cache = json.load(open(a.cache)) if os.path.exists(a.cache) else {}
    con = sqlite3.connect(a.catalog)
    todo = []
    for tbl, plays in (('tracks', 'plays'), ('candidates', '1')):
        cols = {r[1] for r in con.execute(f'PRAGMA table_info({tbl})')}
        if 'vocals' not in cols:
            con.execute(f'ALTER TABLE {tbl} ADD COLUMN vocals INTEGER')
        for tid, title, artist, dur, ins, world, p in con.execute(
                f'SELECT id, title, artist, duration_ms, instrumentalness, world, {plays} FROM {tbl}'):
            if plausible(title, ins, world, p):
                todo.append((tbl, tid, title, artist, dur))
    print(f'{len(todo)} songs to check ({sum(1 for t in todo if f"{nartist(t[3])}|{ntrack(t[2])}" in cache)} cached)', flush=True)
    done = failed = 0
    for tbl, tid, title, artist, dur in todo:
        key = f'{nartist(artist)}|{ntrack(title)}'
        if key not in cache:
            try:
                cache[key] = lookup(title, artist, dur)
            except Transient:
                failed += 1
                continue  # stays as it was, and is asked again on the next run
            time.sleep(0.3)
            done += 1
            if done % 100 == 0:
                json.dump(cache, open(a.cache, 'w'))
                print(f'  {done} looked up', flush=True)
        con.execute(f'UPDATE {tbl} SET vocals=? WHERE id=?', (cache[key], tid))
    json.dump(cache, open(a.cache, 'w'))
    con.commit()
    if failed:
        print(f'{failed} lookups failed (LRCLIB busy or unreachable); run again to fill them in')
    for tbl in ('tracks', 'candidates'):
        n0 = con.execute(f'SELECT COUNT(*) FROM {tbl} WHERE vocals=0').fetchone()[0]
        n1 = con.execute(f'SELECT COUNT(*) FROM {tbl} WHERE vocals=1').fetchone()[0]
        print(f'{tbl}: {n0} instrumental, {n1} with lyrics')


if __name__ == '__main__':
    main()
