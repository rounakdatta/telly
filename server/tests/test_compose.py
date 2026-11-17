"""The composer: a mix researched and written in one voice, every song checked to exist, and the job that makes it in
the background (new moments, changes to a mix, failures, restarts). No network: Apple's catalogue and the model are fakes."""
import json
import os
import sys
import tempfile
import urllib.parse

sys.path.insert(0, os.path.dirname(__file__))
from test_telly import Base  # noqa: E402

from telly import compose as C  # noqa: E402
from telly import engine as E  # noqa: E402
from telly.api import Catalogs  # noqa: E402
from telly.llm import LLMError  # noqa: E402
from telly.store import Store  # noqa: E402

APPLE = {
    'chelsea morning': dict(trackId=101, trackName='Chelsea Morning', artistName='Joni Mitchell', collectionName='Clouds',
                            releaseDate='1969-05-01T00:00:00Z', trackTimeMillis=151_000, artworkUrl100='https://a/100x100bb.jpg'),
    "tom's diner": dict(trackId=102, trackName="Tom's Diner (A Cappella)", artistName='Suzanne Vega', collectionName='Solitude Standing',
                        releaseDate='1987-04-01T00:00:00Z', trackTimeMillis=129_000, artworkUrl100='https://b/100x100bb.jpg'),
    'big yellow taxi': dict(trackId=103, trackName='Big Yellow Taxi', artistName='Joni Mitchell', collectionName='Ladies of the Canyon',
                            releaseDate='1970-04-01T00:00:00Z', trackTimeMillis=135_000),
    'endless': dict(trackId=104, trackName='Endless', artistName='Some Band', collectionName='Long', releaseDate='2001',
                    trackTimeMillis=1_200_000),
}


def fake_get_json(url, pace, headers=None, timeout=20):
    if 'itunes.apple.com' in url:
        term = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)['term'][0].lower()
        return {'results': [r for k, r in APPLE.items() if k in term]}
    raise AssertionError(f'no network in tests: {url}')


def use(name, id_, **inp):
    return {'type': 'tool_use', 'id': id_, 'name': name, 'input': inp}


def turn(*blocks, stop='tool_use'):
    return {'content': list(blocks), 'stop_reason': stop, 'usage': {'input_tokens': 10, 'output_tokens': 5}}


def deliver(id_, songs, title='The Traffic Wrote the Words'):
    return use('deliver_mix', id_, title=title, intro='Give the road a minute.', outro='You don’t have a train to catch.',
               songs=[{'apple_id': a, 'title': t, 'artist': r, 'note': f'A note on {t}.'} for a, t, r in songs],
               facts=[{'claim': 'Joni wrote it in Chelsea', 'source': 'wikipedia: Chelsea Morning'}])


GOOD = [(101, 'Chelsea Morning', 'Joni Mitchell'), (102, "Tom's Diner", 'Suzanne Vega')]


class Script:
    """A model that answers turn by turn from a script; it records every request."""
    ready = True

    def __init__(self, *turns):
        self.turns, self.bodies = list(turns), []

    def stream(self, body, betas=(), **kw):
        self.bodies.append(json.loads(json.dumps(body)))
        t = self.turns.pop(0)
        if isinstance(t, Exception):
            raise t
        return t(body) if callable(t) else t

    def last_results(self, i=-1):
        """The tool results sent back in request i."""
        return [b for b in self.bodies[i]['messages'][-1]['content'] if isinstance(b, dict) and b.get('type') == 'tool_result']


RESEARCH = turn(use('find_recording', 'f1', title='Chelsea Morning', artist='Joni Mitchell'),
                use('find_recording', 'f2', title="Tom's Diner", artist='Suzanne Vega'),
                use('history', 'h1', artist='Joni Mitchell'))


