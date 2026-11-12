package dev.mpauwels.releases;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** The posters, from the servers' own picture store, kept in the app's cache folder. */
final class Posters {
    private final File dir;

    Posters(Context context) {
        dir = new File(context.getCacheDir(), "posters");
    }

    /** Blocking: call it off the main thread. Null when the server has no poster for this title. */
    Bitmap load(Config.Server server, Release r) {
        File file = new File(dir, (r.film ? "film-" : "series-") + r.mediaId + ".jpg");
        // Half of the 250 px the server sends: a row draws the poster 40 dp wide.
        BitmapFactory.Options half = new BitmapFactory.Options();
        half.inSampleSize = 2;
        // shortcut: a cached poster is never asked again; clear the app's cache for a new one, add an age check if that annoys
        if (file.isFile()) return BitmapFactory.decodeFile(file.getPath(), half);
        Http.Reply reply = Http.get(server.url + "/api/v3/mediacover/" + r.mediaId + "/poster-250.jpg", server.key);
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
