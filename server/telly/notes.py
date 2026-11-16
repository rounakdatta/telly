"""One line per song: what an excited, well-read friend leans over and tells you as it starts.

Facts come only from the song's Wikipedia opening (or its film's, or its artist's) and from the listener's own history
with it; when neither has anything worth saying the friend points at something to listen for, or says nothing. Notes
are cached per song for three weeks, so changing a playlist only writes notes for the songs that are new to it.
"""
import json
import re
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

from .story import UA, _get, _plain, is_disambiguation, relevant  # noqa: F401  (UA kept for callers)

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


def intro(query):
    """Top Wikipedia hit's opening, in one request -> (title, extract) or None."""
    try:
        d = _get('https://en.wikipedia.org/w/api.php?' + urllib.parse.urlencode(
            {'action': 'query', 'generator': 'search', 'gsrsearch': query, 'gsrlimit': 1, 'prop': 'extracts',
             'exintro': 1, 'explaintext': 1, 'redirects': 1, 'format': 'json'}))
        pages = list((d.get('query') or {}).get('pages', {}).values())
        if not pages:
            return None
        ex = re.sub(r'\n{2,}', '\n', (pages[0].get('extract') or '').strip())
        hit = (pages[0]['title'], ex[:900])
        return hit if len(ex) > 120 and not is_disambiguation(hit) else None
    except Exception:
        return None


def snippet(store, t):
    """The best opening for a song: its own article, else its film's (Indian film songs rarely have one of their own),
    else its artist's. Looked up once and cached."""
    film = FILM.search(t['title'] or '')
    plan = [(f"{t['title']} {t['artist']} song", 'song')]
    if film:
        plan.append((f'{film.group(1)} film', 'film'))
    plan.append((f"{t['artist']} musician", 'artist'))
    for q, kind in plan:
        hit = store.wiki_get(q)
        if hit is None:
            hit = intro(q)
            store.wiki_put(q, hit)
            time.sleep(0.2)
        if not hit:
            continue
        ok = relevant(t, hit) if kind != 'film' else _plain(film.group(1)) in _plain(hit[0])
        if ok:
            return kind, hit
    return None


def history(t, now):
    """What his own listening says about a song, in words the friend can use."""
    month = lambda ts: time.strftime('%B %Y', time.gmtime(ts)) if ts else None
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


def write(llm, about, moment, tracks, store, now=None):
    """Notes for these tracks -> {track_id: note}. Only songs without a fresh note are written."""
    now = now or time.time()
    have = store.notes_for([t['id'] for t in tracks])
    todo = [t for t in tracks if t['id'] not in have][:24]
    if not todo:
        return have
    with ThreadPoolExecutor(max_workers=4) as pool:
        snips = list(pool.map(lambda t: snippet(store, t), todo))
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
- Facts only from that song's source or his history. Never invent dates, numbers, chart positions, quotes or stories.
  When neither has anything worth saying, point to something to listen for in terms that can't be wrong, or leave it empty.
- Talk to him as "you". Warm and a little playful; no hype words; at most one exclamation mark across all the notes.
- Vary how the notes begin. Not every note is trivia: mix in personal ones ("You've played this 45 times; first in
  March 2023") and things to listen for."""
    out = llm.tool(system, user, TOOL, max_tokens=1800, timeout=90, retries=1, wait=10)
    got = {}
    for item in out.get('notes') or []:
        n, note = item.get('n'), ' '.join(str(item.get('note') or '').split()).strip(' "')
        if isinstance(n, int) and 1 <= n <= len(todo) and 8 <= len(note) <= 240:
            got[todo[n - 1]['id']] = note
    store.put_notes(got)
    return {**have, **got}