class ComposerTest(Base):
    def setUp(self):
        self.tmpd = tempfile.TemporaryDirectory()
        self.store = Store(os.path.join(self.tmpd.name, 'telly.db'))
        self.real_get = C._get_json
        C._get_json = fake_get_json

    def tearDown(self):
        C._get_json = self.real_get
        self.tmpd.cleanup()

    def composer(self, llm):
        return C.Composer(llm, C.Research(self.cat, self.store), about='A listener.', examples='<mix>…</mix>')

    def test_it_researches_then_delivers_a_mix_whose_songs_all_exist(self):
        llm = Script(RESEARCH, turn(deliver('d1', GOOD)))
        res = self.composer(llm).make('For: a walk', 5, edit=False)
        self.assertEqual([s['apple_id'] for s in res['mix']['songs']], [101, 102])
        results = {r['tool_use_id']: r['content'] for r in llm.last_results(1)}
        self.assertIn('apple_id 101', results['f1'])
        self.assertIn("Tom's Diner (A Cappella)", results['f2'])
        self.assertIn('never played anything by Joni Mitchell', results['h1'])
        self.assertEqual([r['tool'] for r in res['log']], ['find_recording', 'find_recording', 'history'])
        body = llm.bodies[0]
        self.assertEqual(body['output_config'], {'effort': 'max'})
        self.assertEqual(body['thinking'], {'type': 'adaptive'})
        self.assertNotIn('tool_choice', body)  # Opus 5.5 refuses forced tool use
        self.assertIn('<mix>…</mix>', body['system'][0]['text'])  # the mixes he loved are the standard it's shown

    def test_a_mix_that_breaks_the_rules_goes_back_until_it_is_right(self):
        llm = Script(
            turn(use('find_recording', 'f1', title='Chelsea Morning', artist='Joni Mitchell'),
                 use('find_recording', 'f2', title='Big Yellow Taxi', artist='Joni Mitchell'),
                 use('find_recording', 'f3', title="Tom's Diner", artist='Suzanne Vega'),
                 use('find_recording', 'f4', title='Endless', artist='Some Band')),
            turn(deliver('d1', [(999, 'Imagined', 'Nobody'), (102, "Tom's Diner", 'Suzanne Vega')])),
            turn(deliver('d2', [(101, 'Chelsea Morning', 'Joni Mitchell'), (103, 'Big Yellow Taxi', 'Joni Mitchell')])),
            turn(deliver('d3', [(104, 'Endless', 'Some Band'), (102, "Tom's Diner", 'Suzanne Vega')])),
            turn(deliver('d4', GOOD)))
        res = self.composer(llm).make('For: a walk', 5, edit=False)
        self.assertEqual(res['mix']['songs'][0]['apple_id'], 101)
        errors = [llm.last_results(i)[0] for i in (2, 3, 4)]
        self.assertTrue(all(e.get('is_error') for e in errors))
        self.assertIn("didn't come from find_recording", errors[0]['content'])
        self.assertIn('one per artist', errors[1]['content'])
        self.assertIn('for a 5-minute moment', errors[2]['content'])

    def test_the_editor_reads_the_draft_and_everything_the_writer_read(self):
        llm = Script(RESEARCH, turn(deliver('d1', GOOD, title='Draft')),
                     turn({'type': 'text', 'text': 'Cut a Wikipedia sentence.'}, deliver('e1', GOOD, title='Final')))
        res = self.composer(llm).make('For: a walk', 5)
        self.assertEqual(res['draft']['title'], 'Draft')
        self.assertEqual(res['mix']['title'], 'Final')
        self.assertIn('Cut a Wikipedia sentence.', res['editor_notes'])
        editor = llm.bodies[2]
        self.assertIn('You edit the mixtapes', editor['system'][0]['text'])
        user = editor['messages'][0]['content']
        self.assertIn('"title": "Draft"', user)
        self.assertIn('### find_recording', user)  # what the writer read, for checking its facts

    def test_a_model_that_never_delivers_is_an_error_not_a_hang(self):
        llm = Script(turn({'type': 'text', 'text': 'Thinking aloud.'}, stop='end_turn'),
                     turn({'type': 'text', 'text': 'Still thinking.'}, stop='end_turn'),
                     turn({'type': 'text', 'text': 'Hmm.'}, stop='end_turn'))
        with self.assertRaises(C.ComposeError):
            self.composer(llm).make('For: a walk', 5, edit=False)

    def test_history_says_what_he_has_and_hasnt_played(self):
        r = C.Research(self.cat, self.store)
        self.assertIn('songs played', r.t_history(artist='Artist indie 1'))
        self.assertIn('never played anything by Nobody', r.t_history(artist='Nobody'))
        self.assertIn('Song 5 —', r.t_history(title='Song 5'))
        self.assertIn('never played "No Such Song"', r.t_history(title='No Such Song'))


