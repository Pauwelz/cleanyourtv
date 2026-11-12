package dev.mpauwels.releases;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sonarr and Radarr (API v3): the requests for the calendar, the imports and the queue, and their answers. */
final class Arr {
    /** Radarr's release dates under its own names, with the words the list shows; the first match wins. */
    private static final String[][] FILM_DATES = {
            {"digitalRelease", "Digital release"}, {"physicalRelease", "Physical release"}, {"inCinemas", "In cinemas"}};

    /** The queue of one server: how many downloads it holds, and the downloads of the page that was read. */
    static final class Queue {
        final int total;
        final List<Download> downloads;

        Queue(int total, List<Download> downloads) {
            this.total = total;
            this.downloads = downloads;
        }
    }

    private static final Pattern TIME_LEFT = Pattern.compile("(?:(\\d+)\\.)?(\\d+):(\\d+):(\\d+).*");

    private Arr() {
    }

    /** Asks for a day on each side as well: the servers count in UTC, and episodes() and films() pick the days. */
    static String calendarUrl(Config.Server s, LocalDate from, LocalDate to, boolean withSeries) {
        return s.url + "/api/v3/calendar?start=" + from.minusDays(1) + "T00:00:00Z&end=" + to.plusDays(2) + "T00:00:00Z"
                + "&unmonitored=" + s.unmonitored + (withSeries ? "&includeSeries=true" : "");
    }

    /** The imports, newest first. Event type 3 is "downloadFolderImported" on both servers. */
    static String historyUrl(Config.Server s, boolean films) {
        return s.url + "/api/v3/history?pageSize=30&sortKey=date&sortDirection=descending&eventType=3"
                + (films ? "&includeMovie=true" : "&includeSeries=true&includeEpisode=true");
    }

    static String queueUrl(Config.Server s, boolean films) {
        // shortcut: one page of 200; a longer queue still counts right but lists its first 200 only, page through it then
        return s.url + "/api/v3/queue?pageSize=200" + (films ? "&includeMovie=true" : "&includeSeries=true&includeEpisode=true");
    }

