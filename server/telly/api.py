"""HTTP, in two separate servers.

The public API (`python -m telly serve`) is what the phone talks to over the internet: /livez and /api/*, behind a
bearer token. It holds no music-service sign-ins. The admin server (`python -m telly worker`) runs inside the worker,
is never routed from the internet, needs its own token, and is the only place sign-ins and the catalogue arrive.

Hardening that applies to both: tokens compared in constant time; a bounded number of requests in flight; socket
timeouts; small body limits (the catalogue upload is the one exception, admin only); JSON bodies must be objects;
strict path patterns; security headers on every response; nothing secret ever logged. The public API adds a lockout for
addresses that keep presenting bad tokens, and hourly budgets on the routes that spend the language model, so a leaked
token can't drain it.
"""
import hmac
import json
import logging
import os
import re
import tempfile
import threading
import time
import urllib.parse
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .canvas import NoCatalog
from .catalog import Catalog
from .engine import TWEAKS
from . import art
from .lyrics import LyricsError
from . import ytm as ytm_mod

log = logging.getLogger('telly')
DAY = re.compile(r'^\d{4}-\d{2}-\d{2}$')
SID = re.compile(r'^[0-9a-f]{10}$')
API_BODY = 16 << 10
ADMIN_BODY = 64 << 20
HEADERS = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
    'Cross-Origin-Resource-Policy': 'same-origin',
}


class BadRequest(Exception):
    pass


class Catalogs:
    """The current catalogue, swappable while serving. The API process notices a new file by its mtime (the worker
    writes it), so an upload needs no restart."""

    def __init__(self, path):
        self.path, self._cat, self.lock = path, None, threading.Lock()
        self._mtime, self._checked = None, 0
        self._load()

    def _load(self):
        try:
            mtime = os.stat(self.path).st_mtime
        except FileNotFoundError:
            return
        if mtime == self._mtime:
            return
        try:
            cat = Catalog(self.path)
        except Exception:
            log.exception('catalogue at %s did not load', self.path)
            return
        self._cat, self._mtime = cat, mtime

    def get(self):
        if time.time() - self._checked > 5:
            with self.lock:
                self._checked = time.time()
                self._load()
        return self._cat

    def replace(self, data):
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self.path), suffix='.db')
        with os.fdopen(fd, 'wb') as f:
            f.write(data)
        try:
            cat = Catalog(tmp)  # refuse a file that doesn't load
        except Exception:
            os.remove(tmp)
            raise
        os.chmod(tmp, 0o640)
        os.replace(tmp, self.path)
        with self.lock:
            self._mtime = None
            self._load()
        return cat


class Limits:
    """Per-address lockout after repeated bad tokens, and per-route hourly budgets for the holder of the good token.
    The real token always passes the lockout: someone else's guessing must never lock its owner out."""

    def __init__(self, fails=20, window=600, budgets=None):
        self.fails, self.window = fails, window
        self.budgets = budgets or {}
        self.bad = defaultdict(deque)
        self.spent = defaultdict(deque)
        self.lock = threading.Lock()

    def note_bad(self, ip):
        with self.lock:
            q = self.bad[ip]
            q.append(time.time())
            while q and q[0] < time.time() - self.window:
                q.popleft()
            if len(self.bad) > 10000:  # never let the table itself become the attack
                self.bad.clear()

    def locked(self, ip):
        with self.lock:
            q = self.bad.get(ip)
            return bool(q) and len(q) >= self.fails and q[-1] > time.time() - self.window

    def spend(self, kind):
        """False when this kind of request is over its hourly budget."""
        limit = self.budgets.get(kind)
        if not limit:
            return True
        with self.lock:
            q = self.spent[kind]
            now = time.time()
            while q and q[0] < now - 3600:
                q.popleft()
            if len(q) >= limit:
                return False
            q.append(now)
            return True


class Base:
    """Routing, auth and validation shared by both servers."""
    max_body = API_BODY

    def __init__(self, token, name):
        token = (token or '').strip()
        if not token or len(token) < 32:
            raise SystemExit(f'{name} must be set (32+ characters): a missing token never means an open server')
        self.token = token.encode()

    def authorised(self, header):
        if not header or not header.startswith('Bearer '):
            return False
        return hmac.compare_digest(header[7:].strip().encode(), self.token)

    def routes(self):
        return []


