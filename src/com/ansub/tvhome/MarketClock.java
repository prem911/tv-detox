package com.ansub.tvhome;

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

// A stock market's local time and whether it's open, for the world clocks row.
// Sessions come from holidays/holidays.json when it's been downloaded (see holidays/generate.py:
// exchange_calendars, refreshed by a GitHub Action, with early closes and Tokyo's lunch break).
// Outside the dates it covers, or before the first download, the dates typed in below are used.
// Holidays and early closes come from each exchange's own calendar (fetched 25 October 2026):
//   NSE  nseindia.com holiday-master, 2026 only (2027 isn't published yet)
//   TSE  jpx.co.jp Market Holidays, 2026-2027
//   LSE  England and Wales bank holidays (gov.uk), 2026-2027; 24 and 31 December close at 12:30
//   NYSE nyse.com Holidays & Trading Hours, 2026-2027
// ponytail: past both, every weekday counts as a trading day. Also not covered: NSE's Muhurat
// session, and countdowns that cross a clock change are an hour out.
final class MarketClock {
    static final MarketClock[] ALL = {
        new MarketClock("🇮🇳", "NSE", "Asia/Kolkata", 9 * 60 + 15, 15 * 60 + 30,
            "2026-01-15 2026-01-26 2026-03-03 2026-03-26 2026-03-31 2026-04-03 2026-04-14 2026-05-01"
            + " 2026-05-28 2026-06-26 2026-09-14 2026-10-02 2026-10-20 2026-11-10 2026-11-24 2026-12-25", ""),
        new MarketClock("🇯🇵", "TSE", "Asia/Tokyo", 9 * 60, 15 * 60 + 30,
            "2026-01-01 2026-01-02 2026-01-12 2026-02-11 2026-02-23 2026-03-20 2026-04-29 2026-05-04"
            + " 2026-05-05 2026-05-06 2026-07-20 2026-08-11 2026-09-21 2026-09-22 2026-09-23 2026-10-12"
            + " 2026-11-03 2026-11-23 2026-12-31"
            + " 2027-01-01 2027-01-11 2027-02-11 2027-02-23 2027-03-22 2027-04-29 2027-05-03 2027-05-04"
            + " 2027-05-05 2027-07-19 2027-08-11 2027-09-20 2027-09-23 2027-10-11 2027-11-03 2027-11-23"
            + " 2027-12-31", ""),
        new MarketClock("🇬🇧", "LSE", "Europe/London", 8 * 60, 16 * 60 + 30,
            "2026-01-01 2026-04-03 2026-04-06 2026-05-04 2026-05-25 2026-08-31 2026-12-25 2026-12-28"
            + " 2027-01-01 2027-03-26 2027-03-29 2027-05-03 2027-05-31 2027-08-30 2027-12-27 2027-12-28",
            "2026-12-24=12:30 2026-12-31=12:30 2027-12-24=12:30 2027-12-31=12:30"),
        new MarketClock("🇺🇸", "NYSE", "America/New_York", 9 * 60 + 30, 16 * 60,
            "2026-01-01 2026-01-19 2026-02-16 2026-04-03 2026-05-25 2026-06-19 2026-07-03 2026-09-07"
            + " 2026-11-26 2026-12-25"
            + " 2027-01-01 2027-01-18 2027-02-15 2027-03-26 2027-05-31 2027-06-18 2027-07-05 2027-09-06"
            + " 2027-11-25 2027-12-24",
            "2026-11-27=13:00 2026-12-24=13:00 2027-11-26=13:00"),
    };

    final String flag, exchange, zone; // flag: emoji, India, Japan, UK, US
    final int open, close; // minutes after midnight, market time
    final String holidays, earlyCloses; // "yyyy-MM-dd ..." and "yyyy-MM-dd=HH:mm ..."
    // From holidays.json: trading days from..until, like "0915-1530" or "0900-1130,1230-1530".
    private String from, until;
    private Map<String, String> sessions;

    void setSessions(String from, String until, Map<String, String> sessions) {
        this.from = from; this.until = until; this.sessions = sessions;
    }

    MarketClock(String flag, String exchange, String zone, int open, int close, String holidays, String earlyCloses) {
        this.flag = flag; this.exchange = exchange; this.zone = zone; this.open = open; this.close = close;
        this.holidays = holidays; this.earlyCloses = earlyCloses;
    }

