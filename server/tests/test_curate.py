"""The curator: songs chosen along a thread, made real (from his library, by artist, or found anywhere), and a story
told along them with every fact checked."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from test_telly import Base  # noqa: E402

from telly import curate as C  # noqa: E402
from telly import engine as E  # noqa: E402
from telly.api import Catalogs  # noqa: E402


class FakeLLM:
    """Answers each tool as the curator and the checker would; records what it was asked."""
    ready = True

    def __init__(self, picks=None, notes=None, supported=lambda claim: True):
        self.picks, self.notes, self.supported, self.asked = picks, notes, supported, []

    def tool(self, system, user, tool, **kw):
        self.asked.append((tool['name'], user))
        if tool['name'] == 'mixtape':
            return self.picks
        if tool['name'] == 'liner_notes':
            return self.notes
        if tool['name'] == 'verdicts':
            claims = [line.split('Claim:', 1)[1].strip() for line in user.splitlines() if 'Claim:' in line]
            return {'verdicts': [{'n': i, 'supported': self.supported(c), 'problem': 'not in the source'} for i, c in enumerate(claims, 1)]}
        if tool['name'] == 'rewrites':
            return {'rewrites': []}
        raise AssertionError(tool['name'])


class CuratorTest(Base):
    def setUp(self):
        self.cat = Catalogs(self.path).get()
        self.brief = E.normalize({'activity': 'sleep', 'minutes': 18, 'title': '12:39 am sleep', 'start': '00:39'})

    def test_songs_from_the_list_by_artist_and_none_twice(self):
        cur = C.Curator(FakeLLM(), self.cat, 'a listener')
        pool, builder = cur.pool(self.brief, 0, {})
        self.assertTrue(pool, 'the engine offers songs that fit the moment')
        first, other = pool[0], next(t for t in pool if t.get('artist_key') != pool[0].get('artist_key'))
        same_artist = next((t for t in pool[1:] if t.get('artist_key') == first.get('artist_key')), None)
        songs = [{'n': 1, 'part': 'opens', 'link': 'first'},
                 {'n': 1, 'part': 'again', 'link': 'repeat'},
                 {'n': pool.index(other) + 1, 'part': 'turn', 'link': 'contrast'}]
        if same_artist:
            songs.append({'n': pool.index(same_artist) + 1, 'part': 'same voice', 'link': 'again'})
        picks = cur.resolve(songs, pool, builder)
        self.assertEqual([first['id'], other['id']], [t['id'] for t, _, _ in picks], 'a song once, and one song per artist')
        self.assertEqual(('opens', 'first'), picks[0][1:])

    def test_a_song_from_anywhere_must_exist(self):
        cur = C.Curator(FakeLLM(), self.cat, 'a listener')
        found = {'trackName': 'The Sun Won’t Set', 'artistName': 'Anoushka Shankar & Norah Jones', 'trackTimeMillis': 260000,
                 'releaseDate': '2013-01-01T00:00:00Z', 'artworkUrl100': 'https://a/100x100bb.jpg'}
        # Apple's search stood in for: one song exists, one doesn't
        import telly.curate as mod
        orig = mod.urllib.request.urlopen

        class Resp:
            def __init__(self, body): self.body = body
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def read(self): return self.body

        def fake(req, timeout=0):
            q = req.full_url
            return Resp(json.dumps({'results': [found] if 'sun' in q.lower() else []}).encode())
        mod.urllib.request.urlopen = fake
        try:
            t = cur.anywhere("The Sun Won't Set", 'Anoushka Shankar')
            self.assertEqual(('curated', '2013', 260000), (t['pool'], t['year'], t['duration_ms']))
            self.assertIsNone(cur.anywhere('A Song Nobody Wrote', 'Nobody'), 'nothing imagined is played')
        finally:
            mod.urllib.request.urlopen = orig

    def test_a_mix_that_runs_long_is_trimmed_but_keeps_its_closer(self):
        songs = [({'id': str(i), 'duration_ms': 300_000}, f'p{i}', '') for i in range(8)]
        out = C.fit_length(songs, 20)
        self.assertEqual('7', out[-1][0]['id'], 'the closer stays')
        self.assertLessEqual(sum(t['duration_ms'] for t, _, _ in out), 20 * 60_000 + 90_000)

    def test_the_story_along_the_songs_says_only_what_its_sources_say(self):
        tracks = [{'id': 'a', 'title': 'Ajib Dastan Hai Yeh', 'artist': 'Lata Mangeshkar', 'part': 'opens', 'link': ''},
                  {'id': 'b', 'title': 'lovely', 'artist': 'Billie Eilish', 'part': 'closes', 'link': 'decades on'}]
        notes = {'thread': 'Two women, one hush.', 'intro': 'Two voices for the last light.', 'outro': 'Sleep well.',
                 'notes': [{'n': 1, 'text': 'Lata recorded this in 1960 for Dil Apna Aur Preet Parai.'},
                           {'n': 2, 'text': 'Billie wrote it with Khalid, which is how they became friends.'}]}
        llm = FakeLLM(notes=notes, supported=lambda c: 'which is how' not in c)
        got = C.liner_notes(llm, 'a listener', 'bedtime', 'sleep music', None, 'Last Light', tracks,
                            {'a': 'Ajib Dastan Hai Yeh, sung by Lata Mangeshkar for Dil Apna Aur Preet Parai (1960).', 'b': 'lovely, by Billie Eilish and Khalid.'})
        self.assertEqual({'a': 'Lata recorded this in 1960 for Dil Apna Aur Preet Parai.'}, got['notes'], 'the invented cause is not said')
        self.assertEqual(('Two women, one hush.', 'Two voices for the last light.', 'Sleep well.'), (got['thread'], got['intro'], got['outro']))
        self.assertIn('verdicts', [name for name, _ in llm.asked], 'everything was read again')


if __name__ == '__main__':
    unittest.main()


class CuratedCanvasTest(Base):
    """The curator inside the canvas: never holding everyone up, obeying the playlist's rules, keeping its notes."""

    def setUp(self):
        import tempfile
        import threading
        import time as _t
        from telly.canvas import Canvas
        from telly.context import Place
        from telly.llm import LLM
        from telly.store import Store
        self.tmpd = tempfile.TemporaryDirectory()
        self.store = Store(os.path.join(self.tmpd.name, 'telly.db'))
        self.canvas = Canvas(self.store, Catalogs(self.path), Place(), LLM('http://127.0.0.1:9', 'k', 'm'))
        self.canvas.place.forecast = lambda: None
        self.threading, self.time = threading, _t

    def curate_with(self, llm):
        self.canvas.curator_llm = llm

    def mix_llm(self, delay=0.0, pick=lambda pool: [1, 2, 3, 4], by_name=()):
        test = self

        class Mixer(FakeLLM):
            ready = True

            def tool(self, system, user, tool, **kw):
                if tool['name'] == 'mixtape':
                    test.time.sleep(delay)
                    n = user.count('\n[')  # songs listed
                    songs = [{'title': t['title'], 'artist': t['artist'], 'part': 'p', 'link': 'l'} for t in by_name]
                    return {'title': 'A Test Mix', 'thread': 't', 'songs': songs + [{'n': i, 'part': 'p', 'link': 'l'} for i in pick(n) if i <= n]}
                return super().tool(system, user, tool, **kw)
        return Mixer()

    def test_a_slow_curator_holds_nobody_up(self):
        from telly import engine as E
        day = self.canvas.place.today()
        sid = self.store.new_session(day, 'walk')
        brief = E.normalize({'activity': 'walk', 'minutes': 20})
        tracks, _ = E.rebuild(self.canvas.cat, brief, 18, [], {})
        self.canvas._save(sid, 'prompt', 'a walk', 'ok', brief, tracks)
        self.curate_with(self.mix_llm(delay=1.5))
        action = {'target': 'new', 'activity': 'sleep', 'minutes': 15, 'title': 'sleep', 'reply': 'ok'}
        import telly.interpret as I
        real = I.plan
        I.plan = lambda *a, **k: ([action], None, None)
        try:
            t = self.threading.Thread(target=self.canvas.prompt, args=(day, 'night music'))
            t.start()
            self.time.sleep(0.3)
            started = self.time.time()
            self.canvas.edit(sid, 'pin', 0)  # another playlist, while the curator thinks
            waited = self.time.time() - started
            t.join()
        finally:
            I.plan = real
        self.assertLess(waited, 1.0, 'an edit elsewhere never waits on the curator')

    def test_a_curated_change_keeps_pins_and_removals(self):
        from telly import engine as E
        day = self.canvas.place.today()
        self.curate_with(self.mix_llm())
        brief = E.normalize({'activity': 'walk', 'minutes': 20, 'title': 'walk'})
        got = self.canvas._curate(None, day, brief, 'a walk')
        self.assertIsNotNone(got)
        tracks, brief = got
        sid = self.store.new_session(day, 'walk')
        self.canvas._save(sid, 'prompt', 'a walk', 'ok', brief, tracks)
        self.canvas.edit(sid, 'pin', 2)
        pinned = self.store.version(sid)['tracks'][2]['id']
        self.canvas.edit(sid, 'remove', 0)
        removed = tracks[0]['id']
        # the curator now asks for the removed song again, by name, and leaves the pinned one out
        self.curate_with(self.mix_llm(pick=lambda n: [1, 2, 3, 4, 5, 6], by_name=[tracks[0]]))
        self.canvas.tweak(sid, 'calmer')
        after = [t['id'] for t in self.store.version(sid)['tracks']]
        self.assertNotIn(removed, after, 'a song he removed stays removed')
        self.assertIn(pinned, after, 'a song he pinned stays')
        self.assertEqual('A Test Mix', self.store.version(sid)['brief']['mix']['title'], 'a tweak keeps the mix its name')
        self.assertEqual('a walk', self.store.version(sid)['brief']['mix']['said'], 'and remembers what he first asked')

    def test_a_song_by_another_artist_is_not_taken_from_his_library(self):
        cur = C.Curator(FakeLLM(), Catalogs(self.path).get(), 'a listener')
        pool, builder = cur.pool(E_brief(), 0, {})
        self.assertIsNone(cur.in_library('Song 5', 'Somebody Else', builder))

    def test_notes_never_stay_writing_and_survive_a_move(self):
        from telly import engine as E
        from telly.sync import Worker
        day = self.canvas.place.today()
        self.curate_with(self.mix_llm())
        tracks, brief = self.canvas._curate(None, day, E.normalize({'activity': 'walk', 'minutes': 20, 'title': 'walk'}), 'a walk')
        sid = self.store.new_session(day, 'walk')
        self.canvas._save(sid, 'prompt', 'a walk', 'ok', brief, tracks)

        class NoServices:
            def ytm(self): return None
            def spotify(self): return None
        w = Worker(self.store, NoServices(), self.canvas)
        import telly.curate as mod
        real = mod.liner_notes
        mod.liner_notes = lambda *a, **k: (_ for _ in ()).throw(ConnectionResetError('reset'))
        try:
            w._mix_notes(sid, self.store.session(sid), self.store.version(sid))
        finally:
            mod.liner_notes = real
        self.assertEqual('none', self.store.session(sid)['notes_state'], 'a failure never leaves it "writing"')
        mod.liner_notes = lambda *a, **k: {'thread': 'th', 'intro': 'in', 'outro': 'out', 'notes': {t['id']: 'n' for t in a[6]}}
        try:
            w._mix_notes(sid, self.store.session(sid), self.store.version(sid))
            self.canvas.edit(sid, 'move', 0, to=2)
            asked = []
            mod.liner_notes = lambda *a, **k: asked.append(1) or {}
            w._mix_notes(sid, self.store.session(sid), self.store.version(sid))
        finally:
            mod.liner_notes = real
        self.assertEqual([], asked, 'a move keeps the story it has')
        shown = self.canvas.session(sid)
        self.assertEqual('th', shown['summary'])
        self.assertTrue(all(t['note'] == 'n' for t in shown['tracks']))


def E_brief():
    from telly import engine as E
    return E.normalize({'activity': 'walk', 'minutes': 20})
