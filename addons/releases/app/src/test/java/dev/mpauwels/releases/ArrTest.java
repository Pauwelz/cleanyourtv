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
        assertEquals("3x05 · Part 5", r.detail);
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
        assertEquals("3x06 · Part 6", denver.get(0).detail);
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

    @Test public void addedFoldsRepeatsAndBulkImports() throws Exception {
        String sonarr = "{\"records\":[" + imported("2026-11-12T10:14:00Z", 1, "Harbour Lights", 50, 9)
                + "," + imported("2026-11-12T06:37:00Z", 2, "Night Ferry", 60, 5)      // the same episode, imported twice
                + "," + imported("2026-11-12T06:09:00Z", 2, "Night Ferry", 60, 5)
                + "," + imported("2026-11-12T04:10:00Z", 3, "Salt", 70, 8)             // two episodes of one series
                + "," + imported("2026-11-12T04:09:00Z", 3, "Salt", 71, 13)
                + ",{\"date\":\"2026-11-12T03:00:00Z\",\"seriesId\":4,\"episodeId\":80}]}";  // the series is gone: skipped
        List<Release> out = Arr.added(sonarr, BRUSSELS);
        assertEquals(Arrays.asList("Harbour Lights", "Night Ferry", "Salt"), Arrays.asList(out.get(0).title, out.get(1).title, out.get(2).title));
        assertEquals(3, out.size());
        assertEquals("2x09 · Part 9", out.get(0).detail);
        assertEquals("2x05 · Part 5", out.get(1).detail);
        assertEquals(Instant.parse("2026-11-12T06:37:00Z").toEpochMilli(), out.get(1).atMs);
        assertEquals("2 episodes", out.get(2).detail);
        assertEquals("WEBDL-1080p", out.get(0).quality);
        assertEquals("plezy://play?type=show&tvdb=4001", out.get(0).playLink());

        String radarr = "{\"records\":[{\"date\":\"2026-11-11T23:36:00Z\",\"movieId\":9,\"quality\":null,\"movie\":{\"title\":\"Low Tide\",\"tmdbId\":603}}]}";
        Release film = Arr.added(radarr, BRUSSELS).get(0);
        assertEquals("Film", film.detail);
        assertEquals("", film.quality);
        assertEquals("00:36 on the 12th in Brussels", DAY, film.day);
        assertTrue(film.film && film.hasFile);
        assertEquals(9, film.mediaId);
    }

    @Test public void queueCountsAndNamesTheBlockedImports() throws Exception {
        Arr.Queue q = Arr.queue("{\"totalRecords\":57,\"records\":["
                + "{\"trackedDownloadState\":\"downloading\",\"series\":{\"title\":\"Salt\"},\"episode\":{\"seasonNumber\":1,\"episodeNumber\":2}},"
                + "{\"trackedDownloadState\":\"importBlocked\",\"series\":{\"title\":\"Night Ferry\"},\"episode\":{\"seasonNumber\":3,\"episodeNumber\":5}},"
                + "{\"trackedDownloadState\":\"importBlocked\",\"series\":{\"title\":\"Harbour Lights\"}},"   // a season pack
                + "{\"trackedDownloadState\":\"importBlocked\",\"movie\":{\"title\":\"Low Tide\"}},"
                + "{\"trackedDownloadState\":\"importBlocked\"}]}");
        assertEquals(57, q.total);
        assertEquals(Arrays.asList("Night Ferry 3x05", "Harbour Lights", "Low Tide"), q.blocked);
        assertEquals(0, Arr.queue("{\"totalRecords\":0,\"records\":[]}").blocked.size());
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
