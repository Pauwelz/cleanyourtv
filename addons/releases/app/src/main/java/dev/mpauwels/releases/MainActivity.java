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
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * The tile and module card open this: what Sonarr and Radarr release today, what comes in the next days, and what
 * was added last, with the queue in the corner. OK on a downloaded title plays it in Plezy. It reads the servers
 * once each time this screen opens, and never in the background.
 */
public class MainActivity extends Activity {
    private static final int GREEN = 0xFF3FB950, GREY = 0xFF8B949E, RED = 0xFFE84855, AMBER = 0xFFF2B544;
    private static final int DAYS_AHEAD = 7, ADDED_SHOWN = 15, BLOCKED_SHOWN = 3;
    private static final String PLAYER = "com.edde746.plezy";

    private interface Sink {
        void take(String body) throws JSONException;
    }

    /** What one read of the servers gives. */
    private static final class Page {
        final List<Release> calendar = new ArrayList<>();
        final List<Release> added = new ArrayList<>();
        final List<String> blocked = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int queued;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Posters posters;
    private TextView status, queue;
    private LinearLayout today, coming, added;
    /** Counts the times the screen opened: work that an earlier time started is dropped. */
    private volatile int run;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        posters = new Posters(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0D1117);
        page.setPadding(px(48), px(28), px(48), px(24));

        LinearLayout header = new LinearLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Row.text(this, getString(R.string.app_name), 26, 0xFFFFFFFF));
        status = Row.text(this, "", 14, GREY);
        // Its own width, not the column's: a text view of a fixed width keeps that width when a longer text comes in.
        left.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(left);
        queue = new TextView(this);
        queue.setTextSize(13);
        queue.setTextColor(GREY);
        queue.setGravity(Gravity.END);
        queue.setMaxLines(2);
        queue.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        right.leftMargin = px(32);
        right.topMargin = px(6);
        header.addView(queue, right);
        page.addView(header);

