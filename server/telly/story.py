"""One story per playlist: about one of its songs, grounded in Wikipedia, written for this listener and this moment.

Every factual sentence must come from the source extract; the closing "go deeper" pointer is checked against Open
Library and Wikipedia and marked when it can't be confirmed. Earlier stories' subjects are avoided for a month.
"""
import json
import re
import time
import urllib.parse
import urllib.request

UA = {'User-Agent': 'telly/2.0 (personal playlist app; https://github.com/rounakdatta/telly)'}
ORDER = {'new_frontier': 0, 'new_known': 1, 'forgotten': 2, 'asked': 3, 'familiar': 4, 'warm': 5, 'comfort': 6}

TOOL = {
    'name': 'story',
    'description': 'The story for this playlist.',
    'input_schema': {
        'type': 'object',
        'properties': {
            'source': {'type': 'integer', 'description': 'Number of the source you wrote about'},
            'title': {'type': 'string', 'description': 'Song — Artist'},
            'body': {'type': 'string', 'description': 'The story: 150-220 words, two or three short paragraphs separated by blank lines'},
            'deeper': {'type': 'string', 'description': "Exactly one real work, formatted 'Title — Creator (book|documentary|album|podcast episode)'"},
        },
        'required': ['source', 'title', 'body', 'deeper'],
    },
}


def _get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
        return json.loads(r.read())


def wiki(query):
    """Top Wikipedia hit for a query -> (title, extract) or None."""
    try:
        s = _get('https://en.wikipedia.org/w/api.php?' + urllib.parse.urlencode(
            {'action': 'query', 'list': 'search', 'srsearch': query, 'format': 'json', 'srlimit': 1}))
        hits = s['query']['search']
        if not hits:
            return None
        title = hits[0]['title']
        time.sleep(0.8)
        d = _get('https://en.wikipedia.org/w/api.php?' + urllib.parse.urlencode(
            {'action': 'query', 'prop': 'extracts', 'explaintext': 1, 'exsectionformat': 'plain', 'titles': title, 'format': 'json'}))
        page = next(iter(d['query']['pages'].values()))
        ex = re.sub(r'\n{2,}', '\n', (page.get('extract') or '').strip())
        return (title, ex[:3500]) if len(ex) > 200 else None
    except Exception:
        return None


def sources_for(tracks, avoid_text, limit=4):
    cands = [t for t in sorted(tracks, key=lambda t: ORDER.get(t.get('pool'), 9)) if (t.get('artist') or '').lower() not in avoid_text][:8]
    found = []
    for t in cands:
        hit = None
        for q in (f"{t['title']} {t['artist']} song", f"{t['title']} song", f"{t['artist']} musician"):
            hit = wiki(q)
            time.sleep(0.8)
            if hit and (any(w in hit[1].lower() for w in t['title'].lower().split()[:2]) or t['artist'].split(' ')[0].lower() in hit[1].lower()):
                break
            hit = None
        if hit:
            found.append((t, hit))
        if len(found) >= limit:
            break
    return found


def write(llm, about, moment, tracks, avoid_subjects=()):
    """moment: a line describing when/what ('5 pm run on Sunday 16 November; 25°, light rain'). Returns a story dict or None."""
    avoid_text = ' '.join(avoid_subjects).lower()
    found = sources_for(tracks, avoid_text)
    if not found:
        return None
    src = '\n\n'.join(f"[{i}] Song in the playlist: {t['title']} by {t['artist']}\nWikipedia, '{h[0]}':\n{h[1]}"
                      for i, (t, h) in enumerate(found, 1))
    listing = '\n'.join(f"- {t['title']} — {t['artist']}" for t in tracks)
    system = f"""You write the liner notes for one listener's playlist: a short story about ONE of its songs or artists, the kind a
well-read friend tells you because it is genuinely interesting: history, culture, how the song came to be, a surprising
connection. About the listener: {about}"""
    user = f"""The playlist is for: {moment}

Songs:
{listing}

Sources (Wikipedia):
{src}

Write one story about one source. Pick the one with the richest story that stretches him a little beyond what he
knows while connecting to something he loves. Rules:
- Address him as "you"; never his name or the third person.
- 150 to 220 words, plain vivid English, two or three short paragraphs. No hype words, no exclamation marks, no lists.
- Every factual sentence must come from the source you chose. Add only very well established context; never invent
  quotes, dates, numbers or anecdotes. If the source is thin, write less.
- You may touch the moment (the hour, the weather, the activity) in one clause if it fits naturally; never force it.
- `deeper` is exactly one work you are sure exists, formatted 'Title — Creator (book)' (or documentary, album, podcast
  episode). No hedging, no alternatives, no commentary. If nothing specific is certain, name the best-known book or
  documentary about the artist or the genre."""
    out = llm.tool(system, user, TOOL, max_tokens=1200, timeout=120, retries=2, wait=20)
    i = out.get('source')
    if not isinstance(i, int) or not 1 <= i <= len(found) or not (out.get('body') or '').strip():
        return None
    t = found[i - 1][0]
    deeper = (out.get('deeper') or '').strip()
    return {'song_id': t['id'], 'title': (out.get('title') or f"{t['title']} — {t['artist']}").strip(), 'body': out['body'].strip(),
            'deeper': deeper, 'deeper_checked': verify(deeper), 'source': found[i - 1][1][0]}


def verify(line):
    """Does the 'go deeper' recommendation exist? Open Library for books, Wikipedia for everything else."""
    if not line:
        return False
    if len(line) > 160 or re.search(r'(?i)\b(actually|alternatively|or try|aside)\b', line):
        return False  # a hedge, not a pointer
    titles = [re.split(r'\s+[—–-]\s+|\s+by\s+|\s*\(', line)[0].strip(' "\'“”*_')]
    titles += re.findall(r'[\'"“‘]([^\'"”’]{4,80})[\'"”’]', line)
    for title in titles[:3]:
        try:
            ol = _get('https://openlibrary.org/search.json?' + urllib.parse.urlencode({'title': title, 'limit': 1}))
            if ol.get('numFound'):
                return True
        except Exception:
            pass
        w = wiki(title)
        if w and (title.lower()[:12] in w[0].lower() or w[0].lower()[:12] in title.lower()):
            return True
    return False
