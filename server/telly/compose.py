"""A mixtape the way a friend with deep, wide taste makes one: researched first, then written in one voice.

The curator before this (curate.py) chose songs from statistics on his history and had their story written apart from
them, a sourced fact per song. He found the result "very AI generated": facts thrown around with no coherence, the
day's subject taken literally, Hindi because he has played more Hindi. A mix made by hand the way a friend would make
it, researched and written in one sitting, he called a work of art (17 Nov 2026). This does what that took:

1. Find the one idea where his moment meets what he wants to learn.
2. Dig: his history (what he knows, what he has worn out), lyrics, Wikipedia, Genius, Reddit threads where people trade
   deep cuts with their reasons, the web.
3. Choose songs that serve the moment as they sound and carry the idea, check each one exists, and write the songs and
   their story together: every fact from something read while making it.
4. An editor reads it cold: fit, truth, voice.

It takes minutes (Opus at high effort, a few dozen lookups), so it runs in the background. Mixes he has loved are the
standard it is shown; they hold his listening history, so they come from the private store, never this repository.
"""
import concurrent.futures
import json
import logging
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from difflib import SequenceMatcher

from . import engine as E
from .genius import BROWSER, Genius, Unreachable, first_artist
from .llm import LLMError
from .textnorm import nartist, ntrack

log = logging.getLogger('telly.compose')

EFFORT = 'max'
MAX_TURNS = 80
KEEP = 30 * 86400
UA = 'telly/3 (personal playlist app; github.com/rounakdatta/telly)'
BETAS = ('web-fetch-2025-09-10',)
# The dynamic-filtering variants (_20260209) run inside code execution, which the gateway's subscription refuses
# ("too_many_requests"); the basic variants work.
SERVER_TOOLS = [{'type': 'web_search_20250305', 'name': 'web_search', 'max_uses': 12},
                {'type': 'web_fetch_20250910', 'name': 'web_fetch', 'max_uses': 12}]


class ComposeError(Exception):
    pass


# -- what the composer can do --------------------------------------------------------------------------------------------
TOOLS = [
    {'name': 'history', 'description':
        "What he has played in ten years of listening (Spotify, YouTube Music, YouTube). Give an artist, a song title, or "
        "both. Returns his songs by that artist, most played first, or the song asked about: plays, when first and last "
        "played, whether he liked it, how often he skipped it. Use it to know what is new to him and what he has worn out, "
        "and for callbacks to his own listening. Nothing else about his history is known to you.",
     'input_schema': {'type': 'object', 'properties': {'artist': {'type': 'string'}, 'title': {'type': 'string'}}}},
    {'name': 'find_recording', 'description':
        "Look a recording up in Apple Music's catalogue by its title and main artist. Returns the matching recordings "
        "(versions, albums, years, lengths), each with an apple_id. Every song in the mix must be one of these: choose the "
        "version on purpose (studio, a cappella, live, remaster). If nothing matches, try another spelling.",
     'input_schema': {'type': 'object', 'properties': {'title': {'type': 'string'}, 'artist': {'type': 'string'}},
                      'required': ['title', 'artist']}},
    {'name': 'lyrics', 'description':
        "A song's lyrics as plain text, from LRCLIB. Quote only what is here, verbatim and briefly. Many songs, especially "
        "outside English, have none here: then find a lyrics or translation page with web_search and web_fetch.",
     'input_schema': {'type': 'object', 'properties': {'title': {'type': 'string'}, 'artist': {'type': 'string'}},
                      'required': ['title', 'artist']}},
    {'name': 'wikipedia', 'description':
        "A Wikipedia article as plain text: give its title, or words to search for. A long article comes back as its "
        "opening plus the paragraphs that mention any of look_for.",
     'input_schema': {'type': 'object', 'properties': {'page': {'type': 'string'},
                                                       'look_for': {'type': 'array', 'items': {'type': 'string'}}},
                      'required': ['page']}},
    {'name': 'genius', 'description':
        "Genius's page for a song: what it is about as Genius tells it, when it came out, what it samples or covers, and "
        "the best-voted annotations on its lines.",
     'input_schema': {'type': 'object', 'properties': {'title': {'type': 'string'}, 'artist': {'type': 'string'}},
                      'required': ['title', 'artist']}},
    {'name': 'reddit_search', 'description':
        "Search one subreddit's post titles in the Reddit archive (Arctic Shift), where people ask each other for music and "
        "trade deep cuts with their reasons. Returns posts (id, title, comments, year). Slow (about ten seconds a search) "
        "and the busiest subreddits (r/Music) can't be searched, so search with purpose: r/ifyoulikeblank, r/LetsTalkMusic, "
        "r/listentothis, r/musicsuggestions, r/vintageobscura, r/IndianMusic, r/jazz, r/indieheads, r/ambientmusic, "
        "r/classicalmusic, r/bollywood, r/kolkata and the like. Use a word or two, as they would appear in a title.",
     'input_schema': {'type': 'object', 'properties': {'subreddit': {'type': 'string'}, 'words': {'type': 'string'}},
                      'required': ['subreddit', 'words']}},
    {'name': 'reddit_thread', 'description':
        "Read a Reddit thread from the archive: the post and its best comments (what people recommended and why, in their "
        "own words).",
     'input_schema': {'type': 'object', 'properties': {'id': {'type': 'string'}}, 'required': ['id']}},
    {'name': 'deliver_mix', 'strict': True, 'description':
        "Hand over the finished mix: call it once, when every song is chosen, checked and written.",
     'input_schema': {
         'type': 'object', 'additionalProperties': False, 'required': ['title', 'intro', 'songs', 'outro', 'facts'],
         'properties': {
             'title': {'type': 'string', 'description': "A few words: a line from one of its songs, a pun, a reference "
                                                        "he'd catch; never a label"},
             'intro': {'type': 'string', 'description': 'What you tell him before he presses play'},
             'songs': {'type': 'array', 'items': {
                 'type': 'object', 'additionalProperties': False, 'required': ['apple_id', 'title', 'artist', 'note'],
                 'properties': {
                     'apple_id': {'type': 'integer', 'description': 'From find_recording'},
                     'title': {'type': 'string'},
                     'artist': {'type': 'string'},
                     'note': {'type': 'string', 'description': 'What you tell him about this song, in his moment'}}}},
             'outro': {'type': 'string', 'description': 'The landing: a line or two for the end'},
             'facts': {'type': 'array', 'description': 'Every fact the mix states, with where you read it', 'items': {
                 'type': 'object', 'additionalProperties': False, 'required': ['claim', 'source'],
                 'properties': {'claim': {'type': 'string'},
                                'source': {'type': 'string', 'description': 'The tool and page, or the URL'}}}}}}},
]

