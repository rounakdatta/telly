"""Claude through the listener's own gateway (auth2api, in-cluster), Anthropic Messages shape."""
import http.client
import json
import time
import urllib.error
import urllib.request


class LLMError(Exception):
    pass


class _Overloaded(Exception):
    """The stream itself reported overload or a server error, or was cut short: worth another try."""


def _assemble(lines):
    """Server-sent events -> one message dict, its content blocks complete (tool inputs parsed, thinking signed)."""
    msg, blocks, stopped = {}, {}, False
    for raw in lines:
        line = raw.decode('utf-8', 'replace').strip()
        if not line.startswith('data:'):
            continue
        d = json.loads(line[5:].strip())
        t = d.get('type')
        if t == 'message_start':
            msg = d.get('message') or {}
        elif t == 'content_block_start':
            b = dict(d.get('content_block') or {})
            if b.get('type') in ('tool_use', 'server_tool_use'):
                b['_json'] = ''
            blocks[d['index']] = b
        elif t == 'content_block_delta':
            b, dl = blocks[d['index']], d.get('delta') or {}
            kind = dl.get('type')
            if kind == 'text_delta':
                b['text'] = (b.get('text') or '') + dl.get('text', '')
            elif kind == 'input_json_delta':
                b['_json'] += dl.get('partial_json', '')
            elif kind == 'thinking_delta':
                b['thinking'] = (b.get('thinking') or '') + dl.get('thinking', '')
            elif kind == 'signature_delta':
                b['signature'] = (b.get('signature') or '') + dl.get('signature', '')
            elif kind == 'citations_delta':
                b['citations'] = (b.get('citations') or []) + [dl.get('citation')]
        elif t == 'content_block_stop':
            b = blocks[d['index']]
            if '_json' in b:
                raw_json = b.pop('_json')
                try:
                    b['input'] = json.loads(raw_json) if raw_json.strip() else (b.get('input') or {})
                except ValueError:
                    raise _Overloaded('a tool call arrived cut short') from None
        elif t == 'message_stop':
            stopped = True
        elif t == 'message_delta':
            msg.update({k: v for k, v in (d.get('delta') or {}).items() if v is not None})
            msg['usage'] = {**(msg.get('usage') or {}), **(d.get('usage') or {})}
        elif t == 'error':
            err = d.get('error') or {}
            if err.get('type') in ('overloaded_error', 'api_error'):
                raise _Overloaded(err.get('message') or err.get('type'))
            raise LLMError(f"{err.get('type')}: {err.get('message')}")
    if not stopped:
        # A stream that just ends (a dropped connection, a proxy timing out) leaves blocks half built; sent back, they
        # fail the next turn. Asked again instead.
        raise _Overloaded('the stream ended before the message did')
    content = []
    for i in sorted(blocks):
        b = blocks[i]
        if b.get('type') == 'text' and not b.get('citations'):
            b.pop('citations', None)  # an empty citations field isn't accepted back
        content.append(b)
    msg['content'] = content
    return msg


class LLM:
    def __init__(self, url, key, model):
        self.url, self.key, self.model = url.rstrip('/'), key, model

    @property
    def ready(self):
        return bool(self.url and self.key)

    def messages(self, system, user, tools=None, tool_choice=None, max_tokens=1500, timeout=90, retries=1, wait=4):
        if not self.ready:
            raise LLMError('no language model configured')
        body = {'model': self.model, 'max_tokens': max_tokens, 'system': system, 'messages': [{'role': 'user', 'content': user}]}
        if tools:
            body['tools'] = tools
        if tool_choice:
            body['tool_choice'] = tool_choice
        req = urllib.request.Request(f'{self.url}/v1/messages', data=json.dumps(body).encode(), method='POST',
                                     headers={'x-api-key': self.key, 'content-type': 'application/json', 'anthropic-version': '2023-06-01'})
        for attempt in range(retries + 1):
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    return json.loads(r.read())
            except urllib.error.HTTPError as e:
                if e.code in (429, 500, 502, 503, 529) and attempt < retries:
                    time.sleep(wait * (attempt + 1))
                    continue
                raise LLMError(f'HTTP {e.code}: {e.read()[:200]!r}') from None
            except (urllib.error.URLError, TimeoutError) as e:
                if attempt < retries:
                    time.sleep(wait)
                    continue
                raise LLMError(str(e)) from None

    def stream(self, body, betas=(), timeout=180, retries=2, wait=10):
        """One streamed turn -> the assembled message (content blocks as the API sent them, stop_reason, usage).

        Streamed because the gateway gives a buffered reply 120 s and a streamed one 600 s, and a turn at high effort
        can think for minutes. `timeout` is the longest silence between bytes (the API pings while it thinks)."""
        if not self.ready:
            raise LLMError('no language model configured')
        body = dict(body, stream=True)
        body.setdefault('model', self.model)
        headers = {'x-api-key': self.key, 'content-type': 'application/json', 'anthropic-version': '2023-06-01'}
        if betas:
            headers['anthropic-beta'] = ','.join(betas)
        data = json.dumps(body).encode()
        for attempt in range(retries + 1):
            try:
                req = urllib.request.Request(f'{self.url}/v1/messages', data=data, method='POST', headers=headers)
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    return _assemble(r)
            except urllib.error.HTTPError as e:
                if e.code in (429, 500, 502, 503, 504, 529) and attempt < retries:
                    time.sleep(wait * (attempt + 1))
                    continue
                raise LLMError(f'HTTP {e.code}: {e.read()[:300]!r}') from None
            except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException, _Overloaded) as e:
                if attempt < retries:
                    time.sleep(wait * (attempt + 1))
                    continue
                raise LLMError(str(e) or type(e).__name__) from None

    def tool(self, system, user, tool, **kw):
        """Force one tool call and return its input."""
        d = self.messages(system, user, tools=[tool], tool_choice={'type': 'tool', 'name': tool['name']}, **kw)
        for b in d.get('content', []):
            if b.get('type') == 'tool_use' and b.get('name') == tool['name']:
                return b.get('input') or {}
        raise LLMError('no tool call in the reply')
