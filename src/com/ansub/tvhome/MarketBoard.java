package com.ansub.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Choreographer;
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
// while Home is in front (every 5 minutes unless changed in Settings). Each box has a triangle along its bottom: where today's price sits
// between its 52-week low (left) and high (right). A 24-hour clock sits top right.
//
// ponytail: both sources are the websites' own data, not published APIs. NSE's index-tracker page
// calls the JSON endpoints below; Groww's global-indices page embeds its quotes in __NEXT_DATA__.
// If either site changes, those quotes stop updating (their open dots go grey) until this is fixed.
final class MarketBoard {
    private static final String NSE = "https://www.nseindia.in/api/NextApi/apiClient";
    private static final String GROWW = "https://groww.in/indices/global-indices";
    // Dhan's Nifty 50 table (dhan.co/nifty-stocks-list/nifty-50) loads from this: all 50 in one call,
    // with 52-week high and low, which NSE's constituents list doesn't have. No weights, so the grid
    // keeps NSE's order. ponytail: unofficial, like the others.
    private static final String DHAN = "https://ow-scanx-analytics.dhan.co/customscan/fetchdt";
    private static final String DHAN_QUERY = "{\"data\":{\"sort\":\"Mcap\",\"sorder\":\"desc\",\"count\":50,\"pgno\":1,"
            + "\"params\":[{\"field\":\"idxlist.Indexid\",\"op\":\"\",\"val\":\"13\"},"
            + "{\"field\":\"Exch\",\"op\":\"\",\"val\":\"NSE\"},{\"field\":\"OgInst\",\"op\":\"\",\"val\":\"ES\"}],"
            + "\"fields\":[\"Sym\",\"Ltp\",\"PPerchange\",\"High1Yr\",\"Low1Yr\",\"LastTradeTime\"]}}";
    // Settings > Market board: a label and its choices; the saved value is the choice's index.
    // "Prices from" picks the grid's first source, the other is its backup. Dhan gives the 52-week
    // numbers once a day either way.
    static final String[][] SETTINGS = {
        {"52-week marker", "On", "Off"},
        {"Refresh every", "1 min", "2 min", "5 min", "10 min", "15 min"},
        {"Colour cap", "±2%", "±3%", "±5%"},
        {"Prices from", "NSE", "Dhan"},
    };
    private static final String[] SETTING_KEYS = {"board_marker", "board_refresh", "board_cap", "board_source"};
    private static final int[] DEFAULTS = {0, 2, 1, 0};
    private static final int[] REFRESH_MIN = {1, 2, 5, 10, 15};
    private static final double[] CAPS = {2, 3, 5};
    // NSE drops connections that don't look like a browser.
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130 Safari/537.36";
    private static final long RETRY = 30 * 1000; // retry sooner when nothing came back
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
    private static final int COLS = 10;
    // Layout, designed at 1920x1080 like the rest of the page.
    static final int STRIP_TOP = 116, STRIP_H = 84, GRID_TOP = 212, BOTTOM = 1080;
    private static final int CARD_W = 300, CARD_GAP = 16, SPEED = 40; // SPEED: strip scroll, px per second

    static final class Quote { double value, pct; long time; }
    static final class Stock { String symbol; double weight, price, pct, high52, low52; }

    private final Context ctx;
    private final float scale;
    private final Typeface regular, semibold;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout grid;
    final android.widget.TextClock clock;
    // Each strip label has two cards: the strip holds two copies of itself so it loops without a gap.
    private final Map<String, List<View>> cards = new HashMap<>();
    private final Map<String, Quote> quotes = new HashMap<>();
    // The strip and the flash are moved frame by frame here, not with Android's animators: those
    // stop dead when the TV has animations off in Developer options (the HiSmartTV A4 ships so).
    private final Choreographer.FrameCallback scroll;
    private long nextFetch, lastFetch, lastFrame;
    private float offset; // how far the strip has scrolled
    private boolean running, fetching;
    private volatile boolean haveNifty; // fetched NSE at least once since Home started
    private final Map<String, double[]> years = new HashMap<>(); // symbol -> 52-week {high, low}, from Dhan
    private final Map<String, Double> weights = new HashMap<>(); // symbol -> index weight, from NSE
    private volatile long yearsAt, weightsAt; // when the 52-week numbers and the weights were last fetched
    private final Map<String, Double> lastPrice = new HashMap<>(); // to flash the boxes that moved
    private volatile List<Stock> shown;
    // Testing only (install with --ez demo true): no network, about a dozen random prices move every 30s.
    private boolean demo;

    int setting(int i) {
        return ctx.getSharedPreferences(MainActivity.PREFS, 0).getInt(SETTING_KEYS[i], DEFAULTS[i]);
    }

    private long refresh() { return REFRESH_MIN[setting(1)] * 60 * 1000L; }