CRAFT = """You make mixtapes for one friend. You know music the way the best record-shop clerks and the deepest corners
of Reddit do: Hindi film from every decade, Bengali, Urdu, Punjabi, Tamil and Malayalam, ghazal and qawwali, Western
indie, folk, rock, soul, jazz, R&B, hip-hop, electronic, ambient, soundtracks, and music from Africa, Latin America,
Japan and everywhere else; the deep cut and the beloved hit, and when each is right.

He tells you a moment (what he is about to do, where, for how long) and, on some days, something he wants to learn
about. You give him a mix for that moment and the story that goes with it, the way a friend would: a message he reads
before pressing play.

What makes a mix he loves:
- One idea at its heart, found where his moment meets what he wants to learn. Walking to a cafe trying to notice small
  things, on a day he wants to learn about women who changed music, the idea might be "women who changed music by
  noticing what everyone else walked past". Every song earns its place twice: it suits the moment, and it carries the
  idea. When the two truly don't meet, the moment wins and the subject lives in the story.
- Songs that suit the moment as they actually sound: a run must move, a bedtime must settle, a walk must walk. Range
  across languages, decades and voices is part of the pleasure, unless his words narrow it. His history tells you what
  he knows and what he has worn out; it never decides the language or the genre. That he has played more Hindi than
  anything else is no reason to choose Hindi.
- An order with meaning, mapped onto the moment: what opens, what carries the busy stretch, what settles, what lands as
  he arrives.
- Stories, not bios. For each song the one thing worth telling: a scene, a quote, how it was made, what it does to you,
  a coincidence found by digging (an anniversary that falls today, a line that answers his situation). Told like a
  friend: plain words, specific details, second person, warm, a little wry. A pun or a tiny reference when it lands.
  Never a Wikipedia sentence.
- Callbacks to his own listening when they are true and add something ("you've played her other album to death",
  "you know the remix; this is the original").
- Nudges that make the moment richer: what to listen for, when to press play, what to notice.

What breaks it (he can smell these at once):
- Repeating his request or his subject back to him ("the question you asked", "a mix about X for your Y").
- Encyclopedia phrasing: "the legendary", "the iconic", "widely regarded", "born in". Record-book trivia for its own sake.
- Connective glue: "and the third chapter", "answers", "closes the circle", "brings us to".
- Adjectives that judge instead of details that show: haunting, timeless, masterpiece, soulful, ethereal. Delve,
  tapestry, testament, soundscape, sonic; journey unless it is one.
- Em-dash rhythm, "not X but Y", triplets of adjectives, a closing line that could end any mix ("the songs will do the
  rest").
- A forced link. If a song's connection needs explaining, it is the wrong song.

Truth:
- Every fact you tell him must come from something you read while making this mix (a tool result or a web page), not
  from memory. If you remember something you can't confirm, look it up or leave it out. Quotes are verbatim and short.
- Every song is checked with find_recording; use the apple_id it gives, and choose the version on purpose.
- What you say about his listening comes from the history tool.
- deliver_mix's facts list every fact the mix states, with its source.

Shape:
- The songs add up to the moment's length, give or take a couple of minutes. One song per artist.
- A title of a few words: a line from one of its songs, a pun, a reference he'd catch; never a label like "Morning Walk
  Mix".
- A short intro, a paragraph for each song (the best stories get room; a song that needs no explaining gets a line or
  two), and a short landing. A fifteen-minute mix runs to about 450 words; a long one isn't much longer, its weaker
  stories cut.

How to work: think about the moment and the idea first. Then dig: his history, lyrics, Wikipedia, Genius, Reddit
threads where people recommend with reasons, the web. Consider more songs than you need and keep the ones that earn
their place. Check every song and every fact. Then write, and deliver with deliver_mix."""

