"""Brief -> playlist. The flow model, measured from the listener's own history:

- Familiarity has a sweet spot. Songs picked on purpose are skipped least after 5-9 plays and most after 50+, so
  favourites rest between appearances, longer in worlds that wear out fast (synthwave, rock) than in Hindi film.
- New music is scored by how often music like it has stuck for this listener (origin and popularity), not by
  similarity, and each new song is placed after a familiar song from the same world.
- Every moment has an energy arc; the strongest familiar song opens and a favourite closes.

A brief is a plain dict (see `normalize`); a playlist is a list of track dicts with a `pool` saying why each is there.
"""
import math
import random
import re
import time
from collections import Counter

from .catalog import WORLDS
from .textnorm import nartist, ntrack

DAY = 86400
AVG_MS = 215_000
WEAR = {'night': 1.6, 'rock': 1.4, 'pop': 1.2, 'indie': 1.0, 'rap': 0.85, 'edm': 1.0, 'roots': 1.0, 'hindi': 0.75, 'sound': 1.0, None: 1.0}
ORIGIN_PRIOR = {'Hindi/Indian': 0.32, 'Bengali': 0.24, 'Punjabi': 0.2, 'Pakistani': 0.15, 'South Indian': 0.13, 'Western/other': 0.11}
FAMILIAR_POOLS = ('comfort', 'familiar', 'forgotten', 'warm', 'asked')
TAG = {'comfort': 'favourite', 'familiar': 'well known', 'forgotten': 'forgotten', 'warm': 'getting to know',
       'new_known': 'new', 'new_frontier': 'new artist', 'asked': 'asked for'}

