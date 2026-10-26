package com.robint.data.remote;

import com.robint.data.local.SessionStore;
import com.robint.feature.login.LoginActivity;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class RehabApiClient {
    public interface Result { void done(Object data, String error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build();
    public RehabApiClient(Context context) { this.context = context; }
    public static String normalize(String value) {
        String text = value.trim().replaceFirst("^ws://", "http://").replaceFirst("^wss://", "https://");
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        HttpUrl url = HttpUrl.parse(text);
        if (url == null || url.host().isEmpty() || !url.username().isEmpty() || !url.password().isEmpty()) return "";
        String base = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString();
        return base.substring(0, base.length() - 1);
    }
    public String base() { return context.getSharedPreferences("rehab", 0).getString("server_url", ""); }
    public void request(String method, String path, JSONObject body, Result result) {
        request(method, path, body, false, result);
    }
    private void request(String method, String path, JSONObject body, boolean array, Result result) {
        if (base().isEmpty()) { result.done(null, "请先在设置中填写服务端地址"); return; }
        RequestBody requestBody = body == null ? null : RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"), body.toString());
        final String requestBase = base();
        final String token = SessionStore.token(context);
        Request.Builder builder = new Request.Builder().url(requestBase + path).method(method, requestBody);
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        Request request = builder.build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (!call.isCanceled()) main.post(() -> result.done(null, "连接失败，请检查服务地址和局域网"));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Object value = null;
                String error = null;
                try (Response incoming = response) {
                    value = new JSONTokener(incoming.body() == null ? "{}" : incoming.body().string()).nextValue();
                    if (!incoming.isSuccessful()) {
                        error = value instanceof JSONObject ? ((JSONObject) value).optString("detail", "请求失败") : "服务响应异常";
                        if (incoming.code() == 401 && (!path.startsWith("/api/v1/auth/") || path.equals("/api/v1/auth/me"))) main.post(() -> {
                            String current = SessionStore.token(context);
                            if (requestBase.equals(base()) && (current.isEmpty() || token.equals(current))) {
                                if (context instanceof LoginActivity) SessionStore.clear(context); else SessionStore.login(context);
                            }
                        });
                    } else if (!(array ? value instanceof JSONArray : value instanceof JSONObject)) {
                        error = "服务端响应类型错误，请确认连接的是康复服务";
                    }
                } catch (Exception e) { error = "服务端响应格式错误"; }
                Object finalValue = value;
                String finalError = error;
                if (!call.isCanceled()) main.post(() -> {
                    if (!requestBase.equals(base()) || !token.equals(SessionStore.token(context))) {
                        result.done(null, finalError == null ? "服务或账号已切换，请重新操作" : finalError);
                    } else result.done(finalValue, finalError);
                });
            }
        });
    }
    public void get(String path, Result callback) { request("GET", path, null, callback); }
    public void getArray(String path, Result callback) { request("GET", path, null, true, callback); }
    public void post(String path, JSONObject body, Result callback) { request("POST", path, body, callback); }
    public static void cleanup(Context context, String path) {
        // Cleanup must survive the Activity being destroyed immediately after leaving training.
        RehabApiClient api = new RehabApiClient(context.getApplicationContext());
        api.post(path, object(), (data, error) -> api.close());
    }
    public void close() { client.dispatcher().cancelAll(); client.dispatcher().executorService().shutdown(); }
    public static JSONObject object(Object... pairs) {
        JSONObject json = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) json.put((String) pairs[i], pairs[i + 1]); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
        return json;
    }
    public static String side(JSONObject patient) { return "left".equals(patient.optString("affected_side")) ? "左侧" : "右侧"; }
}