EDITOR = """You edit the mixtapes a friend makes for him, before he sees them. He has a record-shop clerk's ears and a
Reddit crate-digger's patience, and he can smell anything written by a machine. The mix below was made by a careful
writer with research tools; you read it cold and make it as good as it can be. The writer's brief follows, then the mix,
then everything the writer read.

Check, in this order:
1. Each song against the moment, as the song actually sounds (energy, mood, length, a language his words ruled out). If
   one doesn't fit, replace it with a better one, checked with find_recording.
2. The idea: does every song earn its place both ways? Replace any song whose link needs explaining.
3. Truth: every fact must be backed by what the writer read (below) or by something you read now. Look up anything
   doubtful; fix or cut what you can't confirm. Quotes must be verbatim. Callbacks to his listening must match the
   history tool.
4. Voice: rewrite anything that sounds like a model or an encyclopedia (the brief lists the tells). Keep what is
   specific, warm and funny; don't sand the voice flat, and don't add length.
5. Shape: the songs add up to the moment's length; one song per artist; the order maps onto the moment.

Say briefly what you changed and why, then deliver the whole finished mix with deliver_mix."""


# -- the services behind the tools -----------------------------------------------------------------------------------------
class _Paced:
    """One call at a time to a service, at least `gap` seconds apart (Apple asks for ~20 a minute; Arctic Shift and
    Wikipedia refuse bursts)."""

    def __init__(self, gap):
        self.gap, self.last, self.lock = gap, 0.0, threading.Lock()

    def __enter__(self):
        self.lock.acquire()
        wait = self.last + self.gap - time.time()
        if wait > 0:
            time.sleep(wait)

    def __exit__(self, *exc):
        self.last = time.time()
        self.lock.release()


APPLE, WIKI, LRC, ARCTIC = _Paced(3.0), _Paced(1.0), _Paced(0.5), _Paced(10.0)


def _get_json(url, pace, headers=None, timeout=20):
    with pace:
        req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json', **(headers or {})})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())


def _mmss(ms):
    s = round((ms or 0) / 1000)
    return f'{s // 60}:{s % 60:02d}'


def _ym(ts):
    return time.strftime('%Y-%m', time.gmtime(ts)) if ts else '?'


