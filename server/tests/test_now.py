"""What's playing, its lyrics (from a stand-in LeZeppLean) and Genius's notes placed on its lines."""
import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from test_telly import Base, start

from telly.api import Catalogs, Limits, PublicApp
from telly.canvas import Canvas
from telly.context import Place
from telly.genius import Genius, clean, pick, place
from telly.llm import LLM
import telly.art
from telly.lyrics import Lyrics
from telly.store import Store

LEZ_TOKEN = 'lez-token'


class FakeLeZeppLean(BaseHTTPRequestHandler):
    """The phone, as LeZeppLean's helper reports it (GET /phone/next waits for a change), and POST /lyrics."""
    version, song_id, title = 7, 'p0123456789ab', 'Bohemian Rhapsody'
    changed = threading.Event()
    lines = [[1200, 'Is this the real life?', [1200, 1500, 1800, 2300, 2600], 4000], [5000, 'Is this just fantasy?', None, None]]

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        if self.headers.get('Authorization') != f'Bearer {LEZ_TOKEN}':
            self._send(401, {'detail': 'bad or missing token'})
            return False
        return True

    def do_POST(self):
        if not self._authed():
            return
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length') or 0)) or b'{}')
        cls = type(self)
        if self.path == '/phone':
            self._send(410, {'detail': 'telly must never report for the phone'})
        elif self.path == '/lyrics':
            if body.get('song') != 'p0123456789ab':
                return self._send(200, {'ok': False, 'reason': 'unknown_song'})
            self._send(200, {'ok': True, 'song': {'id': 'p0123456789ab', 'title': 'Bohemian Rhapsody', 'artist': 'Queen', 'album': None, 'durationMs': 354000},
                             'lyrics': {'version': 'v1', 'kind': 'synced', 'lines': cls.lines}, 'words': {'status': 'pending', 'aligned': 1, 'of': 2}})
        else:
            self._send(404, {})

    def do_GET(self):
        if not self._authed():
            return
        cls = type(self)
        if self.path.startswith('/phone/next'):
            q = dict(x.split('=') for x in self.path.split('?', 1)[1].split('&'))
            if int(q['since']) == cls.version:
                cls.changed.wait(float(q['wait']))  # nothing new: wait for the helper's next report
                cls.changed.clear()
            self._send(200, {'version': cls.version, 'app': 'com.google.android.apps.youtube.music', 'title': cls.title, 'artist': 'Queen',
                             'durationMs': 354000, 'playing': True, 'active': True, 'stale': False, 'positionMs': 61250,
                             'speed': 1.0, 'songId': cls.song_id, 'ageMs': 900, 'heard': None})
        else:
            self._send(404, {})


class FakeGenius(Genius):
    """Genius as it would answer, without the network or the model."""
    looks = 0

    def look(self, title, artist):
        FakeGenius.looks += 1
        return {'id': 1063, 'url': 'https://genius.com/Queen-bohemian-rhapsody-lyrics', 'title': 'Bohemian Rhapsody', 'about': 'x' * 50,
                'released': None, 'links': [], 'refs': []}

    def condense(self, title, artist, page):
        return {'about': ['It was the first single from A Night at the Opera.'],
                'refs': [{'fragment': 'Is this just fantasy?', 'roman': None, 'text': 'The ballad section begins with this question.'}]}


class GeniusTest(unittest.TestCase):
    def test_a_note_sits_where_its_fragment_begins_once(self):
        lines = [{'text': 'Is this the real life?'}, {'text': 'Is this just fantasy?'}, {'text': 'Mama, just killed a man'},
                 {'text': 'Kar de mujhe mujhse hi rihaa'}, {'text': 'Mama, just killed a man'}]
        refs = [{'fragment': 'Mama, just killed a man\n Put a gun against his head', 'text': 'A'},
                {'fragment': '[Intro]', 'text': 'B'},
                {'fragment': 'कर दे मुझे मुझसे ही रिहा', 'roman': 'Kar de mujhe mujhse hi riha', 'text': 'C'},
                {'fragment': 'Is this the real life? Is this just fantasy?', 'text': 'D'},
                {'fragment': 'Something else entirely, never sung', 'text': 'E'}]
        self.assertEqual([{'line': 0, 'text': 'D'}, {'line': 2, 'text': 'A'}, {'line': 3, 'text': 'C'}], place(refs, lines))
        # a fragment that begins with its section's name begins at its first sung line
        self.assertEqual([{'line': 2, 'text': 'F'}], place([{'fragment': '[Verse 1]\nMama, just killed a man', 'text': 'F'}], lines))

    def test_the_song_playing_is_looked_up_first_and_skipped_ones_are_let_go(self):
        import telly.genius as G
        order = []
        kv = type('KV', (), {'get': lambda s, k, d=None: None, 'put': lambda s, k, v: None})()

        class Slow(FakeGenius):
            def look(self, title, artist):
                order.append(title)
                time.sleep(0.2)
                return None
        g = Slow(kv, None)
        g.trivia('First', 'A', [])          # starts at once
        deadline = time.time() + 5
        while g.working != G.Genius.key('First', 'A') and time.time() < deadline:
            time.sleep(0.01)
        g.trivia('Skipped', 'B', [])        # waits...
        g.asked[G.Genius.key('Skipped', 'B')] -= 60  # ...and nobody has asked about it for a minute
        g.trivia('Playing now', 'C', [])    # the newest
        g.trivia('Older', 'D', [])
        g.asked[G.Genius.key('Older', 'D')] -= 5
        self.assertTrue(g.idle())
        self.assertEqual(['First', 'Playing now', 'Older'], order)

    def test_the_song_itself_not_a_translation_or_a_namesake(self):
        hit = lambda i, t, a, views: {'type': 'song', 'result': {'id': i, 'title': t, 'artist_names': a, 'primary_artist': {'name': a},
                                                                  'stats': {'pageviews': views}}}
        hits = [hit(1, 'Tujhe Dekha To (Romanized)', 'Genius Romanizations', 900000), hit(2, 'Tujhe Dekha To', 'Kumar Sanu & Lata Mangeshkar', 5000),
                hit(3, 'Tujhe Dekha To', 'Some Cover Band', 90000)]
        self.assertEqual(2, pick(hits, 'Tujhe Dekha To (From "Dilwale Dulhania Le Jayenge")', 'Lata Mangeshkar, Kumar Sanu')['id'])
        self.assertIsNone(pick(hits[:1], 'Tujhe Dekha To', 'Kumar Sanu'))

    def test_quotes_are_unwrapped_only_when_they_wrap_everything(self):
        self.assertEqual('"Be," he said', clean('"Be," he said'))
        self.assertEqual('whole', clean(' "whole" '))

    def test_looked_up_once_in_the_background_then_kept(self):
        store = {}
        kv = type('KV', (), {'get': lambda s, k, d=None: store.get(k, d), 'put': lambda s, k, v: store.__setitem__(k, v)})()
        g = FakeGenius(kv, None)
        FakeGenius.looks = 0
        lines = [{'text': 'Is this the real life?'}, {'text': 'Is this just fantasy?'}]
        self.assertEqual('pending', g.trivia('Bohemian Rhapsody', 'Queen', lines)['status'])
        self.assertTrue(g.idle())
        got = g.trivia('Bohemian Rhapsody - Remastered 2011', 'Queen', lines)
        self.assertEqual('ready', got['status'], 'the same song under a remaster title is the same page')
        self.assertEqual([{'line': 1, 'text': 'The ballad section begins with this question.'}], got['lines'])
        self.assertEqual(1, FakeGenius.looks)