    // Saves a setting and shows it at once: the grid redrawn, the next refresh moved, or a fetch
    // from the newly chosen source.
    void set(int i, int choice) {
        ctx.getSharedPreferences(MainActivity.PREFS, 0).edit().putInt(SETTING_KEYS[i], choice).apply();
        if (i == 1) nextFetch = Math.min(nextFetch, lastFetch + refresh());
        else if (i == 3) nextFetch = System.currentTimeMillis();
        else if (shown != null) showGrid(shown);
    }

    void setDemo(boolean on) {
        demo = on;
        nextFetch = System.currentTimeMillis() + (on ? 30 * 1000 : 0); // off: real numbers right away
    }
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
        final float speed = px(SPEED) / 1e9f; // px per nanosecond
        scroll = new Choreographer.FrameCallback() {
            @Override public void doFrame(long now) {
                if (lastFrame != 0) offset = (offset + (now - lastFrame) * speed) % loop;
                lastFrame = now;
                row.setTranslationX(-offset);
                if (running) Choreographer.getInstance().postFrameCallback(this);
            }
        };

        grid = new FrameLayout(ctx);
        // No background: the gaps between boxes show the wallpaper.
        root.addView(grid, at(0, px(GRID_TOP), width, px(BOTTOM - GRID_TOP)));