class Research:
    """The tools, run against his catalogue, the store's caches and the outside services."""

    def __init__(self, cat, store, genius=None):
        self.cat, self.store = cat, store
        self.genius = genius or Genius(store, None)
        self.found = {}  # apple_id -> the recording, as find_recording returned it

    def call(self, name, args):
        fn = getattr(self, f't_{name}', None)
        if fn is None:
            return f'No tool called {name}.'
        try:
            return fn(**args)
        except TypeError as e:
            return f'Wrong arguments for {name}: {e}'
        except Exception as e:  # a tool that fails says so; the writer works around it
            log.warning('compose tool %s failed: %s', name, e)
            return f'{name} failed: {e}'

    def _cached(self, key, fresh):
        got = self.store.get(key) if self.store is not None else None
        if got is not None and time.time() - got.get('ts', 0) < KEEP:
            return got['v']
        v = fresh()
        if self.store is not None:
            self.store.put(key, {'ts': time.time(), 'v': v})
        return v

    # his listening
    def t_history(self, artist='', title=''):
        artist, title = (artist or '').strip(), (title or '').strip()
        if not artist and not title:
            return 'Give an artist, a title, or both.'
        rows = []
        if title:
            t = self.cat.find(title, artist or None)
            mine = [t] if t and t.get('kind') == 'known' else []
            if not mine:
                tk = ntrack(title)
                mine = [x for x in self.cat.tracks if tk and tk in ntrack(x['title'])
                        and (not artist or nartist(artist).split(' ')[0] in nartist(x['artist']))][:8]
            if not mine:
                rows.append(f'He has never played "{title}"' + (f' by {artist}.' if artist else '.'))
            rows += [self._row(x) for x in sorted(mine, key=lambda x: -(x.get('plays') or 0))]
        if artist:
            keys = self.cat.artist_keys([artist])
            mine = sorted((x for x in self.cat.tracks if x.get('artist_key') in keys), key=lambda x: -(x.get('plays') or 0))
            if not mine:
                rows.append(f'He has never played anything by {artist}.')
            else:
                total = sum(x.get('plays') or 0 for x in mine)
                rows.append(f'{artist}: {len(mine)} songs played, {total} plays in all, first {_ym(min(x.get("first_ts") or 9e9 for x in mine))}, '
                            f'last {_ym(max(x.get("last_ts") or 0 for x in mine))}. Most played:')
                rows += [self._row(x) for x in mine[:15]]
        return '\n'.join(rows)

    @staticmethod
    def _row(x):
        skip = ''
        if x.get('sp_streams'):
            skip = f", skipped {x.get('sp_skips') or 0} of {x['sp_streams']} Spotify plays"
        return (f"- {x['title']} — {x['artist']}: {x.get('plays') or 0} plays, first {_ym(x.get('first_ts'))}, "
                f"last {_ym(x.get('last_ts'))}{', liked' if x.get('liked') else ''}{skip}")

    # does it exist (and how long is it)
    def t_find_recording(self, title, artist):
        key = f'applefind1:{nartist(first_artist(artist))}|{ntrack(title)}'

        def fresh():
            out = []
            for term in (f'{title} {artist}', f"{first_artist(artist)} {re.sub(r'[^\w\s]', ' ', title).replace('&', 'and')}"):
                for country in ('IN', 'US'):
                    q = urllib.parse.urlencode({'term': term, 'entity': 'song', 'limit': 25, 'country': country})
                    try:
                        res = _get_json(f'https://itunes.apple.com/search?{q}', APPLE).get('results') or []
                    except urllib.error.HTTPError as e:
                        raise ComposeError(f'Apple Music answered {e.code}; try again shortly') from None
                    out = [self._recording(r) for r in res if self._matches(r, title, artist)]
                    if out:
                        return out[:10]
            return []

        recs = self._cached(key, fresh)
        if not recs:
            return f'Nothing on Apple Music matches "{title}" by {artist}. Try another spelling, or another song.'
        for r in recs:
            self.found[r['apple_id']] = r
        return '\n'.join(f"- apple_id {r['apple_id']}: {r['title']} — {r['artist']} · {r['album']} ({r['year']}) · {_mmss(r['ms'])}"
                         for r in recs)

    @staticmethod
    def _recording(r):
        return {'apple_id': r.get('trackId'), 'title': r.get('trackName'), 'artist': r.get('artistName'),
                'album': r.get('collectionName'), 'year': (r.get('releaseDate') or '')[:4], 'ms': r.get('trackTimeMillis') or 0,
                'art': (r.get('artworkUrl100') or '').replace('100x100bb', '600x600bb')}

    @staticmethod
    def _matches(r, title, artist):
        names = nartist(r.get('artistName') or '')
        want = nartist(first_artist(artist))
        a_ok = bool(want) and (want in names or names in want or SequenceMatcher(None, want, names).ratio() >= 0.6
                               or want.split(' ')[0] in names.split(' '))
        t_want, t_got = ntrack(title), ntrack(r.get('trackName') or '')
        t_ok = bool(t_want) and (t_want in t_got or t_got in t_want or SequenceMatcher(None, t_want, t_got).ratio() >= 0.6)
        return a_ok and t_ok

    # what it says
    def t_lyrics(self, title, artist):
        key = f'lrclib1:{nartist(first_artist(artist))}|{ntrack(title)}'

        def fresh():
            q = urllib.parse.urlencode({'track_name': title, 'artist_name': first_artist(artist)})
            hits = _get_json(f'https://lrclib.net/api/search?{q}', LRC)
            best, best_s = None, 0.0
            for h in hits if isinstance(hits, list) else []:
                text = h.get('plainLyrics') or re.sub(r'^\[[^\]]*\]\s?', '', h.get('syncedLyrics') or '', flags=re.M)
                if not text.strip():
                    continue
                s = SequenceMatcher(None, ntrack(title), ntrack(h.get('trackName') or '')).ratio() + \
                    SequenceMatcher(None, nartist(first_artist(artist)), nartist(h.get('artistName') or '')).ratio()
                if s > best_s:
                    best, best_s = {'track': h.get('trackName'), 'artist': h.get('artistName'), 'text': text}, s
            return best if best and best_s >= 1.3 else None

        got = self._cached(key, fresh)
        if not got:
            return f'No lyrics for "{title}" by {artist} on LRCLIB.'
        return f"{got['track']} — {got['artist']}\n\n{got['text'][:6000]}"

    def t_wikipedia(self, page, look_for=None):
        key = f'wiki2:{page.strip().lower()}'

        def fresh():
            api = 'https://en.wikipedia.org/w/api.php?'

            def extract(title):
                d = _get_json(api + urllib.parse.urlencode({'action': 'query', 'prop': 'extracts', 'explaintext': 1, 'redirects': 1,
                                                            'titles': title, 'format': 'json'}), WIKI)
                p = next(iter((d.get('query') or {}).get('pages', {}).values()), {})
                return (p.get('title'), p.get('extract')) if p.get('extract') else (None, None)

            t, text = extract(page)
            if not text:
                d = _get_json(api + urllib.parse.urlencode({'action': 'query', 'list': 'search', 'srsearch': page, 'srlimit': 1,
                                                            'format': 'json'}), WIKI)
                hits = (d.get('query') or {}).get('search') or []
                if hits:
                    t, text = extract(hits[0]['title'])
            return {'title': t, 'text': text} if text else None

        got = self._cached(key, fresh)
        if not got:
            return f'No Wikipedia article for "{page}".'
        text, words = got['text'], [w.lower() for w in (look_for or []) if w.strip()]
        head = f"{got['title']} (https://en.wikipedia.org/wiki/{urllib.parse.quote(got['title'].replace(' ', '_'))})\n\n"
        if len(text) <= 9000:
            return head + text
        paras = [p for p in text.split('\n') if p.strip()]
        lead, rest, picked = text[:2500], [], 0
        for p in paras:
            if words and any(w in p.lower() for w in words) and p not in lead:
                rest.append(p)
                picked += len(p)
                if picked > 6500:
                    break
        tail = '\n\n[…]\n\n' + '\n\n'.join(rest) if rest else '\n\n[… the rest of the article is longer; ask with look_for to see more]'
        return head + lead + tail

    def t_genius(self, title, artist):
        try:
            page = self.genius.page(title, artist)
        except Unreachable as e:
            return f'Genius is unreachable just now ({e}).'
        if not page:
            return f'Genius has no page for "{title}" by {artist}.'
        out = [f"{page.get('title')} ({page.get('url')})", f"Released: {page.get('released') or '?'}"]
        if page.get('links'):
            out.append('Links: ' + '; '.join(page['links']))
        if page.get('about'):
            out.append('About: ' + page['about'][:3000])
        for r in (page.get('refs') or [])[:12]:
            out.append(f"On \"{r['fragment'][:200]}\": {r['body'][:500]}")
        return '\n\n'.join(out)

    # what people say
    def _arctic(self, path):
        url = 'https://arctic-shift.photon-reddit.com/api/' + path
        for attempt in range(2):
            try:
                return _get_json(url, ARCTIC, timeout=40)
            except urllib.error.HTTPError as e:
                body = e.read()[:200].decode('utf-8', 'replace')
                if e.code in (422, 429) and attempt == 0:
                    time.sleep(25)
                    continue
                raise ComposeError(f'the Reddit archive answered {e.code}: {body}') from None

    def t_reddit_search(self, subreddit, words):
        sub = subreddit.strip().removeprefix('r/').strip('/')
        key = f'arctic1:s:{sub.lower()}|{words.strip().lower()}'

        def fresh():
            q = urllib.parse.urlencode({'subreddit': sub, 'title': words, 'limit': 50, 'fields': 'id,title,num_comments,score,created_utc'})
            d = self._arctic(f'posts/search?{q}')
            if d.get('error'):
                raise ComposeError(d['error'])
            return sorted(d.get('data') or [], key=lambda p: -(p.get('num_comments') or 0))[:15]

        posts = self._cached(key, fresh)
        if not posts:
            return f'No posts in r/{sub} with "{words}" in the title.'
        return '\n'.join(f"- {p['id']}: {p['title'][:160]} ({p.get('num_comments') or 0} comments, {_ym(p.get('created_utc'))[:4]})"
                         for p in posts)

    def t_reddit_thread(self, id):
        pid = id.strip().removeprefix('t3_')
        key = f'arctic1:t:{pid}'

        def fresh():
            post = (self._arctic(f'posts/ids?ids={pid}').get('data') or [{}])[0]
            tree = self._arctic(f'comments/tree?link_id=t3_{pid}&limit=600').get('data') or []
            flat = []

            def walk(nodes, depth):
                for n in nodes or []:
                    d = n.get('data') if isinstance(n.get('data'), dict) else n
                    if d.get('body') and d.get('body') not in ('[deleted]', '[removed]'):
                        flat.append({'depth': depth, 'score': d.get('score') or 0, 'body': d['body'][:700]})
                    rep = d.get('replies')
                    if isinstance(rep, dict):
                        rep = (rep.get('data') or {}).get('children')
                    if depth < 2:
                        walk(rep if isinstance(rep, list) else [], depth + 1)

            walk(tree, 0)
            top = sorted(flat, key=lambda c: -c['score'])[:30]
            return {'title': post.get('title'), 'sub': post.get('subreddit'), 'text': (post.get('selftext') or '')[:1500], 'comments': top}

        t = self._cached(key, fresh)
        if not t.get('title'):
            return f'No thread {pid} in the archive.'
        lines = [f"r/{t['sub']}: {t['title']}", t['text']] + [f"[{c['score']}] {c['body']}" for c in t['comments']]
        return '\n\n'.join(x for x in lines if x)