class PublicApp(Base):
    def __init__(self, token, canvas, store, catalogs, limits=None, lyrics=None, genius=None):
        super().__init__(token, 'TELLY_TOKEN')
        self.canvas, self.store, self.catalogs, self.lyrics, self.genius = canvas, store, catalogs, lyrics, genius
        # The phone asks what's playing in a long poll (one at a time, each waiting up to 8 s), and a song's lyrics every
        # few seconds while its words are timed.
        self.limits = limits or Limits(budgets={'prompt': 60, 'change': 600, 'now': 1500, 'lyrics': 2400})

    def routes(self):
        return [
            ('GET', r'/livez', self.livez, False, None),
            ('GET', r'/api/status', self.status, True, None),
            ('GET', r'/api/day/(today|\d{4}-\d{2}-\d{2})', self.get_day, True, None),
            ('POST', r'/api/day/(today|\d{4}-\d{2}-\d{2})/prompt', self.prompt, True, 'prompt'),
            ('POST', r'/api/day/(today|\d{4}-\d{2}-\d{2})/lens', self.lens, True, 'change'),
            ('GET', r'/api/session/([0-9a-f]{10})', self.get_session, True, None),
            ('POST', r'/api/session/([0-9a-f]{10})/tweak', self.tweak, True, 'change'),
            ('POST', r'/api/session/([0-9a-f]{10})/edit', self.edit, True, 'change'),
            ('POST', r'/api/session/([0-9a-f]{10})/undo', self.undo, True, 'change'),
            ('POST', r'/api/session/([0-9a-f]{10})/sync', self.resync, True, 'change'),
            ('POST', r'/api/session/([0-9a-f]{10})/compose', self.recompose, True, 'change'),
            ('POST', r'/api/session/([0-9a-f]{10})/quick', self.quick, True, 'change'),
            ('DELETE', r'/api/session/([0-9a-f]{10})', self.delete, True, 'change'),
            ('GET', r'/api/library', self.library, True, None),
            ('GET', r'/api/now', self.now, True, 'now'),
            ('GET', r'/api/lyrics/([A-Za-z0-9_-]{1,64})', self.get_lyrics, True, 'lyrics'),
        ]

    def _day(self, d):
        if d == 'today':
            return self.canvas.place.today()
        import datetime as _dt
        try:
            _dt.date.fromisoformat(d)
        except ValueError:
            raise BadRequest('not a date') from None
        return d

    def livez(self, m, q, body):
        return 200, {'ok': True}

    def status(self, m, q, body):
        cat = self.catalogs.get()
        svc = self.store.get('svc_status') or {}
        return 200, {
            'catalog': {'songs': cat.size, 'new': len(cat.candidates), 'built_at': int(cat.meta.get('built_at', 0))} if cat else None,
            'llm': self.canvas.llm.ready, 'today': self.canvas.place.today(), 'place': self.canvas.place.name,
            'lyrics': bool(self.lyrics and self.lyrics.ready),
            'ytm': {k: v for k, v in (svc.get('ytm') or {'ok': False, 'error': 'not checked yet'}).items() if k in ('ok', 'account', 'error')},
            'spotify': {k: v for k, v in (svc.get('spotify') or {'ok': False, 'error': 'not checked yet'}).items() if k in ('ok', 'account', 'error')},
        }

    def get_day(self, m, q, body):
        return 200, self.canvas.day(self._day(m.group(1)))

    def prompt(self, m, q, body):
        text = body.get('text')
        if not isinstance(text, str) or not text.strip():
            return 400, {'error': 'say something'}
        focus = body.get('focus')
        focus = focus if isinstance(focus, str) and SID.match(focus) else None
        return 200, self.canvas.prompt(self._day(m.group(1)), text, focus)

    def lens(self, m, q, body):
        text = body.get('text')
        if not isinstance(text, (str, type(None))):
            return 400, {'error': 'text must be a string'}
        day = self._day(m.group(1))
        got = self.canvas.set_lens(day, text or '')
        return 200, dict(self.canvas.day(day), lens=got)

    def get_session(self, m, q, body):
        s = self.canvas.session(m.group(1))
        return (200, s) if s else (404, {'error': 'no such playlist'})

    def tweak(self, m, q, body):
        name = body.get('tweak')
        if name not in TWEAKS:
            return 400, {'error': f'tweak must be one of {sorted(TWEAKS)}'}
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        return 200, self.canvas.tweak(m.group(1), name)

    def edit(self, m, q, body):
        op, index, to = body.get('op'), body.get('index'), body.get('to')
        if op not in ('remove', 'swap', 'pin', 'unpin', 'move') or not isinstance(index, int) or isinstance(index, bool):
            return 400, {'error': 'op (remove|swap|pin|unpin|move) and index are required'}
        if to is not None and (not isinstance(to, int) or isinstance(to, bool)):
            return 400, {'error': 'to must be a position'}
        s = self.canvas.session(m.group(1))
        if not s:
            return 404, {'error': 'no such playlist'}
        track_id = body.get('id')
        if track_id is not None and (not isinstance(track_id, str) or len(track_id) > 300):
            return 400, {'error': 'id must be a song id'}
        if not 0 <= index < s['count'] and track_id is None:
            return 400, {'error': 'index out of range'}
        return 200, self.canvas.edit(m.group(1), op, index, to, track_id)

    def undo(self, m, q, body):
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        return 200, self.canvas.undo(m.group(1))

    def resync(self, m, q, body):
        s = self.canvas.resync(m.group(1))
        return (200, s) if s else (404, {'error': 'no such playlist'})

    def recompose(self, m, q, body):
        """Make a mix again (after it failed, or just because)."""
        s = self.canvas.recompose(m.group(1))
        return (200, s) if s else (404, {'error': 'no such playlist, or no composer'})

    def quick(self, m, q, body):
        """The engine's quick list instead, for when he can't wait for the mix."""
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        return 200, self.canvas.quick(m.group(1))

    def delete(self, m, q, body):
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        self.canvas.delete(m.group(1))
        return 200, {'ok': True}

    # -- what's playing, and its lyrics ------------------------------------------------------------------------------
    def now(self, m, q, body):
        """What the phone plays, as LeZeppLean's helper reports it. With ?since=<version>&wait=<s> the answer waits (up
        to 8 s) for something to change, so the phone hears of a new song, a pause or a seek at once without polling."""
        if not (self.lyrics and self.lyrics.ready):
            return 503, {'error': 'Lyrics are not set up on this server.'}
        try:
            since = int((q.get('since') or ['-1'])[0])
            wait = float((q.get('wait') or ['0'])[0])
        except ValueError:
            return 400, {'error': 'since and wait must be numbers'}
        if not (wait == wait and 0 <= wait <= 60):  # NaN, or nonsense
            return 400, {'error': 'wait must be 0-60'}
        try:
            return 200, self.lyrics.phone(since, wait)
        except LyricsError as e:
            log.warning('lyrics: %s', str(e)[:160])
            return 502, {'error': "Couldn't reach the lyrics service."}

    def get_lyrics(self, m, q, body):
        """A song's lyrics again, while its words are being timed. With ?known=<version> the lines are left out when
        they haven't changed (they are most of the answer); the timing status and the notes always come."""
        if not (self.lyrics and self.lyrics.ready):
            return 503, {'error': 'Lyrics are not set up on this server.'}
        try:
            got = self.lyrics.lyrics(m.group(1))
        except LyricsError as e:
            log.warning('lyrics: %s', str(e)[:160])
            return 502, {'error': "Couldn't reach the lyrics service."}
        return 200, self._dress(got, (q.get('known') or [''])[0][:40] or None)

    def _dress(self, got, known):
        song = got.get('song') or {}
        if song.get('title'):
            try:
                got['art'] = art.cover(self.store, song['title'], song.get('artist') or '')
            except Exception:
                log.exception('cover for %s', song.get('id'))
        if self.genius and song.get('title'):
            try:
                # Untimed words carry notes too: they're placed on the words, whatever their timing.
                on = got['lines'] or [{'text': t} for t in got.get('plain') or []]
                got['trivia'] = self.genius.trivia(song['title'], song.get('artist') or '', on)
            except Exception:
                log.exception('genius notes for %s', song.get('id'))
        if known and got.get('version') == known:
            got = dict(got, lines=[], plain=[], unchanged=True)
        return got

    def library(self, m, q, body):
        before = (q.get('before') or [''])[0]
        if not DAY.match(before):
            before = self.canvas.place.today()
        try:
            limit = max(1, min(60, int((q.get('limit') or ['14'])[0])))
        except ValueError:
            limit = 14
        return 200, self.canvas.library(before, limit)