    // Minutes until the market closes (positive: it's open) or opens (negative: it's closed).
    int minutesToChange(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(zone));
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        // The longest closure (Tokyo's New Year plus a weekend) is well under two weeks.
        for (int d = 0; d < 14; d++, c.add(Calendar.DAY_OF_MONTH, 1)) {
            int dow = c.get(Calendar.DAY_OF_WEEK);
            String day = String.format(Locale.US, "%04d-%02d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            for (int[] s : sessionsOn(day, dow)) {
                int o = d * 1440 + s[0], e = d * 1440 + s[1];
                if (m < o) return m - o;
                if (m < e) return e - m;
            }
        }
        throw new IllegalStateException("no trading day within two weeks for " + exchange);
    }

    // A day's trading sessions in market minutes: none on a holiday, two around a lunch break.
    private int[][] sessionsOn(String day, int dow) {
        if (sessions != null && day.compareTo(from) >= 0 && day.compareTo(until) <= 0) {
            String v = sessions.get(day);
            if (v == null) return new int[0][];
            String[] parts = v.split(",");
            int[][] out = new int[parts.length][];
            for (int i = 0; i < parts.length; i++)
                out[i] = new int[]{minutes(parts[i].substring(0, 4)), minutes(parts[i].substring(5, 9))};
            return out;
        }
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY || holidays.contains(day)) return new int[0][];
        return new int[][]{{open, closeOn(day)}};
    }

    private static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(2));
    }

    private int closeOn(String day) {
        int i = earlyCloses.indexOf(day + "=");
        if (i < 0) return close;
        String hm = earlyCloses.substring(i + day.length() + 1, i + day.length() + 6);
        return Integer.parseInt(hm.substring(0, 2)) * 60 + Integer.parseInt(hm.substring(3));
    }

    boolean isOpen(long now) { return minutesToChange(now) > 0; }

    String status(long now) {
        int t = minutesToChange(now);
        return t > 0 ? "Closes in " + span(t) : "Opens in " + span(-t);
    }

    static String span(int min) {
        if (min >= 1440) return (min / 1440) + "d " + (min % 1440 / 60) + "h";
        if (min >= 60) return String.format(Locale.US, "%dh %02dm", min / 60, min % 60);
        return min + "m";
    }

    // Self-check: java -ea -cp out/check com.ansub.tvhome.MarketClock
    public static void main(String[] a) {
        MarketClock nse = ALL[0], tse = ALL[1], lse = ALL[2], nyse = ALL[3];
        assert nse.minutesToChange(at("Asia/Kolkata", 2026, 10, 26, 10, 0)) == 330;            // Monday 10:00, open
        assert nse.minutesToChange(at("Asia/Kolkata", 2026, 10, 26, 9, 0)) == -15;             // Monday before the bell
        assert nse.minutesToChange(at("Asia/Kolkata", 2026, 10, 30, 15, 30)) == -(2 * 1440 + 1065); // Friday close
        assert nse.minutesToChange(at("Asia/Kolkata", 2026, 10, 25, 12, 0)) == -(1275);        // Sunday noon
        assert nyse.isOpen(at("Asia/Kolkata", 2026, 10, 26, 20, 0));                           // NYSE, 10:30 New York
        assert "Opens in 2d 17h".equals(nse.status(at("Asia/Kolkata", 2026, 10, 30, 15, 30)));
        assert "Closes in 5h 30m".equals(nse.status(at("Asia/Kolkata", 2026, 10, 26, 10, 0)));
        // Holidays: Guru Nanak Jayanti (Tuesday) opens Wednesday 09:15.
        assert "Opens in 23h 15m".equals(nse.status(at("Asia/Kolkata", 2026, 11, 24, 10, 0)));
        assert !nyse.isOpen(at("America/New_York", 2026, 11, 26, 11, 0));                      // Thanksgiving
        // Tokyo's New Year: closed 31 Dec to 1 Jan, then the weekend; opens Monday 4 Jan 09:00.
        assert tse.minutesToChange(at("Asia/Tokyo", 2026, 12, 31, 10, 0)) == -(4 * 1440 - 60);
        // Early closes: NYSE at 13:00 the day after Thanksgiving, LSE at 12:30 on Christmas Eve.
        assert nyse.minutesToChange(at("America/New_York", 2026, 11, 27, 12, 0)) == 60;
        assert !nyse.isOpen(at("America/New_York", 2026, 11, 27, 14, 0));
        assert lse.minutesToChange(at("Europe/London", 2026, 12, 24, 12, 0)) == 30;
        // Downloaded sessions: Tokyo's lunch break, a listed Saturday session, an unlisted weekday.
        MarketClock t = new MarketClock("", "TSE", "Asia/Tokyo", 9 * 60, 15 * 60 + 30, "", "");
        java.util.Map<String, String> days = new java.util.HashMap<>();
        days.put("2026-10-26", "0900-1130,1230-1530");
        days.put("2026-10-31", "0900-1200");
        t.setSessions("2026-10-26", "2026-10-31", days);
        assert t.minutesToChange(at("Asia/Tokyo", 2026, 10, 26, 11, 0)) == 30;                 // morning session
        assert t.minutesToChange(at("Asia/Tokyo", 2026, 10, 26, 12, 0)) == -30;                // lunch
        assert t.minutesToChange(at("Asia/Tokyo", 2026, 10, 26, 15, 0)) == 30;                 // afternoon
        assert t.minutesToChange(at("Asia/Tokyo", 2026, 10, 27, 10, 0)) == -(4 * 1440 - 60);   // 27-30 unlisted
        assert t.minutesToChange(at("Asia/Tokyo", 2026, 10, 31, 15, 0)) == -(2 * 1440 - 360);  // past "until"
        System.out.println("MarketClock ok");
    }

    private static long at(String zone, int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(zone));
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }
}