def _say(name, args):
    """What a tool call looks like in the progress line he sees while the mix is being made."""
    a = args or {}
    return {
        'history': lambda: f"Looking through your history for {a.get('title') or a.get('artist')}",
        'find_recording': lambda: f"Finding {a.get('title')} by {a.get('artist')}",
        'lyrics': lambda: f"Reading the lyrics of {a.get('title')}",
        'wikipedia': lambda: f"Reading about {a.get('page')}",
        'genius': lambda: f"Reading Genius on {a.get('title')}",
        'reddit_search': lambda: f"Searching r/{str(a.get('subreddit', '')).removeprefix('r/')} for “{a.get('words')}”",
        'reddit_thread': lambda: 'Reading a Reddit thread',
        'web_search': lambda: f"Searching the web for “{a.get('query')}”",
        'web_fetch': lambda: f"Reading {urllib.parse.urlparse(a.get('url') or '').netloc}",
        'deliver_mix': lambda: 'Writing it up',
    }.get(name, lambda: name)()


# -- making it -------------------------------------------------------------------------------------------------------------
def brief(about, moment, said, lens, minutes, today, top_ever=(), top_year=(), recent=(), notebook=(), current=None,
          instruction=None):
    """What the composer is told about this moment and him. `current`: the mix as it is (title, songs with a pinned
    flag) when he asks for it to change; `instruction`: what he asked."""
    parts = [f'For: {moment}', f'His words: "{said}"' if said else '',
             f'What he wants to learn about today: "{lens}"' if lens else 'Nothing in particular he wants to learn about today.',
             f'Length: about {minutes} minutes.', f'Today is {today}.', '', f'About him: {about}' if about else '']
    if top_ever:
        parts.append(f"His most played artists, ever: {', '.join(top_ever)}.")
    if top_year:
        parts.append(f"In the last year: {', '.join(top_year)}.")
    if recent:
        parts.append('Mixes he has had lately (no repeats; find fresh ground):\n' + '\n'.join(f'- {r}' for r in recent))
    if notebook:
        parts.append('What he has said about past mixes:\n' + '\n'.join(f'- {n}' for n in notebook))
    if current:
        songs = '\n'.join(f"- {t['artist']} – {t['title']}{' (he pinned this: it stays, in place)' if t.get('pinned') else ''}"
                          for t in current.get('songs') or [])
        parts += ['', f"The mix he has now, \u201c{current.get('title') or ''}\u201d:\n{songs}",
                  f'He now asks: "{instruction}"' if instruction else 'He asked for it to be made again.',
                  'Keep what still serves the idea and his ask; change what doesn\'t. Write the story afresh for the mix '
                  'as it ends up.']
    return '\n'.join(p for p in parts if p is not None).strip()


