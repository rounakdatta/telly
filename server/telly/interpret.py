"""What the listener types -> edits to the day's canvas. Claude does the reading; a small rule parser stands in when the
gateway is down, so the app never stops answering."""
import re

from .catalog import ORIGINS, WORLDS
from .context import clock
from .engine import ACTIVITIES, TAG

TOOL = {
    'name': 'update_canvas',
    'description': "Apply the listener's message to today's canvas of playlists.",
    'input_schema': {
        'type': 'object',
        'properties': {
            'actions': {'type': 'array', 'items': {
                'type': 'object',
                'properties': {
                    'target': {'type': 'string', 'description': "'new' for a new moment of the day, or the id of a playlist on the canvas"},
                    'delete': {'type': 'boolean', 'description': 'True when he cancels this moment'},
                    'title': {'type': 'string', 'description': "Short name, time first when known: '5 pm run', 'post-dinner walk', 'train to Mysore'"},
                    'activity': {'type': 'string', 'enum': list(ACTIVITIES)},
                    'start': {'type': 'string', 'description': 'Local start time today, HH:MM 24-hour'},
                    'minutes': {'type': 'integer', 'minimum': 5, 'maximum': 600},
                    'familiar': {'type': 'number', 'minimum': 0.05, 'maximum': 0.98, 'description': 'Share of songs he already knows'},
                    'energy': {'type': 'array', 'items': {'type': 'number'}, 'minItems': 2, 'maxItems': 2, 'description': '[start, end] on 0-1'},
                    'tempo': {'type': 'array', 'items': {'type': 'number'}, 'minItems': 2, 'maxItems': 2, 'description': 'BPM band; only when he asks'},
                    'worlds': {'type': 'object', 'additionalProperties': {'type': 'number'},
                               'description': 'Multipliers on his usual mix for this moment, by world key: 0 removes, 2 doubles'},
                    'origins': {'type': 'array', 'items': {'type': 'string', 'enum': ORIGINS},
                                'description': 'Strict filter: only songs from these origins. [] clears it'},
                    'include_artists': {'type': 'array', 'items': {'type': 'string'}},
                    'exclude_artists': {'type': 'array', 'items': {'type': 'string'}},
                    'instrumental': {'type': 'boolean'},
                    'want': {'type': 'array', 'description': 'Guaranteed counts: "some Bengali" -> [{origin: Bengali, count: 3}]',
                             'items': {'type': 'object', 'properties': {
                                 'world': {'type': 'string', 'enum': list(WORLDS)}, 'origin': {'type': 'string', 'enum': ORIGINS},
                                 'artist': {'type': 'string'}, 'count': {'type': 'integer', 'minimum': 1, 'maximum': 10}},
                                 'required': ['count']}},
                    'ops': {'type': 'array', 'items': {
                        'type': 'object',
                        'properties': {
                            'op': {'type': 'string', 'enum': ['remove', 'swap', 'pin', 'unpin', 'move', 'add']},
                            'n': {'type': 'integer', 'description': 'Song number as shown in the list (1 = first)'},
                            'to': {'type': 'integer', 'description': 'For move/add: the position number it should end up at (1 = first)'},
                            'title': {'type': 'string', 'description': 'The song title (always give it, for every op)'},
                            'artist': {'type': 'string', 'description': 'Its main artist'},
                        },
                        'required': ['op']}},
                    'reshuffle': {'type': 'boolean', 'description': 'A fresh pick with the same brief'},
                    'reply': {'type': 'string', 'description': 'One short plain sentence, second person, saying what you did'},
                },
                'required': ['target', 'reply']}},
            'lens': {'type': 'string', 'description': "Today's subject to learn about, when he names one ('20th-century history', "
                                                      "'something romantic', 'Indiranagar'); 'none' clears it"},
            'say': {'type': 'string', 'description': 'Only when no edit is wanted: a one-line answer'},
        },
        'required': ['actions'],
    },
}


