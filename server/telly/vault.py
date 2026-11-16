"""The music-service sign-ins (YouTube Music cookies, Spotify's refresh token), kept apart from everything else.

They live in one JSON file on a volume that only the worker container mounts. The internet-facing API process never
has this file, so a compromise there can't read a Google session. Writes are atomic (temp file + rename) and the file
is mode 600.
"""
import json
import os
import tempfile
import threading


class Vault:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)

    def _read(self):
        try:
            with open(self.path) as f:
                return json.load(f)
        except FileNotFoundError:
            return {}

    def get(self, key, default=None):
        with self.lock:
            return self._read().get(key, default)

    def put(self, key, value):
        with self.lock:
            data = self._read()
            if value is None:
                data.pop(key, None)
            else:
                data[key] = value
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(self.path)), prefix='.vault-')
            try:
                with os.fdopen(fd, 'w') as f:
                    json.dump(data, f)
                os.chmod(tmp, 0o600)
                os.replace(tmp, self.path)
            except BaseException:
                if os.path.exists(tmp):
                    os.remove(tmp)
                raise