class AdminApp(Base):
    """Inside the worker. Never routed from the internet (the ingress only forwards /api and /livez, and a
    NetworkPolicy admits only agentfest to this port)."""
    max_body = ADMIN_BODY

    def __init__(self, token, worker, vault, catalogs):
        super().__init__(token, 'TELLY_ADMIN_TOKEN')
        self.worker, self.vault, self.catalogs = worker, vault, catalogs
        self.limits = Limits()

    def routes(self):
        return [
            ('GET', r'/livez', lambda m, q, b: (200, {'ok': True}), False, None),
            ('GET', r'/admin/status', self.status, True, None),
            ('PUT', r'/admin/catalog', self.put_catalog, True, None),
            ('POST', r'/admin/ytm', self.put_ytm, True, None),
            ('POST', r'/admin/spotify', self.put_spotify, True, None),
        ]

    def status(self, m, q, body):
        st = self.worker.check_services()
        cat = self.catalogs.get()
        return 200, dict(st, catalog={'songs': cat.size, 'new': len(cat.candidates)} if cat else None)

    def put_catalog(self, m, q, raw):
        if not raw:
            return 400, {'error': 'send the catalogue file as the body'}
        try:
            cat = self.catalogs.replace(raw)
        except Exception as e:
            return 400, {'error': f'not a usable catalogue: {str(e)[:160]}'}
        return 200, {'ok': True, 'songs': cat.size, 'new': len(cat.candidates)}

    def put_ytm(self, m, q, body):
        """{"cookie": "<Cookie header>"} or {"cookies": [{"name","value"}, ...]} from a signed-in music.youtube.com."""
        ck = ytm_mod.cookies_from_header(body['cookie']) if isinstance(body.get('cookie'), str) else \
            {c['name']: c['value'] for c in body.get('cookies') or [] if isinstance(c, dict) and isinstance(c.get('name'), str)
             and isinstance(c.get('value'), str)}
        if not (ck.get('SAPISID') or ck.get('__Secure-3PAPISID')):
            return 400, {'error': 'no SAPISID cookie in what was sent'}
        try:
            who = ytm_mod.YTM(ck).whoami()
        except Exception as e:
            return 400, {'error': f'those cookies are not signed in: {str(e)[:120]}'}
        self.vault.put('ytm_cookies', ck)
        self.vault.put('ytm_rev', time.time())  # the worker rebuilds its client from this paste, not its old jar
        self.worker.check_services()
        return 200, {'ok': True, 'account': who}

    def put_spotify(self, m, q, body):
        if not all(isinstance(body.get(k), str) and body[k] for k in ('client_id', 'client_secret', 'refresh_token')):
            return 400, {'error': 'client_id, client_secret and refresh_token are required'}
        from .spotify import Spotify
        try:
            me = Spotify(body['client_id'], body['client_secret'], body['refresh_token']).me() or {}
        except Exception as e:
            return 400, {'error': f'Spotify refused: {str(e)[:160]}'}
        self.vault.put('spotify', {k: body[k] for k in ('client_id', 'client_secret', 'refresh_token')})
        self.worker.check_services()
        return 200, {'ok': True, 'account': me.get('display_name') or me.get('id')}


