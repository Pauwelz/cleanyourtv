package dev.mpauwels.releases;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The servers, from servers.json in the app's own storage (backups are off). setup.sh sends that file. */
final class Config {
    static final String FILE = "servers.json";

    static final class Server {
        final String url;
        final String key;
        /** Also the titles that are not monitored. */
        final boolean unmonitored;
        /** Radarr only: see Arr.films. */
        final String types;

        Server(String url, String key, boolean unmonitored, String types) {
            this.url = url;
            this.key = key;
            this.unmonitored = unmonitored;
            this.types = types;
        }
    }

    /** Null when the file does not name it. */
    final Server sonarr;
    final Server radarr;

    Config(Server sonarr, Server radarr) {
        this.sonarr = sonarr;
        this.radarr = radarr;
    }

    /** {"sonarr": {"url", "key", "unmonitored"}, "radarr": {"url", "key", "unmonitored", "types"}}. */
    static Config parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new Config(server(o.optJSONObject("sonarr")), server(o.optJSONObject("radarr")));
    }

    private static Server server(JSONObject o) {
        if (o == null) return null;
        String url = o.optString("url").trim().replaceAll("/+$", "");
        String key = o.optString("key").trim();
        if (url.isEmpty() || key.isEmpty()) return null;
        return new Server(url, key, o.optBoolean("unmonitored"), o.optString("types"));
    }

    /** Null when setup.sh has not sent the file, or the file is not JSON. */
    static Config load(Context c) {
        try {
            return parse(new String(Files.readAllBytes(new File(c.getFilesDir(), FILE).toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }
}
