"""One line per song: what an excited, well-read friend leans over and tells you as it starts.

Trivia and cultural references, never his own listening ("you've played this 45 times" was the weakest kind of note):
facts come only from the song's Genius page (its story, and what it samples or covers) and its Wikipedia opening (or
its film's, or its artist's). When neither has anything worth saying, the friend says nothing. Notes are cached per
song for three weeks, so changing a playlist only writes notes for the songs that are new to it.
"""
import json
import re
import time
import urllib.parse
import urllib.request

from .genius import Genius, clean
from .ground import _items, settle
from .genius import Unreachable as GeniusUnreachable
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
        # Only an article named for the song is the song's ("Home" in a singer's opening lines is not); a soundtrack's
        # or an album's is about all its songs ("lyrics by A and B" may be true of the album and not of this one).
        if kind == 'song' and _names(title, hit[0]):
            return 'song', hit
        if kind == 'song' and re.search(r'\(soundtrack\)|\(album\)|\bsoundtrack\b', hit[0], re.I) and fallback is None:
            fallback = ('album', hit)
            continue
        if kind == 'film' and _names(film.group(1), hit[0]):
            return 'film', hit
        if fallback is None and kind != 'film' and (_names(t['artist'], hit[0]) or _names(t['artist'], hit[1][:300])):
            fallback = ('artist', hit)  # the film, when there is one, is asked first
    return fallback


RULES = 3  # bump when the rules change: notes written under older ones are dropped once, and recent playlists rewritten


def migrate(store):
    """Notes written under older rules (they counted his plays) are dropped, once, and the last two days' playlists
    are marked for notes again (the worker picks them up as unfinished)."""
    if store.get('notes_rules') == RULES:
        return
    with store.db() as c:
        c.execute('DELETE FROM notes')
        c.execute("UPDATE sessions SET notes_state = 'pending' WHERE deleted = 0 AND created > ?", (time.time() - 2 * 86400,))
    store.put('notes_rules', RULES)


def unwritten(tracks, store):
    """The songs still without a note written in the last three weeks (a blank one counts: nothing worth saying)."""
    have = store.notes_for([t['id'] for t in tracks], max_age=21 * 86400)
    return [t for t in tracks if t['id'] not in have]


def write(llm, about, moment, tracks, store, now=None, batch=20, batches=None, genius=None):
    """Notes for these tracks -> {track_id: note}. Every song without a fresh note gets one, in batches (at most
    `batches` of them this call: the worker writes one at a time, so a playlist waiting to sync never waits long); a
    song the friend had nothing to say about is remembered as blank, so it isn't asked about again."""
    now = now or time.time()
    genius = genius or Genius(store, None)
    todo = unwritten(tracks, store)
    for n, i in enumerate(range(0, len(todo), batch)):
        if batches is not None and n >= batches:
            break
        _write_batch(llm, about, moment, todo[i:i + batch], store, now, genius)
    return {k: v for k, v in store.notes_for([t['id'] for t in tracks]).items() if v}


def _snippets(store, todo):
    """Each song's Wikipedia opening, one lookup at a time (Wikipedia asks for that). Should it stop answering, the rest
    go without, and nothing is remembered about them -> (snippets, down)."""
    snips = []
    for t in todo:
        try:
            snips.append(snippet(store, t))
        except Unreachable:
            return snips + [None] * (len(todo) - len(snips)), True
    return snips, False


def _pages(genius, todo):
    """Each song's Genius page (read once, kept, and shared with the whispers in its lyrics) -> (pages, down)."""
    pages = []
    for t in todo:
        try:
            pages.append(genius.page(t['title'], t['artist']))
        except GeniusUnreachable:
            return pages + [None] * (len(todo) - len(pages)), True
    return pages, False


def _genius_source(page):
    if not page:
        return None
    about = ' '.join((page.get('about') or '').split())
    about = '' if about in ('', '?') else about[:700]
    links = '; '.join(page.get('links') or [])
    return ' '.join(x for x in (about, f'(connections: {links})' if links else '') if x) or None


def _write_batch(llm, about, moment, todo, store, now, genius):
    snips, down = _snippets(store, todo)
    pages, gdown = _pages(genius, todo)
    down = down or gdown  # a song is only remembered as having nothing to say when every source was asked
    lines, sourced, sources = [], 0, []
    for i, (t, sn, pg) in enumerate(zip(todo, snips, pages), 1):
        g = _genius_source(pg)
        # Said outright when an article isn't about the song itself, for the writer and the checker alike.
        w = (f'the song\'s own article "{sn[1][0]}": ' if sn[0] == 'song' else f'an article about the {sn[0]} "{sn[1][0]}", not about this song: ') \
            + sn[1][1][:600] if sn else None
        sourced += bool(g or w)
        sources.append(f'About the song "{t["title"]}" by {t["artist"]}. Genius: {g or "nothing"} Wikipedia: {w or "nothing"}')
        lines.append(f"[{i}] {t['title']} — {t['artist']}\n    Genius: {g or 'nothing'}\n    Wikipedia: {w or 'nothing'}")
    if not sourced:
        if not down:
            store.put_notes({t['id']: '' for t in todo})  # nothing known about any of them: nothing to say
        return {}
    system = f"""You sit next to one listener while his playlist plays: a well-read friend who loves music and can't help
leaning over as each song starts with something worth knowing about it. About him: {about}"""
    user = f"""The playlist is for: {moment}

{chr(10).join(lines)}

For each song, the one thing you'd tell him as it starts: a piece of trivia, a cultural reference, the story behind
it, where it came from or what it led to. One or two short sentences, at most 26 words.

Rules:
- A fact must be stated in that song's sources in so many words. Never combine or infer: "nominated five times
  between 1977 and 1981" is not "this song nearly won". Never invent dates, numbers, chart positions, quotes or
  stories, and never join two facts into a cause ("thanks to", "because", "after") unless the source does.
- A Wikipedia article labelled "artist", "film" or "album" is about the artist, the film or the whole album: say its
  facts about them, never as facts about this song ("the album's lyrics are by A and B" isn't "this song's are").
- Never mention his own listening: how often he plays it, when he first heard it, whether it's new to him.
- When the sources have nothing worth saying about a song, leave its note empty. No filler ("a classic", "listen for
  the guitar") unless a source says it.
- Talk to him as "you" when it helps. Warm and a little playful; no hype words; at most one exclamation mark across
  all the notes. Vary how they begin."""
    out = llm.tool(system, user, TOOL, max_tokens=1800, timeout=90, retries=1, wait=10)
    got, usable = {}, 0
    for item in _items(out.get('notes'), 'note'):
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
    # Read again, strictly, against each song's own sources: a note that says more than they do is rewritten once with
    # the objection in hand, and if it still does, isn't said.
    written = [(i, tid) for i, t in enumerate(todo) if (tid := t['id']) in got and got[tid]]
    for (i, tid), note in zip(written, settle(llm, [(sources[i], got[tid]) for i, tid in written])):
        got[tid] = note
    if not down:
        for t in todo:
            got.setdefault(t['id'], '')  # considered and passed over: don't ask again
    store.put_notes(got)
    return got
