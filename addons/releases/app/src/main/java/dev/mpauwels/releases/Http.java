package dev.mpauwels.releases;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One GET to Sonarr or Radarr. Blocking: call it off the main thread. */
final class Http {
    static final class Reply {
        /** 0 when the server could not be reached; error then says why. */
        final int code;
        final byte[] data;
        final String error;

        Reply(int code, byte[] data, String error) {
            this.code = code;
            this.data = data;
            this.error = error;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }

        String body() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static final int TIMEOUT_MS = 10_000;
    /** Ten days of episodes, each with its whole series, stay far below this. */
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private Http() {
    }

    static Reply get(String url, String apiKey) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("X-Api-Key", apiKey);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Reply(code, in == null ? new byte[0] : read(in), "");
        } catch (IOException | RuntimeException e) {
            return new Reply(0, new byte[0], String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("the answer is too large");
            }
            return out.toByteArray();
        }
    }
}
