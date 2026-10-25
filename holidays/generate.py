"""Writes holidays.json: every trading session of NSE, TSE, LSE and NYSE for this year and next.

    pip install exchange_calendars && python holidays/generate.py holidays/holidays.json

The home screen downloads the file once a day. Each market lists its trading days in local time,
"0915-1530", or with a lunch break "0900-1130,1230-1530". A weekday between "from" and "until" that
isn't listed is a holiday. Outside that range the app falls back to the dates built into it.
"""
import datetime
import json
import sys

import exchange_calendars as xcals

# Exchange shown on the TV -> exchange_calendars code. India has no NSE calendar; BSE (XBOM) shares
# NSE's trading holidays.
MARKETS = {"NSE": "XBOM", "TSE": "XTKS", "LSE": "XLON", "NYSE": "XNYS"}


def hhmm(ts, tz):
    return ts.tz_convert(tz).strftime("%H%M")


def market(code, start, end):
    # Calendars kept as a typed-in holiday list (India) refuse years not listed yet: stop a year
    # earlier, and "until" tells the app where the list ends.
    while True:
        try:
            cal = xcals.get_calendar(code, start=start, end=end)
            break
        except ValueError:
            if end.year <= start.year:
                raise
            end = datetime.date(end.year - 1, 12, 31)
    days = {}
    for day, s in cal.schedule.loc[str(start):str(end)].iterrows():
        tz = cal.tz
        if s.break_start is not None and s.break_start == s.break_start:  # NaT != NaT
            v = f"{hhmm(s.open, tz)}-{hhmm(s.break_start, tz)},{hhmm(s.break_end, tz)}-{hhmm(s.close, tz)}"
        else:
            v = f"{hhmm(s.open, tz)}-{hhmm(s.close, tz)}"
        days[day.strftime("%Y-%m-%d")] = v
    return {"from": str(start), "until": str(end), "days": days}


def main(out):
    year = datetime.date.today().year
    start, end = datetime.date(year, 1, 1), datetime.date(year + 1, 12, 31)
    data = {"source": f"exchange_calendars {xcals.__version__}",
            "markets": {name: market(code, start, end) for name, code in MARKETS.items()}}
    with open(out, "w") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv[1])
