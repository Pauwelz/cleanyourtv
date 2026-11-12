package dev.mpauwels.releases;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

/** One download in the queue of Sonarr or Radarr. */
final class Download {
    static final long UNKNOWN = -1;
    /** The soonest first; a download without a time left comes last. */
    static final Comparator<Download> ORDER = Comparator.comparingLong((Download d) -> d.leftSeconds < 0 ? Long.MAX_VALUE : d.leftSeconds)
            .thenComparing(d -> d.title);

    /** The id of the download at its client: the same over two reads of the queue. */
    final String key;
    final String title;
    /** "3x05", "4 episodes" or "Film"; "" for a season pack that names no episode. */
    final String detail;
    final boolean film;
    /** The id of the film or the series on its server: the poster is asked by it. */
    final int mediaId;
    /** The download is done, and the server refuses to import it. */
    final boolean blocked;
    /** Why it is blocked, in the server's words; "" when it gave none. */
    final String reason;
    final long size;
    final long sizeLeft;
    /** UNKNOWN when the server gave no time, as for a paused download. */
    final long leftSeconds;
    final String status;
    final String client;
    /** The ids of the episodes it holds, on their server; empty for a film. */
    final Set<Integer> episodes;

    Download(String key, String title, String detail, boolean film, int mediaId, boolean blocked, String reason, long size, long sizeLeft,
            long leftSeconds, String status, String client, Set<Integer> episodes) {
        this.key = key;
        this.title = title;
        this.detail = detail;
        this.film = film;
        this.mediaId = mediaId;
        this.blocked = blocked;
        this.reason = reason;
        this.size = size;
        this.sizeLeft = sizeLeft;
        this.leftSeconds = leftSeconds;
        this.status = status;
        this.client = client;
        this.episodes = episodes;
    }

    /** This download brings that release of the calendar: the same film, or an episode that it holds. */
    boolean brings(Release r) {
        return r.film ? film && mediaId == r.mediaId : !film && episodes.contains(r.episodeId);
    }

    int percent() {
        return size <= 0 ? 0 : (int) Math.max(0, Math.min(100, Math.round(100.0 * (size - sizeLeft) / size)));
    }

    /** "8 min", "1 h 01 min", "1 d 5 h"; "" without a time left. */
    String left() {
        if (leftSeconds <= 0) return "";
        long minutes = Math.max(1, leftSeconds / 60);
        if (minutes < 60) return minutes + " min";
        if (minutes < 24 * 60) return String.format(Locale.ROOT, "%d h %02d min", minutes / 60, minutes % 60);
        return minutes / (24 * 60) + " d " + minutes / 60 % 24 + " h";
    }

    /** The figure beside its title: "8 min", "Importing" for a download that is done, "Paused"; "" when nothing is known. */
    String when() {
        if ("completed".equals(status)) return "Importing";
        String left = left();
        return !left.isEmpty() ? left : "paused".equals(status) ? "Paused" : "";
    }

    /** The detail line of its card: "Film, 6.4 of 8.4 GB, SABnzbd". */
    String line() {
        String amount = String.format(Locale.ROOT, "%.1f of %.1f GB", (size - sizeLeft) / 1e9, size / 1e9);
        return (detail.isEmpty() ? "" : detail + ", ") + amount + (client.isEmpty() ? "" : ", " + client);
    }
}
