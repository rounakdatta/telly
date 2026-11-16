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
