package dev.mpauwels.releases;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One focusable line of a column: with a poster and up to three lines of text, or small with a time. */
final class Row extends LinearLayout {
    /** Null on a small row. */
    final ImageView poster;

    private Row(Context context, int verticalDp, ImageView poster) {
        super(context);
        this.poster = poster;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(context, 12), dp(context, verticalDp), dp(context, 12), dp(context, verticalDp));
        setBackgroundResource(R.drawable.row_background);
        // Focusable also without an action, so that the arrows scroll a long column.
        setFocusable(true);
    }

    static Row poster(Context c, String title, String line2, String line3, int line3Colour) {
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.poster_background);
        image.setClipToOutline(true);
        Row row = new Row(c, 7, image);
        LayoutParams size = new LayoutParams(dp(c, 40), dp(c, 60));
        size.rightMargin = dp(c, 12);
        row.addView(image, size);
        LinearLayout lines = new LinearLayout(c);
        lines.setOrientation(VERTICAL);
        lines.addView(text(c, title, 16, 0xFFFFFFFF));
        if (!line2.isEmpty()) lines.addView(text(c, line2, 12, 0xFF8B949E));
        if (!line3.isEmpty()) lines.addView(text(c, line3, 12, line3Colour));
        row.addView(lines, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    static Row small(Context c, String time, CharSequence text, String state, int stateColour) {
        Row row = new Row(c, 4, null);
        row.addView(text(c, time, 15, 0xFFC9D1D9), new LayoutParams(dp(c, 56), LayoutParams.WRAP_CONTENT));
        row.addView(text(c, text, 14, 0xFFFFFFFF), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        if (!state.isEmpty()) {
            TextView right = text(c, state, 12, stateColour);
            right.setPadding(dp(c, 8), 0, 0, 0);
            row.addView(right, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /** One line; a text that does not fit ends in an ellipsis. */
    static TextView text(Context c, CharSequence s, int size, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(colour);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static int dp(Context c, int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
