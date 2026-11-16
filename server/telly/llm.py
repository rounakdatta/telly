"""Claude through the listener's own gateway (auth2api, in-cluster), Anthropic Messages shape."""
import json
import time
import urllib.error
import urllib.request


class LLMError(Exception):
    pass


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

    def tool(self, system, user, tool, **kw):
        """Force one tool call and return its input."""
        d = self.messages(system, user, tools=[tool], tool_choice={'type': 'tool', 'name': tool['name']}, **kw)
        for b in d.get('content', []):
            if b.get('type') == 'tool_use' and b.get('name') == tool['name']:
                return b.get('input') or {}
        raise LLMError('no tool call in the reply')