class Composer:
    def __init__(self, llm, research, about='', examples='', effort=EFFORT):
        self.llm, self.research, self.about, self.examples, self.effort = llm, research, about, examples, effort

    def system(self):
        s = CRAFT
        if self.examples:
            s += ("\n\nMixes he has loved, to show the standard. Never reuse their songs, their lines or their shape: each "
                  f"mix finds its own.\n\n{self.examples}")
        return s

    def make(self, brief, minutes, progress=lambda s: None, deadline=None, edit=True):
        """-> {'mix', 'draft', 'log', 'usage', 'editor_notes', 'seconds'}; raises ComposeError when it can't."""
        t0 = time.time()
        deadline = deadline or t0 + 30 * 60
        usage = {}
        draft, research_log, _ = self._loop(self.system(), f'{brief}\n\nMake the mix.', minutes, progress, deadline, usage)
        mix, notes = draft, ''
        if edit:
            progress('Reading it through once more')
            editor_brief = (f"{EDITOR}\n\n## The writer's brief\n\n{self.system()}\n\n## His moment\n\n{brief}")
            user = (f"## The mix\n\n{json.dumps(draft, ensure_ascii=False, indent=1)}\n\n## What the writer read\n\n"
                    f"{self._digest(research_log)}\n\nEdit it.")
            try:
                mix, _, notes = self._loop(editor_brief, user, minutes, progress, deadline, usage)
            except (ComposeError, LLMError) as e:  # the draft stands when the editor can't finish
                log.warning('compose: editor failed, keeping the draft: %s', e)
                notes = f'(editor failed: {e})'
        return {'mix': mix, 'draft': draft, 'log': research_log, 'usage': usage, 'editor_notes': notes,
                'seconds': round(time.time() - t0)}

    def _loop(self, system, user, minutes, progress, deadline, usage):
        messages = [{'role': 'user', 'content': user}]
        research_log, said, nudged = [], [], 0
        tools = TOOLS + SERVER_TOOLS
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=6)
        try:
            for _ in range(MAX_TURNS):
                if time.time() > deadline:
                    raise ComposeError('ran out of time')
                msg = self.llm.stream({'max_tokens': 64000, 'system': [{'type': 'text', 'text': system, 'cache_control': {'type': 'ephemeral'}}],
                                       'messages': messages, 'tools': tools, 'thinking': {'type': 'adaptive'},
                                       'output_config': {'effort': self.effort}}, betas=BETAS)
                for k, v in (msg.get('usage') or {}).items():
                    if isinstance(v, (int, float)):
                        usage[k] = usage.get(k, 0) + v
                content = msg.get('content') or []
                messages.append({'role': 'assistant', 'content': content})
                research_log += self._server_reads(content, progress)
                said += [b['text'] for b in content if b.get('type') == 'text' and b.get('text', '').strip()]
                stop = msg.get('stop_reason')
                if stop == 'refusal':
                    raise ComposeError(f"declined: {(msg.get('stop_details') or {}).get('category')}")
                if stop == 'pause_turn':
                    continue
                calls = [b for b in content if b.get('type') == 'tool_use']
                if not calls:
                    if nudged >= 2:
                        raise ComposeError('finished without delivering a mix')
                    nudged += 1
                    messages.append({'role': 'user', 'content': 'Deliver the mix with deliver_mix.'})
                    continue
                for b in calls:
                    progress(_say(b['name'], b.get('input')))
                futures = {b['id']: pool.submit(self.research.call, b['name'], b.get('input') or {})
                           for b in calls if b['name'] != 'deliver_mix'}
                results, done = [], None
                for b in calls:
                    if b['name'] == 'deliver_mix':
                        problems = self.check(b.get('input') or {}, minutes)
                        if problems:
                            results.append({'type': 'tool_result', 'tool_use_id': b['id'], 'is_error': True,
                                            'content': 'Not yet:\n' + '\n'.join(f'- {p}' for p in problems)})
                        else:
                            done = b['input']
                            results.append({'type': 'tool_result', 'tool_use_id': b['id'], 'content': 'Delivered.'})
                        continue
                    out = futures[b['id']].result()
                    research_log.append({'tool': b['name'], 'input': b.get('input'), 'output': out})
                    results.append({'type': 'tool_result', 'tool_use_id': b['id'], 'content': out or '(nothing)'})
                if done:
                    return done, research_log, '\n\n'.join(said)
                messages.append({'role': 'user', 'content': results})
            raise ComposeError('too many turns')
        finally:
            pool.shutdown(wait=False)

    @staticmethod
    def _server_reads(content, progress):
        """What the web tools read this turn, kept for the editor; their calls shown as progress."""
        out = []
        for b in content:
            if b.get('type') == 'server_tool_use':
                progress(_say(b.get('name'), b.get('input')))
            elif b.get('type') == 'web_fetch_tool_result':
                c = b.get('content') or {}
                doc = ((c.get('content') or {}).get('source') or {}).get('data') or ''
                if doc:
                    out.append({'tool': 'web_fetch', 'input': {'url': c.get('url')}, 'output': doc[:12000]})
            elif b.get('type') == 'web_search_tool_result' and isinstance(b.get('content'), list):
                out.append({'tool': 'web_search', 'input': {},
                            'output': '\n'.join(f"- {r.get('title')}: {r.get('url')}" for r in b['content'] if isinstance(r, dict))})
            elif b.get('type') == 'text' and b.get('citations'):
                for c in b['citations']:
                    if isinstance(c, dict) and c.get('cited_text'):
                        out.append({'tool': 'web_search', 'input': {'url': c.get('url')}, 'output': c['cited_text']})
        return out

    def check(self, mix, minutes):
        """What stops a mix being delivered: songs nobody looked up, two by one artist, a length far from the moment's."""
        problems, total, artists = [], 0, set()
        songs = mix.get('songs') or []
        if len(songs) < 2:
            problems.append('A mix needs at least two songs.')
        for s in songs:
            rec = self.research.found.get(s.get('apple_id'))
            if not rec:
                problems.append(f"{s.get('title')}: apple_id {s.get('apple_id')} didn't come from find_recording; look it up.")
                continue
            total += rec['ms']
            a = nartist(first_artist(rec['artist']))
            if a in artists:
                problems.append(f"Two songs by {rec['artist']}: one per artist.")
            artists.add(a)
            if not (s.get('note') or '').strip():
                problems.append(f"{s.get('title')} has no note.")
        lo, hi = minutes * 0.8, minutes + max(2.5, minutes * 0.1)
        if not problems and not lo * 60_000 <= total <= hi * 60_000:
            problems.append(f'The songs add up to {_mmss(total)} for a {minutes}-minute moment: aim for {lo:.0f} to {hi:.0f} minutes.')
        return problems

    @staticmethod
    def _digest(research_log, cap=5000):
        return '\n\n'.join(f"### {r['tool']} {json.dumps(r.get('input') or {}, ensure_ascii=False)}\n{(r.get('output') or '')[:cap]}"
                           for r in research_log)

    def render(self, result, heading=''):
        """The mix as he would read it (markdown), with each song's length and year from Apple."""
        mix = result['mix']
        total = sum((self.research.found.get(s['apple_id']) or {}).get('ms', 0) for s in mix['songs'])
        out = [f"## {mix['title']}", f"*{heading + ' · ' if heading else ''}{len(mix['songs'])} songs · {_mmss(total)}*", '', mix['intro'], '']
        for i, s in enumerate(mix['songs'], 1):
            r = self.research.found.get(s['apple_id']) or {}
            q = urllib.parse.quote_plus(f"{s['artist']} {s['title']}")
            out += [f"**{i}. {s['artist']} — {s['title']}** ({r.get('year') or '?'} · {_mmss(r.get('ms'))}) · "
                    f"[YouTube Music](https://music.youtube.com/search?q={q})", s['note'], '']
        out += [mix['outro']]
        return '\n'.join(out)


