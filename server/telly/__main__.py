"""python -m telly --data /data --port 8080

Environment:
  TELLY_TOKEN       bearer token the app sends (required, 16+ characters)
  TELLY_LLM_URL     Anthropic-shaped gateway (default: auth2api in-cluster)
  TELLY_LLM_KEY     its key (without one, telly still works, reading messages with rules and writing no stories)
  TELLY_LLM_MODEL   default claude-sonnet-5
"""
import argparse
import logging
import os

from .api import App, Catalogs, serve
from .canvas import Canvas
from .context import Place
from .llm import LLM
from .store import Store
from .sync import Jobs, Services


def main():
    ap = argparse.ArgumentParser(prog='telly')
    ap.add_argument('--data', default=os.environ.get('TELLY_DATA', './data'))
    ap.add_argument('--host', default='0.0.0.0')
    ap.add_argument('--port', type=int, default=int(os.environ.get('PORT', '8080')))
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    os.makedirs(a.data, exist_ok=True)
    store = Store(os.path.join(a.data, 'telly.db'))
    catalogs = Catalogs(os.path.join(a.data, 'catalog.db'))
    cat = catalogs.get()
    meta = cat.meta if cat else {}
    place = Place(meta.get('home_name', 'Bengaluru'), meta.get('home_lat', 12.9716), meta.get('home_lon', 77.5946), meta.get('tz_minutes', 330))
    llm = LLM(os.environ.get('TELLY_LLM_URL', 'http://auth2api.apps.svc.cluster.local:8317'), os.environ.get('TELLY_LLM_KEY', ''),
              os.environ.get('TELLY_LLM_MODEL', 'claude-sonnet-5'))
    canvas = Canvas(store, catalogs, place, llm)
    services = Services(store)
    jobs = Jobs(store, services, canvas)
    canvas.jobs = jobs
    jobs.start()
    serve(App(os.environ.get('TELLY_TOKEN', ''), canvas, services, catalogs, store), a.host, a.port)


if __name__ == '__main__':
    main()
