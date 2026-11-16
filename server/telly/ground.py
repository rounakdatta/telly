"""A second, strict reading of everything telly tells him about a song, against the text it came from.

The writing prompts say "only what the source says", and the model still slipped, each time in a new way: a nomination
became "nearly won", a critic's reading became the band's, two true facts became a cause ("thanks to Wayne's World",
"which is how this duet came together"). Rules get paraphrased around; a reader checking one claim against one source
doesn't. Whatever it can't find in the source is not said (the song just has less to say).
"""
OPINIONS = """
Opinion, mood and framing need no source ("a quiet one to close on", "soft enough to fall asleep to", "the best of
them"); only facts do: names, dates, places, numbers, who did what, why it happened, what it is about."""

TOOL = {
    'name': 'verdicts',
    'description': 'Whether each claim is fully stated in its source.',
    'input_schema': {
        'type': 'object',
        'properties': {'verdicts': {'type': 'array', 'items': {
            'type': 'object',
            'properties': {'n': {'type': 'integer'}, 'supported': {'type': 'boolean'},
                           'problem': {'type': 'string', 'description': 'When not supported: what the source does not say'}},
            'required': ['n', 'supported']}}},
        'required': ['verdicts'],
    },
}


REWRITE = {
    'name': 'rewrites',
    'description': 'Each rejected claim, rewritten to say only what its source says (empty to drop it).',
    'input_schema': {
        'type': 'object',
        'properties': {'rewrites': {'type': 'array', 'items': {
            'type': 'object', 'properties': {'n': {'type': 'integer'}, 'text': {'type': 'string'}}, 'required': ['n', 'text']}}},
        'required': ['rewrites'],
    },
}


def check(llm, pairs, limit=1500, opinions=False):
    """[(source, claim)] -> [(supported, problem)], one per pair: supported only when everything the claim says is
    stated in its source. A missing verdict counts as unsupported; a checker that can't be asked raises (nothing
    unchecked is said, or stored)."""
    if not pairs:
        return []
    if len(pairs) > 12:  # a long mix is read in parts: one long reading loses track of which claim had which source
        return check(llm, pairs[:12], limit, opinions) + check(llm, pairs[12:], limit, opinions)
    listing = '\n\n'.join(f'[{i}] Source: {" ".join(src.split())[:limit]}\n    Claim: {claim}' for i, (src, claim) in enumerate(pairs, 1))
    system = 'You check facts for a music app, strictly. You are given claims, each with the only text it may rely on.'
    user = f"""{listing}

For each claim, is everything it says stated in its source? Judge strictly. It is NOT supported when it:
- says why or how something happened (a cause, a reason, how something came together, what led to what) that the
  source doesn't state;
- turns a nomination, a rumour, a reading or a hedge ("may", "some think") into a fact, or credits a claim to someone
  the source doesn't;
- says something about this song that the source says only about an album, a film, the artist or another song (when
  the source names this song in it, it is about this song);
- has a number, date, ranking, quote or name that isn't in the source.
Plain rewording, and leaving things out, are fine. Things to listen for are fine when the source describes them.{OPINIONS if opinions else ''}
When a claim isn't supported, say in a few words what the source doesn't say."""
    out = llm.tool(system, user, TOOL, max_tokens=1500, timeout=90, retries=1, wait=10)
    got = [(False, 'no verdict')] * len(pairs)
    for v in _items(out.get('verdicts')):
        n = v.get('n')
        if isinstance(n, int) and 1 <= n <= len(pairs):
            got[n - 1] = (v.get('supported') is True, str(v.get('problem') or ''))
    return got


def _items(xs, key='text'):
    """A tool's list, as numbered dicts, however the model wrote it: a bare string counts by its position."""
    out = []
    for i, x in enumerate(xs if isinstance(xs, list) else [], 1):
        if isinstance(x, dict):
            out.append(x)
        elif isinstance(x, str):
            out.append({'n': i, key: x})
    return out


def supported(llm, pairs, limit=1500, opinions=False):
    return [ok for ok, _ in check(llm, pairs, limit, opinions)]


def settle(llm, pairs, words=26, limit=1500, opinions=False):
    """[(source, claim)] -> the claims to say: each one checked against its source; one that says more than its source
    is rewritten once with the checker's objection in hand and checked again; whatever still isn't supported becomes ''.
    """
    verdicts = check(llm, pairs, limit, opinions)
    final = [claim if ok else '' for (_, claim), (ok, _) in zip(pairs, verdicts)]
    bad = [k for k, (ok, _) in enumerate(verdicts) if not ok]
    if not bad:
        return final
    listing = '\n\n'.join(f'[{i}] Source: {" ".join(pairs[k][0].split())[:limit]}\n    Claim: {pairs[k][1]}\n    Problem: {verdicts[k][1]}'
                           for i, k in enumerate(bad, 1))
    user = f"""{listing}

Each claim above says something its source doesn't (the problem is given). Rewrite it so that everything it says is
stated in its source: the same purpose, warm and plain, at most {words} words. No causes, readings or numbers the source
doesn't give. If the source has nothing as good to say, return it empty."""
    out = llm.tool('You write short, exact notes about songs for a music app.', user, REWRITE, max_tokens=1200, timeout=90, retries=1, wait=10)
    redo = {}
    for item in _items(out.get('rewrites')):
        n, text = item.get('n'), ' '.join(str(item.get('text') or '').split()).strip()
        if isinstance(n, int) and 1 <= n <= len(bad) and 8 <= len(text) <= max(240, words * 8):
            redo[bad[n - 1]] = text
    if redo:
        keys = list(redo)
        for k, (ok, _) in zip(keys, check(llm, [(pairs[k][0], redo[k]) for k in keys], limit, opinions)):
            if ok:
                final[k] = redo[k]
    return final