# Each activity: its default share of familiar songs and how that share splits, its energy arc (start, end),
# tempo band, hard limits, and where its world mix comes from ('Run'/'Walk'/'Ride' = what was actually played on
# those Strava activities; 'hour' = what is played at that hour of the day; 'all' = the last two years).
ACTIVITIES = {
    'walk': dict(label='Walk', familiar=0.75, fam={'familiar': .30, 'comfort': .15, 'forgotten': .25, 'warm': .05},
                 new={'new_known': .8, 'new_frontier': .2}, energy=(0.55, 0.35), tempo=(70, 118), max_energy=0.75,
                 acoustic_bonus=0.6, worlds='Walk', cool=0.6,
                 tilt={'indie': 2.0, 'hindi': 2.3, 'roots': 2.4, 'sound': 1.2, 'rap': 0.4, 'edm': 0.3, 'pop': 0.6, 'rock': 0.8},
                 blurb="Unhurried and easy on a full brain: songs you love but haven't worn out, forgotten favourites, and a few gentle new ones from artists you know. The energy glides down."),
    'run': dict(label='Run', familiar=0.75, fam={'comfort': .35, 'familiar': .30, 'forgotten': .10}, new={'new_known': .8, 'new_frontier': .2},
                energy=(0.72, 0.92), tempo=(118, 180), min_energy=0.68, min_dance=0.5, max_acoustic=0.45, dance_bonus=0.5,
                worlds='Run', peak=True, cool=0.35,
                blurb='Built to move: songs you know the words to, a climb to a long peak, and a few new bangers from artists you trust.'),
    'workout': dict(label='Workout', familiar=0.8, fam={'comfort': .40, 'familiar': .30, 'forgotten': .10}, new={'new_known': .8, 'new_frontier': .2},
                    energy=(0.70, 0.88), tempo=(95, 180), min_energy=0.62, min_dance=0.45, max_acoustic=0.5, dance_bonus=0.4,
                    worlds='Run', peak=True, cool=0.35,
                    blurb='Heavy and steady: favourites that push, held at a high plateau.'),
    'ride': dict(label='Drive', familiar=0.75, fam={'comfort': .30, 'familiar': .25, 'forgotten': .15, 'warm': .05},
                 new={'new_known': .6, 'new_frontier': .4}, energy=(0.60, 0.75), tempo=(80, 170), worlds='Ride', cool=0.6,
                 blurb='Sing-along miles: familiar choruses, rediscovered favourites and new songs placed between friends.'),
    'commute': dict(label='Commute', familiar=0.7, fam={'familiar': .35, 'comfort': .25, 'forgotten': .15, 'warm': .10},
                    new={'new_known': .7, 'new_frontier': .3}, energy=(0.55, 0.62), worlds='hour', cool=0.7,
                    blurb='Easy company for the way: mostly songs you know, a couple of new ones to notice.'),
    'focus': dict(label='Focus', familiar=0.55, fam={'familiar': .35, 'forgotten': .15, 'warm': .05}, new={'new_known': .55, 'new_frontier': .45},
                  energy=(0.45, 0.45), max_energy=0.75, instrumental=True, cool=0.5,
                  worlds_fixed={'night': 0.4, 'sound': 0.25, 'indie': 0.15, 'edm': 0.1, 'rock': 0.1},
                  blurb='Instrumental only: lyrics compete with reading even when you love them. Steady, never fast and loud.'),
    'morning': dict(label='Morning', familiar=0.7, fam={'familiar': .35, 'comfort': .15, 'forgotten': .15, 'warm': .05},
                    new={'new_known': .7, 'new_frontier': .3}, energy=(0.40, 0.62), acoustic_bonus=0.3, worlds='hour', cool=0.7,
                    tilt={'indie': 1.5, 'roots': 1.4, 'pop': 1.2, 'edm': 0.5, 'rap': 0.6},
                    blurb='A soft start that warms up: acoustic first, brighter as the day gets going.'),
    'cook': dict(label='Cooking', familiar=0.8, fam={'comfort': .35, 'familiar': .30, 'forgotten': .15},
                 new={'new_known': .8, 'new_frontier': .2}, energy=(0.60, 0.68), worlds='hour', cool=0.6,
                 tilt={'pop': 1.4, 'hindi': 1.3, 'roots': 1.2},
                 blurb='Hands busy, voice free: sing-alongs and songs that keep a rhythm.'),
    'unwind': dict(label='Unwind', familiar=0.7, fam={'familiar': .30, 'comfort': .15, 'forgotten': .20, 'warm': .05},
                   new={'new_known': .7, 'new_frontier': .3}, energy=(0.45, 0.35), max_energy=0.7, acoustic_bonus=0.4, worlds='hour', cool=0.7,
                   tilt={'indie': 1.6, 'roots': 1.4, 'night': 1.3, 'edm': 0.4, 'rap': 0.6},
                   blurb='Letting the day go: warm, slow and familiar, a little melancholy allowed.'),
    'late-night': dict(label='Late night', familiar=0.75, fam={'comfort': .20, 'familiar': .30, 'forgotten': .20, 'warm': .05},
                       new={'new_known': .6, 'new_frontier': .4}, energy=(0.50, 0.30), max_energy=0.7, worlds='hour', cool=0.8,
                       blurb='Your most forgiving hours: indie folk, night-drive, Bollywood heartbreak, gliding down.'),
    'sleep': dict(label='Wind down', familiar=0.7, fam={'familiar': .35, 'comfort': .15, 'forgotten': .15, 'warm': .05},
                  new={'new_known': .6, 'new_frontier': .4}, energy=(0.32, 0.20), max_energy=0.5, acoustic_bonus=0.8, worlds='hour', cool=0.8,
                  tilt={'indie': 1.6, 'sound': 1.8, 'night': 1.4, 'edm': 0.2, 'rap': 0.3, 'rock': 0.5},
                  blurb='Quiet and slow, down to almost nothing.'),
    'party': dict(label='Friends over', familiar=0.85, fam={'comfort': .45, 'familiar': .30, 'forgotten': .10},
                  new={'new_known': .8, 'new_frontier': .2}, energy=(0.70, 0.85), min_dance=0.55, dance_bonus=0.6, worlds='hour',
                  peak=True, cool=0.5, tilt={'pop': 1.5, 'hindi': 1.5, 'edm': 1.3, 'rap': 1.2},
                  blurb='Songs everyone can shout along to, climbing.'),
    'discovery': dict(label='Discovery', familiar=0.4, fam={'familiar': .25, 'comfort': .05, 'forgotten': .10},
                      new={'new_known': .5, 'new_frontier': .5}, energy=(0.55, 0.60), worlds='all', cool=1.0,
                      tilt={'hindi': 1.4, 'roots': 1.5, 'rock': 1.2, 'night': 0.8, 'edm': 0.8},
                      blurb='Mostly new, from ground where new music sticks for you, each new song next to a familiar one.'),
    'journey': dict(label='Journey', familiar=0.55, fam={'familiar': .25, 'comfort': .10, 'forgotten': .20},
                    new={'new_known': .55, 'new_frontier': .45}, energy=(0.50, 0.60), worlds='all', cool=0.8,
                    tilt={'rock': 1.3, 'roots': 1.3, 'sound': 1.5, 'indie': 1.2},
                    blurb='Room to listen: songs with stories, rediscovered favourites, and new music that rewards attention.'),
    'anything': dict(label='Listening', familiar=0.7, fam={'familiar': .35, 'comfort': .20, 'forgotten': .15, 'warm': .05},
                     new={'new_known': .7, 'new_frontier': .3}, energy=(0.55, 0.55), worlds='hour', cool=0.7,
                     blurb='Your usual for this hour, with a few new songs.'),
}

BRIEF_KEYS = ('activity', 'title', 'start', 'minutes', 'familiar', 'energy', 'tempo', 'worlds', 'origins', 'include_artists',
              'exclude_artists', 'instrumental', 'avoid', 'seed', 'want', 'mix')


