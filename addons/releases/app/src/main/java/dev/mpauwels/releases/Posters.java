package dev.mpauwels.releases;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The posters, from the servers' own picture store, kept in the app's cache folder. */
final class Posters {
    private final File dir;
    // shortcut: every poster that was shown stays in memory (about 40 KB each); use an LruCache if a library of thousands scrolls by
    private final Map<String, Bitmap> shown = new ConcurrentHashMap<>();

    Posters(Context context) {
        dir = new File(context.getCacheDir(), "posters");
    }

    /** The poster when this run of the app has loaded it before, else null. Does not block. */
    Bitmap shown(boolean film, int mediaId) {
        return shown.get(name(film, mediaId));
    }

    /** Blocking: call it off the main thread. Null when the server has no poster for this title. */
    Bitmap load(Config.Server server, boolean film, int mediaId) {
        Bitmap bitmap = read(server, film, mediaId);
        if (bitmap != null) shown.put(name(film, mediaId), bitmap);
        return bitmap;
    }

    private static String name(boolean film, int mediaId) {
        return (film ? "film-" : "series-") + mediaId + ".jpg";
    }

    private Bitmap read(Config.Server server, boolean film, int mediaId) {
        File file = new File(dir, name(film, mediaId));
        // Half of the 250 px the server sends: a row draws the poster 40 dp wide.
        BitmapFactory.Options half = new BitmapFactory.Options();
        half.inSampleSize = 2;
        // shortcut: a cached poster is never asked again; clear the app's cache for a new one, add an age check if that annoys
        if (file.isFile()) return BitmapFactory.decodeFile(file.getPath(), half);
        Http.Reply reply = Http.get(server.url + "/api/v3/mediacover/" + mediaId + "/poster-250.jpg", server.key);
        if (!reply.ok()) return null;
        Bitmap bitmap = BitmapFactory.decodeByteArray(reply.data, 0, reply.data.length, half);
        if (bitmap != null && (dir.isDirectory() || dir.mkdirs())) {
            try {
                Files.write(file.toPath(), reply.data);
            } catch (IOException e) {
                // A full disk: the poster is asked again next time.
            }
        }
        return bitmap;
    }
}
