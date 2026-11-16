"""HTTP: a small JSON API for the app, and two admin routes for the catalogue and the music-service sign-ins.
Every route but /livez needs `Authorization: Bearer <TELLY_TOKEN>`."""
import hmac
import json
import logging
import os
import re
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .canvas import NoCatalog
from .catalog import Catalog
from .engine import TWEAKS
from . import ytm as ytm_mod

log = logging.getLogger('telly')
DAY = re.compile(r'^\d{4}-\d{2}-\d{2}$')
MAX_BODY = 64 << 20


class Catalogs:
    """The current catalogue, swappable while serving."""

    def __init__(self, path):
        self.path, self._cat, self.lock = path, None, threading.Lock()
        if os.path.exists(path):
            try:
                self._cat = Catalog(path)
            except Exception:
                log.exception('catalogue at %s did not load', path)

    def get(self):
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
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.path)
        with self.lock:
            self._cat = Catalog(self.path)
        return cat


class App:
    def __init__(self, token, canvas, services, catalogs, store):
        if not token or len(token) < 16:
            raise SystemExit('TELLY_TOKEN must be set (16+ characters): a missing token never means an open server')
        self.token, self.canvas, self.services, self.catalogs, self.store = token.encode(), canvas, services, catalogs, store

    def routes(self):
        return [
            ('GET', r'/livez', self.livez, False),
            ('GET', r'/api/status', self.status, True),
            ('GET', r'/api/day/(today|\d{4}-\d{2}-\d{2})', self.get_day, True),
            ('POST', r'/api/day/(today|\d{4}-\d{2}-\d{2})/prompt', self.prompt, True),
            ('GET', r'/api/session/([0-9a-f]{10})', self.get_session, True),
            ('POST', r'/api/session/([0-9a-f]{10})/tweak', self.tweak, True),
            ('POST', r'/api/session/([0-9a-f]{10})/edit', self.edit, True),
            ('POST', r'/api/session/([0-9a-f]{10})/undo', self.undo, True),
            ('POST', r'/api/session/([0-9a-f]{10})/sync', self.resync, True),
            ('DELETE', r'/api/session/([0-9a-f]{10})', self.delete, True),
            ('GET', r'/api/library', self.library, True),
            ('PUT', r'/admin/catalog', self.put_catalog, True),
            ('POST', r'/admin/ytm', self.put_ytm, True),
            ('POST', r'/admin/spotify', self.put_spotify, True),
        ]

    def authorised(self, header):
        if not header or not header.startswith('Bearer '):
            return False
        return hmac.compare_digest(header[7:].strip().encode(), self.token)

    # -- handlers: (match, query, body) -> (status, payload) ---------------------------------------------------------
    def _day(self, d):
        return self.canvas.place.today() if d == 'today' else d

    def livez(self, m, q, body):
        return 200, {'ok': True}

    def status(self, m, q, body):
        cat = self.catalogs.get()
        out = {'catalog': {'songs': cat.size, 'new': len(cat.candidates), 'built_at': int(cat.meta.get('built_at', 0))} if cat else None,
               'llm': self.canvas.llm.ready, 'today': self.canvas.place.today(), 'place': self.canvas.place.name}
        y = self.services.ytm()
        if y:
            try:
                out['ytm'] = {'ok': True, 'account': y.whoami()}
            except ytm_mod.AuthError as e:
                out['ytm'] = {'ok': False, 'error': str(e)}
            except Exception as e:
                out['ytm'] = {'ok': False, 'error': str(e)[:160]}
        else:
            out['ytm'] = {'ok': False, 'error': 'not connected'}
        sp = self.services.spotify()
        if sp:
            try:
                me = sp.me() or {}
                out['spotify'] = {'ok': True, 'account': me.get('display_name') or me.get('id')}
            except Exception as e:
                out['spotify'] = {'ok': False, 'error': str(e)[:160]}
        else:
            out['spotify'] = {'ok': False, 'error': 'not connected'}
        return 200, out

    def get_day(self, m, q, body):
        return 200, self.canvas.day(self._day(m.group(1)))

    def prompt(self, m, q, body):
        text = (body or {}).get('text', '')
        if not isinstance(text, str) or not text.strip():
            return 400, {'error': 'say something'}
        return 200, self.canvas.prompt(self._day(m.group(1)), text, (body or {}).get('focus'))

    def get_session(self, m, q, body):
        s = self.canvas.session(m.group(1))
        return (200, s) if s else (404, {'error': 'no such playlist'})

    def tweak(self, m, q, body):
        name = (body or {}).get('tweak')
        if name not in TWEAKS:
            return 400, {'error': f'tweak must be one of {sorted(TWEAKS)}'}
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        return 200, self.canvas.tweak(m.group(1), name)

    def edit(self, m, q, body):
        b = body or {}
        if b.get('op') not in ('remove', 'swap', 'pin', 'unpin', 'move') or not isinstance(b.get('index'), int):
            return 400, {'error': 'op (remove|swap|pin|unpin|move) and index are required'}
        s = self.canvas.session(m.group(1))
        if not s:
            return 404, {'error': 'no such playlist'}
        if not 0 <= b['index'] < s['count']:
            return 400, {'error': 'index out of range'}
        return 200, self.canvas.edit(m.group(1), b['op'], b['index'], b.get('to'))

    def undo(self, m, q, body):
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        return 200, self.canvas.undo(m.group(1))

    def resync(self, m, q, body):
        s = self.canvas.resync(m.group(1))
        return (200, s) if s else (404, {'error': 'no such playlist'})

    def delete(self, m, q, body):
        if not self.canvas.session(m.group(1)):
            return 404, {'error': 'no such playlist'}
        self.canvas.delete(m.group(1))
        return 200, {'ok': True}

    def library(self, m, q, body):
        before = (q.get('before') or [''])[0]
        if not DAY.match(before):
            before = self.canvas.place.today()
        try:
            limit = max(1, min(60, int((q.get('limit') or ['14'])[0])))
        except ValueError:
            limit = 14
        return 200, self.canvas.library(before, limit)

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
        b = body or {}
        ck = ytm_mod.cookies_from_header(b['cookie']) if isinstance(b.get('cookie'), str) else \
            {c['name']: c['value'] for c in b.get('cookies') or [] if isinstance(c, dict) and c.get('name')}
        if not (ck.get('SAPISID') or ck.get('__Secure-3PAPISID')):
            return 400, {'error': 'no SAPISID cookie in what was sent'}
        try:
            who = ytm_mod.YTM(ck).whoami()
        except Exception as e:
            return 400, {'error': f'those cookies are not signed in: {str(e)[:120]}'}
        self.store.put('ytm_cookies', ck)
        return 200, {'ok': True, 'account': who}

    def put_spotify(self, m, q, body):
        b = body or {}
        if not all(isinstance(b.get(k), str) and b[k] for k in ('client_id', 'client_secret', 'refresh_token')):
            return 400, {'error': 'client_id, client_secret and refresh_token are required'}
        from .spotify import Spotify
        try:
            me = Spotify(b['client_id'], b['client_secret'], b['refresh_token']).me() or {}
        except Exception as e:
            return 400, {'error': f'Spotify refused: {str(e)[:160]}'}
        self.store.put('spotify', {k: b[k] for k in ('client_id', 'client_secret', 'refresh_token')})
        return 200, {'ok': True, 'account': me.get('display_name') or me.get('id')}


