"""Genius, for what a song's lines mean: the annotations its readers (and now and then its writers) left on them, and
the song's own story. Read from Genius's JSON (with TELLY_GENIUS_TOKEN its official API, otherwise the same JSON its own
pages read), condensed by the model into the few things a friend would lean over and say, and kept: each song costs one
look, made in the background while its lyrics are being timed.

Each note belongs to a fragment of the lyrics, and is placed on the line of the recording's lyrics where that fragment
begins when the lyrics are asked for (they change version as they are romanised or refreshed; placing is cheap).
"""
import json
import logging
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from difflib import SequenceMatcher

from .story import _plain
from .textnorm import nartist, ntrack

log = logging.getLogger('telly.genius')

BROWSER = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'
KEEP_NONE = 14 * 86400   # "Genius has no page for it" is asked again after a fortnight
RETRY = 15 * 60          # a failed look (network, the model) is tried again after this
STALE = 30               # a song not asked about for this long (the phone asks every few seconds) was skipped past
TOOL = {
    'name': 'trivia',
    'description': 'What a friend would say while this song plays.',
    'input_schema': {
        'type': 'object',
        'properties': {
            'about': {'type': 'array', 'items': {'type': 'string'},
                      'description': 'Up to three things to say as it starts or between verses, each at most 24 words'},
            'lines': {'type': 'array', 'items': {
                'type': 'object',
                'properties': {'n': {'type': 'integer'}, 'text': {'type': 'string', 'description': 'At most 22 words; empty to skip'},
                               'roman': {'type': 'string', 'description': "Only for a line not in Latin letters: its first line in Latin letters"}},
                'required': ['n', 'text']}},
        },
        'required': ['about', 'lines'],
    },
}


class Unreachable(Exception):
    pass


def clean(s):
    """One line of the model's text, unwrapped from quotes only when they wrap all of it ("Be," he said stays whole)."""
    s = ' '.join(str(s or '').split())
    return s[1:-1].strip() if len(s) >= 2 and s[0] == s[-1] and s[0] in '"\'' else s


def first_artist(artist):
    return re.split(r'\s*(?:,|&|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bwith\b)\s*', artist or '', maxsplit=1, flags=re.I)[0].strip()


def pick(hits, title, artist):
    """The hit that is this song: its title, by one of its artists, not a translation page. Among several (a live
    version, a remaster), the one most read."""
    want_t, want_a = ntrack(title), nartist(first_artist(artist))
    best, best_key = None, None
    for h in hits:
        r = h.get('result') or {}
        if h.get('type', 'song') != 'song' or (r.get('primary_artist') or {}).get('name', '').startswith('Genius'):
            continue
        ts = SequenceMatcher(None, want_t, ntrack(r.get('title') or '')).ratio()
        names = nartist(r.get('artist_names') or (r.get('primary_artist') or {}).get('name') or '')
        a = 1.0 if want_a and f' {want_a} ' in f' {names} ' else SequenceMatcher(None, want_a, names).ratio()
        if ts < 0.85 or a < 0.6:
            continue
        key = (round(ts + a, 1), (r.get('stats') or {}).get('pageviews') or 0, r.get('annotation_count') or 0)
        if best_key is None or key > best_key:
            best, best_key = r, key
    return best


def place(refs, lines):
    """[{'fragment', 'text'}] onto the lyrics -> [{'line': i, 'text'}]: each note on the first line its fragment begins
    (a chorus is explained once), at most one per line. Fragments that are only a section's name ("[Verse 2]") can't
    be placed on lines that don't carry sections, and are left out."""
    plain = [_plain(l.get('text') or '') for l in lines]
    out, used = [], set()
    for ref in refs:
        # Its first sung line ("[Verse 1]\nMama, just killed a man" begins at "Mama"), as written and in Latin letters
        # (lyrics in Hindi or Bengali are shown romanised).
        bare = re.sub(r'\[[^\]]*\]', '', ref.get('fragment') or '')
        first = next((x for x in bare.split('\n') if _plain(x)), '')
        tries = [f for f in (_plain(first), _plain(ref.get('roman') or '')) if len(f) >= 6]
        hit = next((i for f in tries for i, l in enumerate(plain) if l and i not in used and _same_line(f, l)), None)
        if hit is not None:
            used.add(hit)
            out.append({'line': hit, 'text': ref['text']})
    return sorted(out, key=lambda x: x['line'])


