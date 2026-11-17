"""What a review of the composer's first version found, each pinned down so it stays fixed: a mix nobody wants any
more stops being made; what he says while it's being made reaches the model; undo brings back a mix's own story; a newer
ask is never overwritten; pinned songs stay; nothing is left 'pending' for the app to wait on; caches don't grow for ever;
a stream cut short is asked again rather than half kept."""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
from test_compose import GOOD, RESEARCH, ComposingBase, Script, deliver, turn, use  # noqa: E402
from test_telly import Base  # noqa: E402

from telly import compose as C  # noqa: E402
from telly import engine as E  # noqa: E402
from telly.llm import LLMError  # noqa: E402


class ReviewFindingsTest(ComposingBase):
    def test_asking_again_stops_the_mix_being_made_rather_than_finishing_it(self):
        sid, _ = self.new_mix(Script())
        test = self

        def research_then_ask_again(body):
            C.queue(test.store, sid, 'actually, a run')  # he changes his mind while it digs
            return RESEARCH

        llm = Script(research_then_ask_again, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD)))
        self.canvas.composer_llm = llm
        C.Worker(self.store, self.canvas).make(sid)
        self.assertEqual(len(llm.turns), 2, 'no more turns spent on a mix nobody wants')
        self.assertEqual(self.canvas.session(sid)['tracks'], [])
        self.assertEqual(C.job(self.store, sid)['state'], 'queued')  # the newer ask, untouched, runs next

    def test_a_follow_up_while_it_is_being_made_reaches_the_model(self):
        sid, _ = self.new_mix(Script())
        self.I.plan = lambda *a, **k: ([{'target': sid, 'activity': 'run', 'reply': ''}], None, None)
        self.canvas.prompt(self.day, 'actually make it a run', focus=sid)
        llm = Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD)))
        self.canvas.composer_llm = llm
        C.Worker(self.store, self.canvas).make(sid)
        asked = llm.bodies[0]['messages'][0]['content']
        self.assertIn('Since he asked, he has added: "actually make it a run"', asked)
        self.assertIn('(run)', asked)

    def test_hard_rules_from_his_words_reach_the_model(self):
        from telly.canvas import rules_of
        b = E.normalize({'activity': 'focus', 'minutes': 30, 'exclude_artists': ['Coldplay'], 'include_artists': ['Ilaiyaraaja']})
        text = C.brief('A listener.', 'For: work', 'deep work', '', 30, 'today', rules=rules_of(b))
        self.assertIn('Instrumental only', text)
        self.assertIn('Nothing by: Coldplay.', text)
        self.assertIn('Include songs by: Ilaiyaraaja.', text)

    def test_undo_brings_back_the_older_mix_with_its_own_story(self):
        sid, _ = self.new_mix(Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD))))
        C.Worker(self.store, self.canvas).make(sid)
        calmer = [(103, 'Big Yellow Taxi', 'Joni Mitchell'), (102, "Tom's Diner", 'Suzanne Vega')]
        self.canvas.composer_llm = Script(
            turn(use('find_recording', 'f1', title='Big Yellow Taxi', artist='Joni Mitchell'),
                 use('find_recording', 'f2', title="Tom's Diner", artist='Suzanne Vega')),
            turn(deliver('d2', calmer, title='Calmer')), turn(deliver('e2', calmer, title='Calmer')))
        self.canvas.tweak(sid, 'calmer')
        C.Worker(self.store, self.canvas).make(sid)
        self.assertEqual(self.canvas.session(sid)['story']['intro'], 'Intro of Calmer.')
        s = self.canvas.undo(sid)
        self.assertEqual([t['title'] for t in s['tracks']], ['Chelsea Morning', "Tom's Diner (A Cappella)"])
        self.assertEqual(s['story']['intro'], 'Intro of The Traffic Wrote the Words.')
        self.assertEqual(s['tracks'][0]['note'], 'A note on Chelsea Morning.')

    def test_a_newer_ask_landing_as_the_mix_is_saved_is_never_overwritten(self):
        sid, _ = self.new_mix(Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD))))
        real = self.canvas.save_composed

        def save_then_ask(*a, **k):
            C.queue(self.store, sid, 'and some jazz')  # lands between the last check and the clear
            return real(*a, **k)

        self.canvas.save_composed = save_then_ask
        C.Worker(self.store, self.canvas).make(sid)
        self.assertEqual(C.job(self.store, sid)['said'], 'and some jazz')
        self.assertEqual(C.job(self.store, sid)['state'], 'queued')

    def test_a_song_he_pinned_must_stay_in_a_reworked_mix(self):
        without = [(101, 'Chelsea Morning', 'Joni Mitchell'), (103, 'Big Yellow Taxi', 'Joni Mitchell')]
        llm = Script(RESEARCH, turn(deliver('d1', without)), turn(deliver('d2', GOOD)))
        c = C.Composer(llm, C.Research(self.cat, self.store), keep=[('Suzanne Vega', "Tom's Diner (A Cappella)")])
        c.research.t_find_recording('Big Yellow Taxi', 'Joni Mitchell')
        res = c.make('For: a walk', 5, edit=False)
        self.assertIn("Tom's Diner (A Cappella)", [c.research.found[x['apple_id']]['title'] for x in res['mix']['songs']])
        sent_back = [r for r in llm.last_results(2) if r.get('is_error')]
        self.assertTrue(sent_back and 'He pinned' in sent_back[0]['content'], sent_back)

    def test_a_mix_that_stopped_answering_is_shown_as_failed_and_can_be_tried_again(self):
        j = {'state': 'working', 'job': 'x', 'started': 0, 'beat': 0, 'progress': ['Reading about Joni Mitchell']}
        self.assertEqual(C.public(j)['state'], 'failed')
        self.assertEqual(C.public(dict(j, beat=time.time()))['state'], 'working')

    def test_a_new_subject_leaves_composed_and_unfinished_mixes_settled(self):
        sid, _ = self.new_mix(Script(RESEARCH, turn(deliver('d1', GOOD)), turn(deliver('e1', GOOD))))
        C.Worker(self.store, self.canvas).make(sid)
        waiting, _ = self.new_mix(Script(LLMError('HTTP 429: busy')))
        C.Worker(self.store, self.canvas).make(waiting)
        self.canvas.set_lens(self.day, 'The space race')
        self.assertEqual(self.canvas.session(sid)['story']['state'], 'ready')  # its story came with its songs
        self.assertEqual(self.canvas.session(waiting)['story']['state'], 'none')  # nothing to wait on

    def test_cached_lookups_older_than_a_month_are_dropped(self):
        self.store.put('wiki2:old', {'ts': time.time() - 40 * 86400, 'v': 'x'})
        self.store.put('wiki2:new', {'ts': time.time(), 'v': 'y'})
        self.store.put('mix:abc', {'ts': 0})  # not a cache: never touched
        self.assertEqual(self.store.evict('wiki2:', time.time() - C.KEEP), 1)
        self.assertIsNone(self.store.get('wiki2:old'))
        self.assertIsNotNone(self.store.get('wiki2:new'))
        self.assertIsNotNone(self.store.get('mix:abc'))


