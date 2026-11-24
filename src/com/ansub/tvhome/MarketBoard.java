package com.ansub.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The market board on the home screen: a scrolling strip of world indices and a Nifty 50 grid, one
// equal box per stock in index-weight order, coloured by the day's change. Refreshed every 5 minutes
// while Home is in front, with a countdown to the next refresh.
//
// ponytail: both sources are the websites' own data, not published APIs. NSE's index-tracker page
// calls the JSON endpoints below; Groww's global-indices page embeds its quotes in __NEXT_DATA__.
// If either site changes, those quotes stop updating (their open dots go grey) until this is fixed.
final class MarketBoard {
    private static final String NSE = "https://www.nseindia.in/api/NextApi/apiClient";
    private static final String GROWW = "https://groww.in/indices/global-indices";
    // NSE drops connections that don't look like a browser.
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130 Safari/537.36";
    private static final long REFRESH = 5 * 60 * 1000, RETRY = 30 * 1000; // retry sooner when nothing came back
    private static final long OPEN_AGE = 20 * 60 * 1000; // a quote newer than this means its market is open
    // NSE's trading days and hours (holidays/holidays.json in this repo, kept current by its GitHub
    // Action). Outside them NSE isn't asked: its numbers can't change. Downloaded once a day.
    private static final String HOLIDAYS_URL =
            "https://raw.githubusercontent.com/prem911/tv-detox/main/holidays/holidays.json";
    private static final int NSE_EARLY = 15, NSE_LATE = 15; // minutes polled before the open and after the close

    // Strip, east to west: flag, label, source ("nse:" index, "gift", or Groww's name), futures?
    private static final String[][] STRIP = {
        {"🇯🇵", "NIKKEI 225", "NIKKEI", ""}, {"🇰🇷", "KOSPI", "KOSPI", ""}, {"🇭🇰", "HANG SENG", "HANG SENG", ""},
        {"🇮🇳", "GIFT NIFTY", "gift", "fut"}, {"🇮🇳", "NIFTY 50", "nse:NIFTY 50", ""},
        {"🇮🇳", "NIFTY BANK", "nse:NIFTY BANK", ""}, {"🇩🇪", "DAX", "DAX", ""}, {"🇫🇷", "CAC 40", "CAC", ""},
        {"🇬🇧", "FTSE 100", "FTSE 100", ""}, {"🇺🇸", "DOW FUT", "Dow Futures", "fut"},
        {"🇺🇸", "DOW JONES", "Dow", ""}, {"🇺🇸", "S&P 500", "S&P", ""},
    };
    // Where a long NSE symbol may go onto two lines; it only does if it doesn't fit on one.
    // A symbol that doesn't fit and isn't listed gets smaller instead.
    private static final String[] BREAKS = {"HDFC|BANK", "ICICI|BANK", "BHARTI|ARTL", "AXIS|BANK", "KOTAK|BANK",
        "BAJ|FINANCE", "HIND|UNILVR", "SUN|PHARMA", "ULTRA|CEMCO", "TATA|STEEL", "POWER|GRID", "ADANI|PORTS",
        "BAJAJ|FINSV", "JSW|STEEL", "ASIAN|PAINT", "HIND|ALCO", "COAL|INDIA", "BAJAJ-|AUTO", "SHRIRAM|FIN",
        "NESTLE|IND", "EICHER|MOT", "HDFC|LIFE", "APOLLO|HOSP", "TATA|CONSUM", "MAX|HEALTH", "ADANI|ENT", "DR|REDDY"};
    private static final int COLS = 10;
    // Layout, designed at 1920x1080 like the rest of the page.
    static final int STRIP_TOP = 116, STRIP_H = 84, GRID_TOP = 212, BOTTOM = 1080;
    private static final int CARD_W = 300, CARD_GAP = 16, SPEED = 40; // SPEED: strip scroll, px per second

    static final class Quote { double value, pct; long time; }
    static final class Stock { String symbol; double weight, price, pct; }

    private final Context ctx;
    private final float scale;
    private final Typeface regular, semibold;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout grid;
    final TextView countdown;
    // Each strip label has two cards: the strip holds two copies of itself so it loops without a gap.
    private final Map<String, List<View>> cards = new HashMap<>();
    private final Map<String, Quote> quotes = new HashMap<>();
    private final ValueAnimator scroll;
    private long nextFetch;
    private boolean running, fetching;
    private volatile boolean haveNifty; // fetched NSE at least once since Home started
    private volatile String nseFrom, nseUntil;
    private volatile Map<String, String> nseDays;