class ComposingTest(Base):
    """The background job, through the canvas the app talks to."""

    def setUp(self):
        from telly.canvas import Canvas
        from telly.context import Place
        from telly.llm import LLM
        import telly.interpret as I
        self.tmpd = tempfile.TemporaryDirectory()
        self.store = Store(os.path.join(self.tmpd.name, 'telly.db'))
        self.canvas = Canvas(self.store, Catalogs(self.path), Place(), LLM('http://127.0.0.1:9', 'k', 'm'))
        self.canvas.place.forecast = lambda: None
        self.day = self.canvas.place.today()
        self.real_get, self.real_plan, self.I = C._get_json, I.plan, I
        C._get_json = fake_get_json
        action = {'target': 'new', 'activity': 'walk', 'minutes': 5, 'title': 'cafe walk', 'reply': ''}
        I.plan = lambda *a, **k: ([dict(action)], None, None)

    def tearDown(self):
        C._get_json = self.real_get
        self.I.plan = self.real_plan
        self.tmpd.cleanup()

    def new_mix(self, llm):
        self.canvas.composer_llm = llm
        got = self.canvas.prompt(self.day, 'a walk to the cafe, noticing small things')
        return got['focus'], got

    def test_a_new_moment_is_composed_in_the_background_and_arrives_with_its_story(self):
        llm = Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD)))
        sid, got = self.new_mix(llm)
        self.assertIn('few minutes', got['reply'])
        waiting = self.canvas.session(sid)
        self.assertEqual(waiting['tracks'], [])
        self.assertEqual(waiting['composing']['state'], 'queued')
        # nothing claims to be writing notes for songs that haven't arrived (the app would wait on it for ever)
        self.assertEqual((waiting['notes_state'], waiting['story']['state']), ('none', 'none'))
        self.assertEqual((waiting['ytm']['state'], waiting['spotify']['state']), ('waiting', 'waiting'))

        C.Worker(self.store, self.canvas).make(sid)
        s = self.canvas.session(sid)
        self.assertNotIn('composing', s)
        self.assertEqual([t['title'] for t in s['tracks']], ['Chelsea Morning', "Tom's Diner (A Cappella)"])
        self.assertEqual(s['tracks'][0]['note'], 'A note on Chelsea Morning.')
        self.assertTrue(s['tracks'][0]['new'])
        self.assertEqual(s['summary'], '“The Traffic Wrote the Words”')
        self.assertEqual(s['story']['intro'], 'Give the road a minute.')
        self.assertEqual(s['story']['outro'], 'You don’t have a train to catch.')
        self.assertIn('Here it is', s['log'][-1]['reply'])
        self.assertEqual((s['ytm']['state'], s['spotify']['state']), ('pending', 'pending'))  # now there is something to save
        asked = llm.bodies[0]['messages'][0]['content']
        self.assertIn('His words: "a walk to the cafe, noticing small things"', asked)
        self.assertIn('Length: about 5 minutes.', asked)

    def test_an_edit_keeps_the_composed_story_and_a_change_reworks_it_keeping_pins(self):
        sid, _ = self.new_mix(Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD))))
        C.Worker(self.store, self.canvas).make(sid)
        self.canvas.edit(sid, 'pin', 1)
        s = self.canvas.session(sid)
        self.assertTrue(s['tracks'][1]['pinned'])

        llm = Script(RESEARCH, turn(deliver('d2', GOOD, title='Calmer')), turn(deliver('e2', GOOD, title='Calmer')))
        self.canvas.composer_llm = llm
        got = self.canvas.tweak(sid, 'calmer')
        self.assertIn('Reworking', got['note'])
        self.assertEqual(len(got['tracks']), 2)  # the mix stays as it is while the new one is made
        self.assertEqual(got['composing']['state'], 'queued')
        C.Worker(self.store, self.canvas).make(sid)
        asked = llm.bodies[0]['messages'][0]['content']
        self.assertIn('The mix he has now', asked)
        self.assertIn("Tom's Diner (A Cappella) (he pinned this", asked)
        self.assertIn('He now asks', asked)
        s = self.canvas.session(sid)
        self.assertEqual(s['summary'], '“Calmer”')
        self.assertTrue(s['tracks'][1]['pinned'])

    def test_a_failed_mix_says_why_and_can_be_tried_again(self):
        sid, _ = self.new_mix(Script(LLMError('HTTP 429: busy')))
        C.Worker(self.store, self.canvas).make(sid)
        s = self.canvas.session(sid)
        self.assertEqual(s['composing']['state'], 'failed')
        self.assertIn('busy', s['composing']['error'])
        self.canvas.composer_llm = Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD)))
        self.assertEqual(self.canvas.recompose(sid)['composing']['state'], 'queued')
        C.Worker(self.store, self.canvas).make(sid)
        self.assertEqual(len(self.canvas.session(sid)['tracks']), 2)

    def test_a_quick_list_replaces_a_mix_he_cant_wait_for(self):
        sid, _ = self.new_mix(Script())
        s = self.canvas.quick(sid)
        self.assertGreater(len(s['tracks']), 0)
        self.assertNotIn('composing', s)

    def test_a_deploy_mid_mix_starts_it_again(self):
        sid, _ = self.new_mix(Script())
        j = C.job(self.store, sid)
        self.store.put(f'compose:{sid}', dict(j, state='working', progress=['Reading about Joni Mitchell']))
        C.Worker(self.store, self.canvas).requeue()
        self.assertEqual(C.job(self.store, sid)['state'], 'queued')

    def test_asking_again_while_it_is_made_lets_the_newer_ask_win(self):
        sid, _ = self.new_mix(Script())
        test = self

        def ask_again(body):
            C.queue(test.store, sid, 'actually, a run')  # he changes his mind mid-mix
            return turn(deliver('d1', GOOD))

        self.canvas.composer_llm = Script(RESEARCH, ask_again, turn(deliver('e1', GOOD)))
        C.Worker(self.store, self.canvas).make(sid)
        self.assertEqual(self.canvas.session(sid)['tracks'], [])  # the older mix is dropped
        self.assertEqual(C.job(self.store, sid)['state'], 'queued')

    def test_a_mix_still_being_composed_is_not_synced(self):
        from telly.sync import Worker

        class NoServices:
            def ytm(self):
                raise AssertionError('nothing to sync yet')

            spotify = ytm

        sid, _ = self.new_mix(Script())
        Worker(self.store, NoServices(), self.canvas).sync(sid)


if __name__ == '__main__':
    import unittest
    unittest.main()
