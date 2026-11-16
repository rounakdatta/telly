"""The day's canvas: one session per moment of the day, each a chain of versions. Every message, tweak or edit makes a
new version, so nothing is lost and anything can be undone."""
import datetime as dt
import logging
import threading
import time

from . import curate
from . import engine as E
from . import interpret
from .context import clock
from .spotify import playlist_url as sp_url
from .ytm import playlist_url as ytm_url

log = logging.getLogger('telly')
KEEP = ('id', 'title', 'artist', 'artist_key', 'pool', 'kind', 'world', 'origin', 'duration_ms', 'energy', 'tempo', 'danceability',
        'acousticness', 'instrumentalness', 'speechiness', 'plays', 'days_played', 'first_ts', 'last_ts', 'liked', 'ytm_vid',
        'spotify_uri', 'playcount', 'adjacency', 'artist_aff', 'pinned', 'since', 'skip', 'sp_streams', 'sp_skips', 'part', 'link', 'year', 'thumb')
SHAPE = ('activity', 'minutes', 'familiar', 'energy', 'tempo', 'worlds', 'origins', 'include_artists', 'exclude_artists', 'instrumental',
         'want')


class NoCatalog(Exception):
    pass


def slim(tracks):
    return [{k: t[k] for k in KEEP if t.get(k) is not None} for t in tracks]


def day_label(day):
    d = dt.date.fromisoformat(day)
    return d.strftime('%A, %-d %B')