    MarketBoard(Context ctx, FrameLayout root, float scale, Typeface regular, Typeface semibold) {
        this.ctx = ctx;
        this.scale = scale;
        this.regular = regular;
        this.semibold = semibold;
        int width = ctx.getResources().getDisplayMetrics().widthPixels;

        FrameLayout strip = new FrameLayout(ctx);
        final LinearLayout row = new LinearLayout(ctx);
        row.setPadding(px(48), 0, 0, 0);
        for (int copy = 0; copy < 2; copy++)
            for (String[] s : STRIP) {
                View card = card(s[0] + " " + s[1], !s[3].isEmpty());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(STRIP_H));
                lp.rightMargin = px(CARD_GAP);
                row.addView(card, lp);
                List<View> l = cards.get(s[1]);
                if (l == null) cards.put(s[1], l = new ArrayList<>());
                l.add(card);
            }
        int loop = STRIP.length * px(CARD_W + CARD_GAP);
        // Its full width, not the screen's: cards past the edge must be laid out to scroll in.
        strip.addView(row, new FrameLayout.LayoutParams(px(48) + 2 * loop, -1));
        root.addView(strip, at(0, px(STRIP_TOP), width, px(STRIP_H)));
        scroll = ValueAnimator.ofFloat(0, -loop);
        scroll.setDuration(loop * 1000L / px(SPEED));
        scroll.setInterpolator(new LinearInterpolator());
        scroll.setRepeatCount(ValueAnimator.INFINITE);
        scroll.addUpdateListener(a -> row.setTranslationX((Float) a.getAnimatedValue()));

        grid = new FrameLayout(ctx);
        grid.setBackgroundColor(0xFF050405);
        root.addView(grid, at(0, px(GRID_TOP), width, px(BOTTOM - GRID_TOP)));

