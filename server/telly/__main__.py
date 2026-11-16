"""Two processes, one image:

    python -m telly serve  --data /data --port 8080                        the public API (the phone talks to this)
    python -m telly worker --data /data --vault /vault/vault.json --port 8081   sync, stories and the admin server

They share /data (telly.db and catalog.db). Only the worker mounts the vault with the music-service sign-ins.

Environment:
  TELLY_TOKEN        serve: the bearer token the app sends (32+ characters; refuses to start without it)
  TELLY_ADMIN_TOKEN  worker: the admin bearer token (32+ characters; refuses to start without it)
  TELLY_LLM_URL      Anthropic-shaped gateway (default: auth2api in-cluster)
  TELLY_LLM_KEY      its key (without one telly still works: rules read messages, no stories)
  TELLY_LLM_MODEL    default claude-sonnet-5
  TELLY_CURATOR_MODEL the model that chooses and orders songs and writes the story along them (default claude-fable-5)
  TELLY_LYRICS_URL   serve: LeZeppLean, for what's playing and its word-timed lyrics (unset: no lyrics)
  TELLY_LYRICS_TOKEN serve: its bearer token
  TELLY_GENIUS_TOKEN serve: a Genius API client token (optional: without one, Genius's public JSON is read)
"""
import argparse
import logging
import os
import signal
import sys

from .api import AdminApp, Catalogs, PublicApp, serve
from .canvas import Canvas
from .context import Place
from .genius import Genius
from .llm import LLM
from .lyrics import Lyrics
from .store import Store
from .sync import Services, Worker, migrate_secrets
from .vault import Vault


def main():
    ap = argparse.ArgumentParser(prog='telly')
    ap.add_argument('role', choices=['serve', 'worker'])
    ap.add_argument('--data', default=os.environ.get('TELLY_DATA', './data'))
    ap.add_argument('--vault', default=os.environ.get('TELLY_VAULT', './vault/vault.json'))
    ap.add_argument('--host', default='0.0.0.0')
    ap.add_argument('--port', type=int)
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    # PID 1 in the container: without a handler SIGTERM is ignored and every rollout waits out the grace period.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    os.makedirs(a.data, exist_ok=True)
    store = Store(os.path.join(a.data, 'telly.db'))
    catalogs = Catalogs(os.path.join(a.data, 'catalog.db'))
    cat = catalogs.get()
    meta = cat.meta if cat else {}
    place = Place(meta.get('home_name', 'Bengaluru'), meta.get('home_lat', 12.9716), meta.get('home_lon', 77.5946), meta.get('tz_minutes', 330))
    llm = LLM(os.environ.get('TELLY_LLM_URL', 'http://auth2api.apps.svc.cluster.local:8317'), os.environ.get('TELLY_LLM_KEY', ''),
              os.environ.get('TELLY_LLM_MODEL', 'claude-sonnet-5'))
    # The curator (choosing and ordering songs, and the story along them) runs on the model with the best ear.
    curator_llm = LLM(os.environ.get('TELLY_LLM_URL', 'http://auth2api.apps.svc.cluster.local:8317'), os.environ.get('TELLY_LLM_KEY', ''),
                      os.environ.get('TELLY_CURATOR_MODEL', 'claude-fable-5'))
    canvas = Canvas(store, catalogs, place, llm, curator_llm)
    if a.role == 'serve':
        lyrics = Lyrics(os.environ.get('TELLY_LYRICS_URL', ''), os.environ.get('TELLY_LYRICS_TOKEN', ''))
        genius = Genius(store, llm, os.environ.get('TELLY_GENIUS_TOKEN', ''))
        serve(PublicApp(os.environ.get('TELLY_TOKEN', ''), canvas, store, catalogs, lyrics=lyrics, genius=genius), a.host, a.port or 8080)
    else:
        vault = Vault(a.vault)
        migrate_secrets(store, vault)
        worker = Worker(store, Services(vault), canvas)
        admin = AdminApp(os.environ.get('TELLY_ADMIN_TOKEN', ''), worker, vault, catalogs)
        worker.start()
        serve(admin, a.host, a.port or 8081, slots=8)


if __name__ == '__main__':
    main()
