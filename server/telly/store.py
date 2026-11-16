"""telly.db: the canvas (days, sessions and every version of them), the song-resolution cache, and settings."""
import json
import os
import secrets
import sqlite3
import threading
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  created REAL NOT NULL,
  updated REAL NOT NULL,
  title TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  deleted INTEGER NOT NULL DEFAULT 0,
  ytm_id TEXT, ytm_state TEXT, ytm_error TEXT, ytm_synced INTEGER, ytm_count INTEGER,
  sp_id TEXT, sp_state TEXT, sp_error TEXT, sp_synced INTEGER,
  story TEXT, story_state TEXT,
  notes_state TEXT
);
CREATE INDEX IF NOT EXISTS sessions_day ON sessions(day);
CREATE TABLE IF NOT EXISTS versions (
  session_id TEXT NOT NULL,
  n INTEGER NOT NULL,
  created REAL NOT NULL,
  source TEXT NOT NULL,      -- prompt | tweak | edit | undo
  said TEXT,                 -- what you asked, or the tweak's name
  reply TEXT,                -- what telly did, in a line
  brief TEXT NOT NULL,
  tracks TEXT NOT NULL,
  parent INTEGER,            -- the version this one was made from (0 = none); undo goes back along it
  PRIMARY KEY (session_id, n)
);
CREATE TABLE IF NOT EXISTS resolved (
  id TEXT PRIMARY KEY,       -- catalog track id, or ext|artist|title for songs asked for by name
  ytm_vid TEXT, ytm_checked REAL,
  thumb TEXT,
  sp_uri TEXT, sp_checked REAL
);
CREATE TABLE IF NOT EXISTS served (track_id TEXT NOT NULL, ts REAL NOT NULL, session_id TEXT NOT NULL, pool TEXT);
CREATE INDEX IF NOT EXISTS served_ts ON served(ts);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
-- One line per song, as an excited friend would say it (reused for three weeks, so a tweak only writes new songs').
CREATE TABLE IF NOT EXISTS notes (track_id TEXT PRIMARY KEY, note TEXT NOT NULL, created REAL NOT NULL);
-- Wikipedia openings looked up for songs, films and artists (kept two months).
CREATE TABLE IF NOT EXISTS wiki (q TEXT PRIMARY KEY, title TEXT, extract TEXT, ts REAL NOT NULL);
-- Work for the worker process: one row per session waiting to be synced (a newer request replaces an older one).
CREATE TABLE IF NOT EXISTS jobs (session_id TEXT PRIMARY KEY, queued REAL NOT NULL);
"""


class Store:
    def __init__(self, path):
        self.path = path
        self.local = threading.local()
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with self.db() as c:
            c.executescript(SCHEMA)
            cols = {r['name'] for r in c.execute('PRAGMA table_info(versions)')}
            if 'parent' not in cols:  # databases made before undo walked back
                try:
                    c.execute('ALTER TABLE versions ADD COLUMN parent INTEGER')
                except sqlite3.OperationalError:
                    pass  # the other process added it first
            cols = {r['name'] for r in c.execute('PRAGMA table_info(sessions)')}
            if 'notes_state' not in cols:  # databases made before per-song notes
                try:
                    c.execute('ALTER TABLE sessions ADD COLUMN notes_state TEXT')
                except sqlite3.OperationalError:
                    pass  # the other process (api or worker) added it first
                # Recent playlists get their notes written; older ones simply have none (never "writing" forever).
                c.execute("UPDATE sessions SET notes_state = CASE WHEN created > ? THEN 'pending' ELSE 'none' END "
                          "WHERE notes_state IS NULL", (time.time() - 2 * 86400,))

    def db(self):
        c = getattr(self.local, 'c', None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=30, isolation_level=None)
            c.row_factory = sqlite3.Row
            c.execute('PRAGMA journal_mode=WAL')
            c.execute('PRAGMA foreign_keys=ON')
            self.local.c = c
        return _Tx(c)

    # -- key/value (settings and credentials; the file lives on the app's own volume) ---------------------------
    def get(self, k, default=None):
        with self.db() as c:
            r = c.execute('SELECT v FROM kv WHERE k=?', (k,)).fetchone()
        return json.loads(r['v']) if r else default

    def put(self, k, v):
        with self.db() as c:
            if v is None:
                c.execute('DELETE FROM kv WHERE k=?', (k,))
            else:
                c.execute('INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v', (k, json.dumps(v)))

    # -- sessions ------------------------------------------------------------------------------------------------
    def new_session(self, day, title):
        sid = secrets.token_hex(5)
        now = time.time()
        with self.db() as c:
            c.execute('INSERT INTO sessions(id, day, created, updated, title) VALUES(?,?,?,?,?)', (sid, day, now, now, title))
        return sid

    def session(self, sid):
        with self.db() as c:
            r = c.execute('SELECT * FROM sessions WHERE id=?', (sid,)).fetchone()
        return dict(r) if r else None

    def sessions_on(self, day):
        with self.db() as c:
            rows = c.execute('SELECT * FROM sessions WHERE day=? AND deleted=0 ORDER BY created', (day,)).fetchall()
        return [dict(r) for r in rows]

    def days_before(self, day, limit):
        with self.db() as c:
            rows = c.execute('SELECT DISTINCT day FROM sessions WHERE day < ? AND deleted=0 AND version > 0 ORDER BY day DESC LIMIT ?',
                             (day, limit)).fetchall()
        return [r['day'] for r in rows]

    def update_session(self, sid, **fields):
        if not fields:
            return
        fields['updated'] = time.time()
        cols = ', '.join(f'{k}=?' for k in fields)
        with self.db() as c:
            c.execute(f'UPDATE sessions SET {cols} WHERE id=?', (*fields.values(), sid))

    # -- versions ------------------------------------------------------------------------------------------------
    def add_version(self, sid, source, said, reply, brief, tracks, parent=None):
        """parent: the version this is made from; None means the current one (an ordinary change)."""
        with self.db() as c:
            c.execute('BEGIN IMMEDIATE')
            cur = c.execute('SELECT COALESCE(MAX(n), 0) FROM versions WHERE session_id=?', (sid,)).fetchone()[0]
            n = cur + 1
            c.execute('INSERT INTO versions(session_id, n, created, source, said, reply, brief, tracks, parent) VALUES(?,?,?,?,?,?,?,?,?)',
                      (sid, n, time.time(), source, said, reply, json.dumps(brief), json.dumps(tracks), cur if parent is None else parent))
            c.execute('UPDATE sessions SET version=?, updated=? WHERE id=?', (n, time.time(), sid))
            c.execute('COMMIT')
        return n

    def version(self, sid, n=None):
        with self.db() as c:
            if n is None:
                r = c.execute('SELECT * FROM versions WHERE session_id=? ORDER BY n DESC LIMIT 1', (sid,)).fetchone()
            else:
                r = c.execute('SELECT * FROM versions WHERE session_id=? AND n=?', (sid, n)).fetchone()
        if not r:
            return None
        v = dict(r)
        v['brief'], v['tracks'] = json.loads(v['brief']), json.loads(v['tracks'])
        return v

    def history(self, sid):
        with self.db() as c:
            rows = c.execute('SELECT n, created, source, said, reply FROM versions WHERE session_id=? ORDER BY n', (sid,)).fetchall()
        return [dict(r) for r in rows]

    # -- resolution cache ----------------------------------------------------------------------------------------
    def resolved(self, ids):
        if not ids:
            return {}
        out = {}
        ids = list(ids)
        with self.db() as c:
            for i in range(0, len(ids), 500):
                chunk = ids[i:i + 500]
                q = f"SELECT * FROM resolved WHERE id IN ({','.join('?' * len(chunk))})"
                for r in c.execute(q, chunk):
                    out[r['id']] = dict(r)
        return out

    def resolve_put(self, tid, **fields):
        cols = ', '.join(fields)
        with self.db() as c:
            c.execute(f"INSERT INTO resolved(id, {cols}) VALUES(?{', ?' * len(fields)}) "
                      f"ON CONFLICT(id) DO UPDATE SET {', '.join(f'{k}=excluded.{k}' for k in fields)}", (tid, *fields.values()))

    # -- the queue between the API and the worker --------------------------------------------------------------------
    def enqueue(self, sid, delay=0):
        with self.db() as c:
            c.execute('INSERT INTO jobs(session_id, queued) VALUES(?, ?) ON CONFLICT(session_id) DO UPDATE SET queued=excluded.queued',
                      (sid, time.time() + delay))

    def claim(self):
        """Take the oldest session that is due, or None. A request that arrives while it syncs queues it again."""
        with self.db() as c:
            c.execute('BEGIN IMMEDIATE')
            r = c.execute('SELECT session_id FROM jobs WHERE queued <= ? ORDER BY queued LIMIT 1', (time.time(),)).fetchone()
            if r:
                c.execute('DELETE FROM jobs WHERE session_id=?', (r['session_id'],))
            c.execute('COMMIT')
        return r['session_id'] if r else None

    def unfinished(self):
        """Sessions a restart may have interrupted mid-sync (the job is taken off the queue when work starts)."""
        with self.db() as c:
            rows = c.execute("SELECT id FROM sessions WHERE version > 0 AND (ytm_state IN ('pending','syncing') OR "
                             "sp_state IN ('pending','syncing') OR story_state IN ('pending','writing') OR notes_state IN ('pending','writing') OR "
                             "(deleted=1 AND (ytm_id IS NOT NULL OR sp_id IS NOT NULL)))").fetchall()
        return [r['id'] for r in rows]

    def scrub(self, keys):
        """Delete settings so their bytes are gone from the file and its journal too, not just unlinked."""
        with self.db() as c:
            c.execute('PRAGMA secure_delete=ON')
            for k in keys:
                c.execute('DELETE FROM kv WHERE k=?', (k,))
            c.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            c.execute('VACUUM')
            c.execute('PRAGMA wal_checkpoint(TRUNCATE)')

    # -- per-song notes and the Wikipedia openings behind them --------------------------------------------------------
    def notes_for(self, ids, max_age=None):
        """Notes by track id. `max_age` is for deciding what to write again; showing a playlist uses them all (a note
        written a month ago still belongs to a month-old playlist). An empty note means "nothing worth saying" and
        keeps the song from being asked about again."""
        ids = list(ids)
        if not ids:
            return {}
        out = {}
        with self.db() as c:
            for i in range(0, len(ids), 500):
                chunk = ids[i:i + 500]
                q = f"SELECT track_id, note FROM notes WHERE created > ? AND track_id IN ({','.join('?' * len(chunk))})"
                out.update({r['track_id']: r['note'] for r in c.execute(q, (time.time() - max_age if max_age else 0, *chunk))})
        return out

    def put_notes(self, notes):
        now = time.time()
        with self.db() as c:
            c.executemany('INSERT INTO notes(track_id, note, created) VALUES(?,?,?) ON CONFLICT(track_id) DO UPDATE SET '
                          'note=excluded.note, created=excluded.created', [(k, v, now) for k, v in notes.items()])

    def wiki_get(self, q, max_age=60 * 86400):
        with self.db() as c:
            r = c.execute('SELECT title, extract FROM wiki WHERE q=? AND ts > ?', (q, time.time() - max_age)).fetchone()
        return None if r is None else ((r['title'], r['extract']) if r['title'] else False)  # False = looked, found nothing

    def wiki_put(self, q, hit):
        with self.db() as c:
            c.execute('INSERT INTO wiki(q, title, extract, ts) VALUES(?,?,?,?) ON CONFLICT(q) DO UPDATE SET title=excluded.title, '
                      'extract=excluded.extract, ts=excluded.ts', (q, hit[0] if hit else None, hit[1] if hit else None, time.time()))

    # -- the day's lens: what he wants to learn about today -----------------------------------------------------------
    def lens(self, day):
        return (self.get(f'lens:{day}') or {}).get('text')

    def set_lens(self, day, text):
        self.put(f'lens:{day}', {'text': text, 'at': time.time()} if text else None)

    def stories_since(self, ts, exclude_session=None):
        """Earlier stories, newest first: [(day, title, first sentence, Wikipedia source)]."""
        with self.db() as c:
            rows = c.execute('SELECT id, day, story FROM sessions WHERE story IS NOT NULL AND created > ? AND id != ? AND deleted=0 '
                             'ORDER BY created DESC', (ts, exclude_session or '')).fetchall()
        out = []
        for r in rows:
            try:
                st = json.loads(r['story'])
            except ValueError:
                continue
            body = (st.get('body') or '').strip()
            first = body.split('. ')[0][:220] + ('.' if body else '')
            out.append((r['day'], st.get('title') or '', first, st.get('source') or ''))
        return out

    # -- what has been served (wear-out between sessions) --------------------------------------------------------
    def mark_served(self, sid, tracks):
        now = time.time()
        with self.db() as c:
            c.execute('DELETE FROM served WHERE session_id=?', (sid,))
            c.executemany('INSERT INTO served VALUES(?,?,?,?)', [(t['id'], now, sid, t.get('pool')) for t in tracks])

    def served_since(self, ts, exclude_session=None):
        with self.db() as c:
            rows = c.execute('SELECT track_id, MAX(ts) AS ts, pool FROM served WHERE ts > ? AND session_id != ? GROUP BY track_id',
                             (ts, exclude_session or '')).fetchall()
        return {r['track_id']: (r['ts'], r['pool']) for r in rows}


class _Tx:
    """`with store.db() as c:` hands out this thread's connection (autocommit; explicit BEGIN where it matters)."""

    def __init__(self, c):
        self.c = c

    def __enter__(self):
        return self.c

    def __exit__(self, et, ev, tb):
        if et is not None and self.c.in_transaction:
            self.c.execute('ROLLBACK')
        return False