# -- in the app: a job the worker runs ---------------------------------------------------------------------------------------
def _key(sid):
    return f'compose:{sid}'


def queue(store, sid, said, instruction=None, keep=None):
    """Ask for a mix (a new one, or this one reworked). The worker makes it; the app shows how it's going. Asking
    again replaces the job: the newest ask wins."""
    store.put(_key(sid), {'state': 'queued', 'job': uuid.uuid4().hex[:12], 'said': said, 'instruction': instruction,
                          'keep': keep or [], 'queued': time.time(), 'progress': []})


def job(store, sid):
    return store.get(_key(sid))


def public(j):
    """What the app sees of a job: where it is and the last few things it did."""
    if not j:
        return None
    return {'state': j['state'], 'progress': (j.get('progress') or [])[-6:], 'since': j.get('started') or j.get('queued'),
            'error': j.get('error')}


def recent(store, today, exclude=None, days=14, limit=12):
    """The mixes he has had lately, as the composer is told of them: their names and songs."""
    out = []
    for d in [today] + store.days_before(today, days):
        for s in store.sessions_on(d):
            if s['id'] == exclude or s['deleted'] or not s['version']:
                continue
            v = store.version(s['id'])
            name = (v['brief'].get('mix') or {}).get('title')
            if v['tracks'] and name:
                songs = '; '.join(f"{t['artist']} – {t['title']}" for t in v['tracks'][:20])
                out.append(f"{d}, {s['title']}, “{name}”: {songs}")
                if len(out) >= limit:
                    return out
    return out


