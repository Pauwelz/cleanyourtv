package dev.mpauwels.releases;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

public class ArrTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    private static String episode(int number, String airDate, String airDateUtc, boolean hasFile) {
        return "{\"seriesId\":7,\"seasonNumber\":3,\"episodeNumber\":" + number + ",\"title\":\"Part " + number + "\",\"airDate\":\"" + airDate
                + "\",\"airDateUtc\":" + airDateUtc + ",\"hasFile\":" + hasFile + ",\"series\":{\"title\":\"Harbour Lights\",\"tvdbId\":4242}}";
    }

    private static final String EPISODES = "[" + episode(4, "2026-11-11", "\"2026-11-11T22:30:00Z\"", true)   // 23:30 on the 11th in Brussels
            + "," + episode(5, "2026-11-11", "\"2026-11-12T03:00:00Z\"", true)        // 04:00 on the 12th: the network's date is the 11th
            + "," + episode(6, "2026-11-12", "\"2026-11-12T23:30:00Z\"", false)       // 00:30 on the 13th
            + "," + episode(7, "2026-11-12", "null", false) + "]";

    @Test public void episodesOfTheLocalDay() throws Exception {
        List<Release> out = Arr.episodes(EPISODES, BRUSSELS, DAY, DAY);
        assertEquals(1, out.size());
        Release r = out.get(0);
        assertEquals("Harbour Lights", r.title);
        assertEquals("3x05, Part 5", r.detail);
        assertEquals(DAY, r.day);
        assertTrue(r.hasFile);
        assertTrue("monitored unless the server says otherwise", r.monitored);
        assertFalse(r.film);
        assertEquals(7, r.mediaId);
        assertEquals(Instant.parse("2026-11-12T03:00:00Z").toEpochMilli(), r.atMs);
        assertEquals("plezy://play?type=show&tvdb=4242", r.playLink());
        // The same answer in a zone west of UTC: 03:00Z is the evening of the 11th there, 23:30Z is the 12th.
        List<Release> denver = Arr.episodes(EPISODES, ZoneId.of("America/Denver"), DAY, DAY);
        assertEquals(1, denver.size());
        assertEquals("3x06, Part 6", denver.get(0).detail);
    }

    @Test public void episodesOfSeveralDays() throws Exception {
        List<Release> out = Arr.episodes(EPISODES, BRUSSELS, DAY, DAY.plusDays(7));
        assertEquals(2, out.size());
        assertEquals(DAY.plusDays(1), out.get(1).day);
    }

    @Test public void filmsByDatePartAndType() throws Exception {
        String json = "[{\"id\":3,\"tmdbId\":603,\"title\":\"Low Tide\",\"inCinemas\":\"2026-08-01T00:00:00Z\",\"digitalRelease\":\"2026-11-12T00:00:00Z\","
                + "\"physicalRelease\":null,\"hasFile\":false},"
                + "{\"title\":\"Paper Moons\",\"inCinemas\":\"2026-11-12T00:00:00Z\",\"digitalRelease\":null,\"hasFile\":false,\"monitored\":false},"
                + "{\"title\":\"Tomorrow\",\"digitalRelease\":\"2026-11-13T00:00:00Z\",\"hasFile\":true}]";
        List<Release> all = Arr.films(json, "", DAY, DAY);
        assertEquals(2, all.size());
        assertEquals("Low Tide", all.get(0).title);
        assertEquals("Digital release", all.get(0).detail);
        assertEquals(Release.NO_TIME, all.get(0).atMs);
        assertTrue(all.get(0).film);
        assertEquals("plezy://play?type=movie&tmdb=603", all.get(0).playLink());
        assertEquals("In cinemas", all.get(1).detail);
        assertNull("no id, no link", all.get(1).playLink());
        assertTrue(all.get(0).monitored);
        assertFalse(all.get(1).monitored);
        List<Release> digital = Arr.films(json, "digitalRelease", DAY, DAY);
        assertEquals(1, digital.size());
        assertFalse(digital.get(0).hasFile);
        assertEquals(DAY.plusDays(1), Arr.films(json, "digitalRelease", DAY, DAY.plusDays(7)).get(1).day);
    }

    private static String imported(String date, int seriesId, String series, int episodeId, int number) {
        return "{\"date\":\"" + date + "\",\"seriesId\":" + seriesId + ",\"episodeId\":" + episodeId + ",\"quality\":{\"quality\":{\"name\":\"WEBDL-1080p\"}},"
                + "\"series\":{\"title\":\"" + series + "\",\"tvdbId\":" + (4000 + seriesId) + "},\"episode\":{\"seasonNumber\":2,\"episodeNumber\":" + number + ",\"title\":\"Part " + number + "\"}}";
    }

    @Test public void addedListsEveryImport() throws Exception {
        String sonarr = "{\"records\":[" + imported("2026-11-12T10:14:00Z", 1, "Harbour Lights", 50, 9)
                + "," + imported("2026-11-12T06:37:00Z", 2, "Night Ferry", 60, 5)      // the same episode, imported twice
                + "," + imported("2026-11-12T06:09:00Z", 2, "Night Ferry", 60, 5)
                + "," + imported("2026-11-12T04:10:00Z", 3, "Salt", 70, 8)             // two episodes of one series
                + "," + imported("2026-11-12T04:09:00Z", 3, "Salt", 71, 13)
                + "," + imported("2026-11-08T01:39:00Z", 1, "Harbour Lights", 51, 1)   // the same series on another day: its own line
                + ",{\"date\":\"2026-11-12T03:00:00Z\",\"seriesId\":4,\"episodeId\":80}]}";  // the series is gone: skipped
        List<Release> out = Arr.added(sonarr, BRUSSELS);
        assertEquals("every import is a line, also a second import of one episode", 6, out.size());
        assertEquals(Arrays.asList("Harbour Lights", "Night Ferry", "Night Ferry", "Salt", "Salt", "Harbour Lights"),
                Arrays.asList(out.get(0).title, out.get(1).title, out.get(2).title, out.get(3).title, out.get(4).title, out.get(5).title));
        assertEquals("2x09, Part 9", out.get(0).detail);
        assertEquals("2x05, Part 5", out.get(1).detail);
        assertEquals(Instant.parse("2026-11-12T06:37:00Z").toEpochMilli(), out.get(1).atMs);
        assertEquals("2x08, Part 8", out.get(3).detail);
        assertEquals("2x13, Part 13", out.get(4).detail);
        assertEquals("WEBDL-1080p", out.get(0).quality);
        assertEquals("plezy://play?type=show&tvdb=4001", out.get(0).playLink());

        String pack = "{\"records\":[{\"date\":\"2026-11-12T14:45:04Z\",\"seriesId\":5,\"episodeId\":91,\"downloadId\":\"P\",\"series\":{\"title\":\"Side Quest\"},\"episode\":{\"seasonNumber\":1,\"episodeNumber\":2}},"
                + "{\"date\":\"2026-11-12T14:45:03Z\",\"seriesId\":5,\"episodeId\":90,\"downloadId\":\"P\",\"series\":{\"title\":\"Side Quest\"},\"episode\":{\"seasonNumber\":1,\"episodeNumber\":1}}]}";
        assertEquals("two episodes from one download are a season pack", "Season 1", Arr.added(pack, BRUSSELS).get(0).detail);
        assertEquals(1, Arr.added(pack, BRUSSELS).size());
        List<Release> apart = Arr.added(pack.replace("\"downloadId\":\"P\",\"series", "\"downloadId\":\"Q\",\"series").replaceFirst("\"Q\"", "\"P\""), BRUSSELS);
        assertEquals("from two downloads they are two lines", Arrays.asList("1x02", "1x01"), Arrays.asList(apart.get(0).detail, apart.get(1).detail));

        String radarr = "{\"records\":[{\"date\":\"2026-11-11T23:36:00Z\",\"movieId\":9,\"quality\":null,\"movie\":{\"title\":\"Low Tide\",\"tmdbId\":603}}]}";
        Release film = Arr.added(radarr, BRUSSELS).get(0);
        assertEquals("Film", film.detail);
        assertEquals("", film.quality);
        assertEquals("00:36 on the 12th in Brussels", DAY, film.day);
        assertTrue(film.film && film.hasFile);
        assertEquals(9, film.mediaId);
    }

    private static final String UPGRADE = "Not a Custom Format upgrade for existing episode file(s). New: [] (0) do not improve on Existing: [AMZN] (1775)";

    @Test public void queueListsOneDownloadPerId() throws Exception {
        Arr.Queue q = Arr.queue("{\"totalRecords\":57,\"records\":["
                + "{\"downloadId\":\"A\",\"seriesId\":7,\"status\":\"downloading\",\"trackedDownloadState\":\"downloading\",\"size\":2000,\"sizeleft\":500,"
                + "\"timeleft\":\"00:07:54\",\"downloadClient\":\"SABnzbd\",\"series\":{\"title\":\"Salt\"},\"episode\":{\"seasonNumber\":1,\"episodeNumber\":2}},"
                + "{\"downloadId\":\"B\",\"status\":\"completed\",\"trackedDownloadState\":\"importBlocked\",\"size\":10,\"sizeleft\":0,\"timeleft\":\"00:00:00\","
                + "\"series\":{\"title\":\"Night Ferry\"},\"episode\":{\"seasonNumber\":3,\"episodeNumber\":5},"
                + "\"statusMessages\":[{\"title\":\"a\",\"messages\":[]},{\"title\":\"b\",\"messages\":[\"Sample\",\"" + UPGRADE + "\"]}]},"
                + "{\"downloadId\":\"C\",\"episodeId\":21,\"seasonNumber\":2,\"status\":\"downloading\",\"series\":{\"title\":\"Harbour Lights\"},\"episode\":{\"seasonNumber\":2,\"episodeNumber\":1}},"   // a season pack
                + "{\"downloadId\":\"C\",\"episodeId\":22,\"seasonNumber\":2,\"status\":\"downloading\",\"series\":{\"title\":\"Harbour Lights\"},\"episode\":{\"seasonNumber\":2,\"episodeNumber\":2}},"
                + "{\"status\":\"paused\",\"movieId\":9,\"size\":0,\"sizeleft\":0,\"timeleft\":null,\"errorMessage\":\"No space\",\"movie\":{\"title\":\"Low Tide\"}},"
                + "{\"trackedDownloadState\":\"importBlocked\"}]}");
        assertEquals("the two records of the pack count once", 56, q.total);
        assertEquals(4, q.downloads.size());
        Download salt = q.downloads.get(0), ferry = q.downloads.get(1), pack = q.downloads.get(2), film = q.downloads.get(3);
        assertEquals(Arrays.asList("Salt", "1x02", "A", "SABnzbd"), Arrays.asList(salt.title, salt.detail, salt.key, salt.client));
        assertEquals(7, salt.mediaId);
        assertEquals(474, salt.leftSeconds);
        assertEquals("7 min", salt.when());
        assertEquals(75, salt.percent());
        assertEquals("1x02, 0.0 of 0.0 GB, SABnzbd", salt.line());
        assertFalse(salt.blocked || salt.film);
        assertTrue(ferry.blocked);
        assertEquals("the first sentence, and not the note on the sample file", "Not a Custom Format upgrade for existing episode file(s)", ferry.reason);
        assertEquals("Importing", ferry.when());
        assertEquals("Season 2", pack.detail);
        assertEquals("the pack holds both episodes", new java.util.HashSet<>(Arrays.asList(21, 22)), pack.episodes);
        Release second = Arr.episodes("[{\"id\":22,\"seriesId\":7,\"airDateUtc\":\"2026-11-12T03:00:00Z\"}]", BRUSSELS, DAY, DAY).get(0);
        assertTrue("an episode of the calendar that the pack brings", pack.brings(second));
        assertFalse(salt.brings(second));
        assertTrue(film.brings(Arr.films("[{\"id\":9,\"title\":\"Low Tide\",\"digitalRelease\":\"2026-11-12T00:00:00Z\"}]", "", DAY, DAY).get(0)));
        assertEquals("Film", film.detail);
        assertTrue(film.film);
        assertEquals(9, film.mediaId);
        assertEquals("No space", film.reason);
        assertEquals("Paused", film.when());
        assertEquals("no size: no division by zero", 0, film.percent());
        assertEquals(Download.UNKNOWN, film.leftSeconds);
        assertEquals(0, Arr.queue("{\"totalRecords\":0,\"records\":[]}").downloads.size());
    }

    private static Download download(String title, long size, long sizeLeft, String timeLeft, String status) {
        return new Download(title, title, "Film", true, 1, false, "", size, sizeLeft, Arr.seconds(timeLeft), status, "qBittorrent", java.util.Collections.emptySet());
    }

    @Test public void timeLeftAndOrder() {
        assertEquals(29 * 3600 + 17 * 60 + 11, Arr.seconds("1.05:17:11"));
        assertEquals(474, Arr.seconds("00:07:54.1230000"));
        assertEquals(Download.UNKNOWN, Arr.seconds(""));
        Download day = download("A", 13_000_000_000L, 13_000_000_000L, "1.05:17:11", "downloading");
        Download hour = download("B", 9_200_000_000L, 1_700_000_000L, "01:00:56", "downloading");
        Download paused = download("C", 100, 50, "", "paused");
        Download seconds = download("D", 100, 1, "00:00:20", "downloading");
        assertEquals("1 d 5 h", day.when());
        assertEquals("1 h 00 min", hour.when());
        assertEquals(82, hour.percent());
        assertEquals("Film, 7.5 of 9.2 GB, qBittorrent", hour.line());
        assertEquals("Paused", paused.when());
        assertEquals("1 min", seconds.when());
        List<Download> all = new java.util.ArrayList<>(Arrays.asList(paused, day, hour, seconds));
        all.sort(Download.ORDER);
        assertEquals("the soonest first, no time left last", Arrays.asList(seconds, hour, day, paused), all);
    }

    @Test public void requests() throws Exception {
        Config c = Config.parse("{\"sonarr\":{\"url\":\"http://192.168.1.50:8989/\",\"key\":\" abc \",\"unmonitored\":true},"
                + "\"radarr\":{\"url\":\"\",\"key\":\"abc\"}}");
        assertNull("a server without an address is not set", c.radarr);
        assertEquals("abc", c.sonarr.key);
        assertEquals("http://192.168.1.50:8989/api/v3/calendar?start=2026-11-11T00:00:00Z&end=2026-11-21T00:00:00Z"
                + "&unmonitored=true&includeSeries=true", Arr.calendarUrl(c.sonarr, DAY, DAY.plusDays(7), true));
        assertTrue(Arr.historyUrl(c.sonarr, false).endsWith("&eventType=3&includeSeries=true&includeEpisode=true"));
        assertTrue(Arr.queueUrl(c.sonarr, true).endsWith("/api/v3/queue?pageSize=200&includeMovie=true"));
    }
}
