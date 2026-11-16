"""A mixtape, the way a friend who loves music makes one.

The engine (engine.py) knows his history: which songs he loves and how often they bear repeating, what's new to him
that's likely to stick, how a run's energy should climb. It doesn't know what a song is about, who sings it, or why
two songs belong side by side; and the day's story was written apart from the songs, so a story about women who
changed music sat on top of four songs sung by men. Here the thinking comes first:

1. A thread, for this moment, his words and the day's subject: what the mix is about, its arc, what joins its songs.
2. Songs chosen and ordered along it: his own (the engine's best fits for the moment, or his song by an artist the
   thread calls for) and songs from anywhere he hasn't heard, each checked against Apple's catalogue so nothing
   imagined is played; every song has its part, and a reason it follows the one before.
3. The engine's limits on top: the length, at most one song per artist, the share he knows, his exclusions.

The story along the songs is written afterwards (story.py, in the background), from what is known about each.
"""
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request

from difflib import SequenceMatcher

from . import engine as E
from .genius import first_artist
from .textnorm import nartist, ntrack

log = logging.getLogger('telly.curate')

TOOL = {
    'name': 'mixtape',
    'description': 'The mix: its thread and its songs, in order.',
    'input_schema': {
        'type': 'object',
        'properties': {
            'title': {'type': 'string', 'description': 'Its name: a few words, a pun welcome, never a cliché'},
            'thread': {'type': 'string', 'description': 'One sentence: what joins these songs, for this moment'},
            'songs': {'type': 'array', 'items': {
                'type': 'object',
                'properties': {
                    'n': {'type': 'integer', 'description': 'A song from the list, by its number'},
                    'library_artist': {'type': 'string', 'description': 'Or: his best-fitting song by this artist from his library'},
                    'title': {'type': 'string', 'description': 'Or a song from anywhere: its exact title'},
                    'artist': {'type': 'string', 'description': '... and its main artist'},
                    'part': {'type': 'string', 'description': "Its part in the mix, a few words ('the voice that started it')"},
                    'link': {'type': 'string', 'description': 'Why it follows the song before (the first: why it opens)'},
                },
                'required': ['part', 'link']}},
        },
        'required': ['title', 'thread', 'songs'],
    },
}

SYSTEM = """You make mixtapes for one friend, the way the best music friends do: a record-shop clerk's ears, a crate
digger's patience, the range of the best corners of Reddit (r/listentothis, r/ifyoulikeblank, r/vintageobscura,
r/indieheads, r/LetsTalkMusic, r/IndianMusic, r/jazz, r/hiphopheads): Hindi film from every decade, Bengali, Urdu,
Punjabi, Tamil and Malayalam, ghazal and qawwali, Western indie, rock, soul, jazz, R&B, bossa nova, Afrobeat, ambient,
soundtracks, the deep cut over the obvious hit, but the beloved hit when it is exactly right.

A mix serves its moment first (a run must move, a bedtime must settle), then its thread: a reason these songs are
together, and an order that means something (it opens, it turns, it lands). Each song earns its place, and each one
follows the one before for a reason a friend would enjoy hearing: the same voice twenty years apart, a song and its
sample, a cover and its original, two cities, a lyric that answers the last, a raga that carries over, a joke in the
titles. Range is part of the pleasure: languages, decades and voices mixed unless the moment or his words narrow them."""


WORLD_WORDS = {'hindi': 'Hindi film/indie', 'roots': 'Bengali/Pakistani/South Indian', 'indie': 'indie/folk', 'pop': 'pop',
               'rock': 'rock', 'rap': 'rap/R&B', 'night': 'electronic/chill', 'edm': 'dance', 'sound': 'soundtrack/world'}


def _words(t):
    """A song's sound in a few words, from its features (what the list shows the curator)."""
    bits = []
    e = t.get('energy')
    if e is not None:
        bits.append('very quiet' if e < 0.25 else 'quiet' if e < 0.45 else 'mid' if e < 0.65 else 'lively' if e < 0.8 else 'loud')
    if (t.get('acousticness') or 0) > 0.6:
        bits.append('acoustic')
    if (t.get('instrumentalness') or 0) > 0.6 or t.get('vocals') == 0:
        bits.append('instrumental')
    if t.get('tempo'):
        bits.append(f"{round(t['tempo'])} bpm")
    return ', '.join(bits)