class Canvas:
    def __init__(self, store, catalogs, place, llm, curator_llm=None):
        self.store, self.catalogs, self.place, self.llm = store, catalogs, place, llm
        self.curator_llm = curator_llm  # chooses and orders the songs (curate.py); the engine alone when there is none
        self.lock = threading.RLock()

    def curator(self):
        if self.curator_llm is None or not self.curator_llm.ready:
            return None
        return curate.Curator(self.curator_llm, self.cat, self.about, self.store)

    def moment(self, day, brief, title):
        """'12:39 am sleep (sleep), 12:39 am on Tuesday 17 November, at home in Bengaluru; weather 20°, clear'."""
        when = clock(brief['start']) + ' ' if brief.get('start') else ''
        m = f"{title} ({brief['activity']}), {when}on {time.strftime('%A %-d %B', time.strptime(day, '%Y-%m-%d'))}, at home in {self.place.name}"
        try:
            wx = self.place.weather(self.place.at(day, brief['start']).replace(minute=0)) if brief.get('start') else None
        except Exception:
            wx = None
        return m + (f'; weather {wx}' if wx else '')

    def _curate(self, sid, day, brief, text, keep=None, instruction=None, deadline=None, rename=True):
        """The curator's mix for this brief -> (tracks, brief carrying the mix) or None (the engine then builds it).
        `text`: his message. A mix remembers what he first asked for, and keeps its name through tweaks and edits
        (`rename` only for a fresh pick)."""
        cur = self.curator()
        if cur is None:
            return None
        old = brief.get('mix') or {}
        asked = old.get('said') or text
        got = curate.mixtape(cur, brief, self.moment(day, brief, brief['title']), asked, self.store.lens(day),
                             int((brief.get('start') or '12:00')[:2]), self._served(sid), keep, instruction,
                             deadline=deadline or time.time() + 90)
        if not got:
            return None
        tracks, mix = got
        title = mix['title'] if rename or not old.get('title') else old['title']
        return tracks, dict(brief, mix={'title': title, 'said': asked})

    @property
    def about(self):
        c = self.catalogs.get()
        return (c.meta.get('about') if c else '') or ''

    @property
    def cat(self):
        c = self.catalogs.get()
        if c is None:
            raise NoCatalog('no catalogue yet: upload one to /admin/catalog')
        return c

    # -- reading -------------------------------------------------------------------------------------------------------
    def day(self, day):
        sessions = [self.render(s) for s in self.store.sessions_on(day) if s['version'] > 0]
        sessions.sort(key=lambda s: (s['start'] or '99:99', s['created']))
        now = self.place.now()
        return {'day': day, 'label': day_label(day), 'today': day == now.date().isoformat(),
                'weather': self.place.weather(now.replace(minute=0)) if day == now.date().isoformat() else None,
                'lens': self.store.lens(day), 'lens_ideas': lens_ideas(day), 'sessions': sessions}

    def session(self, sid):
        s = self.store.session(sid)
        if not s or s['deleted'] or s['version'] == 0:
            return None
        return self.render(s)

    def library(self, before, limit=14):
        days = self.store.days_before(before, limit)
        return {'days': [{'day': d, 'label': day_label(d), 'sessions': [self.render(s, brief_only=True) for s in self.store.sessions_on(d) if s['version'] > 0]}
                         for d in days]}

    def render(self, s, v=None, brief_only=False):
        v = v or self.store.version(s['id'])
        b, tracks = v['brief'], v['tracks']
        cache = self.store.resolved([t['id'] for t in tracks])
        # A curated mix's notes are its own story, told along its songs (written in the background, per version).
        mix = None
        if b.get('mix'):
            mix = self.store.get(f"mix:{s['id']}:{v['n']}")
            if not mix:  # until this version's are written, the last notes stay on the songs still here
                mix = self.store.get(f"mix:{s['id']}")
        notes = (mix or {}).get('notes') or {} if b.get('mix') else \
            {k: v for k, v in self.store.notes_for([t['id'] for t in tracks]).items() if v}
        rows = []
        for i, t in enumerate(tracks):
            r = cache.get(t['id']) or {}
            vid = r.get('ytm_vid') or t.get('ytm_vid')
            thumb = r.get('thumb') or (f'https://i.ytimg.com/vi/{vid}/mqdefault.jpg' if vid else None) or t.get('thumb')
            # A song from outside his library shows as new (the app knows the engine's pools by name).
            pool = 'new_known' if t.get('pool') == 'curated' else t.get('pool')
            rows.append({'n': i + 1, 'id': t['id'], 'title': t['title'], 'artist': t['artist'], 'pool': pool,
                         'tag': E.TAG.get(t.get('pool'), ''), 'why': E.why(t), 'new': E.is_new(t),
                         'pinned': bool(t.get('pinned')), 'thumb': thumb, 'duration_s': round(E.dur_ms(t) / 1000), 'note': notes.get(t['id']),
                         'ytm': vid, 'spotify': t.get('spotify_uri') or r.get('sp_uri')})
        start = b.get('start')
        story = s.get('story')
        out = {
            'id': s['id'], 'day': s['day'], 'title': s['title'], 'activity': b['activity'], 'start': start,
            'start_label': clock(start) if start else None, 'minutes': b['minutes'],
            'duration_min': round(E.duration_ms(tracks) / 60000), 'count': len(tracks),
            'summary': (mix or {}).get('thread') or (f"\u201c{b['mix']['title']}\u201d. " if b.get('mix') else '') + E.summary(b, tracks),
            'blurb': E.ACTIVITIES[b['activity']]['blurb'],
            'brief': dict({k: b.get(k) for k in ('familiar', 'energy', 'worlds', 'origins', 'include_artists', 'exclude_artists')},
                          instrumental=E.is_instrumental(b)),
            'notes_state': s.get('notes_state') or 'none',
            'tracks': rows, 'version': v['n'], 'can_undo': bool(v.get('parent')), 'created': s['created'], 'updated': s['updated'],
            'ytm': {'state': s.get('ytm_state') or 'pending', 'url': ytm_url(s['ytm_id']) if s.get('ytm_id') else None,
                    'play': (f"https://music.youtube.com/watch?v={rows[0]['ytm']}&list={s['ytm_id']}" if s.get('ytm_id') and rows and rows[0]['ytm']
                             else (ytm_url(s['ytm_id']) if s.get('ytm_id') else None)),
                    'error': s.get('ytm_error')},
            'spotify': {'state': s.get('sp_state') or 'pending', 'url': sp_url(s['sp_id']) if s.get('sp_id') else None, 'error': s.get('sp_error')},
            'story': self._story(s),
        }
        if start and s['day'] == self.place.today():
            out['weather'] = self.place.weather(self.place.at(s['day'], start).replace(minute=0))
        if not brief_only:
            out['log'] = [{'at': h['created'], 'source': h['source'], 'said': h['said'], 'reply': h['reply']} for h in self.store.history(s['id'])]
        return out

    @staticmethod
    def _story(s):
        import json
        st = s.get('story_state') or 'pending'
        if s.get('story'):
            d = json.loads(s['story'])
            return {'state': 'ready', 'title': d.get('title'), 'body': d.get('body'), 'deeper': d.get('deeper'),
                    'deeper_checked': d.get('deeper_checked', False), 'song_id': d.get('song_id')}
        return {'state': st}

    # -- writing -------------------------------------------------------------------------------------------------------
    def prompt(self, day, text, focus=None):
        """Read a message and apply it. Returns {'reply', 'focus', 'day'}."""
        started = time.time()
        text = (text or '').strip()[:1000]
        if not text:
            return {'reply': None, 'focus': focus, 'day': self.day(day)}
        canvas = [(s, self.store.version(s['id'])) for s in self.store.sessions_on(day) if s['version'] > 0]
        if focus and not any(s['id'] == focus for s, _ in canvas):
            focus = None
        say, rough, new_lens = None, False, None
        try:
            actions, say, new_lens = interpret.plan(self.llm, self.about, self.place, day, canvas, focus, text, self.store.lens(day))
        except Exception as e:
            log.warning('planner unavailable, using rules: %s', str(e)[:160])
            focus_brief = next((v['brief'] for s, v in canvas if s['id'] == focus), None)
            actions, tw = interpret.fallback(text, self.place, focus_brief)
            rough = True
            if tw and focus:
                done = self.tweak(focus, tw, said=text)  # its reply carries the build's own notes ("only 9 fit")
                return {'reply': done['note'], 'focus': focus, 'day': self.day(day), 'rough': True}
            if not actions:
                return {'reply': "I can't read that right now (the language model is unreachable). The buttons still work.",
                        'focus': focus, 'day': self.day(day), 'rough': True}
        replies, last = [], focus
        if new_lens is not None:
            got = self.set_lens(day, new_lens)
            replies.append(f"Today's subject: {got}. The stories will lean on it." if got else 'No subject for today, then.')
        ids = {s['id'] for s, _ in canvas}
        # The curator is slow (seconds to a minute): it works before the lock is taken, so nothing else waits on it, and
        # within the time the phone waits for an answer (120 s), the planner's time included.
        pre = [self._precurate(day, a, text, ids, started + 100) for a in actions]
        with self.lock:
            for a, p in zip(actions, pre):
                try:
                    sid = self._apply(day, a, text, ids, p)
                except NoCatalog:
                    raise
                except Exception:
                    log.exception('could not apply %s', {k: a.get(k) for k in ('target', 'activity')})
                    continue
                if sid:
                    last = sid
                elif a.get('delete') and a.get('target') == last:
                    last = None  # the playlist he was looking at is gone: there is nothing to point him back to
                if a.get('reply'):
                    replies.append(a['reply'].strip())
        return {'reply': ' '.join(replies) or say or "I couldn't turn that into a change.", 'focus': last,
                'day': self.day(day), 'rough': rough}

    @staticmethod
    def _target(a, ids):
        t = a.get('target')
        return t if t == 'new' or t in ids else 'new'

    @staticmethod
    def _fields(a):
        fields = {k: a[k] for k in ('activity', 'start', 'minutes', 'familiar', 'energy', 'tempo', 'worlds', 'origins',
                                    'include_artists', 'exclude_artists', 'instrumental', 'title', 'want') if a.get(k) is not None}
        if 'start' in fields:
            fields['start'] = _hhmm(fields['start'])
            if not fields['start']:
                fields.pop('start')
        return fields

    def _new_brief(self, fields):
        fields = dict(fields)
        fields.setdefault('start', self.place.now().strftime('%H:%M'))
        return E.normalize(fields)

    @staticmethod
    def _changed_brief(brief, fields):
        base = dict(brief)
        if fields.get('activity') and fields['activity'] != brief['activity']:
            for k in ('energy', 'familiar', 'tempo', 'instrumental'):
                base.pop(k, None)  # "make it a run" means a run's arc, not the walk's
        return E.normalize({**base, **fields, 'seed': brief['seed']})

    def _precurate(self, day, a, text, ids, deadline):
        """The curator's mix for an action that needs one (a new moment; a curated mix reshaped or reshuffled), worked
        out before the edit takes the lock -> {'n': version it was made from (None for new), 'got': (tracks, brief)}."""
        try:
            if a.get('delete') or a.get('ops') or self.curator() is None:
                return None
            target = self._target(a, ids)
            fields = self._fields(a)
            if target == 'new':
                brief = self._new_brief(fields)
                got = self._curate(None, day, brief, text, deadline=deadline)
                return {'n': None, 'brief': brief, 'got': got} if got else None
            v = self.store.version(target)
            if not v or not v['brief'].get('mix'):
                return None
            brief = v['brief']
            new_brief = self._changed_brief(brief, fields)
            reshuffle = bool(a.get('reshuffle'))
            if not reshuffle and not any(new_brief.get(k) != brief.get(k) for k in SHAPE):
                return None
            if reshuffle:
                new_brief['seed'] = E.normalize({'activity': new_brief['activity']})['seed']
            got = self._curate(target, day, new_brief, text, None if reshuffle else v['tracks'], instruction=text,
                               deadline=deadline, rename=reshuffle)
            return {'n': v['n'], 'got': got} if got else None
        except Exception:
            log.exception('curating ahead failed')
            return None

    def _apply(self, day, a, text, ids, pre=None):
        cat = self.cat
        target = self._target(a, ids)
        fields = self._fields(a)
        if target == 'new':
            if a.get('delete'):
                return None
            brief = pre['brief'] if pre and pre['n'] is None else self._new_brief(fields)
            sid = self.store.new_session(day, brief['title'])
            got = pre['got'] if pre and pre['n'] is None else None
            if got:
                order, brief = got
                a['reply'] = f"Made you \u201c{brief['mix']['title']}\u201d: {len(order)} songs. The notes are on their way."
            else:
                order, bl = E.rebuild(cat, brief, int(brief['start'][:2]), [], self._served(sid))
                a['reply'] = ' '.join([a.get('reply') or ''] + bl.notes).strip()
            self._save(sid, 'prompt', text, a['reply'], brief, order)
            return sid
        sid = target
        if a.get('delete'):
            self.delete(sid)
            return None
        v = self.store.version(sid)
        brief = dict(v['brief'])
        if fields.get('title'):
            self.store.update_session(sid, title=fields['title'][:60])
        new_brief = self._changed_brief(brief, fields)
        tracks = v['tracks']
        hour = int((new_brief.get('start') or '12:00')[:2])
        notes = []
        if a.get('ops'):
            bl = E.Builder(cat, brief, hour, served=self._served(sid), prev=tracks)
            tracks, after = E.apply_ops(cat, brief, tracks, a['ops'], bl, notes)
            new_brief['avoid'] = after['avoid']
        reshuffle = bool(a.get('reshuffle'))
        if reshuffle:
            new_brief['seed'] = E.normalize({'activity': new_brief['activity']})['seed']
        if reshuffle or any(new_brief.get(k) != brief.get(k) for k in SHAPE):
            got = pre['got'] if pre and pre['n'] == v['n'] and not a.get('ops') else None  # made from this very version
            if got:
                tracks, new_brief = got
            else:
                tracks, bl = E.rebuild(cat, new_brief, hour, tracks, self._served(sid), sticky=not reshuffle)
                notes += bl.notes
        reply = ' '.join([a.get('reply') or ''] + notes).strip()
        shape = lambda ts: [(t['id'], bool(t.get('pinned'))) for t in ts]
        if shape(tracks) == shape(v['tracks']) and new_brief == {**brief, 'avoid': new_brief['avoid']}:
            reply = (' '.join(notes) or 'Nothing changed.')  # never claim an edit that didn't happen
        a['reply'] = reply
        self._save(sid, 'prompt', text, reply, new_brief, tracks)
        return sid

    def tweak(self, sid, name, said=None):
        if name not in E.TWEAKS:
            raise ValueError('unknown tweak')
        # A curated mix is asked again with the change (its name and first ask kept), before the lock is taken.
        v0, s = self.store.version(sid), self.store.session(sid)
        b0 = E.tweak(v0['brief'], name)
        key = name if name != 'instrumental' or E.is_instrumental(b0) else 'vocals'
        asked = said or ('Bring the vocals back' if key == 'vocals' else E.TWEAKS[name])
        got = self._curate(sid, s['day'], b0, asked, None if name == 'reshuffle' else v0['tracks'], instruction=asked,
                           deadline=time.time() + 90, rename=name == 'reshuffle') if v0['brief'].get('mix') else None
        with self.lock:
            v = self.store.version(sid)
            if got and v['n'] == v0['n']:
                tracks, b = got
                reply = E.TWEAK_REPLY[key]
            else:  # no curator, or it changed meanwhile: the engine, on the playlist as it is now
                b = E.tweak(v['brief'], name)
                hour = int((b.get('start') or '12:00')[:2])
                tracks, bl = E.rebuild(self.cat, b, hour, v['tracks'], self._served(sid), sticky=name != 'reshuffle')
                reply = ' '.join([E.TWEAK_REPLY[key]] + bl.notes)
            self._save(sid, 'tweak', said or E.TWEAKS[name], reply, b, tracks)
        return dict(self.session(sid), note=reply)

    def edit(self, sid, op, index, to=None, track_id=None):
        with self.lock:
            v = self.store.version(sid)
            tracks = v['tracks']
            if track_id is not None:
                # The list on the phone may be a version behind: the id decides which song, the index only hints.
                if not (0 <= index < len(tracks) and tracks[index]['id'] == track_id):
                    index = next((i for i, t in enumerate(tracks) if t['id'] == track_id), -1)
            if not 0 <= index < len(tracks):
                return self.session(sid)
            if op == 'move' and not isinstance(to, int):
                return self.session(sid)
            hour = int((v['brief'].get('start') or '12:00')[:2])
            bl = E.Builder(self.cat, v['brief'], hour, served=self._served(sid), prev=tracks)
            t = tracks[index]['title']
            notes = []
            new, b = E.apply_ops(self.cat, v['brief'], tracks, [{'op': op, 'index': index, 'to': to}], bl, notes)
            if notes:
                reply = ' '.join(notes)
            else:
                reply = {'remove': f'Removed {t}.', 'swap': f'Swapped {t} for {new[index]["title"] if index < len(new) else "another"}.',
                         'pin': f'Keeping {t}.', 'unpin': f'{t} can change again.', 'move': f'Moved {t}.'}.get(op, 'Done.')
            if [(x['id'], bool(x.get('pinned'))) for x in new] == [(x['id'], bool(x.get('pinned'))) for x in tracks]:
                # nothing changed: no new version, just the honest note
                return dict(self.session(sid), note=reply)
            self._save(sid, 'edit', f'{op} {t}', reply, b, new)
        return dict(self.session(sid), note=reply)

    def undo(self, sid):
        with self.lock:
            cur = self.store.version(sid)
            if not cur or not cur.get('parent'):
                return self.session(sid)
            prev = self.store.version(sid, cur['parent'])
            self.store.add_version(sid, 'undo', 'Undo', 'Back to how it was.', prev['brief'], prev['tracks'], parent=prev.get('parent') or 0)
            self.store.mark_served(sid, prev['tracks'])
            self.store.update_session(sid, ytm_state='pending' if self.store.session(sid).get('ytm_state') != 'off' else 'off')
            self.store.enqueue(sid)
        return self.session(sid)

    def delete(self, sid):
        with self.lock:
            self.store.update_session(sid, deleted=1)
        self.store.enqueue(sid)

    def resync(self, sid):
        s = self.store.session(sid)
        if not s or s['deleted']:
            return None
        self.store.update_session(sid, **{k: 'pending' for k in ('ytm_state', 'sp_state') if s.get(k) not in (None, 'off')})
        self.store.enqueue(sid)
        # A resync makes no version, so the last change's reply would be stale: say what this one does instead.
        return dict(self.session(sid), note='Saving it to your music apps again.')

    # -- the day's lens -------------------------------------------------------------------------------------------------
    def set_lens(self, day, text):
        """What he wants to learn about today. Every playlist's story is rewritten to lean on it."""
        text = ' '.join((text or '').split())[:80]
        self.store.set_lens(day, text or None)
        for s in self.store.sessions_on(day):
            if s['version'] > 0:
                self.store.update_session(s['id'], story_state='pending')
                self.store.enqueue(s['id'])
        return text or None

    # -- helpers -------------------------------------------------------------------------------------------------------
    def _served(self, sid):
        return self.store.served_since(time.time() - 2 * 86400, exclude_session=sid)

    def _save(self, sid, source, said, reply, brief, tracks):
        self.store.add_version(sid, source, said, reply, brief, slim(tracks))
        self.store.mark_served(sid, tracks)
        self.store.update_session(sid, ytm_state='pending' if self.store.session(sid).get('ytm_state') != 'off' else 'off',
                                  notes_state='pending')
        self.store.enqueue(sid)


LENS_IDEAS = ['20th-century history', 'Something romantic', 'Pop culture of the 2000s', 'Indiranagar', 'The Bengal Renaissance',
              'Cinema', 'The Partition', 'The Jazz Age', 'Mughal India', 'The space race', 'Cricket', 'Food and how we eat',
              'The Cold War', "Bollywood's golden age", 'Architecture', 'Explorers', 'Women who changed music', 'Old Bangalore',
              'Science', 'Myths and epics', 'The 1970s', 'Cities', 'Rivers', 'Inventions', 'Love letters']


def lens_ideas(day, n=5):
    """A few subjects to offer, different each day (deterministic, so a reload doesn't reshuffle them)."""
    import random
    pool = list(LENS_IDEAS)
    random.Random(day).shuffle(pool)
    return pool[:n]


def _hhmm(s):
    """'7:00' -> '07:00'; anything that isn't a time of day -> None."""
    try:
        h, m = (int(x) for x in str(s).split(':')[:2])
    except ValueError:
        return None
    return f'{h:02d}:{m:02d}' if 0 <= h < 24 and 0 <= m < 60 else None
