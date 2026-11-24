package com.ansub.tvhome;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

// Checks MarketBoard.nseLive, the rule for when NSE gets polled. Runs on a computer, no TV:
//   sh ci/market-hours-check.sh
public class MarketHoursCheck {
    public static void main(String[] a) throws Exception {
        Map<String, String> days = new HashMap<>();
        days.put("2026-11-23", "0915-1530"); // Monday; Tuesday 24th is a holiday (not listed)
        days.put("2026-11-25", "0915-1530");
        String from = "2026-01-01", until = "2026-12-31";
        check(true, MarketBoard.nseLive(from, until, days, ist("2026-11-23 11:00")), "open mid-session");
        check(true, MarketBoard.nseLive(from, until, days, ist("2026-11-23 09:00")), "15 min before the open");
        check(false, MarketBoard.nseLive(from, until, days, ist("2026-11-23 08:59")), "earlier than that");
        check(true, MarketBoard.nseLive(from, until, days, ist("2026-11-23 15:45")), "15 min after the close");
        check(false, MarketBoard.nseLive(from, until, days, ist("2026-11-23 15:46")), "later than that");
        check(false, MarketBoard.nseLive(from, until, days, ist("2026-11-24 11:00")), "holiday");
        check(true, MarketBoard.nseLive(from, until, days, ist("2027-01-04 11:00")), "past the calendar: poll");
        check(true, MarketBoard.nseLive(null, null, null, ist("2026-11-24 11:00")), "no calendar: poll");
        days.put("2026-11-26", "0900-1130,1230-1530");
        check(false, MarketBoard.nseLive(from, until, days, ist("2026-11-26 12:00")), "lunch break");
        check(true, MarketBoard.nseLive(from, until, days, ist("2026-11-26 12:20")), "just before the afternoon");
        // Every box's text reads at 4.5:1 or better, for any move and every colour cap.
        for (double cap : new double[] {2, 3, 5})
            for (double pct = -6; pct <= 6; pct += 0.01) {
                int bg = MarketBoard.shade(pct, cap);
                check(true, MarketBoard.contrast(MarketBoard.ink(bg), bg) >= 4.5, "text contrast at " + pct + "%, cap " + cap);
            }
        System.out.println("ok");
    }

    static long ist(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.parse(s).getTime();
    }

    static void check(boolean want, boolean got, String what) {
        if (want != got) throw new AssertionError(what + ": expected " + want);
    }
}
