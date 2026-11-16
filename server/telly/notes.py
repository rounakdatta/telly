"""One line per song: what an excited, well-read friend leans over and tells you as it starts.

Facts come only from the song's Wikipedia opening (or its film's, or its artist's) and from the listener's own history
with it; when neither has anything worth saying the friend points at something to listen for, or says nothing. Notes
are cached per song for three weeks, so changing a playlist only writes notes for the songs that are new to it.
"""
import json
import re
import time
import urllib.parse
import urllib.request

from .genius import clean
from .story import UA, _get, _plain, is_disambiguation, relevant  # noqa: F401  (UA kept for callers)
from .textnorm import ntrack

TOOL = {
    'name': 'notes',
    'description': 'One short note per song, by its number.',
    'input_schema': {
        'type': 'object',
        'properties': {'notes': {'type': 'array', 'items': {
            'type': 'object',
            'properties': {'n': {'type': 'integer'}, 'note': {'type': 'string', 'description': 'At most 26 words; empty to skip'}},
            'required': ['n', 'note']}}},
        'required': ['notes'],
    },
}
FILM = re.compile(r'[\(\[]\s*From\s+["“]?([^"”\)\]]+)["”]?\s*[\)\]]', re.I)


class Unreachable(Exception):
    """Wikipedia couldn't be asked (a timeout, a 429): unlike "no article", this must not be remembered."""


def intro(query):
    """Top Wikipedia hit's opening, in one request -> (title, extract), or None when there is none worth having."""
    try:
        with urllib.request.urlopen(urllib.request.Request('https://en.wikipedia.org/w/api.php?' + urllib.parse.urlencode(
                {'action': 'query', 'generator': 'search', 'gsrsearch': query, 'gsrlimit': 1, 'prop': 'extracts',
                 'exintro': 1, 'explaintext': 1, 'redirects': 1, 'format': 'json'}), headers=UA), timeout=8) as r:
            d = json.loads(r.read())
    except Exception as e:
        raise Unreachable(str(e)) from None
    if 'error' in d:  # throttled or refused, in a 200: not an answer either
        raise Unreachable(str(d['error'])[:200])
    pages = list((d.get('query') or {}).get('pages', {}).values())
    if not pages:
        return None
    ex = re.sub(r'\n{2,}', '\n', (pages[0].get('extract') or '').strip())
    hit = (pages[0]['title'], ex[:900])
    return hit if len(ex) > 120 and not is_disambiguation(hit) else None


def _names(name, text):
    """Is `name` in `text` as whole words? ('Dil' must not match 'Dilbert'.)"""
    n = _plain(name)
    return bool(n) and f' {n} ' in f' {_plain(text)} '


def snippet(store, t):
    """The best opening for a song: its own article, else its film's (Indian film songs rarely have one of their own),
    else its artist's, each labelled for what it is about, so a fact about the singer is never told as one about the
    song. Looked up once and cached; a lookup that failed is not cached, and raises Unreachable."""
    film = FILM.search(t['title'] or '')
    plan = [(f"{t['title']} {t['artist']} song", 'song')]
    if film:
        plan.append((f'{film.group(1)} film', 'film'))
    plan.append((f"{t['artist']} musician", 'artist'))
    title = ntrack(t['title'])  # 'Kesariya (From "Brahmastra")', 'Wish You Were Here - 2011 Remaster': the song's own name
    fallback = None
    for q, kind in plan:
        hit = store.wiki_get(q)
        if hit is None:
            hit = intro(q)  # Unreachable propagates: the caller stops asking for this pass
            store.wiki_put(q, hit)
            time.sleep(0.2)
        if not hit:
            continue
        # Only an article named for the song is the song's ("Home" in a singer's opening lines is not).
        if kind == 'song' and _names(title, hit[0]):
            return 'song', hit
        if kind == 'film' and _names(film.group(1), hit[0]):
            return 'film', hit
        if fallback is None and kind != 'film' and (_names(t['artist'], hit[0]) or _names(t['artist'], hit[1][:300])):
            fallback = ('artist', hit)  # the film, when there is one, is asked first
    return fallback