def dur_ms(t):
    """A song's length for budgeting. Lengths seen on YouTube are sometimes the video's (a 13-minute "Don't Smile"),
    so anything outside 1:40-10:00 counts as an average song."""
    d = t.get('duration_ms')
    return d if d and 100_000 <= d <= 600_000 else AVG_MS


def normalize(b):
    """Fill a brief's gaps from its activity and clamp what was set."""
    b = {k: v for k, v in (b or {}).items() if k in BRIEF_KEYS and v is not None}
    a = b.get('activity') if b.get('activity') in ACTIVITIES else 'anything'
    p = ACTIVITIES[a]
    b['activity'] = a
    b['minutes'] = int(min(600, max(5, b.get('minutes') or 45)))
    b['familiar'] = round(min(0.98, max(0.05, float(b.get('familiar', p['familiar'])))), 2)
    e = b.get('energy') or p['energy']
    b['energy'] = [round(min(0.98, max(0.08, float(x))), 2) for x in (list(e) + list(e))[:2]]
    t = b.get('tempo')
    b['tempo'] = [float(t[0]), float(t[1])] if t and len(t) == 2 and t[0] < t[1] else None
    b['worlds'] = {w: round(max(0.0, min(8.0, float(f))), 2) for w, f in (b.get('worlds') or {}).items() if w in WORLDS}
    b['origins'] = [o for o in (b.get('origins') or []) if isinstance(o, str)]
    for k in ('include_artists', 'exclude_artists', 'avoid'):
        b[k] = list(dict.fromkeys(x for x in (b.get(k) or []) if isinstance(x, str) and x))
    if 'instrumental' in b:
        b['instrumental'] = bool(b['instrumental'])
    want = []
    for w in b.get('want') or []:
        if not isinstance(w, dict):
            continue
        w = {k: w[k] for k in ('world', 'origin', 'artist') if isinstance(w.get(k), str) and w[k]} | {'count': w.get('count')}
        if w.get('world') not in WORLDS:
            w.pop('world', None)
        try:
            w['count'] = int(min(10, max(1, int(w['count'] or 2))))
        except (TypeError, ValueError):
            w['count'] = 2
        if len(w) > 1:
            want.append(w)
    b['want'] = want
    b['seed'] = int(b.get('seed') or random.randrange(1, 10**9))
    b['title'] = (b.get('title') or p['label']).strip()[:60]
    return b


def config(b):
    """The activity's sound rules, shifted by how far the brief moved its energy."""
    p = ACTIVITIES[b['activity']]
    shift = (b['energy'][0] + b['energy'][1]) / 2 - (p['energy'][0] + p['energy'][1]) / 2
    c = dict(p)
    c['energy'] = tuple(b['energy'])
    if b.get('tempo'):
        c['tempo'] = tuple(b['tempo'])
    if 'min_energy' in p:
        c['min_energy'] = p['min_energy'] + min(0.0, shift)
    if 'max_energy' in p:
        c['max_energy'] = p['max_energy'] + max(0.0, shift)
    if shift < -0.08:  # asked for calmer than the activity's usual: its floors no longer apply
        c.pop('min_dance', None)
        c.pop('max_acoustic', None)
    if 'instrumental' in b:
        c['instrumental'] = b['instrumental']
    return c


def world_weights(cat, b, hour):
    p = ACTIVITIES[b['activity']]
    if p.get('worlds_fixed'):
        ww = dict(p['worlds_fixed'])
    else:
        src = p.get('worlds', 'hour')
        if src == 'hour':
            ww = Counter()
            for dh, f in ((-1, 0.5), (0, 1.0), (1, 0.5)):  # smooth over the neighbouring hours
                for w, s in cat.mix.get(f'h{(hour + dh) % 24:02d}', {}).items():
                    ww[w] += s * f
            ww = dict(ww) or dict(cat.mix.get('all', {}))
        else:
            ww = dict(cat.mix.get(src) or cat.mix.get('all') or {})
        for w, f in p.get('tilt', {}).items():
            ww[w] = ww.get(w, 0.05) * f
    for w in WORLDS:
        ww[w] = max(ww.get(w, 0), 0.02)
    for w, f in b['worlds'].items():  # the listener's own tilts last; 0 removes a world
        ww[w] = ww[w] * f if f >= 0.05 else 0.0
    s = sum(ww.values()) or 1
    return {w: v / s for w, v in ww.items()}


def affinity(t):
    a = math.log1p(t.get('plays') or 0) + 0.6 * math.log1p(t.get('days_played') or 0) + 0.8 * (1 if t.get('liked') else 0)
    if t.get('skip') is not None:
        a -= 3.0 * t['skip']
    return a


