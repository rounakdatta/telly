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
        key = ck.get('SAPISID'), ck.get('__Secure-3PSID')
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

    def run(self):
        while True:
            try:
                if time.time() - self._status_at > STATUS_EVERY:
                    self.check_services()
                sid = self.store.claim()
                if not sid:
                    time.sleep(self.poll)
                    continue
                self.sync(sid)
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
        self._story(sid)

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
            if s.get('ytm_id'):
                try:
                    rows = y.replace(s['ytm_id'], want, current_count=s.get('ytm_count'))
                    y.set_meta(s['ytm_id'], title=title, description=desc)
                    pid = s['ytm_id']
                except ytm_mod.AuthError:
                    raise
                except Exception:
                    log.warning('YouTube Music playlist %s unusable, making a new one', s['ytm_id'])
                    pid = y.create(title, desc, want)
                    rows = y.settled_items(pid, len(want))
            else:
                pid = y.create(title, desc, want)
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

    def _story(self, sid):
        s = self.store.session(sid)
        v = self.store.version(sid)
        ids = {t['id'] for t in v['tracks']}
        lens = self.store.lens(s['day'])
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
            st = story_mod.write(llm, self.canvas.about, moment, v['tracks'], avoid, lens=lens, callbacks=[p[:3] for p in callbacks])
        except Exception as e:
            log.warning('story %s: %s', sid, str(e)[:200])
            st = None
        if not st:
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
        if s.get('ytm_id') and y:
            try:
                y.delete(s['ytm_id'])
                self.store.update_session(s['id'], ytm_id=None, ytm_state='deleted')
            except Exception as e:
                log.warning('ytm delete %s: %s', s['id'], str(e)[:160])
        if s.get('sp_id') and sp:
            try:
                sp.delete(s['sp_id'])
                self.store.update_session(s['id'], sp_id=None, sp_state='deleted')
            except Exception as e:
                log.warning('spotify delete %s: %s', s['id'], str(e)[:160])


def migrate_secrets(store, vault):
    """Earlier builds kept the sign-ins in telly.db, which the API can read. Move them into the Vault, once."""
    for key in ('ytm_cookies', 'spotify'):
        v = store.get(key)
        if v and not vault.get(key):
            vault.put(key, v)
        if v:
            store.put(key, None)