def _same_line(f, l):
    """Does a fragment begin this line? Exactly, as the start of one or the other, or near enough (spellings differ)."""
    return l == f or (len(f) >= 10 and (l.startswith(f) or (f.startswith(l) and len(l) >= 10))) \
        or SequenceMatcher(None, f, l).ratio() >= 0.84


class Genius:
    def __init__(self, store, llm, token=''):
        self.store, self.llm, self.token = store, llm, token or ''
        # One look at a time (polite to Genius and the model), the song asked about most recently first: skipping
        # through ten songs mustn't leave the one playing waiting behind nine lookups nobody will read.
        self.waiting, self.asked, self.failed, self.working = {}, {}, {}, None
        self.cv = threading.Condition()
        self.thread = None

    # -- reading -------------------------------------------------------------------------------------------------------
    def _get(self, path, **params):
        base, headers = ('https://api.genius.com', {'Authorization': f'Bearer {self.token}'}) if self.token else \
            ('https://genius.com/api', {'User-Agent': BROWSER})
        req = urllib.request.Request(f'{base}{path}?{urllib.parse.urlencode(params)}', headers={**headers, 'Accept': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read()).get('response') or {}
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {}
            raise Unreachable(f'{path}: HTTP {e.code}') from None
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            raise Unreachable(f'{path}: {e}') from None

    def look(self, title, artist):
        """Genius's page for the song -> {'id', 'url', 'title', 'about', 'released', 'links', 'refs'} or None."""
        bare = re.sub(r'\s*[(\[].*?[)\]]', '', title).strip() or title  # "(From …)", "(Remastered)": not in Genius's titles
        song = pick(self._get('/search', q=f'{bare} {artist[:60]}').get('hits') or [], title, artist)
        if not song:
            return None
        full = self._get(f"/songs/{song['id']}", text_format='plain').get('song') or {}
        links = []
        for rel in full.get('song_relationships') or []:
            # What this song draws on (its samples, what it covers) is worth a word; the long tail that sampled it isn't.
            if rel.get('relationship_type') in ('samples', 'interpolates', 'cover_of', 'remix_of'):
                names = [s.get('full_title') for s in (rel.get('songs') or [])[:3] if s.get('full_title')]
                if names:
                    links.append(f"{rel['relationship_type'].replace('_', ' ')}: {'; '.join(names)}")
        refs = []
        for page in (1, 2):
            got = self._get('/referents', song_id=song['id'], text_format='plain', per_page=50, page=page).get('referents') or []
            for r in got:
                a = (r.get('annotations') or [{}])[0]
                body = ((a.get('body') or {}).get('plain') or '').strip()
                if body and r.get('fragment'):
                    refs.append({'fragment': r['fragment'], 'body': body, 'votes': a.get('votes_total') or 0,
                                 'verified': bool(a.get('verified'))})
            if len(got) < 50:
                break
        return {'id': song['id'], 'url': song.get('url'), 'title': song.get('full_title'),
                'about': ((full.get('description') or {}).get('plain') or '').strip(),
                'released': full.get('release_date_for_display'), 'links': links, 'refs': refs}

    def condense(self, title, artist, page):
        """The page -> {'about': [...], 'refs': [{'fragment', 'text'}]}: only what a friend would actually say."""
        # A fragment that is only a section's name ("[Guitar Solo]") has no line to sit on.
        placeable = [r for r in page['refs'] if len(_plain(re.sub(r'\[[^\]]*\]', '', r['fragment']))) >= 6]
        refs = sorted(placeable, key=lambda r: (r['verified'], r['votes']), reverse=True)[:16]
        about = page['about'] if page['about'] and page['about'] != '?' else ''
        if not refs and not about:
            return {'about': [], 'refs': []}
        listing = '\n'.join(
            f"[{i}] \"{' / '.join(x.strip() for x in r['fragment'].splitlines() if x.strip())[:160]}\""
            f"{' (written by the artist)' if r['verified'] else ''}: {' '.join(r['body'].split())[:520]}"
            for i, r in enumerate(refs, 1))
        system = 'You sit next to one listener while a song plays: a well-read friend who loves music, who has just read its Genius page.'
        user = f"""Song: {title} by {artist}{f" (released {page['released']})" if page.get('released') else ''}

About it, from Genius:
{' '.join(about.split())[:1800] or '(nothing)'}
{('Connections: ' + ' | '.join(page['links'])) if page['links'] else ''}

Annotations on its lines:
{listing or '(none)'}

Write:
- about: up to three things you'd say as it starts or in a pause between verses. The most surprising first.
- lines: for the annotations worth leaning over for, the one thing you'd whisper as that line is sung: what it
  refers to, the story behind it, a double meaning. At most eight, by their numbers.

Rules:
- Each must be stated in the text above in so many words. Never combine or infer, never add dates, numbers, chart
  places or quotes that aren't there.
- Say who says something only as the text does: never credit a claim to the band, the artist or anyone the text
  doesn't name for it. When the text hedges ("may", "some think", "reportedly"), keep the hedge or skip it.
- Leave out guesses about private lives (love affairs, sexuality, illness, feuds) unless the artist said it in the text.
- Skip annotations that only restate the line or guess at its feelings ("the protagonist seems to...").
- For a line not written in Latin letters, also give "roman": its first line in Latin letters, as a listener would type it.
- Short and plain: "about" at most 24 words, "lines" at most 22. No hype words, no exclamation marks.
- Don't open every one the same way, and don't address him by name."""
        out = self.llm.tool(system, user, TOOL, max_tokens=1500, timeout=90, retries=1, wait=10)
        abouts = [clean(a) for a in (out.get('about') or [])]
        lines = []
        for item in out.get('lines') or []:
            n, text = item.get('n'), clean(item.get('text'))
            if isinstance(n, int) and 1 <= n <= len(refs) and 8 <= len(text) <= 170:
                lines.append({'fragment': refs[n - 1]['fragment'], 'roman': clean(item.get('roman'))[:200] or None, 'text': text})
        return {'about': [a for a in abouts if 8 <= len(a) <= 200][:3], 'refs': lines[:8]}

    # -- the cache, and the look in the background ------------------------------------------------------------------
    @staticmethod
    def key(title, artist):
        return f'genius:{nartist(first_artist(artist))}|{ntrack(title)}'

    def _run(self):
        while True:
            with self.cv:
                while not self.waiting:
                    self.cv.wait()
                k = max(self.waiting, key=lambda x: self.asked.get(x, 0))
                title, artist = self.waiting.pop(k)
                if time.time() - self.asked.get(k, 0) > STALE:
                    continue  # skipped past: looked up the next time it plays
                self.working = k
            try:
                self._work(k, title, artist)
            finally:
                with self.cv:
                    self.working = None
                    self.cv.notify_all()

    def _work(self, k, title, artist):
        try:
            page = self.look(title, artist)
            if page is None:
                self.store.put(k, {'none': True, 'ts': time.time()})
                return
            got = self.condense(title, artist, page)
            self.store.put(k, {'url': page['url'], **got, 'ts': time.time()})
        except Exception as e:  # Genius or the model couldn't be asked: nothing is kept, and it's tried again later
            log.warning('genius: %s by %s: %s', title, artist, str(e)[:160])
            with self.cv:
                self.failed[k] = time.time() + RETRY

    def idle(self, timeout=10):
        """Wait until nothing is waiting or being looked up (tests)."""
        with self.cv:
            return self.cv.wait_for(lambda: not self.waiting and self.working is None, timeout)

    def trivia(self, title, artist, lines):
        """What there is to say about this song, placed on these lines; starts the look when there's nothing yet.
        -> {'status': 'ready' | 'pending' | 'none', 'about': [...], 'lines': [{'line', 'text'}], 'url'}"""
        k = self.key(title, artist)
        got = self.store.get(k)
        if got and not (got.get('none') and time.time() - got.get('ts', 0) > KEEP_NONE):
            if got.get('none'):
                return {'status': 'none', 'about': [], 'lines': []}
            return {'status': 'ready', 'about': got.get('about') or [], 'lines': place(got.get('refs') or [], lines), 'url': got.get('url')}
        with self.cv:
            if self.failed.get(k, 0) > time.time():
                return {'status': 'none', 'about': [], 'lines': []}
            self.asked[k] = time.time()
            if len(self.asked) > 500:  # forget songs asked about long ago
                self.asked = {x: t for x, t in self.asked.items() if time.time() - t < 3600}
            if k != self.working:
                self.waiting[k] = (title, artist)
                self.cv.notify_all()
            if self.thread is None:
                self.thread = threading.Thread(target=self._run, daemon=True, name='genius')
                self.thread.start()
        return {'status': 'pending', 'about': [], 'lines': []}