def tracks_for(mix, found, cat, keep=()):
    """The composer's songs -> telly tracks: his own copy when he has played that very recording, otherwise the
    recording as Apple has it (found on YouTube Music and Spotify at sync time). Songs he pinned stay pinned."""
    pinned = {(nartist(first_artist(t['artist'])), ntrack(t['title'])) for t in keep or () if t.get('pinned')}
    now, out = time.time(), []
    for s in mix['songs']:
        rec = found[s['apple_id']]
        mine = cat.find(rec['title'], rec['artist']) if cat is not None else None
        if mine and mine.get('kind') == 'known' and abs((mine.get('duration_ms') or 0) - rec['ms']) < 20_000:
            t = dict(mine, pool=E.pool_of(mine, now) or 'familiar')
        else:
            t = dict(E.external(rec['title'], rec['artist']), pool='curated', kind='curated', duration_ms=rec['ms'])
        t.update(year=rec.get('year') or None, thumb=t.get('thumb') or rec.get('art') or None,
                 pinned=(nartist(first_artist(rec['artist'])), ntrack(rec['title'])) in pinned)
        out.append(t)
    return out


class Worker(threading.Thread):
    """Makes the mixes asked for, one at a time (each takes minutes), in the worker process."""

    def __init__(self, store, canvas, poll=2.0):
        super().__init__(daemon=True, name='composer')
        self.store, self.canvas, self.poll = store, canvas, poll

    def requeue(self):
        """A deploy mid-mix (every deploy restarts the worker): what was being made starts again."""
        for k in self.store.keys('compose:'):
            j = self.store.get(k)
            if j and j.get('state') == 'working':
                self.store.put(k, dict(j, state='queued', progress=[]))

    def run(self):
        self.requeue()
        while True:
            try:
                sid = self.next()
                if sid:
                    self.make(sid)
                else:
                    time.sleep(self.poll)
            except Exception:
                log.exception('composer')
                time.sleep(5)

    def next(self):
        waiting = []
        for k in self.store.keys('compose:'):
            j = self.store.get(k)
            if j and j.get('state') == 'queued':
                waiting.append((j.get('queued') or 0, k.split(':', 1)[1]))
        return min(waiting)[1] if waiting else None

    def make(self, sid):
        key = _key(sid)
        j = self.store.get(key)
        if not j or j.get('state') != 'queued':
            return
        jid = j['job']
        self.store.put(key, dict(j, state='working', started=time.time(), progress=[]))

        def current():
            now = self.store.get(key)
            return now if now and now.get('job') == jid else None

        def progress(line):
            now = current()
            if now:
                self.store.put(key, dict(now, progress=(now.get('progress') or [])[-11:] + [line]))

        s = self.store.session(sid)
        if not s or s['deleted']:
            self.store.put(key, None)
            return
        try:
            res, research = self.canvas.compose(s, j, progress)
            if not current():
                return  # asked again while this was made: the newer ask wins
            s = self.store.session(sid)
            if not s or s['deleted']:
                self.store.put(key, None)
                return
            self.canvas.save_composed(sid, j, res, research)
            if current():
                self.store.put(key, None)
        except Exception as e:
            log.warning('compose %s: %s', sid, str(e)[:300])
            now = current()
            if now:
                self.store.put(key, dict(now, state='failed', error=_plain(e)))


def _plain(e):
    """A failure as he'd want it said."""
    msg = str(e)
    if 'HTTP 429' in msg or 'rate' in msg.lower():
        return 'The language model is busy right now. Try again in a few minutes.'
    if 'ran out of time' in msg:
        return 'It took too long to finish. Try again, or ask for something simpler.'
    if isinstance(e, (LLMError, ConnectionError, TimeoutError)):
        return f'The language model could not be reached ({msg[:120]}).'
    return f'Something went wrong while making it ({msg[:160]}).'