def new_score(c):
    """How likely a new song is to stick, from the listener's measured stick rates by origin and popularity."""
    pop = math.log10(max(c.get('playcount') or 0, 1000))
    base = (pop - 3) / 6.5
    o = ORIGIN_PRIOR.get(c.get('origin') or 'Western/other', 0.11) / 0.11
    if c.get('kind') == 'new_known':
        return base * (0.6 + 0.25 * math.log1p(c.get('artist_aff') or 0)) * (o ** 0.5)
    return base * (0.4 + 0.15 * (c.get('adjacency') or 0)) * (o ** 0.6)


INSTRUMENTAL_TITLE = re.compile(r'(?i)\b(instrumental|theme|bgm|score|suite|interlude|prelude|overture|nocturne|sonata|concerto|'
                                r'etude|piano|flute|sitar|santoor|sarod|violin|cello)\b')


def instrumental_ok(t):
    """No vocals? LRCLIB decides when it knows the song (tools/enrich_vocals.py: 0 = instrumental, 1 = has lyrics); the
    audio features can't (they call Billie Eilish and Corona instrumental). Unknown to LRCLIB: only a title that says so."""
    v = t.get('vocals')
    if v is not None:
        return v == 0
    return bool(INSTRUMENTAL_TITLE.search(t.get('title') or ''))


def fit(t, c, target_e):
    """How well a track fits the moment's sound. Unknown audio features are neutral, not rejected."""
    e, tempo = t.get('energy'), t.get('tempo')
    if c.get('instrumental') and not instrumental_ok(t):
        return 0.0
    if e is None:
        if c.get('min_energy') is not None:  # runs: never gamble on an unknown new song
            return 0.0 if str(t.get('pool', t.get('kind', ''))).startswith('new') else 0.5
        return 0.8  # unknown is common for Indian music; missing data must not decide
    if e > c.get('max_energy', 1.1) or e < c.get('min_energy', -1):
        return 0.05
    if c.get('min_dance') and (t.get('danceability') or 0) < c['min_dance']:
        return 0.05
    if c.get('max_acoustic') is not None and (t.get('acousticness') or 0) > c['max_acoustic']:
        return 0.05  # high energy and very acoustic: the feature data is probably wrong for this song
    f = math.exp(-((e - target_e) ** 2) / (2 * 0.15 ** 2))
    lo, hi = c.get('tempo', (0, 999))
    if tempo:
        doubled = tempo * 2 if (lo <= tempo * 2 <= hi and e >= 0.75) else None  # half-time ballads don't count as fast
        t2 = tempo if lo <= tempo <= hi else (doubled or (tempo / 2 if lo <= tempo / 2 <= hi else None))
        f *= 1.0 if t2 else 0.5
    if c.get('acoustic_bonus') and t.get('acousticness') is not None:
        f *= 1 + c['acoustic_bonus'] * t['acousticness']
    if c.get('dance_bonus') and t.get('danceability') is not None:
        f *= 1 + c['dance_bonus'] * (t['danceability'] - 0.5)
    return f


def pool_of(t, now):
    """Which familiarity pool a known song is in today (the measured flow curve), or None."""
    n, days = t.get('plays') or 0, t.get('days_played') or 0
    since = (now - t['last_ts']) / DAY if t.get('last_ts') else 9999
    if n >= 8 and days >= 4 and since > 540:
        return 'forgotten'
    if n >= 20:
        return 'comfort'
    if n >= 5:
        return 'familiar'
    streams = t.get('sp_streams') or 0
    skip = (t.get('sp_skips') or 0) / streams if streams >= 4 else None
    if 1 <= n <= 4 and t.get('first_ts') and now - t['first_ts'] < 120 * DAY and (skip is None or skip < 0.5):
        return 'warm'
    return None