class NowApiTest(Base):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        lez = ThreadingHTTPServer(('127.0.0.1', 0), FakeLeZeppLean)
        threading.Thread(target=lez.serve_forever, daemon=True).start()
        store = Store(os.path.join(cls.tmp.name, 'now.db'))
        cats = Catalogs(cls.path)
        canvas = Canvas(store, cats, Place(), LLM('http://127.0.0.1:9', 'k', 'm'))
        canvas.place.forecast = lambda: None
        cls.token = 'x' * 40
        lyrics = Lyrics(f'http://127.0.0.1:{lez.server_address[1]}', LEZ_TOKEN)
        cls.genius = FakeGenius(store, None)
        cls.PORT = start(PublicApp(cls.token, canvas, store, cats, limits=Limits(budgets={'now': 4, 'lyrics': 100}), lyrics=lyrics, genius=cls.genius))
        cls.BARE = start(PublicApp(cls.token, canvas, store, cats))
        cls.real_cover, telly.art.cover = telly.art.cover, lambda store, title, artist: 'https://covers.example/rhapsody.jpg'

    @classmethod
    def tearDownClass(cls):
        telly.art.cover = cls.real_cover
        super().tearDownClass()

    def req(self, method, path, body=None, port=None):
        r = urllib.request.Request(f'http://127.0.0.1:{port or self.PORT}{path}', method=method,
                                   data=json.dumps(body).encode() if body is not None else None, headers={'Authorization': f'Bearer {self.token}'})
        try:
            with urllib.request.urlopen(r, timeout=30) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b'{}')

    def test_now_playing_to_word_timed_lyrics_and_notes(self):
        code, now = self.req('GET', '/api/now?since=-1&wait=0')
        self.assertEqual(200, code)
        self.assertEqual(('Bohemian Rhapsody', 'p0123456789ab', True, 61250), (now['title'], now['songId'], now['playing'], now['positionMs']))
        started = time.time()
        threading.Timer(0.3, FakeLeZeppLean.changed.set).start()  # the helper says something
        self.assertEqual(200, self.req('GET', f"/api/now?since={now['version']}&wait=5")[0])
        self.assertLess(time.time() - started, 3, 'a long poll answers when the phone changes, not when it times out')
        code, got = self.req('GET', f"/api/lyrics/{now['songId']}")
        self.assertEqual(200, code)
        self.assertEqual('https://covers.example/rhapsody.jpg', got['art'])
        self.assertEqual({'start': 1200, 'text': 'Is this the real life?', 'words': [1200, 1500, 1800, 2300, 2600], 'end': 4000}, got['lines'][0])
        self.assertIsNone(got['lines'][1]['words'], 'a line the aligner is unsure of is timed by the line')
        self.assertEqual('pending', got['trivia']['status'])
        self.assertTrue(self.genius.idle())
        code, again = self.req('GET', '/api/lyrics/p0123456789ab?known=v1')
        self.assertEqual((200, [], True), (code, again['lines'], again['unchanged']), 'lines the phone has are not sent again')
        self.assertEqual([{'line': 1, 'text': 'The ballad section begins with this question.'}], again['trivia']['lines'])
        self.assertEqual(400, self.req('GET', '/api/now?since=x')[0])
        self.assertEqual(400, self.req('GET', '/api/now?wait=nan')[0])
        self.assertEqual(429, self.req('GET', '/api/now?since=-1&wait=0')[0], 'asking what plays has an hourly budget (4 here)')

    def test_without_lyrics_configured_it_says_so(self):
        self.assertEqual(503, self.req('GET', '/api/now', port=self.BARE)[0])
        self.assertFalse(self.req('GET', '/api/status', port=self.BARE)[1]['lyrics'])


if __name__ == '__main__':
    unittest.main()
