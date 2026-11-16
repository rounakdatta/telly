"""The worker: everything that needs a music-service sign-in, in its own process (and container), away from the internet.

It takes sessions from the queue the API fills, finds each song on YouTube Music and Spotify, writes the playlists
there (in place, so a link stays the same), fetches artwork, and writes the story. The sign-ins live in the Vault, which
only this process can read; what the API may know about them (signed in or not, and as whom) is written to the
database as plain status.
"""
import json
import logging
import random
import threading
import time
import traceback

from . import notes as notes_mod
from . import spotify as sp_mod
from . import story as story_mod
from . import ytm as ytm_mod
from .context import clock

log = logging.getLogger('telly')
NOT_FOUND_RETRY = 7 * 86400
STATUS_EVERY = 600


class Services:
    """Service clients built from the Vault, rebuilt when the stored sign-in changes (no restart needed)."""

    def __init__(self, vault):
        self.vault = vault
        self._ytm, self._ytm_key = None, None
        self._sp, self._sp_key = None, None

    def ytm(self):
        ck = self.vault.get('ytm_cookies')
        if not ck:
            return None
        # A fresh paste from the admin bumps ytm_rev; the client's own rotations don't, so they never rebuild it.
        key = self.vault.get('ytm_rev', 0)
        if self._ytm is None or self._ytm_key != key:
            self._ytm = ytm_mod.YTM(ck, on_rotate=lambda c: self.vault.put('ytm_cookies', c))
            self._ytm_key = key
        return self._ytm

    def spotify(self):
        cfg = self.vault.get('spotify')
        if not cfg or not cfg.get('refresh_token'):
            return None
        key = (cfg['client_id'], cfg['refresh_token'])
        if self._sp is None or self._sp_key != key:
            def keep(rt, cfg=cfg):
                self.vault.put('spotify', dict(cfg, refresh_token=rt))
                self._sp_key = (cfg['client_id'], rt)
            self._sp = sp_mod.Spotify(cfg['client_id'], cfg['client_secret'], cfg['refresh_token'], on_refresh=keep)
            self._sp_key = key
        return self._sp