class Builder:
    def __init__(self, cat, brief, hour, now=None, served=None, prev=None, sticky=True):
        self.cat, self.b, self.now = cat, brief, now or time.time()
        self.c = config(brief)
        self.ww = world_weights(cat, brief, hour)
        self.rng = random.Random(brief['seed'])
        self.served = served or {}
        self.prev_ids = {t['id'] for t in (prev or [])} if sticky else set()
        self.avoid = set(brief['avoid'])
        self.excl = cat.artist_keys(brief['exclude_artists'])
        self.incl = cat.artist_keys(brief['include_artists'])
        self.origins = set(brief['origins'])
        self.mid_e = (self.c['energy'][0] + self.c['energy'][1]) / 2
        self.pools = self._pools()
        self.notes = []  # what the listener should hear about this build (e.g. it came out short)

    def _pools(self):
        P = {}
        for t in self.cat.tracks:
            if not t.get('ytm_vid') and not t.get('spotify_uri'):
                continue
            p = pool_of(t, self.now)
            if p:
                streams = t.get('sp_streams') or 0
                t['skip'] = (t.get('sp_skips') or 0) / streams if streams >= 4 else None
                t['since'] = (self.now - t['last_ts']) / DAY if t.get('last_ts') else 9999
                P.setdefault(p, []).append(t)
        for c in self.cat.candidates:
            P.setdefault(c['kind'], []).append(c)
        return P

    def allowed(self, t):
        if t['id'] in self.avoid or t.get('artist_key') in self.excl:
            return False
        if self.origins and (t.get('origin') or 'Western/other') not in self.origins:
            return False
        return self.ww.get(t.get('world'), 0.02) > 0 or t.get('artist_key') in self.incl

    def score(self, t, pool, target_e=None):
        if not self.allowed(t):
            return 0.0
        if pool.startswith('new'):
            s = new_score(t)
        else:
            cool = self.c.get('cool', 1.0)
            base = {'comfort': 21, 'familiar': 10, 'warm': 2}.get(pool, 0)
            if t['id'] not in self.prev_ids and t['since'] < base * cool * WEAR.get(t.get('world'), 1.0):
                return 0.0  # wear-out guard: resting
            s = affinity(t)
            if pool == 'familiar' and 5 <= (t.get('plays') or 0) <= 9:
                s *= 1.25  # the measured sweet spot
        s *= self.ww.get(t.get('world'), 0.03) ** 0.7
        s *= fit(t, self.c, self.mid_e if target_e is None else target_e)
        if t.get('artist_key') in self.incl:
            s *= 5
        seen = self.served.get(t['id'])
        if seen and t['id'] not in self.prev_ids:
            s *= 0.1 if pool.startswith('new') or pool == 'warm' else 0.35  # just served in another session
        if t['id'] in self.prev_ids:
            s *= 3  # keep what is already there unless it no longer fits
        return s * math.exp(self.rng.gauss(0, 0.35))

    def counts(self, n):
        f = self.b['familiar']
        p = ACTIVITIES[self.b['activity']]
        shares = {}
        fam_tot, new_tot = sum(p['fam'].values()), sum(p['new'].values())
        for k, v in p['fam'].items():
            shares[k] = f * v / fam_tot
        for k, v in p['new'].items():
            shares[k] = (1 - f) * v / new_tot
        counts = {k: int(round(v * n)) for k, v in shares.items()}
        while sum(counts.values()) > n:
            counts[max(counts, key=counts.get)] -= 1
        while sum(counts.values()) < n:
            counts[max(shares, key=lambda k: shares[k] * n - counts[k])] += 1
        return counts

    def build(self, pins=()):
        n = max(4, math.ceil(self.b['minutes'] * 60_000 / AVG_MS) + 2)
        counts = self.counts(n)
        picked = [dict(t) for t in pins]
        per_artist = Counter(t.get('artist_key') for t in picked)
        ids = {t['id'] for t in picked}
        for t in picked:  # pinned songs take their pool's slots
            if counts.get(t.get('pool'), 0) > 0:
                counts[t['pool']] -= 1
        for pool, k in sorted(counts.items(), key=lambda x: x[0].startswith('new')):
            ranked = sorted(((self.score(t, pool), t) for t in self.pools.get(pool, [])), key=lambda x: -x[0])
            got = 0
            for s, t in ranked:
                if got >= k or s <= 0:
                    break
                cap = 2 if pool in ('comfort', 'familiar', 'forgotten') else 1
                if t.get('artist_key') in self.incl:
                    cap += 2
                if per_artist[t.get('artist_key')] >= cap or t['id'] in ids:
                    continue
                picked.append(dict(t, pool=pool))
                ids.add(t['id'])
                per_artist[t.get('artist_key')] += 1
                got += 1
        self.fill(picked, ids, per_artist)
        return self.enforce_wants(self.trim(sequence(picked, self.c)))

    def fill(self, picked, ids, per_artist):
        """When the pools' shares come up short of the time (narrow filters do that: instrumental, one origin, a run's
        energy floor), top up with the best of what fits from any pool, familiar ones first; then say if it's still
        short, rather than hand over 12 minutes for a 40-minute ask in silence."""
        budget = self.b['minutes'] * 60_000
        total = sum(dur_ms(t) for t in picked)
        if total >= budget - 120_000:
            return
        for pools in (('comfort', 'familiar', 'forgotten', 'warm'), ('new_known', 'new_frontier')):
            ranked = sorted(((self.score(t, p), p, t) for p in pools for t in self.pools.get(p, [])), key=lambda x: -x[0])
            for s, p, t in ranked:
                if total >= budget or s <= 0:
                    break
                if t['id'] in ids or per_artist[t.get('artist_key')] >= 3:
                    continue
                picked.append(dict(t, pool=p))
                ids.add(t['id'])
                per_artist[t.get('artist_key')] += 1
                total += dur_ms(t)
        if total < budget * 0.7:
            what = 'instrumental songs' if self.c.get('instrumental') else 'songs'
            self.notes.append(f'Only {round(total / 60000)} minutes of {what} fit this, so it is shorter than you asked.')

    def _matcher(self, w):
        keys = self.cat.artist_keys([w['artist']]) if w.get('artist') else None
        return lambda t: ((not w.get('world') or t.get('world') == w['world']) and
                          (not w.get('origin') or (t.get('origin') or 'Western/other') == w['origin']) and
                          (keys is None or t.get('artist_key') in keys))

    def enforce_wants(self, order):
        """'Some Bengali', 'two Arijit songs': guarantee the count. The activity's hard limits are relaxed for these
        (a run's energy floor would otherwise rule out most of a world), and each one takes the place of the weakest
        middle song that no other wish is holding."""
        wants = self.b.get('want') or []
        if not wants:
            return order
        matchers = [self._matcher(w) for w in wants]
        soft = {k: v for k, v in self.c.items() if k not in ('min_energy', 'max_energy', 'min_dance', 'max_acoustic')}
        for w, match in zip(wants, matchers):
            need = w['count'] - sum(1 for t in order if match(t))
            if need <= 0:
                continue
            ids = {t['id'] for t in order}
            cands = []
            for pool in ('comfort', 'familiar', 'forgotten', 'warm', 'new_known', 'new_frontier'):
                for t in self.pools.get(pool, []):
                    if t['id'] in ids or t['id'] in self.avoid or t.get('artist_key') in self.excl or not match(t):
                        continue
                    base = new_score(t) if pool.startswith('new') else affinity(t)
                    if not pool.startswith('new'):
                        rest = {'comfort': 21, 'familiar': 10, 'warm': 2}.get(pool, 0) * self.c.get('cool', 1.0) * WEAR.get(t.get('world'), 1.0)
                        if t['since'] < rest:
                            continue
                        base *= 1.5  # a wish is better met with songs he knows
                    cands.append((max(base, 0.01) * max(fit(t, soft, self.mid_e), 0.05) * math.exp(self.rng.gauss(0, 0.25)), pool, t))
            cands.sort(key=lambda x: -x[0])
            for _, pool, t in cands[:need]:
                held = lambda x: x.get('pinned') or any(m(x) for m in matchers)
                victims = [i for i in range(1, len(order) - 1) if not held(order[i])]
                new = dict(t, pool=pool)
                if victims:
                    i = min(victims, key=lambda i: new_score(order[i]) if order[i]['pool'].startswith('new') else affinity(order[i]))
                    order[i] = new
                else:
                    order.insert(max(1, len(order) - 1), new)
        return order

    def trim(self, order):
        """Fit the time budget: drop the weakest middle songs from whichever side is over its share; never pins."""
        budget = self.b['minutes'] * 60_000
        dur = dur_ms
        total = sum(dur(t) for t in order)
        while len(order) > 4 and total > budget + 90_000:
            middle = [i for i in range(1, len(order) - 1) if not order[i].get('pinned')]
            if not middle:
                break
            n_new = sum(1 for t in order if t['pool'].startswith('new'))
            drop_new = n_new / len(order) > (1 - self.b['familiar']) and n_new > 1
            cand = [i for i in middle if order[i]['pool'].startswith('new') == drop_new] or middle
            i = min(cand, key=lambda i: new_score(order[i]) if order[i]['pool'].startswith('new') else affinity(order[i]))
            total -= dur(order.pop(i))
        return order

    def replacement(self, old, current_ids, target_e=None):
        """The best song to put in `old`'s place: same pool first, then any pool on its side."""
        pools = [old.get('pool')] + [p for p in (FAMILIAR_POOLS if old.get('pool') in FAMILIAR_POOLS else ('new_known', 'new_frontier'))
                                     if p != old.get('pool')]
        e = target_e if target_e is not None else old.get('energy')
        for pool in pools:
            ranked = sorted(((self.score(t, pool, e), t) for t in self.pools.get(pool, [])
                             if t['id'] not in current_ids and t['id'] != old['id']), key=lambda x: -x[0])
            if ranked and ranked[0][0] > 0:
                return dict(ranked[0][1], pool=pool)
        return None