class BoundedServer(ThreadingHTTPServer):
    """A thread per request, but never more than `slots` at once: a flood gets its connections closed, not a thread
    each."""
    daemon_threads = True

    def __init__(self, addr, handler, slots=32):
        super().__init__(addr, handler)
        self.slots = threading.BoundedSemaphore(slots)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def client_ip(handler):
    """The caller's address. Only Traefik and agentfest can reach these pods (NetworkPolicy), and Traefik sets
    X-Real-Ip from the connection it accepted, so it can be believed here."""
    ip = (handler.headers.get('X-Real-Ip') or handler.client_address[0] or '?').strip()[:64]
    return ip if re.fullmatch(r'[0-9A-Fa-f:.]+', ip) else '?'


def make_handler(app):
    routes = [(meth, re.compile(f'^{rx}$'), fn, auth, kind) for meth, rx, fn, auth, kind in app.routes()]
    limits = getattr(app, 'limits', None)

    class Handler(BaseHTTPRequestHandler):
        server_version = 'telly'
        sys_version = ''
        protocol_version = 'HTTP/1.1'
        timeout = 20  # a client that stops sending is cut off, not waited on

        def log_message(self, fmt, *args):
            pass

        def version_string(self):
            return 'telly'  # no Python version on the wire

        def _send(self, code, payload, extra=None, close=False):
            data = json.dumps(payload, ensure_ascii=False).encode()
            if close:
                self.close_connection = True
            self.send_response(code)
            for k, v in HEADERS.items():
                self.send_header(k, v)
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            if close:
                self.send_header('Connection', 'close')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _handle(self, method):
            t0 = time.time()
            url = urllib.parse.urlsplit(self.path)
            code = 404
            try:
                if len(self.path) > 2048:
                    code = 414
                    return self._send(414, {'error': 'too long'}, close=True)
                for meth, rx, fn, auth, kind in routes:
                    m = rx.match(url.path)
                    if not m:
                        continue
                    if meth != method:
                        continue
                    if auth:
                        ip = client_ip(self)
                        if not app.authorised(self.headers.get('Authorization')):
                            if limits:
                                limits.note_bad(ip)
                                if limits.locked(ip):
                                    code = 429
                                    return self._send(429, {'error': 'too many bad tokens; wait a few minutes'}, {'Retry-After': '600'}, close=True)
                            code = 401
                            log.warning('bad token from %s for %s', ip, url.path)
                            return self._send(401, {'error': 'unauthorised'}, {'WWW-Authenticate': 'Bearer'}, close=True)
                        if limits and kind and not limits.spend(kind):
                            code = 429
                            return self._send(429, {'error': 'That is a lot of changes for one hour. Give it a little while.'},
                                              {'Retry-After': '300'}, close=True)
                    n = self.headers.get('Content-Length')
                    try:
                        n = int(n or 0)
                    except ValueError:
                        code = 400
                        return self._send(400, {'error': 'bad length'}, close=True)
                    if n < 0 or n > app.max_body:
                        code = 413
                        return self._send(413, {'error': 'too large'}, close=True)
                    if self.headers.get('Transfer-Encoding'):
                        code = 411
                        return self._send(411, {'error': 'send a Content-Length'}, close=True)
                    raw = self.rfile.read(n) if n else b''
                    if url.path == '/admin/catalog':
                        body = raw
                    else:
                        try:
                            body = json.loads(raw) if raw else {}
                        except ValueError:
                            code = 400
                            return self._send(400, {'error': 'body must be JSON'})
                        if not isinstance(body, dict):
                            code = 400
                            return self._send(400, {'error': 'body must be a JSON object'})
                    try:
                        code, payload = fn(m, urllib.parse.parse_qs(url.query, max_num_fields=10), body)
                    except NoCatalog as e:
                        code, payload = 503, {'error': str(e)}
                    except BadRequest as e:
                        code, payload = 400, {'error': str(e)}
                    return self._send(code, payload)
                code = 404
                self._send(404, {'error': 'not found'}, close=True)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass
            except Exception:
                log.exception('%s %s', method, url.path)
                code = 500
                try:
                    self._send(500, {'error': 'something broke on the server'})
                except Exception:
                    pass
            finally:
                if url.path != '/livez':
                    log.info('%s %s %s %dms', method, url.path, code, (time.time() - t0) * 1000)

        def do_GET(self):
            self._handle('GET')

        def do_POST(self):
            self._handle('POST')

        def do_PUT(self):
            self._handle('PUT')

        def do_DELETE(self):
            self._handle('DELETE')

    return Handler


def make_server(app, host, port, slots=32):
    return BoundedServer((host, port), make_handler(app), slots=slots)


def serve(app, host, port, slots=32):
    httpd = make_server(app, host, port, slots)
    log.info('telly %s listening on %s:%d', type(app).__name__, host, httpd.server_address[1])
    httpd.serve_forever()
