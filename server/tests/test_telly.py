"""Behaviour tests on a small synthetic catalogue (no network, no language model). Run: cd server && python -m unittest -v"""
import json
import os
import random
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from telly import engine as E  # noqa: E402
from telly.api import AdminApp, Catalogs, Limits, PublicApp, make_server  # noqa: E402
from telly.canvas import Canvas  # noqa: E402
from telly.catalog import Catalog  # noqa: E402
from telly.context import Place  # noqa: E402
from telly.llm import LLM  # noqa: E402
from telly.store import Store  # noqa: E402
from telly.ytm import best_match, parse_rows  # noqa: E402

NOW = time.time()
DAY = 86400
WORLDS = ['hindi', 'roots', 'indie', 'pop', 'rock', 'rap', 'night', 'edm', 'sound']
ORIGIN = {'hindi': 'Hindi/Indian', 'roots': 'Bengali'}


def make_catalog(path, seed=1):
    """600 known songs across nine worlds (favourites, well known, forgotten, warm), 150 new candidates.
    Bengali (roots) songs are all calm, so a run's energy floor rules every one of them out."""
    rng = random.Random(seed)
    con = sqlite3.connect(path)
    cols = ['id', 'title', 'artist', 'artist_key', 'spotify_uri', 'ytm_vid', 'duration_ms', 'plays', 'days_played', 'first_ts', 'last_ts',
            'sp_streams', 'sp_skips', 'liked', 'world', 'origin', 'tempo', 'energy', 'valence', 'danceability', 'acousticness',
            'instrumentalness', 'speechiness']
    ccols = ['id', 'title', 'artist', 'artist_key', 'spotify_uri', 'duration_ms', 'world', 'origin', 'kind', 'playcount', 'monthly',
             'adjacency', 'artist_aff', 'tempo', 'energy', 'valence', 'danceability', 'acousticness', 'instrumentalness', 'speechiness']
    con.execute(f"CREATE TABLE tracks ({', '.join(cols)})")
    con.execute(f"CREATE TABLE candidates ({', '.join(ccols)})")
    con.execute('CREATE TABLE world_mix (context TEXT, world TEXT, share REAL)')
    con.execute('CREATE TABLE meta (k TEXT, v TEXT)')
    for i in range(600):
        w = WORLDS[i % 9]
        artist = f'Artist {w} {i % 40}'
        kind = i % 4
        plays = [30, 7, 12, 2][kind]
        last = NOW - [60, 30, 800, 5][kind] * DAY
        energy = rng.uniform(0.2, 0.5) if w == 'roots' else rng.uniform(0.3, 0.95)
        con.execute(f"INSERT INTO tracks VALUES ({','.join('?' * len(cols))})", (
            f'{artist.lower()}|song {i}', f'Song {i}', artist, artist.lower(), f'spotify:track:{i}', f'vid{i:07d}', 200_000 + (i % 7) * 10_000,
            plays, max(1, plays // 2), NOW - 900 * DAY if kind != 3 else NOW - 20 * DAY, last, plays, 0, 0, w, ORIGIN.get(w, 'Western/other'),
            rng.uniform(80, 170), energy, 0.5, rng.uniform(0.4, 0.9), 0.2, 0.0, 0.05))
    for i in range(150):
        w = WORLDS[i % 9]
        artist = f'New {w} {i % 30}'
        con.execute(f"INSERT INTO candidates VALUES ({','.join('?' * len(ccols))})", (
            f'{artist.lower()}|fresh {i}', f'Fresh {i}', artist, artist.lower(), f'spotify:track:n{i}', 210_000, w, ORIGIN.get(w, 'Western/other'),
            'new_known' if i % 2 else 'new_frontier', 10 ** rng.uniform(5, 8), 100000, 1.0, 2.0, rng.uniform(80, 170),
            rng.uniform(0.3, 0.95), 0.5, rng.uniform(0.4, 0.9), 0.2, 0.0, 0.05))
    for ctx in ['Run', 'Walk', 'Ride', 'all'] + [f'h{h:02d}' for h in range(24)]:
        for w in WORLDS:
            con.execute('INSERT INTO world_mix VALUES (?,?,?)', (ctx, w, 1 / 9))
    con.executemany('INSERT INTO meta VALUES (?,?)', [('about', 'A listener.'), ('built_at', str(int(NOW)))])
    con.commit()
    con.close()


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = os.path.join(cls.tmp.name, 'catalog.db')
        make_catalog(cls.path)
        cls.cat = Catalog(cls.path)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def build(self, **brief):
        b = E.normalize(dict({'seed': 42}, **brief))
        order, _ = E.rebuild(self.cat, b, 18, [], {}, now=NOW)
        return b, order


class EngineTest(Base):
    def test_a_run_fills_its_time_with_energetic_songs_opened_and_closed_by_songs_he_knows(self):
        b, order = self.build(activity='run', minutes=40)
        total = E.duration_ms(order) / 60000
        self.assertGreaterEqual(total, 33)
        self.assertLessEqual(total, 42)
        self.assertFalse(order[0]['pool'].startswith('new'))
        self.assertIn(order[-1]['pool'], ('comfort', 'familiar', 'forgotten', 'warm'))
        # nothing calm: a run's floor holds for every song it chose by itself
        self.assertTrue(all((t.get('energy') or 1) >= 0.6 for t in order), [t.get('energy') for t in order])

    def test_more_familiar_means_fewer_new_songs(self):
        _, low = self.build(activity='walk', minutes=60, familiar=0.4)
        _, high = self.build(activity='walk', minutes=60, familiar=0.95)
        new = lambda o: sum(1 for t in o if t['pool'].startswith('new'))
        self.assertGreater(new(low), new(high))

    def test_a_tweak_keeps_most_of_the_playlist(self):
        b, order = self.build(activity='walk', minutes=45)
        b2 = E.tweak(b, 'familiar')
        order2, _ = E.rebuild(self.cat, b2, 18, order, {}, now=NOW)
        kept = len({t['id'] for t in order} & {t['id'] for t in order2})
        self.assertGreaterEqual(kept, len(order) // 2, 'one tap should not reshuffle everything')

    def test_reshuffle_changes_the_pick(self):
        b, order = self.build(activity='walk', minutes=45)
        b2 = E.tweak(b, 'reshuffle')
        order2, _ = E.rebuild(self.cat, b2, 18, order, {}, now=NOW, sticky=False)
        self.assertNotEqual([t['id'] for t in order], [t['id'] for t in order2])

    def test_some_bengali_is_guaranteed_even_where_the_run_rules_rule_it_out(self):
        _, plain = self.build(activity='run', minutes=40)
        self.assertEqual(0, sum(1 for t in plain if t.get('origin') == 'Bengali'))  # the floor really excludes them
        _, wished = self.build(activity='run', minutes=40, want=[{'origin': 'Bengali', 'count': 3}])
        self.assertEqual(3, sum(1 for t in wished if t.get('origin') == 'Bengali'))
        self.assertEqual(len(plain), len(wished), 'wished-for songs replace others rather than lengthening the run')

    def test_excluded_artists_and_zeroed_worlds_never_appear(self):
        _, order = self.build(activity='walk', minutes=60, worlds={'rock': 0}, exclude_artists=['Artist pop 3'])
        self.assertFalse(any(t['world'] == 'rock' for t in order))
        self.assertFalse(any(t['artist_key'] == 'artist pop 3' for t in order))

    def test_edits_by_title_and_honest_notes(self):
        b, order = self.build(activity='walk', minutes=30)
        bl = E.Builder(self.cat, b, 18, now=NOW, prev=order)
        first = order[2]
        notes = []
        out, b2 = E.apply_ops(self.cat, b, order, [{'op': 'move', 'title': first['title'], 'to': 0},
                                                    {'op': 'remove', 'title': 'No Such Song'}], bl, notes)
        self.assertEqual(first['id'], out[0]['id'])
        self.assertEqual(1, len(notes))
        added, _ = E.apply_ops(self.cat, b, order, [{'op': 'add', 'title': 'Song 10', 'artist': 'Artist roots 10', 'to': 0}], bl, [])
        self.assertEqual('Song 10', added[0]['title'])
        self.assertTrue(added[0]['pinned'])
        swapped, b3 = E.apply_ops(self.cat, b, order, [{'op': 'swap', 'index': 1}], bl, [])
        self.assertNotEqual(order[1]['id'], swapped[1]['id'])
        self.assertIn(order[1]['id'], b3['avoid'])

    def test_several_pins_all_keep_their_places(self):
        b, order = self.build(activity='walk', minutes=50)
        for i in (1, 4, 6):
            order[i]['pinned'] = True
        for seed in range(12):
            b2 = E.tweak(dict(b, seed=seed), 'calmer')
            order2, _ = E.rebuild(self.cat, b2, 18, order, {}, now=NOW)
            self.assertEqual([order[i]['id'] for i in (1, 4, 6)], [order2[i]['id'] for i in (1, 4, 6)])

    def test_a_failed_swap_keeps_the_song_and_does_not_ban_it(self):
        b, order = self.build(activity='walk', minutes=30)
        bl = E.Builder(self.cat, b, 18, now=NOW, prev=order)
        bl.replacement = lambda *a, **k: None  # nothing else fits
        notes = []
        out, b2 = E.apply_ops(self.cat, b, order, [{'op': 'swap', 'index': 2}], bl, notes)
        self.assertEqual(order[2]['id'], out[2]['id'])
        self.assertNotIn(order[2]['id'], b2['avoid'])
        self.assertTrue(notes and 'stays' in notes[0])

    def test_pinned_songs_survive_a_rebuild_in_place(self):
        b, order = self.build(activity='walk', minutes=45)
        order[3]['pinned'] = True
        b2 = E.tweak(b, 'calmer')
        order2, _ = E.rebuild(self.cat, b2, 18, order, {}, now=NOW)
        self.assertEqual(order[3]['id'], order2[3]['id'])


class YtmParseTest(unittest.TestCase):
    def row(self, vid, title, artist, dur):
        return {'playlistItemData': {'videoId': vid, 'playlistSetVideoId': 'S' + vid},
                'flexColumns': [{'musicResponsiveListItemFlexColumnRenderer': {'text': {'runs': [{'text': title}]}}},
                                {'musicResponsiveListItemFlexColumnRenderer': {'text': {'runs': [
                                    {'text': artist, 'navigationEndpoint': {'browseEndpoint': {'browseEndpointContextSupportedConfigs': {
                                        'browseEndpointContextMusicConfig': {'pageType': 'MUSIC_PAGE_TYPE_ARTIST'}}}}},
                                    {'text': ' • '}, {'text': dur}]}}}],
                'thumbnail': {'musicThumbnailRenderer': {'thumbnail': {'thumbnails': [{'url': 'https://lh3.googleusercontent.com/x=w60-h60-l90-rj'}]}}}}

    def test_rows_parse_and_the_song_beats_its_remix(self):
        rows = [parse_rows(self.row('a', 'Kun Faya Kun (Lofi Remix)', 'Someone', '3:10')), parse_rows(self.row('b', 'Kun Faya Kun', 'A.R. Rahman', '7:51'))]
        self.assertEqual(('b', 471, 'Sb'), (rows[1]['videoId'], rows[1]['duration_s'], rows[1]['setVideoId']))
        self.assertTrue(rows[1]['thumb'].endswith('=w544-h544-l90-rj'))
        self.assertEqual('b', best_match({'title': 'Kun Faya Kun', 'artist': 'A.R. Rahman', 'duration_ms': 473000}, rows)['videoId'])
        self.assertIsNone(best_match({'title': 'Something Else Entirely', 'artist': 'Nobody'}, rows))


class StorySourceTest(unittest.TestCase):
    def test_a_source_must_name_the_song_or_the_artist(self):
        from telly.story import relevant
        teri = {'title': 'Teri Ay', 'artist': 'Umer Farooq'}
        # what actually happened: a search for the song returned a ghazal singer's page, and 'ay' matched inside words
        self.assertFalse(relevant(teri, ('Munni Begum', 'Munni Begum (born Nadira Begum) is a Pakistani ghazal singer of the Ayub era.')))
        self.assertTrue(relevant(teri, ('Umer Farooq (musician)', 'Umer Farooq is a Pakistani singer-songwriter.')))
        self.assertTrue(relevant({'title': 'Kun Faya Kun', 'artist': 'A.R. Rahman'}, ('Kun Faya Kun', 'Kun Faya Kun is a Sufi song from Rockstar.')))


def start(app, slots=32):
    """Serve on a free port (so parallel runs can't collide) and return it."""
    httpd = make_server(app, '127.0.0.1', 0, slots)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd.server_address[1]


class ApiTest(Base):
    """The public HTTP surface, with no language model reachable (the rules stand in)."""

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.store = Store(os.path.join(cls.tmp.name, 'telly.db'))
        cats = Catalogs(cls.path)
        cls.canvas = Canvas(cls.store, cats, Place(), LLM('http://127.0.0.1:9', 'k', 'm'))
        cls.canvas.place.forecast = lambda: None  # no network in tests
        cls.token = 'x' * 40
        cls.limits = Limits(fails=5, window=600, budgets={'prompt': 50, 'change': 600})
        app = PublicApp(cls.token, cls.canvas, cls.store, cats, limits=cls.limits)
        cls.PORT = start(app)

    def req(self, method, path, body=None, token=None, raw=None, headers=None):
        tok = self.token if token is None else token
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = dict({'Authorization': f'Bearer {tok}'} if tok else {}, **(headers or {}))
        r = urllib.request.Request(f'http://127.0.0.1:{self.PORT}{path}', method=method, data=data, headers=h)
        try:
            with urllib.request.urlopen(r, timeout=30) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b'{}')

    def test_everything_but_livez_needs_the_token(self):
        self.assertEqual(200, self.req('GET', '/livez', token='')[0])
        self.assertEqual(401, self.req('GET', '/api/day/today', token='')[0])
        self.assertEqual(401, self.req('GET', '/api/day/today', token='y' * 40)[0])

    def test_admin_is_not_on_the_public_server_at_all(self):
        self.assertEqual(404, self.req('PUT', '/admin/catalog', raw=b'x')[0])
        self.assertEqual(404, self.req('POST', '/admin/ytm', {'cookie': 'SAPISID=x'})[0])

    def test_someone_elses_guessing_never_locks_the_owner_out(self):
        h = {'X-Real-Ip': '203.0.113.9'}
        codes = [self.req('GET', '/api/day/today', token='z' * 40, headers=h)[0] for _ in range(8)]
        self.assertEqual(401, codes[0])
        self.assertEqual(429, codes[-1], 'repeated bad tokens from one address are refused')
        self.assertEqual(200, self.req('GET', '/api/day/today', headers=h)[0], 'the real token still works from there')

    def test_bodies_must_be_small_json_objects(self):
        self.assertEqual(400, self.req('POST', '/api/day/today/prompt', raw=b'[1,2]', headers={'Content-Type': 'application/json'})[0])
        self.assertEqual(413, self.req('POST', '/api/day/today/prompt', raw=b'{"text": "' + b'a' * 20000 + b'"}')[0])

    def test_security_headers_on_every_answer(self):
        r = urllib.request.Request(f'http://127.0.0.1:{self.PORT}/livez')
        with urllib.request.urlopen(r, timeout=10) as resp:
            self.assertEqual('nosniff', resp.headers['X-Content-Type-Options'])
            self.assertEqual('no-store', resp.headers['Cache-Control'])
            self.assertIn("default-src 'none'", resp.headers['Content-Security-Policy'])
            self.assertEqual('telly', resp.headers['Server'])

    def test_a_message_makes_a_playlist_and_tweaks_change_it(self):
        code, r = self.req('POST', '/api/day/today/prompt', {'text': 'a run at 6 pm, 40 minutes'})
        self.assertEqual(200, code)
        self.assertTrue(r['rough'], 'without the model the reply must say it was a rough reading')
        s = next(x for x in r['day']['sessions'] if x['id'] == r['focus'])
        self.assertEqual(('run', '18:00', 40), (s['activity'], s['start'], s['minutes']))
        self.assertGreater(s['count'], 6)
        self.assertIsNotNone(self.store.claim(), 'the change was queued for the worker')
        code, t = self.req('POST', f"/api/session/{s['id']}/tweak", {'tweak': 'shorter'})
        self.assertEqual((200, 30), (code, t['minutes']))
        self.assertLess(t['count'], s['count'])
        code, u = self.req('POST', f"/api/session/{s['id']}/undo")
        self.assertEqual([x['id'] for x in s['tracks']], [x['id'] for x in u['tracks']])
        self.assertEqual(3, u['version'])

    def test_the_language_model_budget_runs_out_politely(self):
        lim = Limits(budgets={'prompt': 2})
        self.assertTrue(lim.spend('prompt'))
        self.assertTrue(lim.spend('prompt'))
        self.assertFalse(lim.spend('prompt'))
        self.assertTrue(lim.spend('change'), 'kinds without a budget are never refused')

    def test_unreadable_words_on_an_open_playlist_change_nothing(self):
        _, r = self.req('POST', '/api/day/today/prompt', {'text': 'a walk at 9 pm'})
        before = len(r['day']['sessions'])
        _, r2 = self.req('POST', '/api/day/today/prompt', {'text': 'make it feel like a rainy Calcutta afternoon', 'focus': r['focus']})
        self.assertEqual(before, len(r2['day']['sessions']))
        self.assertIn("can't read that", r2['reply'])

    def test_the_days_lens(self):
        code, d = self.req('POST', '/api/day/today/lens', {'text': '  20th-century   history '})
        self.assertEqual((200, '20th-century history'), (code, d['lens']))
        self.assertEqual(5, len(d['lens_ideas']))
        self.assertEqual('20th-century history', self.req('GET', '/api/day/today')[1]['lens'])
        self.assertIsNone(self.req('POST', '/api/day/today/lens', {'text': ''})[1]['lens'])

    def test_undo_walks_back_rather_than_toggling(self):
        _, r = self.req('POST', '/api/day/today/prompt', {'text': 'a walk at 8 pm, 40 minutes'})
        sid = r['focus']
        v1 = [t['id'] for t in self.req('GET', f'/api/session/{sid}')[1]['tracks']]
        v2 = [t['id'] for t in self.req('POST', f'/api/session/{sid}/tweak', {'tweak': 'shorter'})[1]['tracks']]
        self.req('POST', f'/api/session/{sid}/tweak', {'tweak': 'reshuffle'})
        back1 = self.req('POST', f'/api/session/{sid}/undo')[1]
        back2 = self.req('POST', f'/api/session/{sid}/undo')[1]
        self.assertEqual(v2, [t['id'] for t in back1['tracks']])
        self.assertEqual(v1, [t['id'] for t in back2['tracks']], 'a second undo goes back again (it used to redo)')
        self.assertFalse(back2['can_undo'])
        self.assertEqual(back2['version'], self.req('POST', f'/api/session/{sid}/undo')[1]['version'], 'nothing left to undo')

    def test_a_new_activity_brings_its_own_energy(self):
        _, r = self.req('POST', '/api/day/today/prompt', {'text': 'a walk at 9 pm'})
        sid = r['focus']
        day = self.canvas.place.today()
        self.canvas._apply(day, {'target': sid, 'activity': 'run', 'reply': 'Made it a run.'}, 'make it a run', {sid})
        b = self.store.version(sid)['brief']
        self.assertEqual(E.ACTIVITIES['run']['energy'], tuple(b['energy']))
        self.assertEqual('07:00', self.canvas._apply(day, {'target': 'new', 'activity': 'run', 'start': '7:00', 'reply': ''}, 'x', set())
                         and self.store.version(max(self.store.sessions_on(day), key=lambda s: s['created'])['id'])['brief']['start'])

    def test_answers_sent_before_the_body_close_the_connection(self):
        import socket
        sock = socket.create_connection(('127.0.0.1', self.PORT), timeout=10)
        smuggled = b'GET /livez HTTP/1.1\r\nHost: x\r\n\r\n'
        sock.sendall(b'POST /api/day/today/prompt HTTP/1.1\r\nHost: x\r\nContent-Length: %d\r\n\r\n' % len(smuggled) + smuggled)
        got = b''
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            got += chunk
        sock.close()
        self.assertIn(b' 401 ', got.split(b'\r\n')[0])
        self.assertEqual(1, got.count(b'HTTP/1.1 '), 'the bytes after an unread body must never be answered as a request')

    def test_dates_that_are_not_dates_change_nothing(self):
        self.assertEqual(400, self.req('POST', '/api/day/2026-13-45/lens', {'text': 'x'})[0])
        self.assertIsNone(self.store.lens('2026-13-45'))

    def test_edits_are_validated(self):
        _, r = self.req('POST', '/api/day/today/prompt', {'text': 'a drive at 7 am'})
        self.assertEqual(400, self.req('POST', f"/api/session/{r['focus']}/edit", {'op': 'remove', 'index': 999})[0])
        self.assertEqual(400, self.req('POST', f"/api/session/{r['focus']}/edit", {'op': 'remove', 'index': True})[0])
        self.assertEqual(404, self.req('GET', '/api/session/0000000000')[0])


class AdminTest(Base):
    def test_admin_needs_its_own_token_and_keeps_sign_ins_in_the_vault(self):
        from telly.sync import migrate_secrets
        from telly.vault import Vault
        store = Store(os.path.join(self.tmp.name, 'admin.db'))
        vault = Vault(os.path.join(self.tmp.name, 'vault', 'vault.json'))
        store.put('ytm_cookies', {'SAPISID': 'abc'})
        migrate_secrets(store, vault)
        self.assertIsNone(store.get('ytm_cookies'), 'the API-readable copy is gone')
        self.assertEqual({'SAPISID': 'abc'}, vault.get('ytm_cookies'))
        self.assertEqual(0o600, os.stat(vault.path).st_mode & 0o777)

        class FakeWorker:
            def check_services(self):
                return {'ytm': {'ok': False}}
        admin = AdminApp('a' * 40, FakeWorker(), vault, Catalogs(self.path))
        port = start(admin, slots=4)
        for tok, want in (('x' * 40, 401), ('a' * 40, 200)):
            r = urllib.request.Request(f'http://127.0.0.1:{port}/admin/status', headers={'Authorization': f'Bearer {tok}'})
            try:
                with urllib.request.urlopen(r, timeout=10) as resp:
                    code = resp.status
            except urllib.error.HTTPError as e:
                code = e.code
            self.assertEqual(want, code)
        with self.assertRaises(SystemExit):
            AdminApp('short', FakeWorker(), vault, Catalogs(self.path))

    def test_a_restart_puts_interrupted_work_back(self):
        store = Store(os.path.join(self.tmp.name, 'restart.db'))
        sid = store.new_session('2026-11-16', 'walk')
        store.add_version(sid, 'prompt', 'x', 'y', {'activity': 'walk'}, [])
        store.update_session(sid, ytm_state='syncing')
        self.assertIn(sid, store.unfinished())

    def test_rotation_never_adds_cookies(self):
        from telly.ytm import YTM
        y = YTM({'SAPISID': 'a', '__Secure-1PSIDTS': 'old'})
        y._absorb(['__Secure-1PSIDTS=new; Domain=.youtube.com; Path=/; Secure; HttpOnly; Priority=HIGH', 'NEWONE=x; Path=/'])
        self.assertEqual({'SAPISID': 'a', '__Secure-1PSIDTS': 'new'}, y.cookies)

    def test_the_queue_syncs_a_session_once_however_often_it_changed(self):
        store = Store(os.path.join(self.tmp.name, 'queue.db'))
        for _ in range(3):
            store.enqueue('aaaaaaaaaa')
        store.enqueue('bbbbbbbbbb')
        self.assertEqual(['aaaaaaaaaa', 'bbbbbbbbbb', None], [store.claim(), store.claim(), store.claim()])


if __name__ == '__main__':
    unittest.main()