def sequence(picked, c):
    """Opener (strongest familiar) first, favourite last; the middle follows the energy arc; each new song goes after a
    familiar song, preferably from the same world, never two new in a row if avoidable."""
    fam = [t for t in picked if not t['pool'].startswith('new')]
    new = [t for t in picked if t['pool'].startswith('new')]
    if not fam:
        return new
    fam.sort(key=lambda t: -affinity(t))
    closer = fam.pop(0)
    opener = fam.pop(0) if fam else None
    e0, e1 = c['energy']
    mid = (e0 + e1) / 2
    rest = sorted(fam, key=lambda t: (t.get('energy') if t.get('energy') is not None else mid))
    if e1 < e0:
        rest.reverse()  # gliding down: highest energy first
    if c.get('peak'):
        rest = sorted(fam, key=lambda t: (t.get('energy') if t.get('energy') is not None else 0.75))
    F = ([opener] if opener else []) + rest + [closer]
    gaps = len(F) - 1
    if not new or gaps <= 0:
        return F + new
    first = 1 if gaps > len(new) else 0
    pts = sorted({first + round(i * (gaps - first) / len(new)) for i in range(len(new))})
    while len(pts) < len(new):
        pts.append(pts[-1])
    out = []
    for i, f in enumerate(F):
        out.append(f)
        for _ in range(pts.count(i)):
            if i == len(F) - 1 or not new:
                break
            new.sort(key=lambda t: (t.get('world') != f.get('world'), -new_score(t)))
            out.append(new.pop(0))
    return out + new