def history(t, now):
    """What his own listening says about a song, in words the friend can use."""
    month = lambda ts: time.strftime('%B %Y', time.gmtime(ts)) if ts else None
    if t.get('pool') == 'asked':
        return 'you asked for it by name'
    if str(t.get('pool', '')).startswith('new'):
        return 'new to you' + (' (from an artist you play a lot)' if t.get('pool') == 'new_known' else ' (fans of artists you love play it)')
    bits = []
    if t.get('plays'):
        bits.append(f"you've played it {t['plays']} times")
    if t.get('first_ts'):
        bits.append(f"first in {month(t['first_ts'])}")
    if t.get('last_ts') and now - t['last_ts'] > 90 * 86400:
        bits.append(f"last in {month(t['last_ts'])}")
    if t.get('liked'):
        bits.append('liked')
    return ', '.join(bits) or 'one you know'


def unwritten(tracks, store):
    """The songs still without a note written in the last three weeks (a blank one counts: nothing worth saying)."""
    have = store.notes_for([t['id'] for t in tracks], max_age=21 * 86400)
    return [t for t in tracks if t['id'] not in have]


def write(llm, about, moment, tracks, store, now=None, batch=20, batches=None):
    """Notes for these tracks -> {track_id: note}. Every song without a fresh note gets one, in batches (at most
    `batches` of them this call: the worker writes one at a time, so a playlist waiting to sync never waits long); a
    song the friend had nothing to say about is remembered as blank, so it isn't asked about again."""
    now = now or time.time()
    todo = unwritten(tracks, store)
    for n, i in enumerate(range(0, len(todo), batch)):
        if batches is not None and n >= batches:
            break
        _write_batch(llm, about, moment, todo[i:i + batch], store, now)
    return {k: v for k, v in store.notes_for([t['id'] for t in tracks]).items() if v}


def _snippets(store, todo):
    """Each song's source, one lookup at a time (Wikipedia asks for that). Should it stop answering, the rest go
    without, and nothing is remembered about them -> (snippets, down)."""
    snips = []
    for t in todo:
        try:
            snips.append(snippet(store, t))
        except Unreachable:
            return snips + [None] * (len(todo) - len(snips)), True
    return snips, False


def _write_batch(llm, about, moment, todo, store, now):
    snips, down = _snippets(store, todo)
    lines = []
    for i, (t, sn) in enumerate(zip(todo, snips), 1):
        src = f'{sn[0]} article "{sn[1][0]}": {sn[1][1][:600]}' if sn else 'no article found'
        lines.append(f"[{i}] {t['title']} — {t['artist']}\n    his history: {history(t, now)}\n    source: {src}")
    system = f"""You sit next to one listener while his playlist plays, a well-read friend who loves music and can't help
leaning over as each song starts. About him: {about}"""
    user = f"""The playlist is for: {moment}

{chr(10).join(lines)}

For each song, write the one thing you'd say as it starts: a fun fact, a bit of history, something to listen for, or a
callback to his own history with it. One or two short sentences, at most 26 words.

Rules:
- A fact must be stated in that song's source in so many words, or in his history. Never combine or infer: "nominated
  five times between 1977 and 1981" is not "this song nearly won". Never invent dates, numbers, chart positions,
  quotes or stories.
- A source labelled "artist" or "film" is about the artist or the film: say its facts about them, not about this song.
- When neither has anything worth saying, point to something to listen for in terms that can't be wrong, or leave it
  empty.
- Talk to him as "you". Warm and a little playful; no hype words; at most one exclamation mark across all the notes.
- Vary how the notes begin. Not every note is trivia: mix in personal ones ("You've played this 45 times; first in
  March 2023") and things to listen for."""
    out = llm.tool(system, user, TOOL, max_tokens=1800, timeout=90, retries=1, wait=10)
    got, usable = {}, 0
    for item in out.get('notes') or []:
        n, note = item.get('n'), clean(item.get('note'))
        if isinstance(n, int) and 1 <= n <= len(todo):
            tid = todo[n - 1]['id']
            if 8 <= len(note) <= 240:
                got[tid] = note
                usable += 1
            else:
                got.setdefault(tid, '')  # an essay or a scrap never replaces a good note for the same song
    if not usable:  # an empty or cut-off reply: remember nothing, so these are asked about again
        raise RuntimeError('the model wrote no usable notes')
    if not down:
        for t in todo:
            got.setdefault(t['id'], '')  # considered and passed over: don't ask again
    store.put_notes(got)
    return got
