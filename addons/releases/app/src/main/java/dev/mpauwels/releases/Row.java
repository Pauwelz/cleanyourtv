package dev.mpauwels.releases;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * One focusable line of a column. A card is the same wherever a poster shows: the poster, a title with a time
 * at its right, and a detail with a state or a second figure at its right. A line has a time and a name. A link opens a view.
 */
final class Row extends LinearLayout {
    static final int PAPER = 0xFFF3EEE3, QUIET = 0xFF9DB0BD, GROUND = 0xFF14212B;
    /** The condensed face of printed listings: more letters of a title fit in a row. */
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Null on a line and on a link. */
    final ImageView poster;
    /** Where the lines of a card are stacked; null otherwise. */
    private LinearLayout lines;

    private Row(Context context, int verticalDp, ImageView poster) {
        super(context);
        this.poster = poster;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(context, 10), dp(context, verticalDp), dp(context, 10), dp(context, verticalDp));
        setBackgroundResource(R.drawable.row_background);
        // Focusable also without an action, so that the arrows scroll a long column.
        setFocusable(true);
    }

    static Row card(Context c, CharSequence title, String right, String detail, String right2, int right2Colour) {
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.poster_background);
        image.setClipToOutline(true);
        Row row = new Row(c, 4, image);
        LayoutParams size = new LayoutParams(dp(c, 30), dp(c, 45));
        size.rightMargin = dp(c, 10);
        row.addView(image, size);
        row.lines = new LinearLayout(c);
        row.lines.setOrientation(VERTICAL);
        TextView name = title(c, title), under = text(c, detail, 12, QUIET);
        row.lines.addView(pair(c, name, right, QUIET));
        row.lines.addView(pair(c, under, right2, right2Colour));
        row.scrollOnFocus(name, under);
        row.addView(row.lines, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** A text that takes the width, and a figure at its right when there is one. */
    private static LinearLayout pair(Context c, TextView left, String right, int rightColour) {
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.BOTTOM);
        line.addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        if (!right.isEmpty()) {
            TextView figure = text(c, right, 12, rightColour);
            figure.setPadding(dp(c, 8), 0, 0, 0);
            line.addView(figure, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        return line;
    }

    /** Adds a progress bar under the two lines of a card: the card keeps its height, the poster is taller. */
    Row bar(int percent) {
        ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(percent);
        bar.setProgressTintList(ColorStateList.valueOf(PAPER));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(GROUND));
        LayoutParams size = new LayoutParams(LayoutParams.MATCH_PARENT, dp(getContext(), 3));
        size.topMargin = dp(getContext(), 2);
        lines.addView(bar, size);
        return this;
    }

    /** A time, a name and a state. */
    static Row line(Context c, String time, CharSequence text, String state, int stateColour) {
        Row row = new Row(c, 3, null);
        row.setGravity(Gravity.BOTTOM);
        row.addView(text(c, time, 12, QUIET), new LayoutParams(dp(c, 46), LayoutParams.WRAP_CONTENT));
        TextView name = title(c, text);
        row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        if (!state.isEmpty()) {
            TextView right = text(c, state, 12, stateColour);
            right.setPadding(dp(c, 8), 0, 0, 0);
            row.addView(right, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        row.scrollOnFocus(name);
        return row;
    }

    /** A text that does not fit ends in an ellipsis, and scrolls while this row has the focus. */
    private void scrollOnFocus(TextView... texts) {
        for (TextView t : texts) t.setMarqueeRepeatLimit(-1);
        setOnFocusChangeListener((v, focused) -> {
            for (TextView t : texts) {
                t.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                // A text view runs its marquee only while it is selected.
                t.setSelected(focused);
            }
        });
    }

    /** A heading that opens a view: its name, a line of text after it, and an arrow at the right. */
    static Row link(Context c, String name, CharSequence info) {
        Row row = new Row(c, 5, null);
        row.setGravity(Gravity.BOTTOM);
        TextView title = text(c, name, 18, PAPER);
        title.setTypeface(MEDIUM);
        row.addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        TextView after = text(c, info, 12, QUIET);
        after.setPadding(dp(c, 10), 0, dp(c, 8), dp(c, 2));
        row.addView(after, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(c, "›", 16, QUIET), new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 16, PAPER);
        t.setTypeface(CONDENSED);
        return t;
    }

    /** One line; a text that does not fit ends in an ellipsis. Its digits all have one width, so figures line up. */
    static TextView text(Context c, CharSequence s, int size, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(colour);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    static int dp(Context c, int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
