package com.robint;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.training.TrainingActivity;
import com.robint.rtc.WebRtcClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import okhttp3.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Opt-in only: a dedicated test server on port 18121 and a consenting demonstrator. */
@RunWith(AndroidJUnit4.class)
public class TrainingPerformanceTest {
    private final OkHttpClient http = new OkHttpClient();
    private String base, token = "";

    private Object request(String method, String path, JSONObject body) throws Exception {
        RequestBody data = body == null ? null : RequestBody.create(MediaType.parse("application/json"), body.toString());
        Request.Builder request = new Request.Builder().url(base + path).method(method, data);
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        try (Response response = http.newCall(request.build()).execute()) {
            String text = response.body().string();
            assertTrue("Test server HTTP " + response.code() + ": " + text, response.isSuccessful());
            return new org.json.JSONTokener(text).nextValue();
        }
    }

    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private void invoke(Object target, String name) {
        try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private void waitFor(ActivityScenario<TrainingActivity> scenario, Predicate<TrainingActivity> condition) {
        AtomicBoolean matched = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 30000;
        do {
            scenario.onActivity(a -> matched.set(condition.test(a)));
            if (matched.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Training did not become ready");
    }

    @Test public void sampleTraining() throws Exception {
        base = InstrumentationRegistry.getArguments().getString("benchmarkUrl");
        assumeNotNull(base);
        assertTrue("Use the disposable benchmark service", base.matches("http://[a-zA-Z0-9.:-]+:18121"));
        String action = InstrumentationRegistry.getArguments().getString("action", "rub");
        String mode = InstrumentationRegistry.getArguments().getString("mode", "local");
        String label = InstrumentationRegistry.getArguments().getString("label", "baseline");
        int sampleSeconds = Integer.parseInt(InstrumentationRegistry.getArguments().getString("sampleSeconds", "30"));
        assertTrue(sampleSeconds >= 15 && sampleSeconds <= 60);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("rehab", 0);
        Map<String, ?> saved = preferences.getAll();
        try {
            preferences.edit().putString("server_url", base).putBoolean("voice", false).commit();
            JSONObject login = (JSONObject) request("POST", "/api/v1/auth/staff-login", RehabApiClient.object("username", "admin", "password", "123"));
            token = login.getString("token");
            SessionStore.save(context, login);
            JSONObject patient = (JSONObject) request("POST", "/api/v1/patients", RehabApiClient.object("name", "性能测试志愿者", "affected_side", "right", "forearm_cm", 25));
            JSONObject run = (JSONObject) request("POST", "/api/v1/training-runs", RehabApiClient.object("patient_id", patient.getString("id"), "mode", "single", "action", action, "target_reps", 99));
            String runId = run.getString("id");
            request("PUT", "/api/v1/training-runs/" + runId + "/feedback/pre", RehabApiClient.object("state", "skipped"));
            JSONArray catalog = (JSONArray) request("GET", "/api/v1/actions", null);
            Intent intent = new Intent(context, TrainingActivity.class).putExtra("run_id", runId).putExtra("catalog", catalog.toString());
            try (ActivityScenario<TrainingActivity> scenario = ActivityScenario.launch(intent)) {
                waitFor(scenario, a -> field(a, "rtc") != null && ((WebRtcClient) field(a, "rtc")).isPreviewReady());
                scenario.onActivity(a -> invoke(a, "checkAndCountdown"));
                waitFor(scenario, a -> "training".equals(field(a, "state")));
                if ("remote".equals(mode)) {
                    waitFor(scenario, a -> field(field(a, "rtc"), "remoteTrack") != null);
                    scenario.onActivity(a -> ((WebRtcClient) field(a, "rtc")).togglePreview());
                }
                File directory = new File(context.getFilesDir(), "benchmarks");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                long started = SystemClock.elapsedRealtime(), lastAt = started, lastCpu = Process.getElapsedCpuTime();
                try (FileOutputStream output = new FileOutputStream(new File(directory, label + ".jsonl"), true)) {
                    while (SystemClock.elapsedRealtime() - started < 15000 + sampleSeconds * 1000L) {
                        SystemClock.sleep(1000);
                        JSONObject[] sample = new JSONObject[1];
                        scenario.onActivity(a -> {
                            assertEquals("training", field(a, "state"));
                            try { sample[0] = new JSONObject(((WebRtcClient) field(a, "rtc")).performanceSample().toString()); }
                            catch (Exception e) { throw new AssertionError(e); }
                        });
                        long at = SystemClock.elapsedRealtime(), cpu = Process.getElapsedCpuTime();
                        sample[0].put("action", action).put("mode", mode).put("label", label)
                            .put("warmup", at - started < 15000).put("training_elapsed_ms", at - started)
                            .put("wall_time_ms", System.currentTimeMillis())
                            .put("app_cpu_one_core_percent", 100.0 * (cpu - lastCpu) / (at - lastAt))
                            .put("app_pss_kb", Debug.getPss());
                        lastAt = at; lastCpu = cpu;
                        output.write((sample[0].toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                    scenario.onActivity(a -> ((WebRtcClient) field(a, "rtc")).stopTraining());
                    waitFor(scenario, a -> !"training".equals(field(a, "state")));
                    output.write((RehabApiClient.object("type", "saved_run", "run", request("GET", "/api/v1/training-runs/" + runId, null)) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally {
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else if (value instanceof Float) restore.putFloat(key, (Float) value);
            }
            restore.commit();
            http.dispatcher().executorService().shutdown();
        }
    }
}
