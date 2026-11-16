"""Where and when: local time at home, and the weather for an hour (Open-Meteo, no key)."""
import datetime as dt
import json
import threading
import time
import urllib.parse
import urllib.request

WMO = {0: 'clear', 1: 'mostly clear', 2: 'partly cloudy', 3: 'overcast', 45: 'fog', 48: 'fog', 51: 'light drizzle', 53: 'drizzle',
       55: 'heavy drizzle', 61: 'light rain', 63: 'rain', 65: 'heavy rain', 71: 'light snow', 73: 'snow', 75: 'heavy snow',
       80: 'showers', 81: 'showers', 82: 'heavy showers', 95: 'thunderstorm', 96: 'thunderstorm', 99: 'thunderstorm'}


class Place:
    def __init__(self, name='Bengaluru', lat=12.9716, lon=77.5946, tz_minutes=330):
        self.name, self.lat, self.lon = name, float(lat), float(lon)
        self.tz = dt.timezone(dt.timedelta(minutes=int(tz_minutes)))
        self._wx, self._wx_at, self._lock = None, 0, threading.Lock()

    def now(self):
        return dt.datetime.now(self.tz)

    def today(self):
        return self.now().date().isoformat()

    def at(self, day, hhmm):
        """'2026-11-16', '17:00' -> aware datetime at home."""
        h, m = (int(x) for x in hhmm.split(':')[:2])
        y, mo, d = (int(x) for x in day.split('-'))
        return dt.datetime(y, mo, d, h % 24, m % 60, tzinfo=self.tz)

    def forecast(self):
        with self._lock:
            # A good forecast is kept half an hour; a failure is remembered for five minutes, so an unreachable
            # weather service costs one timeout per five minutes rather than one per call.
            if time.time() - self._wx_at < (1800 if self._wx else 300):
                return self._wx
            q = urllib.parse.urlencode({'latitude': self.lat, 'longitude': self.lon, 'timezone': 'auto', 'forecast_days': 3,
                                        'hourly': 'temperature_2m,precipitation_probability,weather_code', 'daily': 'sunrise,sunset'})
            try:
                with urllib.request.urlopen(f'https://api.open-meteo.com/v1/forecast?{q}', timeout=6) as r:
                    self._wx, self._wx_at = json.loads(r.read()), time.time()
            except Exception:
                self._wx_at = time.time()
            return self._wx

    def weather(self, when):
        """A short line about the weather at `when` (aware datetime), or None."""
        f = self.forecast()
        if not f:
            return None
        key = when.strftime('%Y-%m-%dT%H:00')
        h = f.get('hourly', {})
        try:
            i = h['time'].index(key)
        except (KeyError, ValueError):
            return None
        temp, code, rain = h['temperature_2m'][i], h['weather_code'][i], h['precipitation_probability'][i]
        s = f"{round(temp)}°, {WMO.get(code, 'mixed')}"
        if rain is not None and rain >= 40 and code < 51:
            s += f', {rain}% chance of rain'
        return s

    def sun(self, day):
        f = self.forecast()
        if not f:
            return None
        d = f.get('daily', {})
        try:
            i = d['time'].index(day)
        except (KeyError, ValueError):
            return None
        return {'sunrise': d['sunrise'][i][-5:], 'sunset': d['sunset'][i][-5:]}


def part_of_day(hour):
    return ('night' if hour < 5 else 'early morning' if hour < 8 else 'morning' if hour < 12 else 'afternoon' if hour < 17
            else 'evening' if hour < 21 else 'night')


def clock(hhmm):
    """'17:00' -> '5 pm', '06:30' -> '6:30 am'."""
    h, m = (int(x) for x in hhmm.split(':')[:2])
    ap = 'am' if h < 12 else 'pm'
    h12 = h % 12 or 12
    return f'{h12}:{m:02d} {ap}' if m else f'{h12} {ap}'
