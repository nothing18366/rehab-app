package com.robint;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.training.TrainingActivity;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Opt-in UI check against a disposable fixture, never the patient database: -e uiFixture URL. */
@RunWith(AndroidJUnit4.class)
public class TrainingPresentationTest {
    private void waitFor(ActivityScenario<TrainingActivity> scenario, Predicate<TrainingActivity> condition) {
        AtomicBoolean matched = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            scenario.onActivity(a -> matched.set(condition.test(a)));
            if (matched.get()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Training UI did not reach the expected state");
    }

    private void set(TrainingActivity activity, String name, Object value) {
        try { Field field = TrainingActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private Object get(TrainingActivity activity, String name) {
        try { Field field = TrainingActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private void markSpeechPlaying(TrainingActivity activity, boolean announcement) {
        markSpeechPlaying(activity, announcement, "wrist_move", "右侧手腕稳住，手指慢慢动。");
    }

    private void markSpeechPlaying(TrainingActivity activity, boolean announcement, String code, String text) {
        try {
            Object policy = get(activity, "speechPolicy");
            Method spoken = policy.getClass().getDeclaredMethod("spoken", String.class, String.class, long.class, boolean.class);
            spoken.setAccessible(true);
            spoken.invoke(policy, announcement ? "notice:start" : code, text, SystemClock.elapsedRealtime(), announcement);
            set(activity, "speechInFlight", true); set(activity, "utteranceId", "simulated-speech");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private void invoke(TrainingActivity activity, String name) {
        try { Method method = TrainingActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = text(((ViewGroup) view).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }

    private void screenshot(Context context, String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File directory = new File(context.getFilesDir(), "qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally { bitmap.recycle(); }
    }

    @Test public void forwardRaiseSetupCountsAndTeachingCache() throws Exception {
        String fixture = InstrumentationRegistry.getArguments().getString("uiFixture");
        assumeNotNull(fixture);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("rehab", 0);
        Map<String, ?> saved = preferences.getAll();
        com.robint.data.local.TeachingMediaCache cache = new com.robint.data.local.TeachingMediaCache(context);
        try {
            preferences.edit().putString("server_url", fixture).putBoolean("voice", false).commit();
            SessionStore.save(context, RehabApiClient.object("token", "ui-fixture", "role", "staff", "expires", System.currentTimeMillis() / 1000 + 3600));
            JSONArray catalog;
            okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
            try (okhttp3.Response response = http.newCall(new okhttp3.Request.Builder().url(fixture + "/api/v1/actions").build()).execute()) {
                assertTrue(response.isSuccessful()); catalog = new JSONArray(response.body().string());
            } finally { http.dispatcher().executorService().shutdown(); }
            assertEquals(7, catalog.length());
            org.json.JSONObject teaching = catalog.getJSONObject(6);
            assertEquals("forward_raise", teaching.getString("key"));
            java.util.concurrent.CountDownLatch downloaded = new java.util.concurrent.CountDownLatch(1);
            AtomicBoolean cached = new AtomicBoolean();
            cache.video(fixture, teaching, (result, error) -> { cached.set(result != null && error == null && result.video.isFile()); downloaded.countDown(); }, message -> {});
            assertTrue(downloaded.await(20, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(cached.get());
            Intent intent = new Intent(context, TrainingActivity.class).putExtra("run_id", "ui-fixture").putExtra("catalog", catalog.toString());
            try (ActivityScenario<TrainingActivity> scenario = ActivityScenario.launch(intent)) {
                waitFor(scenario, a -> a.findViewById(R.id.training_count) != null && text(a.findViewById(android.R.id.content), "开始训练") != null);
                scenario.onActivity(a -> {
                    assertEquals("患侧有效", ((TextView)a.findViewById(R.id.training_passed_label)).getText().toString());
                    assertEquals("患侧未达标", ((TextView)a.findViewById(R.id.training_failed_label)).getText().toString());
                    set(a, "state", "connecting"); a.onMessage(RehabApiClient.object("type", "started"));
                    assertNull(text(a.findViewById(android.R.id.content), "确认患侧"));
                    a.onMessage(RehabApiClient.object("type", "result", "count", 0, "passed", 0, "progress_count", 0,
                        "setup_ready", false, "setup_confirmed", false, "phase", "准备对齐", "tracking", "正常",
                        "feedback", RehabApiClient.object("text", "保持坐稳，双臂放低", "voice_ready", false)));
                    assertNull(text(a.findViewById(android.R.id.content), "确认患侧"));
                });
                screenshot(context, "forward_raise_setup");
                scenario.onActivity(a -> {
                    a.onMessage(RehabApiClient.object("type", "result", "count", 4, "passed", 2, "progress_count", 2,
                        "setup_ready", true, "setup_confirmed", true, "phase", "顶部到位", "tracking", "正常",
                        "feedback", RehabApiClient.object("text", "双臂自然放回低位", "voice_ready", false)));
                    assertNull(text(a.findViewById(android.R.id.content), "确认患侧"));
                    assertEquals("4", ((TextView)a.findViewById(R.id.training_count)).getText().toString());
                    assertEquals("患侧有效次数 2", a.findViewById(R.id.training_passed).getContentDescription());
                    assertEquals("患侧未达标次数 2", a.findViewById(R.id.training_failed).getContentDescription());
                    assertEquals("患侧有效目标", ((TextView)a.findViewById(R.id.training_target_label)).getText().toString());
                    assertEquals(40, ((android.widget.ProgressBar)a.findViewById(R.id.training_progress)).getProgress());
                    assertFalse((Boolean)get(a, "speechInFlight"));
                });
                screenshot(context, "forward_raise_training");
                scenario.onActivity(a -> {
                    assertEquals(1, ((TextView)a.findViewById(R.id.training_failed_label)).getLineCount());
                    android.graphics.Rect passed = new android.graphics.Rect(), failed = new android.graphics.Rect();
                    a.findViewById(R.id.training_passed).getGlobalVisibleRect(passed);
                    a.findViewById(R.id.training_failed).getGlobalVisibleRect(failed);
                    assertEquals("Affected counts must align", passed.top, failed.top);
                });
                scenario.onActivity(a -> {
                    a.onMessage(RehabApiClient.object("type", "result", "count", 4, "passed", 2, "progress_count", 2,
                        "setup_ready", false, "setup_confirmed", false, "phase", "准备对齐", "tracking", "患侧姿态不清晰"));
                    assertNull(text(a.findViewById(android.R.id.content), "确认患侧"));
                    set(a, "state", "preparing");
                });
            }
        } finally {
            cache.close();
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof String) restore.putString(key, (String)value);
                else if (value instanceof Long) restore.putLong(key, (Long)value);
                else if (value instanceof Integer) restore.putInt(key, (Integer)value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean)value);
                else if (value instanceof Float) restore.putFloat(key, (Float)value);
            }
            restore.commit();
        }
    }

    @Test public void fixedCameraAndLargeCountdownRestoreThePanel() throws Exception {
        String fixture = InstrumentationRegistry.getArguments().getString("uiFixture");
        assumeNotNull(fixture);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("rehab", 0);
        Map<String, ?> saved = preferences.getAll();
        String[] keys = {"server_url", "auth_token", "auth_base", "auth_role", "auth_patient", "auth_expires", "patient_id", "voice", "tts_zh_available", "last_diagnostics"};
        try {
            preferences.edit().putString("server_url", fixture).putBoolean("voice", false).commit();
            SessionStore.save(context, RehabApiClient.object("token", "ui-fixture", "role", "staff", "expires", System.currentTimeMillis() / 1000 + 3600));
            JSONArray catalog = new JSONArray().put(RehabApiClient.object("key", "rub", "name", "摩擦手背", "cooperation", "双手配合", "framing", "双手手掌和手指完整入镜"))
                .put(RehabApiClient.object("key", "fist", "name", "握拳伸展", "cooperation", "双手配合", "framing", "双手手掌和手指完整入镜"));
            boolean restVideo = "true".equals(InstrumentationRegistry.getArguments().getString("restVideo"));
            if (restVideo) {
                okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
                try (okhttp3.Response response = http.newCall(new okhttp3.Request.Builder().url(fixture + "/api/v1/actions").build()).execute()) {
                    assertTrue(response.isSuccessful()); catalog = new JSONArray(response.body().string());
                } finally { http.dispatcher().executorService().shutdown(); }
            }
            Intent intent = new Intent(context, TrainingActivity.class).putExtra("run_id", "ui-fixture").putExtra("catalog", catalog.toString());
            try (ActivityScenario<TrainingActivity> scenario = ActivityScenario.launch(intent)) {
                waitFor(scenario, a -> a.findViewById(R.id.training_camera_status) != null
                    && ((TextView) a.findViewById(R.id.training_camera_status)).getText().toString().contains("后置摄像头已就绪"));
                screenshot(context, "training_ready");
                scenario.onActivity(a -> {
                    assertEquals(View.GONE, a.findViewById(R.id.training_camera_status).getVisibility());
                    assertNull(text(a.findViewById(android.R.id.content), "切换摄像头"));
                    assertEquals(2, ((ViewGroup) a.findViewById(R.id.training_controls)).getChildCount());
                    assertEquals(a.findViewById(R.id.training_root).getWidth(), a.findViewById(R.id.training_local).getWidth());
                    assertEquals(a.findViewById(R.id.training_root).getHeight(), a.findViewById(R.id.training_local).getHeight());
                    TextView passed = a.findViewById(R.id.training_passed), failed = a.findViewById(R.id.training_failed);
                    assertEquals(android.view.Gravity.CENTER, passed.getGravity());
                    assertEquals(android.view.Gravity.CENTER, failed.getGravity());
                    assertTrue(passed.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity >= 36);
                    assertTrue(failed.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity >= 36);
                    set(a, "state", "training");
                    set(a, "item", RehabApiClient.object("action", "fist", "target_reps", 5));
                    a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
                        "attempts", 12, "unassessable", 2, "tracking", "正常", "phase", "缓慢握拳", "processed_fps", 8.0,
                        "feedback", RehabApiClient.object("code", "wrist_drift_early", "level", "mild", "text", "右手手腕正在偏离，请先稳住前臂")));
                    assertEquals("10", ((TextView) a.findViewById(R.id.training_count)).getText().toString());
                    assertEquals("3", ((TextView) a.findViewById(R.id.training_passed)).getText().toString());
                    assertEquals("7", ((TextView) a.findViewById(R.id.training_failed)).getText().toString());
                    assertEquals("合格次数 3", a.findViewById(R.id.training_passed).getContentDescription());
                    assertEquals("不合格次数 7", a.findViewById(R.id.training_failed).getContentDescription());
                    assertEquals("合格目标", ((TextView) a.findViewById(R.id.training_target_label)).getText().toString());
                    assertEquals("3 / 5 次", ((TextView) a.findViewById(R.id.training_detail)).getText().toString());
                    assertEquals(60, ((android.widget.ProgressBar) a.findViewById(R.id.training_progress)).getProgress());
                    assertEquals(a.getResources().getColor(R.color.training_warning), ((TextView) a.findViewById(R.id.training_hint)).getCurrentTextColor());
                    assertFalse(((TextView) a.findViewById(R.id.training_framing)).getText().toString().contains("已达到"));
                });
                screenshot(context, "training_stats_feedback");
                scenario.onActivity(this::assertSpeechLifecycle);
                scenario.onActivity(a -> {
                    a.onMessage(RehabApiClient.object("type", "result", "count", 12, "passed", 5, "progress_count", 5,
                        "tracking", "正常", "feedback", RehabApiClient.object("code", "wrist_move", "level", "severe", "text", "右侧手腕稳住，手指慢慢动。", "voice_ready", true)));
                    assertTrue(((TextView) a.findViewById(R.id.training_framing)).getText().toString().contains("已达到"));
                    assertEquals(a.getResources().getColor(R.color.training_danger), ((TextView) a.findViewById(R.id.training_hint)).getCurrentTextColor());
                    a.onMessage(RehabApiClient.object("type", "result", "count", 12, "passed", 5, "progress_count", 5,
                        "tracking", "正常", "feedback", RehabApiClient.object("code", "phase", "level", "normal", "text", "准备完成，请慢慢握拳")));
                    assertEquals(a.getResources().getColor(R.color.training_text), ((TextView) a.findViewById(R.id.training_hint)).getCurrentTextColor());
                    set(a, "state", "preparing"); set(a, "targetAnnounced", false); set(a, "lastResult", null);
                    ((TextView) a.findViewById(R.id.training_framing)).setText("请让患侧肘、腕和完整手指清晰入镜");
                    text(a.findViewById(android.R.id.content), "开始训练").performClick();
                });
                for (String number : new String[]{"3", "2", "1"}) {
                    waitFor(scenario, a -> a.findViewById(R.id.training_countdown).getVisibility() == View.VISIBLE
                        && number.contentEquals(((TextView) a.findViewById(R.id.training_countdown_number)).getText()));
                    scenario.onActivity(a -> {
                        assertEquals(View.GONE, a.findViewById(R.id.training_info).getVisibility());
                        assertEquals(a.findViewById(R.id.training_panel).getHeight(), a.findViewById(R.id.training_countdown).getHeight());
                    });
                    if ("3".equals(number)) screenshot(context, "training_countdown");
                }
                waitFor(scenario, a -> a.findViewById(R.id.training_countdown).getVisibility() == View.GONE);
                scenario.onActivity(a -> assertEquals(View.VISIBLE, a.findViewById(R.id.training_info).getVisibility()));
                screenshot(context, "training_panel_restored");
                scenario.onActivity(a -> {
                    set(a, "state", "connecting");
                    a.onMessage(RehabApiClient.object("type", "started"));
                    a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
                        "processed_fps", 8.0, "phase", "缓慢握拳", "tracking", "正常",
                        "feedback", RehabApiClient.object("code", "wrist_drift_early", "level", "mild", "text", "右手手腕正在偏离，请先稳住前臂")));
                });
                screenshot(context, "training_stats_live");
                scenario.onActivity(a -> {
                    for (int id : new int[]{R.id.training_count_label, R.id.training_count, R.id.training_passed, R.id.training_failed, R.id.training_detail, R.id.training_progress}) {
                        View view = a.findViewById(id);
                        android.graphics.Rect visible = new android.graphics.Rect();
                        assertTrue("Statistic must be visible: " + id, view.getGlobalVisibleRect(visible));
                        assertEquals("Statistic must fit without scrolling: " + id, view.getHeight(), visible.height());
                    }
                    TextView discomfort = text(a.findViewById(android.R.id.content), "因不适结束\n保存部分结果，结束整次训练");
                    assertNotNull(discomfort);
                    assertTrue("The exit consequence must not be clipped", discomfort.getLayout().getHeight()
                        <= discomfort.getHeight() - discomfort.getCompoundPaddingTop() - discomfort.getCompoundPaddingBottom());
                    android.graphics.Rect stats = new android.graphics.Rect(), footer = new android.graphics.Rect();
                    a.findViewById(R.id.training_stats_group).getGlobalVisibleRect(stats);
                    a.findViewById(R.id.training_controls).getGlobalVisibleRect(footer);
                    assertFalse("Statistics must not overlap the footer", android.graphics.Rect.intersects(stats, footer));
                    a.onMessage(RehabApiClient.object("type", "result", "count", 123, "passed", 63, "progress_count", 63, "tracking", "正常"));
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a -> {
                    TextView count = a.findViewById(R.id.training_count);
                    assertEquals(1, count.getLineCount());
                    assertTrue("Three digit totals must fit", count.getLayout().getLineWidth(0) <= count.getWidth());
                });
                scenario.onActivity(a -> { set(a, "requestGeneration", 100); set(a, "itemIndex", 1); invoke(a, "showRest"); });
                scenario.onActivity(a -> {
                    TextView timer = a.findViewById(R.id.training_rest_timer); assertEquals("00:30", timer.getText().toString());
                    assertTrue(timer.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity >= 100);
                    assertEquals("下一动作：摩擦手背  ›", ((TextView) a.findViewById(R.id.training_rest_teaching)).getText().toString());
                    a.findViewById(R.id.training_rest_add).performClick();
                    assertEquals("00:50", timer.getText().toString());
                    // Expiry must wait for confirmation; adding time must restart the stopped timer.
                    set(a, "restDeadline", SystemClock.elapsedRealtime() - 1000);
                });
                waitFor(scenario, a -> "00:00".contentEquals(((TextView) a.findViewById(R.id.training_rest_timer)).getText()));
                scenario.onActivity(a -> {
                    assertEquals("进入下一项准备", ((TextView) a.findViewById(R.id.training_rest_skip)).getText().toString());
                    assertNull(a.findViewById(R.id.training_camera_status));
                    a.findViewById(R.id.training_rest_add).performClick();
                    assertEquals("00:20", ((TextView) a.findViewById(R.id.training_rest_timer)).getText().toString());
                    assertEquals("跳过休息", ((TextView) a.findViewById(R.id.training_rest_skip)).getText().toString());
                });
                screenshot(context, "training_rest");
                scenario.onActivity(a -> text(a.findViewById(android.R.id.content), "结束本次计划").performClick());
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("继续休息"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
                scenario.onActivity(a -> a.findViewById(R.id.training_rest_teaching).performClick());
                if (restVideo) {
                    waitFor(scenario, a -> {
                        try {
                            Field f = TrainingActivity.class.getDeclaredField("restTeachingDialog"); f.setAccessible(true);
                            android.app.AlertDialog dialog = (android.app.AlertDialog) f.get(a);
                            return dialog != null && ((android.widget.VideoView) dialog.findViewById(R.id.training_rest_video)).isPlaying();
                        } catch (Exception e) { throw new AssertionError(e); }
                    });
                    screenshot(context, "training_rest_teaching");
                }
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("返回休息"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
                scenario.onActivity(a -> {
                    assertNotNull(a.findViewById(R.id.training_rest_timer));
                    a.findViewById(R.id.training_rest_skip).performClick();
                });
                waitFor(scenario, a -> a.findViewById(R.id.training_camera_status) != null
                    && ((TextView) a.findViewById(R.id.training_camera_status)).getText().toString().contains("已就绪"));
                scenario.onActivity(a -> text(a.findViewById(android.R.id.content), "开始训练").performClick());
                waitFor(scenario, a -> a.findViewById(R.id.training_countdown).getVisibility() == View.VISIBLE);
                scenario.moveToState(Lifecycle.State.CREATED);
                SystemClock.sleep(3200);
                scenario.moveToState(Lifecycle.State.RESUMED);
                scenario.onActivity(a -> assertNull(a.findViewById(R.id.training_countdown)));
            }
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : keys) {
                Object value = saved.get(key);
                if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else restore.remove(key);
            }
            restore.commit();
        }
    }

    // Simulated playback metadata exercises result/watchdog wiring without making sound.
    private void assertSpeechLifecycle(TrainingActivity a) {
        markSpeechPlaying(a, false);
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "wrist_move", "level", "severe", "voice_ready", true,
                "text", "右侧手腕稳住，手指慢慢动。")));
        assertEquals(true, get(a, "speechInFlight"));
        set(a, "pendingAnnouncement", "保留待播通知");
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "phase", "level", "normal", "text", "准备完成，请慢慢握拳")));
        assertEquals(false, get(a, "speechInFlight"));
        assertEquals("", get(a, "utteranceId"));
        assertEquals("保留待播通知", get(a, "pendingAnnouncement"));
        set(a, "pendingAnnouncement", "");
        markSpeechPlaying(a, false);
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "wrist_move", "level", "severe", "voice_ready", true,
                "text", "左侧手腕稳住，手指慢慢动。")));
        assertEquals(false, get(a, "speechInFlight"));
        markSpeechPlaying(a, false, "close_range", "双手继续握拳，有不适就暂停。");
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "close_range", "level", "mild", "voice_ready", true,
                "text", "双手继续握拳，有不适就暂停。")));
        assertEquals(true, get(a, "speechInFlight"));
        assertEquals(a.getResources().getColor(R.color.training_warning),
            ((TextView) a.findViewById(R.id.training_hint)).getCurrentTextColor());
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "close_range", "level", "mild", "voice_ready", false,
                "text", "双手继续握拳，有不适就暂停。")));
        assertEquals(false, get(a, "speechInFlight"));
        markSpeechPlaying(a, false, "close_range", "双手继续握拳，有不适就暂停。");
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "open_range", "level", "mild", "voice_ready", true,
                "text", "双手继续张开，有不适就暂停。")));
        assertEquals(false, get(a, "speechInFlight"));
        markSpeechPlaying(a, true);
        a.onMessage(RehabApiClient.object("type", "result", "count", 10, "passed", 3, "progress_count", 3,
            "feedback", RehabApiClient.object("code", "phase", "level", "normal", "text", "准备完成，请慢慢握拳")));
        assertEquals(true, get(a, "speechInFlight"));
        invoke(a, "stopSpeech");
        markSpeechPlaying(a, false);
        set(a, "lastResultAt", SystemClock.elapsedRealtime() - 4000);
        ((Runnable) get(a, "watchResults")).run();
        assertEquals(false, get(a, "speechInFlight"));
        assertTrue(((TextView) a.findViewById(R.id.training_hint)).getText().toString().contains("暂时中断"));
    }

    @Test public void correctedFeedbackCancelsPlaybackWithoutCameraOrAudio() throws Exception {
        String fixture = InstrumentationRegistry.getArguments().getString("uiFixture");
        assumeNotNull(fixture);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("rehab", 0);
        Map<String, ?> saved = preferences.getAll();
        String[] keys = {"server_url", "auth_token", "auth_base", "auth_role", "auth_patient", "auth_expires", "patient_id", "voice", "tts_zh_available", "last_diagnostics"};
        try {
            preferences.edit().putString("server_url", fixture).putBoolean("voice", false).commit();
            SessionStore.save(context, RehabApiClient.object("token", "ui-fixture", "role", "staff", "expires", System.currentTimeMillis() / 1000 + 3600));
            Intent intent = new Intent(context, TrainingActivity.class).putExtra("run_id", "ui-fixture").putExtra("catalog", "[]");
            try (ActivityScenario<TrainingActivity> scenario = ActivityScenario.launch(intent)) {
                waitFor(scenario, a -> a.findViewById(R.id.training_hint) != null && "preparing".equals(get(a, "state")));
                scenario.onActivity(a -> {
                    set(a, "state", "training");
                    set(a, "item", RehabApiClient.object("action", "fist", "target_reps", 5));
                    assertSpeechLifecycle(a);
                    set(a, "state", "preparing");
                });
            }
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : keys) {
                Object value = saved.get(key);
                if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else restore.remove(key);
            }
            restore.commit();
        }
    }
}