        clock = new android.widget.TextClock(ctx); // 24-hour, in the date's size and grey
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        clock.setTextColor(0xA8FFFFFF);
        clock.setTypeface(regular);
        clock.setFontFeatureSettings("tnum");
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.topMargin = px(44); // level with the date
        root.addView(clock, cp);
        loadHolidays();
        for (String w : ctx.getSharedPreferences(MainActivity.PREFS, 0).getString("board_weights", "").split(","))
            if (w.indexOf(':') > 0)
                weights.put(w.substring(0, w.lastIndexOf(':')), Double.parseDouble(w.substring(w.lastIndexOf(':') + 1)));
    }

    // Home came to the front: catch up if a refresh is due, and start the clock and the strip.
    void start() {
        running = true;
        if (System.currentTimeMillis() >= nextFetch) fetch();
        tick.run();
        lastFrame = 0; // carry on from where it stopped
        Choreographer.getInstance().removeFrameCallback(scroll);
        Choreographer.getInstance().postFrameCallback(scroll);
    }

    // An app or Settings is in front: no fetching, no animating.
    void stop() {
        running = false;
        ui.removeCallbacks(tick);
        Choreographer.getInstance().removeFrameCallback(scroll);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long left = nextFetch - System.currentTimeMillis();
            if (left <= 0 && !fetching) fetch();
            if (running) ui.postDelayed(this, 1000);
        }
    };

    private void fetch() {
        if (demo && shown != null) {
            nextFetch = System.currentTimeMillis() + 30 * 1000;
            showGrid(jiggle(shown));
            return;
        }
        fetching = true;
        lastFetch = System.currentTimeMillis();
        nextFetch = lastFetch + refresh();
        new Thread(() -> {
            // Each source on its own, so one failing keeps the others (and its last good numbers).
            final Map<String, Quote> got = new HashMap<>();
            List<Stock> stocks = null;
            refreshHolidays();
            // NSE only while it trades, plus once at the start so a holiday still shows the last close.
            if (!haveNifty || nseLive(nseFrom, nseUntil, nseDays, System.currentTimeMillis())) {
                stocks = prices();
                for (String[] s : STRIP) if (s[2].startsWith("nse:"))
                    try { got.put(s[1], nseIndex(s[2].substring(4))); } catch (Exception ignored) {}
            }
            try { got.put("GIFT NIFTY", gift()); } catch (Exception ignored) {}
            try {
                Map<String, Quote> world = groww();
                for (String[] s : STRIP) if (world.containsKey(s[2])) got.put(s[1], world.get(s[2]));
            } catch (Exception ignored) {}
            if (stocks == null && shown != null && System.currentTimeMillis() - yearsAt > 24L * 60 * 60 * 1000)
                stocks = new ArrayList<>(shown); // market closed: still refresh the 52-week numbers daily
            if (stocks != null) stocks = merge(stocks);
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

    // On the fetch thread: the grid's prices from the primary source, the other as backup.
    private List<Stock> prices() {
        for (int i = 0; i < 2; i++) {
            try {
                if ((i == 0) == (setting(3) == 1)) return keepYears(dhan());
                return keepWeights(nifty());
            } catch (Exception ignored) {}
        }
        return null;
    }

    // NSE's index weights, the grid's order. Saved, so a start-up where NSE fails keeps the order.
    private List<Stock> keepWeights(List<Stock> l) {
        StringBuilder saved = new StringBuilder();
        for (Stock s : l) {
            weights.put(s.symbol, s.weight);
            saved.append(s.symbol).append(':').append(s.weight).append(',');
        }
        ctx.getSharedPreferences(MainActivity.PREFS, 0).edit().putString("board_weights", saved.toString()).apply();
        weightsAt = System.currentTimeMillis();
        return l;
    }

    private List<Stock> keepYears(List<Stock> l) {
        for (Stock s : l) years.put(s.symbol, new double[] {s.high52, s.low52});
        yearsAt = System.currentTimeMillis();
        return l;
    }

    // On the fetch thread: add the 52-week numbers and NSE's weight order (each refetched daily,
    // whichever source gives the prices).
    private List<Stock> merge(List<Stock> l) {
        if (System.currentTimeMillis() - weightsAt > 24L * 60 * 60 * 1000)
            try { keepWeights(nifty()); } catch (Exception ignored) {}
        if (System.currentTimeMillis() - yearsAt > 24L * 60 * 60 * 1000)
            try { keepYears(dhan()); } catch (Exception ignored) {}
        List<Stock> out = new ArrayList<>();
        for (Stock o : l) {
            Stock s = copy(o);
            double[] y = years.get(s.symbol);
            if (y != null) { s.high52 = Math.max(y[0], s.price); s.low52 = Math.min(y[1], s.price); }
            Double w = weights.get(s.symbol);
            if (w != null) s.weight = w;
            out.add(s);
        }
        if (!weights.isEmpty()) java.util.Collections.sort(out, (x, y) -> Double.compare(y.weight, x.weight));
        return out;
    }

    static List<Stock> dhan() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(DHAN).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setDoOutput(true);
        try {
            c.getOutputStream().write(DHAN_QUERY.getBytes("UTF-8"));
            JSONArray a = new JSONObject(read(c.getInputStream())).getJSONArray("data");
            List<Stock> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) { // already in market-cap order, close to index weight
                JSONObject o = a.getJSONObject(i);
                Stock s = new Stock();
                s.symbol = o.getString("Sym");
                s.weight = a.length() - i;
                s.price = o.getDouble("Ltp");
                s.pct = o.getDouble("PPerchange");
                s.high52 = o.getDouble("High1Yr");
                s.low52 = o.getDouble("Low1Yr");
                out.add(s);
            }
            if (out.size() < 50) throw new Exception("short list: " + out.size());
            return out;
        } finally {
            c.disconnect();
        }
    }

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
        try {
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
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
        // One size for every name: the widest symbol just fits its box on one line.
        Paint paint = new Paint();
        paint.setTypeface(NAME_FONT);
        paint.setTextSize(px(100));
        float widest = 1;
        for (Stock s : stocks) widest = Math.max(widest, paint.measureText(s.symbol));
        float nameSize = Math.min(px(40), px(100) * (w / COLS - gap - px(16)) / widest);
        for (int i = 0; i < stocks.size(); i++) {
            Stock s = stocks.get(i);
            int c = i % COLS, r = i / COLS;
            int x = c * w / COLS, y = r * h / rows;
            int cw = (c + 1) * w / COLS - x - (c < COLS - 1 ? gap : 0);
            int ch = (r + 1) * h / rows - y - (r < rows - 1 ? gap : 0);
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            int heat = shade(s.pct, CAPS[setting(2)]), ink = ink(heat);
            cell.setBackgroundColor(heat);
            TextView name = text(Math.round(nameSize), ink, NAME_FONT);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setText(s.symbol);
            cell.addView(name);
            TextView price = text(px(26), ink, PRICE_FONT);
            price.setText(whole.format(s.price));
            price.setFontFeatureSettings("tnum");
            price.setGravity(Gravity.CENTER);
            price.setPadding(px(10), 0, px(10), 0); // room for the change highlight
            cell.addView(price, spaced());
            TextView pct = text(px(28), ink, semibold);
            pct.setText(String.format(Locale.US, "%s%.2f%%", s.pct >= 0 ? "+" : "−", Math.abs(s.pct)));
            pct.setFontFeatureSettings("tnum");
            pct.setGravity(Gravity.CENTER);
            pct.setPadding(px(10), 0, px(10), 0);
            cell.addView(pct, spaced());
            grid.addView(cell, at(x, y, cw, ch));
            if (s.high52 > s.low52 && setting(0) == 0) { // 52-week position: left edge = low, right edge = high
                int tw = px(24), th = px(16), inset = px(14);
                double t = (s.price - s.low52) / (s.high52 - s.low52);
                int tx = x + inset + (int) Math.round(t * (cw - 2 * inset)) - tw / 2;
                grid.addView(triangle(ink & 0xC8FFFFFF), at(tx, y + ch - px(23), tw, th));
            }
            Double was = lastPrice.put(s.symbol, s.price);
            if (was != null && was != s.price) flash(s.price > was, ink, price, pct);
        }
        shown = stocks;
    }

    private View triangle(int colour) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(0.5f, 0);
        p.lineTo(1, 1);
        p.lineTo(0, 1);
        p.close();
        android.graphics.drawable.ShapeDrawable d =
                new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.PathShape(p, 1, 1));
        d.getPaint().setColor(colour);
        View v = new View(ctx);
        v.setBackground(d);
        return v;
    }

    // Like a market ticker: when a price moves, its price and change get a green (up) or red (down)
    // highlight with white text, held a moment, then faded back to the box as it was.
    private void flash(boolean up, final int ink, final TextView... texts) {
        final ArgbEvaluator argb = new ArgbEvaluator();
        final GradientDrawable[] pills = new GradientDrawable[texts.length];
        for (int i = 0; i < texts.length; i++) {
            pills[i] = new GradientDrawable();
            pills[i].setCornerRadius(px(8));
            pills[i].setColor(up ? 0xFF0A7F40 : 0xFFD12A3B); // white text reads at 4.5:1 or better on both
            texts[i].setBackground(pills[i]);
        }
        tween(2500, f -> {
            float a = f < 0.4f ? 1 : 1 - (f - 0.4f) / 0.6f; // hold, then fade
            for (int i = 0; i < texts.length; i++) {
                pills[i].setAlpha(Math.round(255 * a));
                texts[i].setTextColor((Integer) argb.evaluate(a, ink, 0xFFFFFFFF));
                if (f >= 1) texts[i].setBackground(null);
            }
        });
    }

    interface Step { void at(float done); }

    // Calls step every frame for ms milliseconds, with how far along it is, 0 to 1 (always ends on 1).
    private static void tween(final long ms, final Step step) {
        final long start = System.nanoTime(); // the same clock as frame times
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long now) {
                float f = Math.max(0, Math.min(1, (now - start) / 1e6f / ms));
                step.at(f);
                if (f < 1) Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private static Stock copy(Stock o) {
        Stock s = new Stock();
        s.symbol = o.symbol;
        s.weight = o.weight;
        s.price = o.price;
        s.pct = o.pct;
        s.high52 = o.high52;
        s.low52 = o.low52;
        return s;
    }

    // Demo data: the shown stocks with about a dozen prices nudged up or down by up to 1%.
    private static List<Stock> jiggle(List<Stock> from) {
        java.util.Random r = new java.util.Random();
        List<Stock> out = new ArrayList<>();
        for (Stock o : from) {
            Stock s = copy(o);
            if (r.nextInt(4) == 0) {
                double d = (r.nextDouble() * 2 - 1) / 100;
                s.price = Math.round(o.price * (1 + d) * 20) / 20.0; // NSE ticks are 5 paise
                s.pct = ((1 + o.pct / 100) * s.price / o.price - 1) * 100;
            }
            out.add(s);
        }
        return out;
    }

    // Light red or green for a small move, deepening to full at ±cap% (3 unless changed in Settings):
    // the darker the box, the bigger the move.
    static int shade(double pct, double cap) {
        int[] from = pct >= 0 ? new int[] {190, 236, 202} : new int[] {255, 196, 200},
              to = pct >= 0 ? new int[] {8, 92, 46} : new int[] {140, 16, 32};
        // Mid shades too light for white text and too dark for black are skipped: one step deeper.
        for (double t = Math.min(Math.abs(pct) / cap, 1); ; t = Math.min(t + 0.01, 1)) {
            int r = (int) Math.round(from[0] + (to[0] - from[0]) * t), g = (int) Math.round(from[1] + (to[1] - from[1]) * t),
                b = (int) Math.round(from[2] + (to[2] - from[2]) * t);
            int c = 0xFF000000 | r << 16 | g << 8 | b;
            if (t >= 1 || contrast(ink(c), c) >= 4.5) return c;
        }
    }

    // Text on a box: white or near-black, whichever reads better (near-black on the light boxes).
    static int ink(int bg) {
        return contrast(0xFFFFFFFF, bg) >= contrast(0xFF181416, bg) ? 0xFFFFFFFF : 0xFF181416;
    }

    static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static double luminance(int c) {
        double l = 0;
        double[] w = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double v = (c >> (16 - 8 * i) & 0xFF) / 255.0;
            l += w[i] * (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        return l;
    }

    // Grid text, built into Android: condensed bold names (narrow, so they can be bigger) and medium
    // prices (regular is too thin against the colours from across a room).
    private final Typeface NAME_FONT = Typeface.create("sans-serif-condensed", Typeface.BOLD),
            PRICE_FONT = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // A small gap above each line under the name, so the three lines read apart.
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); // as wide as the text: the box centres it
        lp.topMargin = px(5);
        return lp;
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