def system_prompt(about):
    acts = '\n'.join(f"- {k}: {v['blurb']} (default familiar {v['familiar']}, energy {v['energy'][0]}->{v['energy'][1]})"
                     for k, v in ACTIVITIES.items())
    worlds = '\n'.join(f'- {k}: {v}' for k, v in WORLDS.items())
    return f"""You are the planner inside telly, a playlist app for one listener. Each day starts as a blank canvas. He types what his
day holds ("a run at 5 pm", "walk after dinner", "train to Mysore, 3 hours") and how a playlist should change ("more
familiar", "swap the third song", "no rock today"). Turn every message into precise edits by calling update_canvas.

About him: {about}

Activities:
{acts}

His worlds (keys for `worlds`):
{worlds}

Rules:
- target: 'new' for a new moment of the day; a playlist's id to change it. When he is looking at a playlist, short commands
  ("more energy", "swap 3") mean that playlist. One message can hold several moments: one action each.
- title: short and lowercase except names, time first when he gave one: "5 pm run", "post-dinner walk", "train to Mysore".
- start: HH:MM, 24-hour, today. Infer when not given ("after dinner" -> 21:30, "now" -> the current time, a morning
  activity -> 07:00).
- minutes: as said, else run 40, walk 30, workout 50, drive 60, commute 40, focus 90, cooking 40, journey from the trip
  length; open-ended -> 90.
- familiar: "more familiar" = current + 0.15, "more new" or "surprise me" = current - 0.15, "only songs I know" = 0.95,
  "all new" = 0.1. Leave it out when he says nothing about it.
- energy: "calmer" = both ends - 0.12; "more energy" = both + 0.12; "build up" = end above start. Leave it out otherwise.
- worlds: for styles and languages ("more Bengali" -> roots 3; "no rock" -> rock 0; "mostly Hindi film" -> hindi 4).
  Give the full map you want, including tilts already set on the playlist that should stay.
- origins: only for strict filters ("only Bengali songs" -> ["Bengali"]); [] clears.
- want: when he asks for some of a kind ("some Bengali", "a couple of rock songs", "two Arijit songs"): a guaranteed
  count, e.g. [{{"origin": "Bengali", "count": 3}}] or [{{"artist": "Arijit Singh", "count": 2}}]. Keep earlier wants that
  should stay; [] clears them.
- ops use the song numbers shown. "swap"/"not this" -> swap; "remove"/"drop" -> remove; "keep"/"love this" -> pin;
  "play X first" -> move to 1; "add <song>" -> add with its proper title and main artist.
- reshuffle: when he wants a different pick of the same kind.
- delete: when he cancels a moment.
- reply: one plain sentence, second person, saying what you did ("Made it mostly Bengali and dropped the rock.").
  No exclamation marks, no emoji, no hype.
- lens (top level, not per playlist): when he says what he wants to learn about today ("teach me about the
  Mughals", "something romantic today", "2000s pop culture"), set it in a few words; 'none' when he wants no subject.
  Every playlist's story leans on it. Setting only the lens needs no actions.
- Leave out every field you are not changing. If he asks a question rather than for a change, answer it in `say`
  with no actions."""


def render_context(place, day, canvas, focus, lens=None):
    """canvas: [(session_row, version or None)]."""
    now = place.now()
    lines = [f"Now: {now.strftime('%A %-d %B %Y, %H:%M')} at home in {place.name}."]
    wx = place.weather(now.replace(minute=0))
    sun = place.sun(day)
    if wx:
        lines.append(f'Weather now: {wx}.' + (f" Sunset {sun['sunset']}." if sun else ''))
    for h in range(now.hour + 2, 24, 3):
        w = place.weather(now.replace(hour=h, minute=0))
        if w:
            lines.append(f'  {h:02d}:00 {w}')
    if not canvas:
        lines.append('\nToday\'s canvas is empty.')
    else:
        lines.append("\nToday's canvas:")
        for s, v in canvas:
            if not v:
                continue
            b = v['brief']
            lines.append(f"- id {s['id']} · \"{s['title']}\" · {b['activity']} · {b.get('start', '?')} · {b['minutes']} min · "
                         f"familiar {b['familiar']} · energy {b['energy'][0]}->{b['energy'][1]}"
                         + (f" · worlds {b['worlds']}" if b['worlds'] else '') + (f" · origins {b['origins']}" if b['origins'] else '')
                         + (f" · include {b['include_artists']}" if b['include_artists'] else '')
                         + (f" · exclude {b['exclude_artists']}" if b['exclude_artists'] else '')
                         + (f" · want {b.get('want')}" if b.get('want') else ''))
            if focus and s['id'] == focus:
                for i, t in enumerate(v['tracks'], 1):
                    e = f", energy {t['energy']:.2f}" if t.get('energy') is not None else ''
                    pin = ', pinned' if t.get('pinned') else ''
                    lines.append(f"    {i}. {t['title']} — {t['artist']} ({TAG.get(t.get('pool'), '')}{e}{pin})")
    lines.append(f"\nToday's subject to learn about: {lens or 'not chosen yet'}.")
    lines.append(f"He is looking at: {'the playlist ' + focus if focus else 'the whole canvas'}.")
    return '\n'.join(lines)


