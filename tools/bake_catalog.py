"""Bake the listener's catalogue for the telly server from the music-taste workspace.

    python3 -I tools/bake_catalog.py [--taste ~/personal/music-taste] [--out catalog.db]

Reads the workspace's dj.db (every song played, with plays, skips, likes, world and audio features) and its data/
folder (Spotify "fans also like" artists, Strava-matched plays, the full play history), and writes one SQLite file:

  tracks      songs the listener knows
  candidates  new songs lined up for them (top tracks of known and adjacent artists)
  world_mix   how their nine worlds mix by hour of day, during runs/walks/rides, and over the last two years
  meta        build time, home, and the short "about the listener" text the stories are written for

The file holds personal listening history: it goes to the server's private volume (PUT /admin/catalog), never into
this repository or an image.
"""
import argparse
import datetime as dt
import json
import math
import os
import sqlite3
import sys
import time
from collections import Counter, defaultdict

TRACK_COLS = ['id', 'title', 'artist', 'artist_key', 'spotify_uri', 'ytm_vid', 'duration_ms', 'plays', 'days_played', 'first_ts',
              'last_ts', 'sp_streams', 'sp_skips', 'liked', 'world', 'origin', 'tempo', 'energy', 'valence', 'danceability',
              'acousticness', 'instrumentalness', 'speechiness']
CAND_COLS = ['id', 'title', 'artist', 'artist_key', 'spotify_uri', 'duration_ms', 'world', 'origin', 'kind', 'playcount', 'monthly',
             'adjacency', 'artist_aff', 'tempo', 'energy', 'valence', 'danceability', 'acousticness', 'instrumentalness', 'speechiness']
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--taste', default=os.path.expanduser('~/personal/music-taste'))
    ap.add_argument('--out', default='catalog.db')
    a = ap.parse_args()
    engine = os.path.join(a.taste, 'engine')
    data = os.path.join(a.taste, 'data')
    sys.path.insert(0, engine)
    import dj  # noqa: E402  (the workspace's own engine: discovery candidates and world lookups)
    from textnorm import nartist  # noqa: E402
    from worlds import world_of  # noqa: E402
    import stories  # noqa: E402

    src = sqlite3.connect(os.path.join(engine, 'dj.db'))
    src.row_factory = sqlite3.Row
    known = [dict(r) for r in src.execute('SELECT * FROM tracks')]
    for t in known:
        t['liked'] = 1 if (t.get('liked_sp') or t.get('liked_ytm')) else 0

    # new songs, exactly as the workspace's engine finds them
    known_keys = {tuple(t['id'].split('|', 1)) for t in known}
    plays = Counter()
    for t in known:
        plays[t['artist_key']] += t['plays'] or 0
    artist_aff = {k: math.log1p(v) for k, v in plays.items()}
    played_titles = {t['id'].split('|', 1)[1] for t in known if (t['plays'] or 0) >= 1 and len(t['id'].split('|', 1)[1]) >= 4}
    cands = dj.discovery_candidates(known_keys, artist_aff, played_titles)

    # world mixes
    A = json.load(open(os.path.join(data, 'norm/artist_style_origin.json')))
    mix = defaultdict(Counter)
    acts = json.load(open(os.path.join(data, 'norm/plays_during_activities.json')))
    for name in ('Run', 'Walk', 'Ride'):
        for p in acts.get(name, []):
            w = world_of(A.get(nartist(p['artist'])))
            if w:
                mix[name][w] += 1
    cutoff = time.time() - 730 * 86400
    for p in json.load(open(os.path.join(data, 'norm/unified_plays.json'))):
        if p['ts'] < cutoff:
            continue
        w = world_of(A.get(nartist(p['artist'])))
        if not w:
            continue
        mix['all'][w] += 1
        mix[f"h{dt.datetime.fromtimestamp(p['ts'], IST).hour:02d}"][w] += 1

    if os.path.exists(a.out):
        os.remove(a.out)
    out = sqlite3.connect(a.out)
    out.execute(f"CREATE TABLE tracks ({', '.join(c + (' TEXT PRIMARY KEY' if c == 'id' else '') for c in TRACK_COLS)})")
    out.execute(f"CREATE TABLE candidates ({', '.join(c + (' TEXT PRIMARY KEY' if c == 'id' else '') for c in CAND_COLS)})")
    out.execute('CREATE TABLE world_mix (context TEXT, world TEXT, share REAL, PRIMARY KEY (context, world))')
    out.execute('CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)')
    out.executemany(f"INSERT INTO tracks VALUES ({','.join('?' * len(TRACK_COLS))})", [[t.get(c) for c in TRACK_COLS] for t in known])
    seen = set()
    rows = []
    for c in cands:
        if c['id'] in seen:
            continue
        seen.add(c['id'])
        rows.append([c.get(k) for k in CAND_COLS])
    out.executemany(f"INSERT INTO candidates VALUES ({','.join('?' * len(CAND_COLS))})", rows)
    for ctx, c in mix.items():
        tot = sum(c.values())
        out.executemany('INSERT INTO world_mix VALUES (?,?,?)', [(ctx, w, n / tot) for w, n in c.items()])
    meta = {'built_at': str(int(time.time())), 'about': stories.ABOUT.replace('\n', ' '), 'home_name': 'Bengaluru',
            'home_lat': '12.9716', 'home_lon': '77.5946', 'tz_minutes': '330',
            'tracks': str(len(known)), 'candidates': str(len(rows))}
    out.executemany('INSERT INTO meta VALUES (?,?)', meta.items())
    out.commit()
    out.execute('VACUUM')
    out.close()
    print(f'{a.out}: {len(known)} known songs, {len(rows)} new candidates, {len(mix)} world mixes, {os.path.getsize(a.out) // 1024} KB')


if __name__ == '__main__':
    main()
