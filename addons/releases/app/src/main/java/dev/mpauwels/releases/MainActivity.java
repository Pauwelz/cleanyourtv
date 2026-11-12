package dev.mpauwels.releases;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tile and module card open this: what was added last, what Sonarr and Radarr release today, and what comes
 * in the next days, as past, present and future. Today is the lit panel, with a link to the queue at its foot.
 * OK on a downloaded title plays it in Plezy; OK on the link opens the Downloads view, and Back closes it. It
 * reads the servers each time this screen opens, then the queue once a minute while the screen shows, and never
 * in the background.
 */
public class MainActivity extends Activity {
    private static final int PAPER = Row.PAPER, QUIET = Row.QUIET, GREEN = 0xFF7BD88F, RED = 0xFFFF6B5E, AMBER = 0xFFF0C05A;
    private static final int DAYS_AHEAD = 7, ADDED_SHOWN = 15;
    /** Sonarr and Radarr ask their download clients about once a minute, so a faster read shows the same numbers. */
    private static final long REFRESH_MS = 60_000;
    private static final String QUEUE = "queue";
    private static final String PLAYER = "com.edde746.plezy";

    private interface Sink {
        void take(String body) throws JSONException;
    }

    /** What one read of the servers gives. */
    private static final class Page {
        final List<Release> calendar = new ArrayList<>();
        final List<Release> added = new ArrayList<>();
        final List<Download> downloads = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int queued;
    }

    /** One column of a view: where its rows go, the figure beside its heading, and the place under its rows. */
    private static final class Column {
        LinearLayout box, rows, foot;
        TextView count;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Posters posters;
    private TextView title;
    private LinearLayout page, home, downloads;
    private Column added, today, coming, going, blocked;
    private Config config;
    /** The Downloads view shows instead of the three columns. */
    private boolean open;
    /** Counts the times the screen opened: work that an earlier time started is dropped. */
    private volatile int run;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        posters = new Posters(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Row.GROUND);
        page.setPadding(px(48), px(28), px(48), px(24));

        LinearLayout header = new LinearLayout(this);
        title = Row.text(this, getString(R.string.app_name), 26, PAPER);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Row.text(this, longDate(LocalDate.now()), 14, QUIET));
        page.addView(header);

