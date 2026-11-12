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

/** Sonarr and Radarr (API v3): the requests for the calendar, the imports and the queue, and their answers. */
final class Arr {
    /** Radarr's release dates under its own names, with the words the list shows; the first match wins. */
    private static final String[][] FILM_DATES = {
            {"digitalRelease", "Digital release"}, {"physicalRelease", "Physical release"}, {"inCinemas", "In cinemas"}};

    /** The queue of one server: how many downloads it holds, and the titles whose import is blocked. */
    static final class Queue {
        final int total;
        final List<String> blocked;

        Queue(int total, List<String> blocked) {
            this.total = total;
            this.blocked = blocked;
        }
    }

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
        // shortcut: one page of 200; a longer queue still counts right but can miss a blocked import, page through it then
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
                    o.optBoolean("hasFile"), o.optBoolean("monitored", true), "", false, o.optInt("seriesId"), series == null ? 0 : series.optInt("tvdbId")));
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
                        o.optInt("id"), o.optInt("tmdbId")));
                break;
            }
        }
        return out;
    }

    /**
     * The history of Sonarr or Radarr as added titles, newest first: one line per film, and one per series however
     * many of its episodes came in. An episode that was imported twice counts once.
     */
    static List<Release> added(String json, ZoneId zone) throws JSONException {
        JSONArray records = new JSONObject(json).getJSONArray("records");
        Map<Integer, JSONObject> newest = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> episodes = new HashMap<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            boolean film = r.has("movieId");
            if (r.isNull("date") || r.optJSONObject(film ? "movie" : "series") == null) continue;
            int id = r.optInt(film ? "movieId" : "seriesId");
            newest.putIfAbsent(id, r);
            if (!film) episodes.computeIfAbsent(id, k -> new HashSet<>()).add(r.optInt("episodeId"));
        }
        List<Release> out = new ArrayList<>();
        for (Map.Entry<Integer, JSONObject> e : newest.entrySet()) {
            JSONObject r = e.getValue();
            boolean film = r.has("movieId");
            JSONObject media = r.getJSONObject(film ? "movie" : "series");
            int count = film ? 1 : episodes.get(e.getKey()).size();
            Instant at = Instant.parse(r.getString("date"));
            JSONObject quality = r.optJSONObject("quality");
            JSONObject named = quality == null ? null : quality.optJSONObject("quality");
            out.add(new Release(at.atZone(zone).toLocalDate(), at.toEpochMilli(), text(media, "title"),
                    film ? "Film" : count > 1 ? count + " episodes" : episode(r.optJSONObject("episode")), true, true,
                    named == null ? "" : text(named, "name"), film, e.getKey(), media.optInt(film ? "tmdbId" : "tvdbId")));
        }
        return out;
    }

    static Queue queue(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray records = o.optJSONArray("records");
        List<String> blocked = new ArrayList<>();
        for (int i = 0; records != null && i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            if (!"importBlocked".equals(r.optString("trackedDownloadState"))) continue;
            JSONObject media = r.optJSONObject(r.has("movie") ? "movie" : "series");
            if (media == null) continue;
            // A season pack has no episode of its own.
            String number = number(r.optJSONObject("episode"));
            blocked.add(number.isEmpty() ? text(media, "title") : text(media, "title") + " " + number);
        }
        return new Queue(o.optInt("totalRecords"), blocked);
    }

    /** "3x05 · Its name", the number alone when it has no name, "" without an episode. */
    private static String episode(JSONObject o) {
        String number = number(o);
        String name = o == null ? "" : text(o, "title");
        return name.isEmpty() ? number : number + " · " + name;
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
