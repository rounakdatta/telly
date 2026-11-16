"""Name normalisation: the same song spelled differently by Spotify, YouTube Music and Last.fm must compare equal."""
import re
import unicodedata


def strip_accents(s):
    return ''.join(c for c in unicodedata.normalize('NFKD', s) if not unicodedata.combining(c))


def nartist(s):
    s = strip_accents((s or '').lower()).strip()
    s = re.sub(r'^the\s+', '', s)
    s = s.replace('&', 'and')
    s = re.sub(r'[^\w\s]', '', s)
    return re.sub(r'\s+', ' ', s).strip()


def ntrack(s):
    s = strip_accents((s or '').lower())
    s = re.sub(r'\s*[\(\[][^\)\]]*(feat|ft\.|with|from|remaster|version|edit|mix|live|acoustic|reprise|unplugged|original|soundtrack|ost|slowed|reverb|lofi|lo-fi|cover|remix)[^\)\]]*[\)\]]', '', s)
    s = re.sub(r'\s+-\s+.*(remaster|version|edit|live|mono|stereo|mix|from|single|radio).*$', '', s)
    s = re.sub(r'\s*(feat\.|ft\.|featuring)\s.*$', '', s)
    s = re.sub(r'[^\w\s]', '', s)
    return re.sub(r'\s+', ' ', s).strip()