# -- edits ------------------------------------------------------------------------------------------------------------

def external(title, artist):
    """A song asked for by name that isn't in the catalogue; it is found on the services at sync time."""
    return {'id': f'ext|{nartist(artist)}|{ntrack(title)}', 'title': title, 'artist': artist or '', 'artist_key': nartist(artist),
            'pool': 'asked', 'kind': 'asked', 'pinned': True}


def _find_in(tracks, title, artist=None):
    tk, ak = ntrack(title or ''), nartist(artist or '')
    for i, t in enumerate(tracks):
        if t and tk and ntrack(t['title']) == tk and (not ak or ak in nartist(t['artist']) or nartist(t['artist']) in ak):
            return i
    return None


def apply_ops(cat, brief, tracks, ops, builder, notes=None):
    """Edits on the list as shown (indices are 0-based into `tracks`). Returns (tracks, brief); anything that could not
    be done is described in `notes`."""
    tracks = [dict(t) for t in tracks]
    brief = dict(brief, avoid=list(brief['avoid']))
    notes = notes if notes is not None else []
    removed = []
    for op in ops or []:
        kind, i = op.get('op'), op.get('index')
        if op.get('title') and kind in ('remove', 'swap', 'pin', 'unpin', 'move'):
            j = _find_in(tracks, op['title'], op.get('artist'))
            if j is not None:
                i = j
            elif kind == 'move':
                kind = 'add'  # "play X first" for a song that isn't there yet
        ok = isinstance(i, int) and 0 <= i < len(tracks) and tracks[i] is not None
        if kind in ('remove', 'swap', 'pin', 'unpin', 'move') and not ok:
            notes.append(f"Couldn't find {op.get('title') or 'that song'} in the list.")
            continue
        if kind == 'remove':
            removed.append(tracks[i])
            tracks[i] = None
        elif kind == 'swap':
            old = tracks[i]
            builder.avoid.add(old['id'])
            prev_e = next((t.get('energy') for t in tracks[max(0, i - 1):i] if t and t.get('energy') is not None), None)
            new = builder.replacement(old, {t['id'] for t in tracks if t}, old.get('energy') if old.get('energy') is not None else prev_e)
            if new:
                tracks[i] = new
                brief['avoid'].append(old['id'])  # only once it is really gone: a failed swap keeps the song
            else:
                builder.avoid.discard(old['id'])
                notes.append(f"Nothing else fits in place of {old['title']}, so it stays.")
        elif kind in ('pin', 'unpin'):
            tracks[i]['pinned'] = kind == 'pin'
        elif kind == 'move':
            to = op.get('to')
            if isinstance(to, int):
                t = tracks.pop(i)
                tracks.insert(max(0, min(len(tracks), to)), t)
        elif kind == 'add' and op.get('title'):
            t = cat.find(op['title'], op.get('artist'))
            t = dict(t, pool='asked', pinned=True) if t else external(op['title'], op.get('artist', ''))
            to = op.get('to')
            at = to if isinstance(to, int) else max(0, len(tracks) - 1)
            j = next((k for k, x in enumerate(tracks) if x and x['id'] == t['id']), None)
            if j is not None:
                if isinstance(to, int):
                    tracks.insert(max(0, min(len(tracks), at)), tracks.pop(j))
                continue
            tracks.insert(max(0, min(len(tracks), at)), t)
            brief['avoid'] = [a for a in brief['avoid'] if a != t['id']]
    for t in removed:
        brief['avoid'].append(t['id'])
    return [t for t in tracks if t], brief