        home = new LinearLayout(this);
        // Past, present, future. The focus still starts in Today: focusFirst() asks that column first.
        added = column(home, "Recently added", QUIET, 1f, 0, false);
        today = column(home, "Today", PAPER, 1.35f, px(20), true);
        coming = column(home, "Coming up", QUIET, 1f, px(20), false);
        // Both views are laid out at all times, one of them invisible: a view that was GONE has no size, and cannot take the focus.
        FrameLayout views = new FrameLayout(this);
        views.addView(home);
        downloads = new LinearLayout(this);
        going = column(downloads, "Downloading", PAPER, 1.35f, 0, true);
        blocked = column(downloads, "Blocked", RED, 1f, px(20), false);
        downloads.setVisibility(View.INVISIBLE);
        views.addView(downloads);
        LinearLayout.LayoutParams rest = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rest.topMargin = px(16);
        page.addView(views, rest);
        setContentView(page);
    }

    /** One column: its heading with a figure beside it, the rows in a scroll view, and a foot. The stage is the lit panel. */
    private Column column(LinearLayout body, String heading, int colour, float weight, int leftMargin, boolean stage) {
        Column c = new Column();
        c.box = new LinearLayout(this);
        c.box.setOrientation(LinearLayout.VERTICAL);
        if (stage) c.box.setBackgroundResource(R.drawable.stage_background);
        // The headings of all columns stand on one line: the stage has its padding inside, the others have it above.
        c.box.setPadding(stage ? px(14) : 0, px(14), stage ? px(14) : 0, stage ? px(8) : 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.BOTTOM);
        top.setPadding(px(10), 0, px(10), px(8));
        TextView name = Row.text(this, heading, 18, colour);
        name.setTypeface(Row.MEDIUM);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.count = Row.text(this, "", 12, QUIET);
        top.addView(c.count);
        c.box.addView(top);
        ScrollView scroll = new ScrollView(this);
        // A scroll view without a focusable row takes the focus itself, and the highlight is then nowhere.
        scroll.setFocusable(false);
        c.rows = new LinearLayout(this);
        c.rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(c.rows);
        c.box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        c.foot = new LinearLayout(this);
        c.foot.setOrientation(LinearLayout.VERTICAL);
        c.box.addView(c.foot);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        place.leftMargin = leftMargin;
        body.addView(c.box, place);
        return c;
    }

    @Override
    protected void onResume() {
        super.onResume();
        int mine = ++run;
        for (Column c : new Column[] {added, today, coming, going, blocked}) {
            c.rows.removeAllViews();
            c.foot.removeAllViews();
            c.count.setText("");
        }
        config = Config.load(this);
        if (config == null || config.sonarr == null && config.radarr == null) {
            note(today.rows, "No server is set. Run addons/releases/setup.sh on a computer.", 13, QUIET);
            return;
        }
        LocalDate day = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        today.count.setText("reading…");
        net.execute(() -> {
            Page page = read(config, day, zone);
            main.post(() -> {
                if (mine != run || isFinishing()) return;
                show(config, page, day, mine);
                showQueue(page, mine);
                refreshLater(mine);
            });
        });
    }

    /** Reads the queue again after a while, for as long as this screen shows. A read that fails keeps the old list. */
    private void refreshLater(int mine) {
        main.postDelayed(() -> {
            if (mine != run) return;
            net.execute(() -> {
                Page page = new Page();
                readQueue(config.radarr, config.sonarr, page);
                main.post(() -> {
                    if (mine != run || isFinishing()) return;
                    if (page.problems.isEmpty()) showQueue(page, mine);
                    refreshLater(mine);
                });
            });
        }, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        // Nothing reads while the screen is away: the pending read is dropped, and onResume starts over.
        run++;
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (open) close();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    private static Page read(Config config, LocalDate day, ZoneId zone) {
        Page p = new Page();
        LocalDate to = day.plusDays(DAYS_AHEAD);
        Config.Server r = config.radarr, s = config.sonarr;
        boolean films = r != null && get("Radarr", r, Arr.calendarUrl(r, day, to, false), p, body -> p.calendar.addAll(Arr.films(body, r.types, day, to)));
        if (films) get("Radarr", r, Arr.historyUrl(r, true), p, body -> p.added.addAll(Arr.added(body, zone)));
        boolean series = s != null && get("Sonarr", s, Arr.calendarUrl(s, day, to, true), p, body -> p.calendar.addAll(Arr.episodes(body, zone, day, to)));
        if (series) get("Sonarr", s, Arr.historyUrl(s, false), p, body -> p.added.addAll(Arr.added(body, zone)));
        // A server that did not answer above is not asked again.
        readQueue(films ? r : null, series ? s : null, p);
        // Within a day the films come first: they have no time.
        p.calendar.sort(Comparator.comparing((Release x) -> x.day).thenComparingLong(x -> x.atMs).thenComparing(x -> x.title));
        p.added.sort(Comparator.comparingLong((Release x) -> -x.atMs));
        return p;
    }

    /** The queues of the servers that are not null, as one list in the order of the screen. */
    private static void readQueue(Config.Server radarr, Config.Server sonarr, Page p) {
        if (radarr != null) get("Radarr", radarr, Arr.queueUrl(radarr, true), p, body -> queued(p, Arr.queue(body)));
        if (sonarr != null) get("Sonarr", sonarr, Arr.queueUrl(sonarr, false), p, body -> queued(p, Arr.queue(body)));
        p.downloads.sort(Download.ORDER);
    }

    private static void queued(Page p, Arr.Queue q) {
        p.queued += q.total;
        p.downloads.addAll(q.downloads);
    }

    /**
     * One request. A failure adds a problem, so that an error never looks like an empty list. False when the
     * server did not answer well: its other requests are then not tried.
     */
    private static boolean get(String name, Config.Server server, String url, Page p, Sink sink) {
        Http.Reply r = Http.get(url, server.key);
        if (!r.ok()) {
            p.problems.add(name + ": " + (r.code == 0 ? "no answer (" + r.error + ")"
                    : r.code == 401 ? "the API key was refused" : "error " + r.code));
            return false;
        }
        try {
            sink.take(r.body());
        } catch (JSONException | RuntimeException e) {
            p.problems.add(name + ": an answer could not be read");
        }
        return true;
    }

    private void show(Config config, Page p, LocalDate day, int mine) {
        long now = System.currentTimeMillis();
        java.text.DateFormat clock = DateFormat.getTimeFormat(this);
        for (String problem : p.problems) note(today.rows, problem, 13, RED);
        int count = 0;
        LocalDate heading = null;
        Release next = null;
        for (Release r : p.calendar) {
            boolean film = r.atMs == Release.NO_TIME;
            String time = film ? "" : clock.format(new Date(r.atMs));
            boolean later = film ? r.day.isAfter(day) : r.atMs > now;
            // shortcut: this state is from the read that opened the screen; it does not follow the queue's minute, reopen for a new one
            Download inQueue = r.hasFile ? null : bringing(r, p.downloads);
            String state = r.hasFile ? "Downloaded" : inQueue != null ? (inQueue.blocked ? "Blocked" : "Downloading")
                    : later ? "Not aired yet" : r.monitored ? "Missing" : "Not monitored";
            int colour = r.hasFile ? GREEN : inQueue != null ? (inQueue.blocked ? RED : PAPER) : later || !r.monitored ? QUIET : AMBER;
            if (r.day.equals(day)) {
                count++;
                add(today, Row.card(this, r.title, time, r.detail, state, colour), r, config, mine);
            } else {
                if (next == null) next = r;
                if (!r.day.equals(heading)) note(coming.rows, dayLabel(r.day, day), 12, QUIET);
                heading = r.day;
                // "Not aired yet" is what every line here would say, so only the other two states show.
                add(coming, Row.line(this, film ? "Film" : time, named(r.title, r.detail), r.hasFile || inQueue != null || !later ? state : "", colour), r, config, mine);
            }
        }
        if (count == 0 && p.problems.isEmpty()) nothingToday(next, day, clock);
        if (heading == null && p.problems.isEmpty()) note(coming.rows, "Nothing in the next " + DAYS_AHEAD + " days.", 12, QUIET);
        for (Release r : p.added.subList(0, Math.min(ADDED_SHOWN, p.added.size()))) {
            String when = (r.day.equals(day) ? "" : shortDay(r.day) + " ") + clock.format(new Date(r.atMs));
            add(added, Row.card(this, r.title, when, r.detail, r.quality, QUIET), r, config, mine);
        }
        today.count.setText(count == 0 ? (p.problems.isEmpty() ? "" : "no list") : count == 1 ? "1 release" : count + " releases");
    }

    /**
     * The queue: the link at the foot of Today, with the two numbers that matter on the home view, and every
     * download on the Downloads view. The focus stays on the download it was on.
     */
    private void showQueue(Page p, int mine) {
        View focused = getCurrentFocus();
        Object tag = focused == null ? null : focused.getTag();
        boolean lost = focused == null || tag != null;
        List<Download> downloading = new ArrayList<>(), stuck = new ArrayList<>();
        for (Download d : p.downloads) (d.blocked ? stuck : downloading).add(d);

        today.foot.removeAllViews();
        if (!p.downloads.isEmpty()) {
            SpannableStringBuilder info = new SpannableStringBuilder(p.queued + " in the queue");
            if (!stuck.isEmpty()) info.append(", ").append(tint(stuck.size() + " blocked", RED));
            View rule = new View(this);
            rule.setBackgroundColor(Row.GROUND);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1));
            line.setMargins(0, px(6), 0, px(3));
            today.foot.addView(rule, line);
            Row link = Row.link(this, "Downloads", info);
            link.setTag(QUEUE);
            link.setId(View.generateViewId());
            link.setNextFocusDownId(link.getId());
            link.setOnClickListener(v -> open());
            today.foot.addView(link, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        going.rows.removeAllViews();
        blocked.rows.removeAllViews();
        for (Download d : downloading) {
            card(going, d, Row.card(this, d.title, d.when(), d.line(), d.percent() + "%", QUIET).bar(d.percent()), mine);
        }
        if (downloading.isEmpty()) note(going.rows, "Nothing is downloading.", 12, QUIET);
        // The reason takes the detail line, so the episode moves beside the title: its number only, a name would push the title out.
        for (Download d : stuck) card(blocked, d, Row.card(this, d.title, d.detail.replaceFirst(",.*", ""), d.reason, "", QUIET), mine);
        going.count.setText(downloading.size() == 1 ? "1 download" : downloading.size() + " downloads");
        blocked.count.setText(stuck.size() == 1 ? "1 import" : stuck.size() + " imports");
        // Without a blocked import the list has the whole width.
        blocked.box.setVisibility(stuck.isEmpty() ? View.GONE : View.VISIBLE);

        View link = today.foot.findViewWithTag(QUEUE);
        pin(today.rows, link == null ? View.NO_ID : link.getId());
        for (Column c : new Column[] {added, coming, going, blocked}) pin(c.rows, View.NO_ID);
        View again = tag == null ? null : page.findViewWithTag(tag);
        if (again != null && again.isFocusable() && again.isShown()) again.requestFocus();
        else if (lost) focusFirst();
    }

    private void card(Column column, Download d, Row row, int mine) {
        row.setTag(d.key);
        column.rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        poster(row, d.film ? config.radarr : config.sonarr, d.film, d.mediaId, mine);
    }

    /** The first row of the view that shows. A scroll view does not pass the focus on to rows that are not laid out yet. */
    private void focusFirst() {
        for (LinearLayout rows : open ? new LinearLayout[] {going.rows, blocked.rows}
                : new LinearLayout[] {today.rows, today.foot, coming.rows, added.rows}) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                if (rows.getChildAt(i).isFocusable() && rows.getChildAt(i).requestFocus()) return;
            }
        }
    }

    /** The download in the queue that brings this release, or null. */
    private static Download bringing(Release r, List<Download> queue) {
        for (Download d : queue) if (d.brings(r)) return d;
        return null;
    }

    /** A title with its detail after it, smaller and quiet, in one line that can scroll as a whole. */
    private CharSequence named(String title, String detail) {
        if (detail.isEmpty()) return title;
        SpannableStringBuilder s = new SpannableStringBuilder(title).append("  ");
        int from = s.length();
        s.append(detail);
        s.setSpan(new ForegroundColorSpan(QUIET), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new AbsoluteSizeSpan(12, true), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new TypefaceSpan("sans-serif"), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** At the ends of a column Up stays there, and Down goes to downId, or stays there too without one. */
    private static void pin(LinearLayout column, int downId) {
        View first = null, last = null;
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (!v.isFocusable()) continue;
            // A row that was the last one before this read of the queue is free again.
            v.setNextFocusUpId(View.NO_ID);
            v.setNextFocusDownId(View.NO_ID);
            if (first == null) first = v;
            last = v;
        }
        if (first == null) return;
        if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
        first.setNextFocusUpId(first.getId());
        if (last.getId() == View.NO_ID) last.setId(View.generateViewId());
        last.setNextFocusDownId(downId == View.NO_ID ? last.getId() : downId);
    }

    private void open() {
        open = true;
        home.setVisibility(View.INVISIBLE);
        downloads.setVisibility(View.VISIBLE);
        title.setText("Downloads");
        focusFirst();
    }

    private void close() {
        open = false;
        downloads.setVisibility(View.INVISIBLE);
        home.setVisibility(View.VISIBLE);
        title.setText(R.string.app_name);
        View link = today.foot.findViewWithTag(QUEUE);
        if (link == null || !link.requestFocus()) focusFirst();
    }

    private void add(Column column, Row row, Release r, Config config, int mine) {
        column.rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String link = r.hasFile ? r.playLink() : null;
        if (link != null) row.setOnClickListener(v -> play(link));
        if (row.poster != null) poster(row, r.film ? config.radarr : config.sonarr, r.film, r.mediaId, mine);
    }

    /** A poster that was shown before is set at once, so that a new read of the queue does not blink. */
    private void poster(Row row, Config.Server server, boolean film, int mediaId, int mine) {
        Bitmap ready = posters.shown(film, mediaId);
        if (ready != null) {
            row.poster.setImageBitmap(ready);
            return;
        }
        net.execute(() -> {
            if (mine != run) return;
            Bitmap bitmap = posters.load(server, film, mediaId);
            if (bitmap != null) main.post(() -> {
                if (mine == run) row.poster.setImageBitmap(bitmap);
            });
        });
    }

    private void play(String link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PLAYER));
        } catch (ActivityNotFoundException e) {
            today.count.setText("Plezy is not on this TV");
        }
    }

    /** The line in an empty Today column, with the next release when there is one. */
    private void nothingToday(Release next, LocalDate day, java.text.DateFormat clock) {
        note(today.rows, "Nothing today.", 16, PAPER);
        if (next == null) return;
        String when = dayLabel(next.day, day) + (next.atMs == Release.NO_TIME ? "" : " " + clock.format(new Date(next.atMs)));
        note(today.rows, "Next: " + when + ", " + next.title, 12, QUIET).setPadding(px(10), px(2), px(10), 0);
    }

    /** A line that takes no focus and may wrap: a day heading, a problem, an empty column. */
    private TextView note(LinearLayout column, CharSequence text, int size, int colour) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(colour);
        t.setPadding(px(10), px(column.getChildCount() == 0 ? 0 : 10), px(10), px(2));
        column.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private static SpannableString tint(String text, int colour) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(colour), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** A date in the order of the TV's language, from a skeleton such as "EEEEdMMMM". */
    private static String date(LocalDate d, String skeleton) {
        Locale locale = Locale.getDefault();
        return d.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale));
    }

    private static String longDate(LocalDate d) {
        return date(d, "EEEEdMMMM");
    }

    private static String shortDay(LocalDate d) {
        return date(d, "EEEd");
    }

    private static String dayLabel(LocalDate d, LocalDate today) {
        return d.equals(today.plusDays(1)) ? "Tomorrow" : longDate(d);
    }

    private int px(int dp) {
        return Row.dp(this, dp);
    }
}
