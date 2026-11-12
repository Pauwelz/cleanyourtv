package dev.mpauwels.releases;

import java.time.LocalDate;

/** One line of the screen: an episode or a film on the calendar, or a title that was added. */
final class Release {
    static final long NO_TIME = -1;

    /** The day on the TV's clock: of the release, or of the import for an added title. */
    final LocalDate day;
    /** Epoch milliseconds of the air time or the import; NO_TIME for a film on the calendar, which has a date only. */
    final long atMs;
    final String title;
    final String detail;
    final boolean hasFile;
    /** False for a title that the server does not look for: it has no file by choice. */
    final boolean monitored;
    /** Known for an added title only; "" otherwise. */
    final String quality;
    /** A film, from Radarr; otherwise a series, from Sonarr. */
    final boolean film;
    /** The id of the film or the series on its server: the poster is asked by it. */
    final int mediaId;
    /** TMDB id of a film, TVDB id of a series; 0 when the server gave none. */
    final int externalId;

    Release(LocalDate day, long atMs, String title, String detail, boolean hasFile, boolean monitored, String quality, boolean film, int mediaId, int externalId) {
        this.day = day;
        this.atMs = atMs;
        this.title = title;
        this.detail = detail;
        this.hasFile = hasFile;
        this.monitored = monitored;
        this.quality = quality;
        this.film = film;
        this.mediaId = mediaId;
        this.externalId = externalId;
    }

    /**
     * The link that plays this title in Plezy ("plezy:// links" in the README of github.com/edde746/plezy), or
     * null without an id. For a series Plezy plays the next episode to watch.
     */
    String playLink() {
        return externalId <= 0 ? null : "plezy://play?type=" + (film ? "movie&tmdb=" : "show&tvdb=") + externalId;
    }
}