class AccountSwitchTest(ComposingBase):
    def test_a_mix_spanning_a_change_of_gateway_account_starts_afresh_from_its_research(self):
        refused = LLMError('HTTP 400: b\'{"type":"error","error":{"type":"invalid_request_error","message":"messages.5.content.0: '
                           'Invalid `encrypted_content`"}}\'')
        llm = Script(RESEARCH, refused, turn(deliver('d1', GOOD)))
        c = C.Composer(llm, C.Research(self.cat, self.store))
        res = c.make('For: a walk', 5, edit=False)
        self.assertEqual([s['apple_id'] for s in res['mix']['songs']], [101, 102])
        fresh = llm.bodies[2]['messages']
        self.assertEqual(len(fresh), 1)  # nothing another account encrypted is sent again
        self.assertIn('What you have found so far', fresh[0]['content'])
        self.assertIn('apple_id 101', fresh[0]['content'])  # its research survives, songs found included

    def test_other_refusals_still_fail_the_mix(self):
        llm = Script(RESEARCH, LLMError('HTTP 400: invalid_request_error: max_tokens too large'))
        with self.assertRaises(LLMError):
            C.Composer(llm, C.Research(self.cat, self.store)).make('For: a walk', 5, edit=False)


class StreamTest(Base):
    @staticmethod
    def lines(*events):
        return [f'data: {json.dumps(e)}\n'.encode() for e in events]

    def test_a_stream_cut_short_is_asked_again_never_half_kept(self):
        from telly import llm as L
        start = {'type': 'message_start', 'message': {'id': 'm', 'role': 'assistant', 'usage': {}}}
        block = {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'tool_use', 'id': 't', 'name': 'history', 'input': {}}}
        part = {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'input_json_delta', 'partial_json': '{"artist": "Jo'}}
        with self.assertRaises(L._Overloaded):  # the connection dropped mid-block: no message_stop
            L._assemble(self.lines(start, block, part))
        with self.assertRaises(L._Overloaded):  # the block closed on half its input
            L._assemble(self.lines(start, block, part, {'type': 'content_block_stop', 'index': 0}, {'type': 'message_stop'}))
        rest = {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'input_json_delta', 'partial_json': 'ni"}'}}
        msg = L._assemble(self.lines(start, block, part, rest, {'type': 'content_block_stop', 'index': 0},
                                     {'type': 'message_delta', 'delta': {'stop_reason': 'tool_use'}, 'usage': {'output_tokens': 3}},
                                     {'type': 'message_stop'}))
        self.assertEqual(msg['content'][0]['input'], {'artist': 'Joni'})
        self.assertEqual(msg['stop_reason'], 'tool_use')


if __name__ == '__main__':
    import unittest
    unittest.main()