        LinearLayout body = new LinearLayout(this);
        today = column(body, "Today", 1.2f, 0);
        coming = column(body, "Coming up", 1f, px(24));
        added = column(body, "Recently added", 1.1f, px(24));
        LinearLayout.LayoutParams rest = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rest.topMargin = px(14);
        page.addView(body, rest);
        setContentView(page);
    }

    /** One column: its heading, and the rows in a scroll view. Returns where the rows go. */
    private LinearLayout column(LinearLayout body, String heading, float weight, int leftMargin) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView name = Row.text(this, heading, 12, AMBER);
        name.setAllCaps(true);
        name.setLetterSpacing(0.08f);
        name.setPadding(px(12), 0, 0, px(6));
        column.addView(name);
        ScrollView scroll = new ScrollView(this);
        // A scroll view without a focusable row takes the focus itself, and the highlight is then nowhere.
        scroll.setFocusable(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        place.leftMargin = leftMargin;
        body.addView(column, place);
        return rows;
    }

    @Override
    protected void onResume() {
        super.onResume();
        int mine = ++run;
        today.removeAllViews();
        coming.removeAllViews();
        added.removeAllViews();
        queue.setText("");
        Config config = Config.load(this);
        if (config == null || config.sonarr == null && config.radarr == null) {
            status.setText("No server is set. Run addons/releases/setup.sh on a computer.");
            return;
        }
        LocalDate day = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        status.setText(longDate(day) + " · reading…");
        net.execute(() -> {
            Page page = read(config, day, zone);
            main.post(() -> {
                if (mine == run && !isFinishing()) show(config, page, day, mine);
            });
        });
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
        if (r != null && get("Radarr", r, Arr.calendarUrl(r, day, to, false), p, body -> p.calendar.addAll(Arr.films(body, r.types, day, to)))) {
            get("Radarr", r, Arr.historyUrl(r, true), p, body -> p.added.addAll(Arr.added(body, zone)));
            get("Radarr", r, Arr.queueUrl(r, true), p, body -> queued(p, Arr.queue(body)));
        }
        if (s != null && get("Sonarr", s, Arr.calendarUrl(s, day, to, true), p, body -> p.calendar.addAll(Arr.episodes(body, zone, day, to)))) {
            get("Sonarr", s, Arr.historyUrl(s, false), p, body -> p.added.addAll(Arr.added(body, zone)));
            get("Sonarr", s, Arr.queueUrl(s, false), p, body -> queued(p, Arr.queue(body)));
        }
        // Within a day the films come first: they have no time.
        p.calendar.sort(Comparator.comparing((Release x) -> x.day).thenComparingLong(x -> x.atMs).thenComparing(x -> x.title));
        p.added.sort(Comparator.comparingLong((Release x) -> -x.atMs));
        return p;
    }

    private static void queued(Page p, Arr.Queue q) {
        p.queued += q.total;
        p.blocked.addAll(q.blocked);
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
        for (String problem : p.problems) note(today, problem, 13, RED);
        int count = 0;
        LocalDate heading = null;
        Release next = null;
        for (Release r : p.calendar) {
            boolean film = r.atMs == Release.NO_TIME;
            String time = film ? "" : clock.format(new Date(r.atMs));
            boolean later = film ? r.day.isAfter(day) : r.atMs > now;
            String state = r.hasFile ? "Downloaded" : later ? "Not aired yet" : r.monitored ? "Missing" : "Not monitored";
            int colour = r.hasFile ? GREEN : later || !r.monitored ? GREY : AMBER;
            if (r.day.equals(day)) {
                count++;
                add(today, Row.poster(this, r.title, film ? r.detail : time + " · " + r.detail, state, colour), r, config, mine);
            } else {
                if (next == null) next = r;
                if (!r.day.equals(heading)) note(coming, dayLabel(r.day, day), 12, GREY);
                heading = r.day;
                CharSequence text = new SpannableStringBuilder(r.title).append("  ").append(tint(r.detail, GREY));
                // "Not aired yet" is what every line here would say, so only the other two states show.
                add(coming, Row.small(this, film ? "Film" : time, text, r.hasFile || !later ? state : "", colour), r, config, mine);
            }
        }
        if (count == 0 && p.problems.isEmpty()) nothingToday(next, day, clock);
        if (heading == null && p.problems.isEmpty()) note(coming, "Nothing in the next " + DAYS_AHEAD + " days.", 12, GREY);
        for (Release r : p.added.subList(0, Math.min(ADDED_SHOWN, p.added.size()))) {
            String when = (r.day.equals(day) ? "" : shortDay(r.day) + " ") + clock.format(new Date(r.atMs));
            add(added, Row.poster(this, r.title, r.detail, r.quality.isEmpty() ? when : when + " · " + r.quality, GREY), r, config, mine);
        }

        status.setText(longDate(day) + " · " + (count == 0 ? (p.problems.isEmpty() ? "nothing today" : "no list")
                : count == 1 ? "1 release" : count + " releases"));
        SpannableStringBuilder q = new SpannableStringBuilder(p.queued > 0 ? p.queued + " in the queue" : "");
        if (!p.blocked.isEmpty()) {
            if (q.length() > 0) q.append('\n');
            q.append(tint(p.blocked.size() + " blocked: ", RED));
            q.append(TextUtils.join(", ", p.blocked.subList(0, Math.min(BLOCKED_SHOWN, p.blocked.size()))));
            if (p.blocked.size() > BLOCKED_SHOWN) q.append("…");
        }
        queue.setText(q);

        boolean focused = false;
        for (LinearLayout column : new LinearLayout[] {today, coming, added}) {
            View first = null, last = null;
            for (int i = 0; i < column.getChildCount(); i++) {
                View v = column.getChildAt(i);
                if (!v.isFocusable()) continue;
                if (first == null) first = v;
                last = v;
            }
            if (first == null) continue;
            // At the end of a column Up and Down stay there, and do not jump to another column.
            first.setId(View.generateViewId());
            first.setNextFocusUpId(first.getId());
            if (last.getId() == View.NO_ID) last.setId(View.generateViewId());
            last.setNextFocusDownId(last.getId());
            if (!focused) focused = first.requestFocus();
        }
    }

    private void add(LinearLayout column, Row row, Release r, Config config, int mine) {
        column.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String link = r.hasFile ? r.playLink() : null;
        if (link != null) row.setOnClickListener(v -> play(link));
        if (row.poster == null) return;
        Config.Server server = r.film ? config.radarr : config.sonarr;
        net.execute(() -> {
            if (mine != run) return;
            Bitmap bitmap = posters.load(server, r);
            if (bitmap != null) main.post(() -> {
                if (mine == run) row.poster.setImageBitmap(bitmap);
            });
        });
    }

    private void play(String link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PLAYER));
        } catch (ActivityNotFoundException e) {
            status.setText("Plezy is not on this TV, so OK cannot play a title.");
        }
    }

    /** The card in an empty Today column, with the next release when there is one. */
    private void nothingToday(Release next, LocalDate day, java.text.DateFormat clock) {
        SpannableStringBuilder text = new SpannableStringBuilder("Nothing today.");
        if (next != null) {
            String when = dayLabel(next.day, day) + (next.atMs == Release.NO_TIME ? "" : " " + clock.format(new Date(next.atMs)));
            SpannableString line = tint("\nNext: " + when + ", " + next.title, GREY);
            line.setSpan(new RelativeSizeSpan(0.8f), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(line);
        }
        TextView card = note(today, text, 15, 0xFFC9D1D9);
        card.setBackgroundResource(R.drawable.empty_background);
        card.setPadding(px(16), px(14), px(16), px(14));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).setMargins(px(12), px(4), px(12), 0);
    }

    /** A line that takes no focus and may wrap: a day heading, a problem, an empty column. */
    private TextView note(LinearLayout column, CharSequence text, int size, int colour) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(colour);
        t.setPadding(px(12), px(column.getChildCount() == 0 ? 0 : 10), px(12), px(2));
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