def serve(app, host, port):
    routes = [(meth, re.compile(f'^{rx}$'), fn, auth) for meth, rx, fn, auth in app.routes()]

    class Handler(BaseHTTPRequestHandler):
        server_version = 'telly'
        sys_version = ''
        protocol_version = 'HTTP/1.1'

        def log_message(self, fmt, *args):
            pass

        def _send(self, code, payload):
            data = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(data)

        def _handle(self, method):
            t0 = time.time()
            url = urllib.parse.urlsplit(self.path)
            code = 404
            try:
                for meth, rx, fn, auth in routes:
                    m = rx.match(url.path)
                    if not m or meth != method:
                        continue
                    if auth and not app.authorised(self.headers.get('Authorization')):
                        code = 401
                        return self._send(401, {'error': 'unauthorised'})
                    n = int(self.headers.get('Content-Length') or 0)
                    if n > MAX_BODY:
                        code = 413
                        return self._send(413, {'error': 'too large'})
                    raw = self.rfile.read(n) if n else b''
                    if url.path == '/admin/catalog':
                        body = raw
                    else:
                        try:
                            body = json.loads(raw) if raw else None
                        except ValueError:
                            code = 400
                            return self._send(400, {'error': 'body must be JSON'})
                    try:
                        code, payload = fn(m, urllib.parse.parse_qs(url.query), body)
                    except NoCatalog as e:
                        code, payload = 503, {'error': str(e)}
                    return self._send(code, payload)
                code = 404
                self._send(404, {'error': 'not found'})
            except BrokenPipeError:
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

    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.daemon_threads = True
    log.info('telly listening on %s:%d', host, port)
    httpd.serve_forever()
