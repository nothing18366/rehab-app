package com.robint.data.local;

import com.robint.data.remote.RehabApiClient;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class TeachingMediaCache {
    public interface Ready { void done(Cached cached, String message); }
    public interface Progress { void show(String state); }
    public static class Cached {
        public final File video, subtitle;
        public final JSONObject item;
        public final long synced;
        Cached(File directory, JSONObject item, long stamp) {
            this.item = item; synced = stamp;
            video = new File(directory, "video.mp4"); subtitle = new File(directory, "subtitle.srt");
        }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build();
    private volatile int generation;
    public TeachingMediaCache(Context context) { this.context = context; }
    public static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return result.toString();
    }
    private File root(String base, String key) throws Exception {
        return new File(context.getFilesDir(), "teaching/" + hash(base.getBytes("UTF-8")) + "/" + key);
    }
    private String manifestKey(String base, String key) throws Exception { return "media_last_" + hash((base + "/" + key).getBytes("UTF-8")); }
    private Cached previous(String base, String key) {
        try {
            JSONObject saved = new JSONObject(context.getSharedPreferences("rehab", 0).getString(manifestKey(base, key), ""));
            JSONObject item = saved.getJSONObject("item");
            File directory = new File(root(base, key), item.getString("content_version"));
            Cached cached = new Cached(directory, item, saved.getLong("synced"));
            JSONObject files = item.getJSONObject("files");
            return valid(cached.video, files.getJSONObject("video")) && valid(cached.subtitle, files.getJSONObject("subtitle"))
                && valid(new File(directory, "poster.jpg"), files.getJSONObject("poster")) ? cached : null;
        } catch (Exception e) { return null; }
    }
    public void video(String base, JSONObject item, Ready ready, Progress progress) {
        int ticket = ++generation;
        worker.execute(() -> {
            String key = item.optString("key"), version = item.optString("content_version");
            Cached old = previous(base, key);
            try {
                if (!key.matches("rub|fist|stretch|cup|abduction|press") || !version.matches("[a-f0-9]{16}")) throw new IOException("请联网同步教学目录");
                File directory = new File(root(base, key), version);
                JSONObject files = item.getJSONObject("files");
                boolean cached = valid(new File(directory, "video.mp4"), files.getJSONObject("video"))
                    && valid(new File(directory, "subtitle.srt"), files.getJSONObject("subtitle"))
                    && valid(new File(directory, "poster.jpg"), files.getJSONObject("poster"));
                if (!cached) {
                    publish(ticket, () -> progress.show(old == null ? "未下载 → 下载中" : "发现新版本 → 下载中（保留旧缓存）"));
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("缓存目录不可用");
                    for (String kind : new String[]{"video", "subtitle", "poster"}) {
                        if (ticket != generation) return;
                        String extension = kind.equals("video") ? ".mp4" : kind.equals("poster") ? ".jpg" : ".srt";
                        download(base, files.getJSONObject(kind), new File(directory, kind + extension), ticket);
                    }
                }
                long synced = cached && old != null ? old.synced : System.currentTimeMillis();
                context.getSharedPreferences("rehab", 0).edit().putString(manifestKey(base, key), RehabApiClient.object("item", item, "synced", synced).toString()).apply();
                Cached result = new Cached(directory, item, synced);
                publish(ticket, () -> { progress.show("已缓存 · 版本 " + version); ready.done(result, null); });
            } catch (Exception e) {
                publish(ticket, () -> {
                    String text = old == null ? "下载失败 · 未缓存，请联网重试" : "下载失败 · 正在使用旧版缓存 " + old.item.optString("content_version");
                    progress.show(text); ready.done(old, text);
                });
            }
        });
    }
    private void publish(int ticket, Runnable work) { main.post(() -> { if (ticket == generation) work.run(); }); }
    private static boolean valid(File file, JSONObject meta) throws Exception {
        if (!file.isFile() || file.length() != meta.getLong("bytes")) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) { byte[] buffer = new byte[32768]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder text = new StringBuilder(); for (byte b : digest.digest()) text.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return text.toString().equals(meta.getString("sha256"));
    }
    private void download(String base, JSONObject meta, File file, int ticket) throws Exception {
        if (valid(file, meta)) return;
        String path = meta.getString("url");
        if (!path.startsWith("/media/") || path.contains("..")) throw new IOException("素材地址错误");
        File partial = File.createTempFile("download", ".part", file.getParentFile());
        try {
            try (Response response = client.newCall(new Request.Builder().url(base + path).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("素材不可用");
                try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) { if (ticket != generation) throw new IOException("已离开教学页"); out.write(buffer, 0, n); }
                }
            }
            if (!valid(partial, meta) || !partial.renameTo(file)) throw new IOException("素材校验失败");
        } finally { if (partial.exists()) partial.delete(); }
    }
    public void poster(String base, JSONObject item, android.widget.ImageView view) {
        int ticket = generation;
        worker.execute(() -> {
            if (ticket != generation) return;
            try {
                JSONObject meta = item.getJSONObject("files").getJSONObject("poster");
                File directory = new File(root(base, item.getString("key")), item.getString("content_version"));
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                File image = new File(directory, "poster.jpg"); download(base, meta, image, ticket);
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 2;
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(image.getAbsolutePath(), options);
                publish(ticket, () -> view.setImageBitmap(bitmap));
            } catch (Exception ignored) { }
        });
    }
    public void cancel() { generation++; client.dispatcher().cancelAll(); }
    public void close() { cancel(); worker.shutdownNow(); client.dispatcher().executorService().shutdown(); }
}