def plan(llm, about, place, day, canvas, focus, text, lens=None):
    """-> (actions, say, lens) where lens is the new subject, '' to clear it, or None for no change."""
    user = render_context(place, day, canvas, focus, lens) + f'\n\nHe typed: "{text}"'
    # At most ~85 s even with one retry, inside the app's 120 s wait.
    out = llm.tool(system_prompt(about), user, TOOL, max_tokens=1500, timeout=40, retries=1, wait=3)
    acts = [a for a in out.get('actions') or [] if isinstance(a, dict) and a.get('target')]
    for a in acts:
        for op in a.get('ops') or []:
            if isinstance(op.get('n'), int):
                op['index'] = max(1, op['n']) - 1
            if isinstance(op.get('to'), int):
                op['to'] = max(1, op['to']) - 1
    new_lens = out.get('lens')
    if isinstance(new_lens, str):
        new_lens = '' if new_lens.strip().lower() in ('none', 'clear', '') else new_lens.strip()[:80]
    else:
        new_lens = None
    return acts, (out.get('say') or '').strip() or None, new_lens


# -- the stand-in when the gateway is down ---------------------------------------------------------------------------

WORDS = [
    ('run', r'\b(run|jog|running)\b'), ('workout', r'\b(gym|workout|lift|weights|training)\b'),
    ('walk', r'\b(walk|stroll|walking)\b'), ('ride', r'\b(drive|driving|road ?trip|ride|car)\b'),
    ('commute', r'\b(commute|office|metro|bus)\b'), ('focus', r'\b(focus|work|study|read|reading|code|coding|deep)\b'),
    ('journey', r'\b(train|flight|fly|journey|travel|trip)\b'), ('cook', r'\b(cook|cooking|kitchen|dinner prep)\b'),
    ('sleep', r'\b(sleep|bed|wind down)\b'), ('late-night', r'\b(late night|midnight)\b'), ('party', r'\b(party|friends over|guests)\b'),
    ('morning', r'\b(morning|wake|coffee|breakfast)\b'), ('unwind', r'\b(unwind|relax|chill)\b'),
    ('discovery', r'\b(discover|discovery|new music|explore)\b'),
]
TWEAK_WORDS = [
    ('familiar', r'more familiar|songs i know|old favou?rites'), ('new', r'more new|fresh|surprise'),
    ('calmer', r'calm|slower|softer|mellow|quieter'), ('energy', r'energ|faster|hype|pump|louder|upbeat'),
    ('shorter', r'shorter|less time'), ('longer', r'longer|more time|keep going'), ('reshuffle', r'shuffle|different|another|again'),
]


def fallback(text, place, focus_brief=None):
    """Rules for the common cases: returns (actions, tweak). A tweak applies to the open playlist; anything else that
    can't be read with certainty changes nothing."""
    t = text.lower()
    if focus_brief:
        for name, rx in TWEAK_WORDS:
            if re.search(rx, t):
                return [], name
        return [], None
    act = next((k for k, rx in WORDS if re.search(rx, t)), None)
    if not act:
        return [], None
    start = None
    m = re.search(r'\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b', t)
    if m:
        h = int(m.group(1)) % 12 + (12 if m.group(3) == 'pm' else 0)
        start = f'{h:02d}:{int(m.group(2) or 0):02d}'
    minutes = None
    m = re.search(r'(\d+(?:\.\d+)?)\s*(h|hr|hrs|hour|hours)\b', t)
    if m:
        minutes = int(float(m.group(1)) * 60)
    m = re.search(r'(\d+)\s*(m|min|mins|minutes)\b', t)
    if m:
        minutes = int(m.group(1))
    if not start:
        start = place.now().strftime('%H:%M')
    label = ACTIVITIES[act]['label'].lower()
    title = f'{clock(start)} {label}' if m or re.search(r'\d\s*(am|pm)', t) else label
    return [{'target': 'new', 'activity': act, 'start': start, 'minutes': minutes, 'title': title,
             'reply': f'Made a {label} playlist. (The language model was unavailable, so this is a rough reading.)'}], None