def rebuild(cat, brief, hour, prev_tracks, served, sticky=True, now=None):
    """A new list for a changed brief: pinned songs stay where they were, and songs already there are kept unless they
    no longer fit."""
    bl = Builder(cat, brief, hour, now=now, served=served, prev=prev_tracks, sticky=sticky)
    pins = [t for t in prev_tracks if t.get('pinned')]
    order = bl.build(pins=pins)
    # Put pinned songs back at the places they held. Take them all out first, then insert in order of position, so
    # one pin's move can't shift another's.
    places = sorted(((next((j for j, x in enumerate(prev_tracks) if x['id'] == t['id'])), t['id']) for t in pins))
    pinned = {x['id']: x for x in order if x['id'] in {pid for _, pid in places}}
    order = [x for x in order if x['id'] not in pinned]
    for want, pid in places:
        if pid in pinned:
            order.insert(min(want, len(order)), pinned[pid])
    return order, bl


# -- tweaks (one tap, no language model) -----------------------------------------------------------------------------

TWEAKS = {
    'familiar': 'More familiar', 'new': 'More new', 'calmer': 'Calmer', 'energy': 'More energy',
    'instrumental': 'Instrumental', 'shorter': 'Shorter', 'longer': 'Longer', 'reshuffle': 'Reshuffle',
}


def is_instrumental(brief):
    """Instrumental-only right now: set on the brief, or the activity's own default (focus)."""
    return bool(brief.get('instrumental', ACTIVITIES[brief['activity']].get('instrumental', False)))


def tweak(brief, name):
    b = dict(brief)
    if name == 'familiar':
        b['familiar'] = min(0.95, b['familiar'] + 0.15)
    elif name == 'new':
        b['familiar'] = max(0.15, b['familiar'] - 0.15)
    elif name in ('calmer', 'energy'):
        d = -0.12 if name == 'calmer' else 0.12
        b['energy'] = [round(min(0.95, max(0.12, e + d)), 2) for e in b['energy']]
    elif name == 'shorter':
        b['minutes'] = max(10, int(round(b['minutes'] * 0.75 / 5) * 5))
    elif name == 'longer':
        b['minutes'] = min(600, b['minutes'] + max(10, int(round(b['minutes'] * 0.33 / 5) * 5)))
    elif name == 'reshuffle':
        b['seed'] = random.randrange(1, 10**9)
    elif name == 'instrumental':
        b['instrumental'] = not is_instrumental(b)  # a toggle: tap again for vocals
    return normalize(b)


TWEAK_REPLY = {
    'familiar': 'More songs you know.', 'new': 'More songs new to you.', 'calmer': 'Brought the energy down.',
    'energy': 'Turned the energy up.', 'shorter': 'Made it shorter.', 'longer': 'Made it longer.', 'reshuffle': 'A fresh pick, same brief.',
    'instrumental': 'Instrumental only now.', 'vocals': 'Vocals are back.',
}


# -- words for the screen --------------------------------------------------------------------------------------------

def _month(ts):
    return time.strftime('%b %Y', time.gmtime(ts)) if ts else None


def why(t, now=None):
    now = now or time.time()
    p, n = t.get('pool'), t.get('plays') or 0
    if p == 'comfort':
        s = f'A favourite: {n} plays'
        since = (now - t['last_ts']) / DAY if t.get('last_ts') else None
        return s + (f', last in {_month(t["last_ts"])}' if since and since > 60 else '')
    if p == 'familiar':
        return f'Well known: {n} plays'
    if p == 'forgotten':
        return f'Forgotten favourite: {n} plays, none since {_month(t.get("last_ts"))}'
    if p == 'warm':
        return f'Getting to know it: {n} plays since {_month(t.get("first_ts"))}'
    if p == 'new_known':
        return 'New to you, from an artist you play a lot'
    if p == 'new_frontier':
        return 'New artist: loved by fans of artists you love'
    if p == 'asked':
        return 'You asked for it'
    return ''


def summary(brief, tracks):
    """One short line about the shape: how familiar, what's new, and the arc."""
    n_new = sum(1 for t in tracks if str(t.get('pool', '')).startswith('new'))
    n_forgot = sum(1 for t in tracks if t.get('pool') == 'forgotten')
    e0, e1 = brief['energy']
    arc = 'builds to a long peak' if ACTIVITIES[brief['activity']].get('peak') else (
        'winds down' if e1 < e0 - 0.05 else ('builds up' if e1 > e0 + 0.05 else 'stays steady'))
    known = 'All familiar' if n_new == 0 else ('Mostly familiar' if n_new <= len(tracks) / 3 else 'Mostly new to you')
    bits = [known] + ([f'{n_new} new'] if n_new else []) + ([f'{n_forgot} forgotten favourite{"s" if n_forgot > 1 else ""}'] if n_forgot else [])
    lead = 'Instrumental. ' if is_instrumental(brief) else ''
    return f"{lead}{', '.join(bits)}. It {arc}."


def duration_ms(tracks):
    return sum(dur_ms(t) for t in tracks)
