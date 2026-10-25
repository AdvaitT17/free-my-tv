package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My Photos from an Immich server (immich_server and immich_api_key in config.properties): every 6 hours, a random
 * choice of its photos goes through the same import as the ones a computer sends (Photos: upright, whole, the
 * screensaver's size), one at a time, so at most one original is on disk at once. The oldest go to make room.
 */
final class ImmichPhotos {
    private ImmichPhotos() {}

    private static final long EVERY_MS = 6 * 3_600_000L;
    /** After a failure (the server down), tried again this much later. */
    private static final long RETRY_MS = 30 * 60_000L;
    /** Photos per sync; the import keeps Photos.MAX in all. */
    private static final int COUNT = 40;
    /** Smaller ones would be blown up on the screen (Photos also refuses under 640). */
    private static final int MIN_SIDE = 1080;
    /** Originals bigger than this (RAW, TIFF exports…) come as Immich's preview instead. */
    private static final long MAX_ORIGINAL = 25L << 20;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    static boolean configured() {
        return !Config.IMMICH_SERVER.trim().isEmpty() && !Config.IMMICH_KEY.trim().isEmpty();
    }

    /** An album with photos in it: what My Photos can show. */
    static final class Album {
        final String id, name;

        Album(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** The server's albums with photos, once loadAlbums has read them (empty before). */
    static volatile java.util.List<Album> albums = new java.util.ArrayList<>();

    /** Reads the albums in the background; `done` runs on the main thread when they're in. */
    static void loadAlbums(Runnable done) {
        if (!configured()) return;
        WORKER.execute(() -> {
            try {
                JSONArray all = new JSONArray(request("/api/albums", null));
                java.util.List<Album> out = new java.util.ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject a = all.getJSONObject(i);
                    if (a.optInt("assetCount") > 0) out.add(new Album(a.optString("id"), a.optString("albumName")));
                }
                java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
                albums = out;
                if (done != null) MAIN.post(done);
            } catch (Exception e) {
                AppLog.w("immich albums: " + e.getMessage());
            }
        });
    }

    /** My Photos' source changed (Home Settings): the photos go, and a new choice comes at the next home screen. */
    static void sourceChanged(Context c) {
        Context app = c.getApplicationContext();
        //noinspection ResultOfMethodCallIgnored
        new File(app.getFilesDir(), "immich-sync").delete();
        boolean mine = !"default".equals(Prefs.photoSource());
        WORKER.execute(() -> Photos.removeAll(app));   // after a sync under way, never during it
        Prefs.setPhotos(mine);
    }

    /** Starts a sync in the background when one is due (and My Photos is on); `ready` runs on the main thread after. */
    static void syncIfDue(Context c, Runnable ready) {
        if (!configured() || !Prefs.photos() || "default".equals(Prefs.photoSource()) || running) return;
        Context app = c.getApplicationContext();
        File stamp = new File(app.getFilesDir(), "immich-sync");
        if (Math.abs(System.currentTimeMillis() - stamp.lastModified()) < EVERY_MS) return;   // 0 when never
        running = true;
        WORKER.execute(() -> {
            int n = 0;
            try {
                n = sync(app);
                AppLog.w("immich: " + n + " photos, " + Photos.files(app).size() + " in all");
            } catch (Exception e) {
                AppLog.w("immich: " + e.getMessage());
            } finally {
                touch(stamp, n > 0 ? System.currentTimeMillis() : System.currentTimeMillis() - EVERY_MS + RETRY_MS);
                running = false;
            }
            if (n > 0 && ready != null) MAIN.post(ready);
        });
    }

    private static int sync(Context app) throws Exception {
        JSONObject query = new JSONObject().put("size", COUNT * 2).put("type", "IMAGE").put("withExif", true);
        if ("album".equals(Prefs.photoSource()) && !Prefs.photoAlbum().isEmpty()) {
            query.put("albumIds", new JSONArray().put(Prefs.photoAlbum()));
        }
        JSONArray found = new JSONArray(request("/api/search/random", query.toString()));
        File inbox = Photos.privateInbox(app);
        if (!inbox.isDirectory() && !inbox.mkdirs()) throw new IOException("can't create " + inbox);
        Photos.dropOldest(app, Photos.MAX - COUNT);
        int got = 0;
        for (int i = 0; i < found.length() && got < COUNT; i++) {
            JSONObject a = found.getJSONObject(i);
            String id = a.optString("id");
            if (!"IMAGE".equals(a.optString("type")) || !id.matches("[0-9a-fA-F-]{8,64}")) continue;
            JSONObject exif = a.optJSONObject("exifInfo");
            if (exif != null) {
                int w = exif.optInt("exifImageWidth"), h = exif.optInt("exifImageHeight");
                if (w > 0 && h > 0 && Math.min(w, h) < MIN_SIDE) continue;
            }
            long size = exif == null ? 0 : exif.optLong("fileSizeInByte");
            boolean original = "image/jpeg".equals(a.optString("originalMimeType")) && size < MAX_ORIGINAL;
            File part = new File(inbox, ".immich-" + id), done = new File(inbox, "immich-" + id + ".jpg");
            try {
                download("/api/assets/" + id + (original ? "/original" : "/thumbnail?size=preview"), part);
                if (!part.renameTo(done)) continue;
                got++;
                Photos.importWaiting(app);   // right away: the next original doesn't wait beside this one
            } catch (IOException e) {
                AppLog.w("immich: photo skipped: " + e.getMessage());
            } finally {
                part.delete();
            }
        }
        return got;
    }

    private static HttpURLConnection open(String path) throws IOException {
        String server = Config.IMMICH_SERVER.trim();
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        HttpURLConnection http = (HttpURLConnection) new URL(server + path).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        http.setRequestProperty("x-api-key", Config.IMMICH_KEY.trim());
        http.setRequestProperty("Accept", "application/json, image/*");
        return http;
    }

    /** A JSON request: POST with `json`, or GET without it. */
    private static String request(String path, String json) throws IOException {
        HttpURLConnection http = open(path);
        try {
            if (json != null) {
                http.setRequestMethod("POST");
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = http.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " on " + path);
            try (InputStream in = http.getInputStream()) {
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
                return body.toString("UTF-8");
            }
        } finally {
            http.disconnect();
        }
    }

    private static void download(String path, File to) throws IOException {
        HttpURLConnection http = open(path);
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long left = MAX_ORIGINAL;
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if ((left -= n) < 0) throw new IOException("too big");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            http.disconnect();
        }
    }

    private static void touch(File f, long when) {
        try {
            if (!f.exists()) new FileOutputStream(f).close();
            f.setLastModified(Math.max(1, when));
        } catch (IOException ignored) {
            // asked again at the next resume
        }
    }
}