class Worker(threading.Thread):
    def __init__(self, store, services, canvas, poll=0.7):
        super().__init__(daemon=True, name='worker')
        self.store, self.services, self.canvas, self.poll = store, services, canvas, poll
        self._status_at = 0
        self.after = []  # (kind, session) to do once nothing waits to sync: 'notes' (quick, seen in the list) before 'story'

    def run(self):
        # A job leaves the queue when its work starts, so a restart mid-sync (every deploy) would strand it: put
        # back everything still marked as in progress.
        for sid in self.store.unfinished():
            self.store.enqueue(sid)
        while True:
            try:
                if time.time() - self._status_at > STATUS_EVERY:
                    self.check_services()
                sid = self.store.claim()
                if sid:
                    self.sync(sid)
                elif self.after:
                    # Notes and stories are slow (Wikipedia, then the model), so they wait until no playlist is waiting
                    # to sync; notes go first, because they show in the list.
                    i = next((k for k, (kind, _) in enumerate(self.after) if kind == 'notes'), 0)
                    kind, sid = self.after.pop(i)
                    (self._notes if kind == 'notes' else self._story)(sid)
                else:
                    time.sleep(self.poll)
            except Exception:
                log.error('worker: %s', traceback.format_exc()[-600:])
                time.sleep(2)

    # -- what the API may know about the sign-ins --------------------------------------------------------------------
    def check_services(self):
        self._status_at = time.time()
        st = {}
        y = self.services.ytm()
        if y:
            try:
                st['ytm'] = {'ok': True, 'account': y.whoami()}
            except Exception as e:
                st['ytm'] = {'ok': False, 'error': str(e)[:160]}
        else:
            st['ytm'] = {'ok': False, 'error': 'not connected'}
        sp = self.services.spotify()
        if sp:
            try:
                me = sp.me() or {}
                st['spotify'] = {'ok': True, 'account': me.get('display_name') or me.get('id')}
            except Exception as e:
                st['spotify'] = {'ok': False, 'error': str(e)[:160]}
        else:
            st['spotify'] = {'ok': False, 'error': 'not connected'}
        st['checked'] = time.time()
        self.store.put('svc_status', st)
        return st

    # -- one session -----------------------------------------------------------------------------------------------
    def sync(self, sid):
        s = self.store.session(sid)
        if not s:
            return
        if s['deleted']:
            return self._delete_remote(s)
        v = self.store.version(sid)
        if not v:
            return
        y, sp = self.services.ytm(), self.services.spotify()
        if y:
            self._ytm(y, s, v)
        else:
            self.store.update_session(sid, ytm_state='off')
        if sp:
            self._spotify(sp, s, v)
        else:
            self.store.update_session(sid, sp_state='off')
        latest = self.store.session(sid)
        if latest and latest['version'] != v['n']:
            # Changed while syncing: what was just written is already stale, so say so and sync again first.
            self.store.update_session(sid, **{k: 'pending' for k in ('ytm_state', 'sp_state') if latest.get(k) == 'synced'})
            self.store.enqueue(sid)
            return
        for kind in ('notes', 'story'):
            if (kind, sid) not in self.after:
                self.after.append((kind, sid))

    def _remote_title(self, s):
        return f"{s['title'][:1].upper()}{s['title'][1:]} · {time.strftime('%-d %b', time.strptime(s['day'], '%Y-%m-%d'))}"

    def _description(self, s, v, short=False):
        from . import engine as E
        b = v['brief']
        head = f"{E.summary(b, v['tracks'])} {E.ACTIVITIES[b['activity']]['blurb']}"
        if short:
            return head + ' Made with telly.'
        st = json.loads(s['story']) if s.get('story') else None
        parts = [head]
        if st:
            parts += [st['title'], st['body'], f"Go deeper: {st['deeper']}" if st.get('deeper') else '']
        parts.append('Made with telly.')
        return '\n\n'.join(p for p in parts if p)[:4900]

    def _ytm(self, y, s, v):
        sid = s['id']
        self.store.update_session(sid, ytm_state='syncing', ytm_error=None)
        try:
            tracks = v['tracks']
            cache = self.store.resolved([t['id'] for t in tracks])
            vids = []
            for t in tracks:
                r = cache.get(t['id']) or {}
                vid = r.get('ytm_vid') or t.get('ytm_vid')  # the cache holds the id YouTube Music actually keeps
                if not vid and (time.time() - (r.get('ytm_checked') or 0)) > NOT_FOUND_RETRY:
                    row = y.resolve({'title': t['title'], 'artist': t['artist'], 'duration_ms': t.get('duration_ms')})
                    vid = row['videoId'] if row else None
                    self.store.resolve_put(t['id'], ytm_vid=vid, ytm_checked=time.time(), thumb=row['thumb'] if row else None)
                    time.sleep(0.3)
                if vid:
                    vids.append((t['id'], vid))
            want = list(dict.fromkeys(vid for _, vid in vids))
            title, desc = self._remote_title(s), self._description(s, v)
            pid = s.get('ytm_id')
            if pid and not y.exists(pid):
                log.warning('YouTube Music playlist %s is gone; making a new one', pid)
                pid = None
            if pid:
                # A passing failure here is reported and retried, never answered with a second playlist.
                rows = y.replace(pid, want, current_count=s.get('ytm_count'))
                y.set_meta(pid, title=title, description=desc)
            else:
                pid = y.create(title, desc, want)
                # Remember it before anything else can fail, or the next sync would make another.
                self.store.update_session(sid, ytm_id=pid, ytm_count=len(want))
                rows = y.settled_items(pid, len(want))
            # YouTube Music swaps a music video for its song version on the way in, so rows match by position when
            # the counts agree, and by id otherwise; the id it kept is remembered, or every sync would see a change.
            by_vid = {r['videoId']: r for r in rows if r.get('videoId')}
            by_pos = dict(zip(want, rows)) if len(rows) == len(want) else {}
            for tid, vid in vids:
                r = by_pos.get(vid) or by_vid.get(vid)
                if r and r.get('videoId'):
                    self.store.resolve_put(tid, ytm_vid=r['videoId'], thumb=r.get('thumb'))
            missing = len(tracks) - len(vids)
            self.store.update_session(sid, ytm_id=pid, ytm_state='synced', ytm_synced=v['n'], ytm_count=len(rows),
                                      ytm_error=f'{missing} not on YouTube Music' if missing else None)
        except ytm_mod.AuthError as e:
            self.store.update_session(sid, ytm_state='signed-out', ytm_error=str(e)[:200])
            self._status_at = 0  # re-check the sign-in on the next turn
        except Exception as e:
            log.error('ytm sync %s: %s', sid, traceback.format_exc()[-500:])
            self.store.update_session(sid, ytm_state='error', ytm_error=str(e)[:200])

    def _spotify(self, sp, s, v):
        sid = s['id']
        self.store.update_session(sid, sp_state='syncing', sp_error=None)
        try:
            tracks = v['tracks']
            cache = self.store.resolved([t['id'] for t in tracks])
            uris = []
            for t in tracks:
                r = cache.get(t['id']) or {}
                uri = t.get('spotify_uri') or r.get('sp_uri')
                if not uri and (time.time() - (r.get('sp_checked') or 0)) > NOT_FOUND_RETRY:
                    uri = sp.search_track(t['title'], t['artist'], t.get('duration_ms'))
                    self.store.resolve_put(t['id'], sp_uri=uri, sp_checked=time.time())
                if uri:
                    uris.append(uri)
            title, desc = self._remote_title(s), self._description(s, v, short=True)
            pid = s.get('sp_id')
            if pid:
                try:
                    sp.replace(pid, uris)
                    sp.set_meta(pid, name=title, description=desc)
                except sp_mod.AuthError:
                    raise
                except sp_mod.SpotifyError:
                    pid = None
            if not pid:
                pid = sp.create(title, desc)
                sp.replace(pid, uris)
            missing = len(tracks) - len(uris)
            self.store.update_session(sid, sp_id=pid, sp_state='synced', sp_synced=v['n'],
                                      sp_error=f'{missing} not on Spotify' if missing else None)
        except sp_mod.AuthError as e:
            self.store.update_session(sid, sp_state='signed-out', sp_error=str(e)[:200])
        except Exception as e:
            log.error('spotify sync %s: %s', sid, traceback.format_exc()[-500:])
            self.store.update_session(sid, sp_state='error', sp_error=str(e)[:200])

    def _notes(self, sid):
        s = self.store.session(sid)
        if not s or s['deleted']:
            return
        v = self.store.version(sid)
        llm = self.canvas.llm
        if not llm.ready:
            self.store.update_session(sid, notes_state='none')
            return
        self.store.update_session(sid, notes_state='writing')
        b = v['brief']
        moment = f"{s['title']} ({b['activity']}) on {time.strftime('%A %-d %B', time.strptime(s['day'], '%Y-%m-%d'))}"
        try:
            # One batch per turn, so a playlist waiting to sync goes first; the next batch follows while that one made
            # progress (a turn that wrote nothing, say with Wikipedia down and the model passing, ends it).
            left = len(notes_mod.unwritten(v['tracks'], self.store))
            notes_mod.write(llm, self.canvas.about, moment, v['tracks'], self.store, batches=1)
            still = len(notes_mod.unwritten(v['tracks'], self.store))
            if still and still < left:
                self.after.insert(0, ('notes', sid))
                return
            self.store.update_session(sid, notes_state='ready')
        except Exception as e:
            log.warning('notes %s: %s', sid, str(e)[:200])
            self.store.update_session(sid, notes_state='none')

    def _story(self, sid):
        s = self.store.session(sid)
        if not s or s['deleted']:
            return
        v = self.store.version(sid)
        ids = {t['id'] for t in v['tracks']}
        lens = self.store.lens(s['day'])
        tried = f"{lens or ''}|{'|'.join(sorted(ids))}"
        if not s.get('story') and self.store.get(f'story_none:{sid}') == tried:
            if s.get('story_state') != 'none':
                self.store.update_session(sid, story_state='none')
            return  # nothing to write about these songs (for this lens) last time either
        if s.get('story'):
            st = json.loads(s['story'])
            # keep it while its song is still there and it was written for today's lens
            if (st.get('song_id') in ids or st.get('lens_story')) and (st.get('lens') or None) == (lens or None):
                return
        llm = self.canvas.llm
        if not llm.ready:
            self.store.update_session(sid, story_state='none')
            return
        self.store.update_session(sid, story_state='writing')
        b = v['brief']
        place = self.canvas.place
        when = clock(b['start']) + ' ' if b.get('start') else ''
        day = time.strftime('%A %-d %B', time.strptime(s['day'], '%Y-%m-%d'))
        moment = f"{s['title']} ({b['activity']}), {when}on {day}, at home in {place.name}"
        try:
            wx = place.weather(place.at(s['day'], b['start']).replace(minute=0)) if b.get('start') else None
        except Exception:
            wx = None
        if wx:
            moment += f'; weather {wx}'
        past = self.store.stories_since(time.time() - 60 * 86400, exclude_session=sid)
        # Callbacks are a treat, not a habit: about one story in three may look back, and only when it truly connects.
        callbacks = past[:6] if random.Random(sid).random() < 0.35 else []
        try:
            avoid = [p[1] for p in past[:30]] + [p[3] for p in past[:30]]
            st = story_mod.write(llm, self.canvas.about, moment, v['tracks'], avoid, lens=lens, callbacks=[p[:3] for p in callbacks],
                                 home=place.name)
        except Exception as e:
            log.warning('story %s: %s', sid, str(e)[:200])
            st = None
        if not st:
            self.store.put(f'story_none:{sid}', tried)
            self.store.update_session(sid, story_state='none')
            return
        st['lens'] = lens
        self.store.update_session(sid, story=json.dumps(st), story_state='ready')
        s = self.store.session(sid)
        y = self.services.ytm()
        try:
            if y and s.get('ytm_id'):
                y.set_meta(s['ytm_id'], description=self._description(s, v))
        except Exception as e:
            log.warning('story to ytm %s: %s', sid, str(e)[:160])

    def _delete_remote(self, s):
        y, sp = self.services.ytm(), self.services.spotify()
        retry = False
        if s.get('ytm_id') and y:
            try:
                y.delete(s['ytm_id'])
                self.store.update_session(s['id'], ytm_id=None, ytm_state='deleted')
            except Exception as e:
                retry = True
                log.warning('ytm delete %s: %s', s['id'], str(e)[:160])
        if s.get('sp_id') and sp:
            try:
                sp.delete(s['sp_id'])
                self.store.update_session(s['id'], sp_id=None, sp_state='deleted')
            except Exception as e:
                retry = True
                log.warning('spotify delete %s: %s', s['id'], str(e)[:160])
        if retry:
            self.store.enqueue(s['id'], delay=300)


def migrate_secrets(store, vault):
    """Earlier builds kept the sign-ins in telly.db, which the API can read. Move them into the Vault, once, and
    scrub them from the database file and its journal (a plain DELETE leaves the bytes in free pages)."""
    moved = []
    for key in ('ytm_cookies', 'spotify'):
        v = store.get(key)
        if v and not vault.get(key):
            vault.put(key, v)
        if v:
            moved.append(key)
    if moved:
        store.scrub(moved)
