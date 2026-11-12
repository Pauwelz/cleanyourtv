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
    /** The id of the episode on its server, for an episode on the calendar: the queue names its downloads by it. 0 otherwise. */
    final int episodeId;

    Release(LocalDate day, long atMs, String title, String detail, boolean hasFile, boolean monitored, String quality, boolean film, int mediaId, int episodeId) {
        this.day = day;
        this.atMs = atMs;
        this.title = title;
        this.detail = detail;
        this.hasFile = hasFile;
        this.monitored = monitored;
        this.quality = quality;
        this.film = film;
        this.mediaId = mediaId;
        this.episodeId = episodeId;
    }
}