        countdown = text(px(26), 0xA8FFFFFF, regular);
        countdown.setFontFeatureSettings("tnum");
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.topMargin = px(46); // level with the network icon
        root.addView(countdown, cp);
        loadHolidays();
    }

    // Home came to the front: catch up if a refresh is due, and start the clock and the strip.
    void start() {
        running = true;
        if (System.currentTimeMillis() >= nextFetch) fetch();
        tick.run();
        if (scroll.isPaused()) scroll.resume(); else if (!scroll.isStarted()) scroll.start();
    }

    // An app or Settings is in front: no fetching, no animating.
    void stop() {
        running = false;
        ui.removeCallbacks(tick);
        scroll.pause();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long left = nextFetch - System.currentTimeMillis();
            if (left <= 0 && !fetching) fetch();
            long s = Math.max(0, left + 999) / 1000;
            countdown.setText(String.format(Locale.US, "↻ %d:%02d", s / 60, s % 60));
            if (running) ui.postDelayed(this, 1000);
        }
    };

    private void fetch() {
        fetching = true;
        nextFetch = System.currentTimeMillis() + REFRESH;
        new Thread(() -> {
            // Each source on its own, so one failing keeps the others (and its last good numbers).
            final Map<String, Quote> got = new HashMap<>();
            List<Stock> stocks = null;
            refreshHolidays();
            // NSE only while it trades, plus once at the start so a holiday still shows the last close.
            if (!haveNifty || nseLive(nseFrom, nseUntil, nseDays, System.currentTimeMillis())) {
                try { stocks = nifty(); } catch (Exception ignored) {}
                for (String[] s : STRIP) if (s[2].startsWith("nse:"))
                    try { got.put(s[1], nseIndex(s[2].substring(4))); } catch (Exception ignored) {}
            }
            try { got.put("GIFT NIFTY", gift()); } catch (Exception ignored) {}
            try {
                Map<String, Quote> world = groww();
                for (String[] s : STRIP) if (world.containsKey(s[2])) got.put(s[1], world.get(s[2]));
            } catch (Exception ignored) {}
            final List<Stock> st = stocks;
            ui.post(() -> {
                fetching = false;
                if (st == null && got.isEmpty()) nextFetch = System.currentTimeMillis() + RETRY;
                quotes.putAll(got);
                showStrip();
                if (st != null) {
                    haveNifty = true;
                    showGrid(st);
                }
            });
        }).start();
    }

    // ---- Trading hours ----

    // Should NSE be asked now? Not on holidays and weekends, nor outside the session (with a margin
    // either side). Without a calendar for today (not downloaded yet, or past its end), yes.
    static boolean nseLive(String from, String until, Map<String, String> days, long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"), Locale.US);
        c.setTimeInMillis(now);
        String day = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));
        if (days == null || day.compareTo(from) < 0 || day.compareTo(until) > 0) return true;
        String v = days.get(day);
        if (v == null) return false; // a holiday or weekend
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        for (String part : v.split(",")) { // "0915-1530", or several for markets with a lunch break
            int open = Integer.parseInt(part.substring(0, 2)) * 60 + Integer.parseInt(part.substring(2, 4));
            int close = Integer.parseInt(part.substring(5, 7)) * 60 + Integer.parseInt(part.substring(7, 9));
            if (m >= open - NSE_EARLY && m <= close + NSE_LATE) return true;
        }
        return false;
    }

    private File holidaysFile() { return new File(ctx.getFilesDir(), "holidays.json"); }

    private void loadHolidays() {
        try (InputStream in = new FileInputStream(holidaysFile())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject nse = new JSONObject(out.toString("UTF-8")).getJSONObject("markets").getJSONObject("NSE");
            JSONObject d = nse.getJSONObject("days");
            Map<String, String> days = new HashMap<>();
            for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) {
                String k = it.next();
                days.put(k, d.getString(k));
            }
            nseFrom = nse.getString("from");
            nseUntil = nse.getString("until");
            nseDays = days;
        } catch (Exception ignored) {} // not downloaded yet, or unreadable: NSE is polled all the time
    }

    // On the fetch thread: download a fresh calendar once a day, keeping the last good one on failure.
    private void refreshHolidays() {
        File f = holidaysFile();
        if (System.currentTimeMillis() - f.lastModified() < 24L * 60 * 60 * 1000) return;
        try {
            String body = get(HOLIDAYS_URL);
            new JSONObject(body).getJSONObject("markets").getJSONObject("NSE"); // only keep a good file
            File tmp = new File(ctx.getFilesDir(), "holidays.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(body.getBytes("UTF-8")); }
            if (tmp.renameTo(f)) loadHolidays();
        } catch (Exception ignored) {} // offline or GitHub down: tried again at the next refresh
    }

    // ---- Sources ----

    static List<Stock> nifty() throws Exception {
        JSONArray a = new JSONObject(get(NSE + "/indexTrackerApi?functionName=getConstituents&&index=NIFTY%2050&&noofrecords=0"))
                .getJSONArray("data");
        List<Stock> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Stock s = new Stock();
            s.symbol = o.getString("cmSymbol");
            s.weight = o.getDouble("weightage");
            s.price = o.getDouble("lasttradedPrice");
            s.pct = o.getDouble("pchange");
            out.add(s);
        }
        java.util.Collections.sort(out, (x, y) -> Double.compare(y.weight, x.weight));
        if (out.isEmpty()) throw new Exception("no constituents");
        return out;
    }

    static Quote nseIndex(String name) throws Exception {
        JSONObject o = new JSONObject(get(NSE + "/indexTrackerApi?functionName=getIndexData&&index="
                + name.replace(" ", "%20"))).getJSONArray("data").getJSONObject(0);
        return quote(o.getDouble("last"), o.getDouble("percChange"), nseTime(o.getString("timeVal")));
    }

    static Quote gift() throws Exception {
        JSONObject o = new JSONObject(get(NSE + "?functionName=getGiftNifty")).getJSONObject("data").getJSONObject("giftNifty");
        return quote(o.getDouble("lastprice"), o.getDouble("perchange"), nseTime(o.getString("timestmp")));
    }

    static Map<String, Quote> groww() throws Exception {
        Matcher m = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(get(GROWW));
        if (!m.find()) throw new Exception("no __NEXT_DATA__");
        JSONArray a = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("data").getJSONArray("aggregatedGlobalInstrumentDto");
        Map<String, Quote> out = new HashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i), p = o.getJSONObject("livePriceDto");
            out.put(o.getJSONObject("instrumentDetailDto").getString("name").trim(),
                    quote(p.getDouble("value"), p.getDouble("dayChangePerc"), p.getLong("tsInMillis")));
        }
        return out;
    }

    private static Quote quote(double value, double pct, long time) {
        Quote q = new Quote();
        q.value = value;
        q.pct = pct;
        q.time = time;
        return q;
    }

    // "23-Nov-2026 15:30", India time.
    static long nseTime(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.parse(s).getTime();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---- Views ----

    // Name and open dot on top; under it the value, small, and the change, big.
    private View card(String name, boolean futures) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(px(20), 0, px(20), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(px(16));
        if (futures) bg.setStroke(px(2), 0x38FFFFFF, px(8), px(6)); // dashed: futures
        card.setBackground(bg);

        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(px(27), 0xFFFFFFFF, semibold);
        n.setText(name);
        n.setSingleLine(true);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        View dot = new View(ctx);
        dot.setTag("dot");
        top.addView(dot, new LinearLayout.LayoutParams(px(12), px(12)));
        card.addView(top);

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setGravity(Gravity.BOTTOM);
        TextView v = text(px(21), 0xB0FFFFFF, regular);
        v.setTag("value");
        v.setText("—");
        v.setFontFeatureSettings("tnum");
        bottom.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        TextView c = text(px(29), 0xFFFFFFFF, semibold);
        c.setTag("pct");
        c.setFontFeatureSettings("tnum");
        bottom.addView(c);
        card.addView(bottom);
        dot(dot, false);
        return card;
    }

    private void showStrip() {
        DecimalFormat two = new DecimalFormat("#,##0.00");
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Quote> e : quotes.entrySet()) {
            Quote q = e.getValue();
            List<View> l = cards.get(e.getKey());
            if (l == null) continue;
            for (View card : l) {
                ((TextView) card.findViewWithTag("value")).setText(two.format(q.value));
                TextView c = card.findViewWithTag("pct");
                c.setText(String.format(Locale.US, "%s %.2f%%", q.pct >= 0 ? "▲" : "▼", Math.abs(q.pct)));
                c.setTextColor(q.pct >= 0 ? 0xFF3DDC84 : 0xFFFF6B6B);
                dot(card.findViewWithTag("dot"), now - q.time < OPEN_AGE);
            }
        }
    }

    private static void dot(View v, boolean open) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(open ? 0xFF3DDC84 : 0x40FFFFFF);
        v.setBackground(d);
    }

    private void showGrid(List<Stock> stocks) {
        grid.removeAllViews();
        int w = grid.getLayoutParams().width, h = grid.getLayoutParams().height, gap = Math.max(1, px(3));
        int rows = (stocks.size() + COLS - 1) / COLS;
        DecimalFormat whole = new DecimalFormat("#,##0");
        for (int i = 0; i < stocks.size(); i++) {
            Stock s = stocks.get(i);
            int c = i % COLS, r = i / COLS;
            int x = c * w / COLS, y = r * h / rows;
            int cw = (c + 1) * w / COLS - x - (c < COLS - 1 ? gap : 0);
            int ch = (r + 1) * h / rows - y - (r < rows - 1 ? gap : 0);
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(shade(s.pct));
            TextView name = text(px(31), 0xFFFFFFFF, semibold);
            name.setGravity(Gravity.CENTER);
            name.setLineSpacing(0, 0.95f);
            fitName(name, s.symbol, cw - px(12));
            cell.addView(name);
            TextView price = text(px(26), 0xEBFFFFFF, regular);
            price.setText(whole.format(s.price));
            price.setFontFeatureSettings("tnum");
            price.setGravity(Gravity.CENTER);
            cell.addView(price);
            TextView pct = text(px(28), 0xFFFFFFFF, semibold);
            pct.setText(String.format(Locale.US, "%s%.2f%%", s.pct >= 0 ? "+" : "−", Math.abs(s.pct)));
            pct.setFontFeatureSettings("tnum");
            pct.setGravity(Gravity.CENTER);
            cell.addView(pct);
            grid.addView(cell, at(x, y, cw, ch));
        }
    }

    // One line if it fits; else two at its break; else one line, smaller.
    private static void fitName(TextView t, String symbol, int room) {
        Paint p = t.getPaint();
        if (p.measureText(symbol) > room) {
            for (String b : BREAKS)
                if (b.replace("|", "").equals(symbol)) {
                    t.setText(b.replace("|", "\n"));
                    return;
                }
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * room / p.measureText(symbol));
        }
        t.setText(symbol);
    }

    // Grey at 0%, full red or green at ±3% and beyond.
    static int shade(double pct) {
        double t = 0.25 + 0.75 * Math.min(Math.abs(pct) / 3, 1);
        int[] end = pct >= 0 ? new int[] {16, 150, 72} : new int[] {222, 52, 64}, base = {38, 38, 42};
        int r = (int) Math.round(base[0] + (end[0] - base[0]) * t), g = (int) Math.round(base[1] + (end[1] - base[1]) * t),
            b = (int) Math.round(base[2] + (end[2] - base[2]) * t);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private TextView text(int size, int color, Typeface tf) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private int px(int v) { return Math.round(v * scale); }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }
}