    /** Sonarr: the episodes that air from..to in zone. airDate is not used: it is the date at the network. */
    static List<Release> episodes(String json, ZoneId zone, LocalDate from, LocalDate to) throws JSONException {
        List<Release> out = new ArrayList<>();
        JSONArray all = new JSONArray(json);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            if (o.isNull("airDateUtc")) continue;
            Instant at = Instant.parse(o.getString("airDateUtc"));
            LocalDate day = at.atZone(zone).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            JSONObject series = o.optJSONObject("series");
            out.add(new Release(day, at.toEpochMilli(), series == null ? "" : text(series, "title"), episode(o),
                    o.optBoolean("hasFile"), o.optBoolean("monitored", true), "", false, o.optInt("seriesId"), series == null ? 0 : series.optInt("tvdbId"), o.optInt("id")));
        }
        return out;
    }

    /**
     * Radarr: the films with a release date in from..to. Its dates have no time (they are stamped 00:00 UTC), so
     * the date part is read. types holds Radarr's own names, such as "digitalRelease"; empty means all three.
     */
    static List<Release> films(String json, String types, LocalDate from, LocalDate to) throws JSONException {
        List<Release> out = new ArrayList<>();
        JSONArray all = new JSONArray(json);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            for (String[] date : FILM_DATES) {
                if (!types.isEmpty() && !types.contains(date[0])) continue;
                LocalDate day = datePart(text(o, date[0]));
                if (day == null || day.isBefore(from) || day.isAfter(to)) continue;
                out.add(new Release(day, Release.NO_TIME, text(o, "title"), date[1], o.optBoolean("hasFile"), o.optBoolean("monitored", true), "", true,
                        o.optInt("id"), o.optInt("tmdbId"), 0));
                break;
            }
        }
        return out;
    }

    /**
     * The history of Sonarr or Radarr as added titles, newest first: one line per import. An import is a film, an
     * episode, or a season pack ("Season 1").
     */
    static List<Release> added(String json, ZoneId zone) throws JSONException {
        JSONArray records = new JSONObject(json).getJSONArray("records");
        // One line per import. The episodes of a season pack are one import: they share the id of their download.
        Map<String, JSONObject> newest = new LinkedHashMap<>();
        Map<String, Set<Integer>> episodes = new HashMap<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            boolean film = r.has("movieId");
            if (r.isNull("date") || r.optJSONObject(film ? "movie" : "series") == null) continue;
            String id = text(r, "downloadId");
            String key = id.isEmpty() ? "#" + i : id;
            newest.putIfAbsent(key, r);
            if (film) continue;
            episodes.computeIfAbsent(key, k -> new HashSet<>()).add(r.optInt("episodeId"));
        }
        List<Release> out = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : newest.entrySet()) {
            JSONObject r = e.getValue();
            boolean film = r.has("movieId");
            JSONObject media = r.getJSONObject(film ? "movie" : "series");
            int count = film ? 1 : episodes.get(e.getKey()).size();
            // Several episodes from one download are a season pack, named as in the queue.
            boolean pack = count > 1;
            JSONObject episode = r.optJSONObject("episode");
            Instant at = Instant.parse(r.getString("date"));
            JSONObject quality = r.optJSONObject("quality");
            JSONObject named = quality == null ? null : quality.optJSONObject("quality");
            out.add(new Release(at.atZone(zone).toLocalDate(), at.toEpochMilli(), text(media, "title"),
                    film ? "Film" : pack && episode != null ? "Season " + episode.optInt("seasonNumber") : episode(episode), true, true,
                    named == null ? "" : text(named, "name"), film, r.optInt(film ? "movieId" : "seriesId"), media.optInt(film ? "tmdbId" : "tvdbId"), 0));
        }
        return out;
    }

    /**
     * One download per id at the client: a season pack is one record per episode, and all of them carry the id, the
     * season number and the size of the pack (seen on Sonarr 4 with a pack of 4 episodes). A record whose film or series is gone is counted, and not listed.
     */
    static Queue queue(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray records = o.optJSONArray("records");
        Map<String, JSONObject> first = new LinkedHashMap<>();
        Map<String, Set<Integer>> episodes = new HashMap<>();
        Map<String, Integer> perDownload = new HashMap<>();
        for (int i = 0; records != null && i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            String id = text(r, "downloadId");
            String key = id.isEmpty() ? "#" + i : id;
            first.putIfAbsent(key, r);
            perDownload.merge(key, 1, Integer::sum);
            Set<Integer> ids = episodes.computeIfAbsent(key, k -> new HashSet<>());
            if (r.has("episodeId")) ids.add(r.optInt("episodeId"));
        }
        List<Download> downloads = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : first.entrySet()) {
            JSONObject r = e.getValue();
            boolean film = r.has("movie");
            JSONObject media = r.optJSONObject(film ? "movie" : "series");
            if (media == null) continue;
            int count = perDownload.get(e.getKey());
            // Several records of one download are a season pack. A pack that came as one record has no episode of its own.
            String detail = film ? "Film" : count > 1 ? "Season " + r.optInt("seasonNumber") : episode(r.optJSONObject("episode"));
            downloads.add(new Download(e.getKey(), text(media, "title"), detail, film, r.optInt(film ? "movieId" : "seriesId"),
                    "importBlocked".equals(r.optString("trackedDownloadState")), reason(r), r.optLong("size"), r.optLong("sizeleft"),
                    seconds(text(r, "timeleft")), text(r, "status"), text(r, "downloadClient"), episodes.get(e.getKey())));
        }
        int repeats = records == null ? 0 : records.length() - first.size();
        return new Queue(o.optInt("totalRecords") - repeats, downloads);
    }

    /** ".NET" time spans: "00:07:54", "1.05:17:11" with days, and sometimes a fraction of a second at the end. */
    static long seconds(String timeLeft) {
        Matcher m = TIME_LEFT.matcher(timeLeft);
        if (!m.matches()) return Download.UNKNOWN;
        long days = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        return ((days * 24 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 60 + Long.parseLong(m.group(4));
    }

    /** The first sentence of the first message of a queue record, or its error; "" without one. */
    private static String reason(JSONObject r) {
        JSONArray files = r.optJSONArray("statusMessages");
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONArray messages = files.optJSONObject(i) == null ? null : files.optJSONObject(i).optJSONArray("messages");
            for (int j = 0; messages != null && j < messages.length(); j++) {
                String message = messages.optString(j).trim();
                // "Sample" is the note on a sample file, not the reason for the download.
                if (message.isEmpty() || message.equals("Sample")) continue;
                int end = message.indexOf(". ");
                return end < 0 ? message : message.substring(0, end);
            }
        }
        return text(r, "errorMessage");
    }

    /** "3x05, Its name", the number alone when it has no name, "" without an episode. */
    private static String episode(JSONObject o) {
        String number = number(o);
        String name = o == null ? "" : text(o, "title");
        return name.isEmpty() ? number : number + ", " + name;
    }

    private static String number(JSONObject o) {
        return o == null ? "" : String.format(Locale.ROOT, "%dx%02d", o.optInt("seasonNumber"), o.optInt("episodeNumber"));
    }

    private static LocalDate datePart(String stamp) {
        try {
            return stamp.length() < 10 ? null : LocalDate.parse(stamp.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "" for a missing key and for a JSON null: Android's optString gives the text "null" for the second. */
    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key);
    }
}
