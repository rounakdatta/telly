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
            'source': {'type': 'integer', 'description': 'Number of the source you wrote about; 0 for the source on today\'s subject'},
            'title': {'type': 'string', 'description': "'Song — Artist' (for source 0, the subject's name)"},
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


def _plain(s):
    import unicodedata
    s = ''.join(c for c in unicodedata.normalize('NFKD', s or '') if not unicodedata.combining(c)).lower()
    return ' '.join(re.sub(r'[^\w\s]', ' ', s).split())


def relevant(t, hit):
    """Is this article really about the song or its artist? Its title or opening must name the whole song title (four
    letters or more) or the artist's full name. Words alone are not enough: 'Teri Ay' once matched an article on
    Munni Begum because 'ay' is inside so many words."""
    title, opening = _plain(hit[0]), _plain(hit[1][:900])
    from .textnorm import ntrack
    song = _plain(ntrack(t['title']))
    artist = _plain(t.get('artist'))
    if len(song) >= 4 and (song in title or f' {song} ' in f' {opening} '):
        return True
    return len(artist) >= 4 and (artist in title or f' {artist} ' in f' {opening} ')


def sources_for(tracks, avoid_text, limit=4):
    cands = [t for t in sorted(tracks, key=lambda t: ORDER.get(t.get('pool'), 9)) if (t.get('artist') or '').lower() not in avoid_text][:8]
    found = []
    for t in cands:
        hit = None
        for q in (f"{t['title']} {t['artist']} song", f"{t['title']} song", f"{t['artist']} musician"):
            hit = wiki(q)
            time.sleep(0.8)
            if hit and relevant(t, hit):
                break
            hit = None
        if hit:
            found.append((t, hit))
        if len(found) >= limit:
            break
    return found


def write(llm, about, moment, tracks, avoid_subjects=(), lens=None, callbacks=()):
    """moment: when and what ('6 pm run on Sunday 16 November; 25°, light rain'). lens: what he wants to learn about
    today ('20th-century history', 'Indiranagar'), or None. callbacks: earlier stories [(day, title, first line)] the
    story may look back to, when one truly connects. Returns a story dict or None."""
    avoid_text = ' '.join(avoid_subjects).lower()
    found = sources_for(tracks, avoid_text)
    lens_hit = wiki_pick(lens, avoid_text) if lens else None
    if not found and not lens_hit:
        return None
    src = '\n\n'.join(f"[{i}] Song in the playlist: {t['title']} by {t['artist']}\nWikipedia, '{h[0]}':\n{h[1]}"
                      for i, (t, h) in enumerate(found, 1))
    if lens_hit:
        src = f"[0] Today's subject, as he asked for it: \"{lens}\"\nWikipedia, '{lens_hit[0]}':\n{lens_hit[1]}\n\n" + src
    listing = '\n'.join(f"- {t['title']} — {t['artist']}" for t in tracks)
    system = f"""You write the liner notes for one listener's playlist: a short story, the kind a well-read friend tells you because
it is genuinely interesting: history, culture, how something came to be, a surprising connection. About the listener: {about}"""
    lens_rules = f"""
- Today he asked to learn about: "{lens}". Prefer a song whose source genuinely touches that subject. If none does, write
  about source [0] instead and say plainly that it is today's subject; connect it to the music only through facts that
  are true (the same decade, city or tradition), never through an invented link.""" if lens_hit else ''
    back = ''
    if callbacks:
        back = '\n\nEarlier stories you told him (day, title, first line):\n' + '\n'.join(f'- {d}: {t}. {f}' for d, t, f in callbacks) + \
            """\nIf, and only if, one of these genuinely connects to today's story, add one short sentence that calls back to it,
naming when ("On Sunday's run you heard..."). Otherwise ignore them."""
    user = f"""The playlist is for: {moment}

Songs:
{listing}

Sources (Wikipedia):
{src}{back}

Write one story from one source. Pick the richest story that stretches him a little beyond what he knows while
connecting to something he loves. Rules:{lens_rules}
- Address him as "you"; never his name or the third person.
- 150 to 220 words, plain vivid English, two or three short paragraphs. No hype words, no exclamation marks, no lists.
- Every factual sentence must come from the source you chose. Add only very well established context; never invent
  quotes, dates, numbers or anecdotes. If the source is thin, write less.
- You may touch the moment (the hour, the weather, the activity) in one clause if it fits naturally; never force it.
- `deeper` is exactly one work you are sure exists, formatted 'Title — Creator (book)' (or documentary, album, podcast
  episode). No hedging, no alternatives, no commentary. If nothing specific is certain, name the best-known book or
  documentary about the subject."""
    out = llm.tool(system, user, TOOL, max_tokens=1200, timeout=120, retries=2, wait=20)
    i = out.get('source')
    body = (out.get('body') or '').strip()
    if not isinstance(i, int) or not body:
        return None
    deeper = (out.get('deeper') or '').strip()
    if i == 0 and lens_hit:
        # A story about the day's subject is titled by the subject, whatever the model put there (once it named a song).
        return {'song_id': None, 'lens_story': True, 'title': lens_hit[0], 'body': body,
                'deeper': deeper, 'deeper_checked': verify(deeper), 'source': lens_hit[0]}
    if not 1 <= i <= len(found):
        return None
    t = found[i - 1][0]
    return {'song_id': t['id'], 'title': (out.get('title') or f"{t['title']} — {t['artist']}").strip(), 'body': body,
            'deeper': deeper, 'deeper_checked': verify(deeper), 'source': found[i - 1][1][0]}


def wiki_pick(query, avoid_text=''):
    """An article on today's subject, not one already told recently: of the top five hits, the first fresh one."""
    try:
        s = _get('https://en.wikipedia.org/w/api.php?' + urllib.parse.urlencode(
            {'action': 'query', 'list': 'search', 'srsearch': query, 'format': 'json', 'srlimit': 5}))
        titles = [h['title'] for h in s['query']['search']]
    except Exception:
        return None
    for title in titles:
        if title.lower() in avoid_text:
            continue
        time.sleep(0.8)
        hit = wiki(title)
        if hit:
            return hit
    return None


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