def _known(t, now):
    p = t.get('pool')
    if p in ('comfort', 'familiar', 'forgotten', 'warm'):
        return E.TAG.get(p, 'known')
    return 'new to him'


APPLE_KEEP = 30 * 86400  # what Apple's catalogue said about a song (found or not), kept this long
APPLE_REST = [0.0]       # Apple refused us (403/429): no lookups until then, and nothing from it is taken as "doesn't exist"


class Curator:
    def __init__(self, llm, cat, about, store=None):
        self.llm, self.cat, self.about, self.store = llm, cat, about, store

    # -- what the curator chooses from -------------------------------------------------------------------------------
    def pool(self, brief, hour, served, size=110):
        """The engine's best fits for this moment from his library and the new songs lined up for him, and his artists."""
        b = E.Builder(self.cat, brief, hour, served=served, sticky=False)
        known, new = [], []
        for pool, k, out in (('comfort', 18, known), ('familiar', 26, known), ('forgotten', 14, known), ('warm', 8, known),
                             ('new_known', 26, new), ('new_frontier', 18, new)):
            ranked = sorted(((b.score(t, pool), t) for t in b.pools.get(pool, [])), key=lambda x: -x[0])
            out += [dict(t, pool=pool) for s, t in ranked[:k] if s > 0]
        per_artist, picked = {}, []
        for t in known + new:  # no artist crowding the list
            a = t.get('artist_key')
            if per_artist.get(a, 0) < 2:
                per_artist[a] = per_artist.get(a, 0) + 1
                picked.append(t)
        return picked[:size], b

    def artists(self, limit=140):
        """His artists, most loved first: the curator can ask for 'his song by X' when the thread calls for X."""
        top = sorted(self.cat.artist_aff.items(), key=lambda x: -x[1])[:limit]
        return [self.cat.artist_name.get(k, k) for k, _ in top]

    # -- choosing ----------------------------------------------------------------------------------------------------
    def choose(self, brief, moment, said, lens, hour, served, keep=None, instruction=None, deadline=None):
        """-> (title, thread, [(track, part, link)]) or None. `keep`: the songs a mix has now (when changing one), and
        `instruction` what he asked of it. Everything is done by `deadline` (epoch s), or the engine takes over."""
        deadline = deadline or time.time() + 90
        pool, builder = self.pool(brief, hour, served)
        now = time.time()
        listing = '\n'.join(f"[{i}] {t['title']} — {t['artist']} · {WORLD_WORDS.get(t.get('world'), t.get('world') or '')}"
                            f" · {_known(t, now)}{', ' + str(t.get('plays')) + ' plays' if t.get('plays') else ''}"
                            f"{' · ' + _words(t) if _words(t) else ''}" for i, t in enumerate(pool, 1))
        n_songs = min(45, max(3, round(brief['minutes'] * 60_000 / E.AVG_MS)))
        known_n = round(n_songs * brief['familiar'])
        cfg = E.config(brief)
        e0, e1 = cfg['energy']
        rules = [f"{n_songs} songs ({brief['minutes']} minutes); never more.",
                 f"About {known_n} of them songs he knows (from the list, marked by how well he knows them, or his song by one "
                 f"of his artists); the rest new to him, from the list or from anywhere.",
                 f"Energy from {'quiet' if e0 < 0.4 else 'medium' if e0 < 0.65 else 'high'} to "
                 f"{'quiet' if e1 < 0.4 else 'medium' if e1 < 0.65 else 'high'}"
                 f"{' (never loud: ' + str(cfg.get('max_energy')) + ' at most on 0-1)' if cfg.get('max_energy') else ''}."]
        if E.is_instrumental(brief):
            rules.append('Instrumental only: no singing at all.')
        if brief.get('exclude_artists'):
            rules.append('Never: ' + ', '.join(brief['exclude_artists']) + '.')
        if brief.get('include_artists'):
            rules.append('Include songs by: ' + ', '.join(brief['include_artists']) + '.')
        if brief.get('origins'):
            rules.append('Only songs from: ' + ', '.join(brief['origins']) + '.')
        for w in brief.get('want') or []:
            rules.append(f"At least {w['count']} " + ' '.join(str(w[k]) for k in ('origin', 'world', 'artist') if w.get(k)) + ' songs.')
        lens_rule = ''
        if lens:
            lens_rule = f"""
Today he wants to learn about: "{lens}". When that is about music (its people, eras, places, movements), it shapes the
choice: the songs are of it (if it names women who changed music, every voice or song is one of theirs), across
cultures and decades. When it isn't about music, connect only the songs that truly meet it (a film, a place, an era),
never forced; the moment still comes first."""
        current = ''
        if keep:
            current = '\nThe mix as it is now (keep what still serves the thread; songs marked kept stay, in place):\n' + '\n'.join(
                f"- {t['title']} — {t['artist']}{' (kept)' if t.get('pinned') else ''}" for t in keep)
        user = f"""For: {moment}
His words: "{said}"{f'''
He now asks: "{instruction}"''' if instruction else ''}
About him: {self.about}
{lens_rule}
Rules:
{chr(10).join('- ' + r for r in rules)}
- One song per artist.
- Every song must itself suit the moment as recorded: for a bedtime, songs that are soft and slow all the way through,
  never an anthem that builds or a song you imagine played quietly. For songs from his list, its sound is given.
- Range: mix languages, cultures and decades (he loves Hindi film, Bengali and Pakistani music as much as Western), unless
  his words or the subject narrow it.
- A song from anywhere must be one you are sure exists, by its exact title and main artist.
- The title and the thread speak only of what is in this mix; no promises the songs don't keep. The title: a few words,
  wit welcome (a pun, a reference he'd catch), never a label.
{current}
His artists, most loved first (for "his song by X"):
{', '.join(self.artists())}

Songs that fit this moment, from his library and the new ones lined up for him:
{listing}

Make the mix: its title, its thread, and its songs in order, each with its part and why it follows the last."""
        budget = deadline - time.time() - 12  # leave time to find the songs from anywhere
        if budget < 15:
            return None
        out = self.llm.tool(SYSTEM, user, TOOL, max_tokens=4000, timeout=min(75, budget), retries=0, wait=0)
        picks = self.resolve(out.get('songs') or [], pool, builder, keep, deadline)
        if len(picks) < max(3, n_songs // 2):
            log.warning('curator: only %d of the songs resolved', len(picks))
            return None
        return (str(out.get('title') or brief['title']).strip()[:60], str(out.get('thread') or '').strip()[:300], picks)

    # -- making the choice real ----------------------------------------------------------------------------------------
    def resolve(self, songs, pool, builder, keep=None, deadline=None):
        """The curator's songs -> [(track, part, link)]: from the list; his best-fitting song by an artist; or a song
        from anywhere, found in Apple's catalogue (nothing imagined is played). Every pick obeys the playlist's rules
        (removed songs stay removed, excluded artists, origins, instrumental); repeats drop out; songs he pinned keep
        their places."""
        deadline = deadline or time.time() + 60
        out, seen, per_artist = [], set(), {}
        excluded = self.cat.artist_keys(builder.b.get('exclude_artists'))
        included = self.cat.artist_keys(builder.b.get('include_artists'))
        instrumental = E.is_instrumental(builder.b)
        kept = {t['id']: t for t in keep or []}
        for s in songs:
            if not isinstance(s, dict):
                continue
            t = None
            n = s.get('n')
            if isinstance(n, int) and 1 <= n <= len(pool):
                t = pool[n - 1]
            elif s.get('library_artist'):
                t = self.library_song(str(s['library_artist']), builder, seen)
            elif s.get('title') and s.get('artist'):
                title, artist = str(s['title']), str(s['artist'])
                ext = E.external(title, artist)['id']
                t = kept.get(ext) or self.in_library(title, artist, builder)
                if t is None and time.time() < deadline - 5:
                    t = self.anywhere(title, artist)
            if not t or t['id'] in seen:
                continue
            if t['id'] in builder.avoid or t.get('artist_key') in excluded:
                continue  # removed from this playlist before, or an artist he excluded
            if t.get('kind') != 'curated':  # his library: its world, origin and sound are known
                if not builder.allowed(t) or (instrumental and not E.instrumental_ok(t)):
                    continue
            a = t.get('artist_key') or nartist(t.get('artist'))
            if per_artist.get(a, 0) >= (2 if a in included else 1):
                continue
            seen.add(t['id'])
            per_artist[a] = per_artist.get(a, 0) + 1
            out.append((t, str(s.get('part') or '').strip()[:120], str(s.get('link') or '').strip()[:200]))
        # Pins hold: a song he pinned is where it was, whatever the curator did with it.
        for i, t in enumerate(keep or []):
            if t.get('pinned'):
                out = [x for x in out if x[0]['id'] != t['id']]
                out.insert(min(i, len(out)), (dict(t), 'kept in place', ''))
        return out

    def in_library(self, title, artist, builder):
        """His own copy of a song, only when it is surely the same artist (a shared first word isn't)."""
        t = self.cat.find(title, artist)
        if t is None:
            return None
        ours, theirs = nartist(first_artist(artist)), nartist(t.get('artist'))
        if not ours or not (ours == theirs or f' {ours} ' in f' {theirs} ' or f' {theirs} ' in f' {ours} '):
            return None
        return dict(t, pool=E.pool_of(t, builder.now) or ('new_known' if t.get('kind') == 'new_known' else t.get('kind') or 'new_frontier'))

    def library_song(self, artist, builder, seen):
        """His song by this artist that best fits the moment (the engine's choice: the measured familiarity curve)."""
        keys = self.cat.artist_keys([artist])
        best, best_s = None, 0.0
        for t in self.cat.tracks:
            if t.get('artist_key') not in keys or t['id'] in seen:
                continue
            pool = E.pool_of(t, builder.now) or 'familiar'
            if pool not in builder.pools:
                continue
            t = next((x for x in builder.pools[pool] if x['id'] == t['id']), dict(t, since=9999, skip=None))
            s = builder.score(t, pool)
            if s > best_s:
                best, best_s = dict(t, pool=pool), s
        if best is None:  # every one resting, or a poor fit by the numbers: the thread asked for this voice, so his most played
            mine = [t for t in self.cat.tracks if t.get('artist_key') in keys and t['id'] not in seen and builder.allowed(t)
                    and t['id'] not in builder.avoid and (not E.is_instrumental(builder.b) or E.instrumental_ok(t))]
            if mine:
                t = max(mine, key=lambda x: x.get('plays') or 0)
                best = dict(t, pool=E.pool_of(t, builder.now) or 'familiar')
        return best

    def anywhere(self, title, artist):
        """A song he hasn't got, found in Apple's catalogue by its title and artist -> a track, or None. What Apple says
        is kept for a month (found or not); when Apple refuses (403/429) it rests, and nothing is taken as missing."""
        k = f'apple:{nartist(first_artist(artist))}|{ntrack(title)}'
        hit = self.store.get(k) if self.store is not None else None
        if hit is None or time.time() - hit.get('ts', 0) > APPLE_KEEP:
            if APPLE_REST[0] > time.time():
                return None
            q = urllib.parse.urlencode({'term': f'{ntrack(title)} {first_artist(artist)}', 'entity': 'song', 'limit': 10, 'country': 'IN'})
            try:
                with urllib.request.urlopen(urllib.request.Request(f'https://itunes.apple.com/search?{q}',
                                                                   headers={'User-Agent': 'telly/2 (personal playlist app)'}), timeout=5) as r:
                    results = json.loads(r.read()).get('results') or []
            except urllib.error.HTTPError as e:
                if e.code in (403, 429):
                    APPLE_REST[0] = time.time() + 300
                return None
            except Exception:
                return None
            time.sleep(0.25)  # Apple asks for about 20 a minute
            hit = {'found': self._best(results, title, artist), 'ts': time.time()}
            if self.store is not None:
                self.store.put(k, hit)
        best = hit.get('found')
        if not best:
            log.info('curator: %s by %s not found anywhere: dropped', title, artist)
            return None
        t = E.external(title, artist)
        return dict(t, pool='curated', kind='curated', pinned=False, duration_ms=best.get('trackTimeMillis'),
                    year=(best.get('releaseDate') or '')[:4] or None, thumb=(best.get('artworkUrl100') or '').replace('100x100bb', '600x600bb') or None)

    @staticmethod
    def _best(results, title, artist):
        want_t, want_a = ntrack(title), nartist(first_artist(artist))
        best, best_s = None, 0.0
        for x in results:
            if not isinstance(x, dict):
                continue
            ts = SequenceMatcher(None, want_t, ntrack(x.get('trackName') or '')).ratio()
            names = nartist(x.get('artistName') or '')
            a = 1.0 if want_a and f' {want_a} ' in f' {names} ' else SequenceMatcher(None, want_a, names).ratio()
            if ts >= 0.85 and a >= 0.75 and ts + a > best_s:
                best, best_s = {k: x.get(k) for k in ('trackName', 'artistName', 'trackTimeMillis', 'releaseDate', 'artworkUrl100')}, ts + a
        return best

def fit_length(picks, minutes):
    """Trim a mix that runs long, from the end inward but never its closer: within a song of the time asked."""
    budget = minutes * 60_000
    total = sum(E.dur_ms(t) for t, _, _ in picks)
    while len(picks) > 3 and total - E.dur_ms(picks[-2][0]) >= budget - 60_000 and total > budget + 90_000:
        total -= E.dur_ms(picks[-2][0])
        picks = picks[:-2] + picks[-1:]
    return picks


def mixtape(curator, brief, moment, said, lens, hour, served, keep=None, instruction=None, deadline=None):
    """The curated mix -> (tracks, mix dict) or None (then the engine builds it, as before)."""
    try:
        got = curator.choose(brief, moment, said, lens, hour, served, keep, instruction, deadline)
    except Exception as e:
        log.warning('curator unavailable: %s', str(e)[:160])
        return None
    if not got:
        return None
    title, thread, picks = got
    picks = fit_length(picks, brief['minutes'])
    tracks = []
    for i, (t, part, link) in enumerate(picks, 1):
        tracks.append(dict(t, n=i, part=part, link=link))
    return tracks, {'title': title, 'thread': thread}


# -- the story along the songs (written after they are settled, in the background) ------------------------------------
NOTES_TOOL = {
    'name': 'liner_notes',
    'description': 'The liner notes: the thread, an opening, a note for each song in order, a last line.',
    'input_schema': {
        'type': 'object',
        'properties': {
            'thread': {'type': 'string', 'description': "At most 16 words: what joins these songs, the way you'd say it on the tape's spine; never a list of names"},
            'intro': {'type': 'string', 'description': 'Two to four sentences before the first song'},
            'notes': {'type': 'array', 'items': {'type': 'object', 'properties': {
                'n': {'type': 'integer'}, 'text': {'type': 'string', 'description': 'One or two sentences, at most 34 words'}},
                'required': ['n', 'text']}},
            'outro': {'type': 'string', 'description': 'One sentence after the last song'},
        },
        'required': ['thread', 'intro', 'notes', 'outro'],
    },
}


def liner_notes(llm, about, moment, said, lens, mix_title, tracks, sources):
    """The story told along the songs: a thread, an opening, one note per song that carries on from the last (a callback,
    a contrast, a pun, the story behind it), and a last line. `sources`: {track id: text} (Genius and Wikipedia). Every
    fact must be in the song's source (or the previous song's, for a bridge); each note is read again against them
    (ground.settle) and rewritten or dropped. -> {'thread', 'intro', 'outro', 'notes': {track id: text}}"""
    from .ground import settle
    listing = []
    for i, t in enumerate(tracks, 1):
        src = sources.get(t['id']) or 'nothing found'
        listing.append(f"[{i}] {t['title']} — {t['artist']}{' (' + str(t.get('year')) + ')' if t.get('year') else ''}\n"
                       f"    its part: {t.get('part') or ''}\n    why it follows: {t.get('link') or ''}\n    sources: {src[:1400]}")
    system = ("You write the liner notes for a mix made for one friend: the way a friend who loves music talks you through a "
              "mixtape as it plays. Warm, specific, a little playful; a pun or a reference he'd catch when it lands "
              "naturally; never hype. About him: " + about)
    user = f"""The mix: "{mix_title}", for {moment}. His words: "{said}".{f' Today he wants to learn about: "{lens}".' if lens else ''}

The songs, in order:
{chr(10).join(listing)}

Write the notes as one story told along the mix: the opening sets up the thread; each song's note says the one thing
worth knowing as it starts, and carries on from the song before (a callback, a contrast, the next chapter of the same
story) so the order means something; the last line lands it.

Rules:
- Facts (names, dates, places, numbers, who did what, why) only from that song's sources, or the previous song's when
  you bridge. Never combine or infer, never a cause the sources don't give, never a claim about who sings a song unless a
  source says it. When a song's sources are thin, say less: a line about its sound or its place in the mix is fine.
- Never mention his own listening (how often, when first).
- Speak to him as "you" now and then. No exclamation marks, no lists."""
    out = llm.tool(system, user, NOTES_TOOL, max_tokens=4500, timeout=180, retries=1, wait=10)
    raw = {}
    for item in out.get('notes') or []:
        n, text = (item.get('n'), ' '.join(str(item.get('text') or '').split())) if isinstance(item, dict) else (None, '')
        if isinstance(n, int) and 1 <= n <= len(tracks) and 8 <= len(text) <= 300:
            raw[n - 1] = text
    order = sorted(raw)
    # Read again against exactly what each may rest on: its song's sources and the previous song's.
    pairs = [(f"About \"{tracks[i]['title']}\" by {tracks[i]['artist']}: " + (sources.get(tracks[i]['id']) or 'nothing') +
              (f" | The song before, \"{tracks[i - 1]['title']}\" by {tracks[i - 1]['artist']}: " + (sources.get(tracks[i - 1]['id']) or 'nothing') if i else ''),
              raw[i]) for i in order]
    everything = ' | '.join(f"{t['title']} by {t['artist']}: {(sources.get(t['id']) or 'nothing')[:700]}" for t in tracks)
    said_ok = settle(llm, pairs, words=34, limit=2600, opinions=True) if pairs else []
    notes = {tracks[i]['id']: said_ok[k] for k, i in enumerate(order) if said_ok[k]}
    # The opening and the last line may draw on any song: they're read against all the sources at once.
    raw = {k: ' '.join(str(out.get(k) or '').split()) for k in ('thread', 'intro', 'outro')}
    # The opening is about how the mix begins and the last line about how it ends: each is read against those songs
    # (a long mix's sources together would be cut short before its closer).
    ends = lambda ts: ' | '.join(f"{t['title']} by {t['artist']}: {(sources.get(t['id']) or 'nothing')[:1200]}" for t in ts)
    minutes = round(sum(E.dur_ms(t) for t in tracks) / 60000)
    mix = (f"The mix \"{mix_title}\": {len(tracks)} songs, about {minutes} minutes, for {moment}"
           + (f", made for the subject he asked for, \"{lens}\" (the mix's premise, which its framing may state)" if lens else '')
           + ': ' + '; '.join(f"{t['title']} — {t['artist']}" for t in tracks) + '. ')
    against = {'thread': mix + everything, 'intro': mix + ends(tracks[:5]), 'outro': mix + ends(tracks[-5:])}
    framed = {}
    if raw['thread']:  # the tape's spine: one short line, its own form kept through any rewrite
        framed['thread'] = settle(llm, [(against['thread'], raw['thread'])], words=16, limit=9000, opinions=True,
                                  form='Keep it one short line about what joins the songs; never a list of their names. ')[0]
    rest = [k for k in ('intro', 'outro') if raw[k]]
    if rest:
        framed.update(zip(rest, settle(llm, [(against[k], raw[k]) for k in rest], words=60, limit=9000, opinions=True)))
    return {'thread': framed.get('thread', '')[:300], 'intro': framed.get('intro', ''), 'outro': framed.get('outro', ''), 'notes': notes}
