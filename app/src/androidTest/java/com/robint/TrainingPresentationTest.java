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
            JSONArray catalog = new JSONArray().put(RehabApiClient.object("key", "rub", "name", "摩擦手背", "cooperation", "双手配合"))
                .put(RehabApiClient.object("key", "fist", "name", "握拳伸展", "cooperation", "双手配合"));
            Intent intent = new Intent(context, TrainingActivity.class).putExtra("run_id", "ui-fixture").putExtra("catalog", catalog.toString());
            try (ActivityScenario<TrainingActivity> scenario = ActivityScenario.launch(intent)) {
                waitFor(scenario, a -> a.findViewById(R.id.training_camera_status) != null
                    && ((TextView) a.findViewById(R.id.training_camera_status)).getText().toString().contains("后置摄像头已就绪"));
                scenario.onActivity(a -> {
                    assertEquals(View.GONE, a.findViewById(R.id.training_camera_status).getVisibility());
                    assertNull(text(a.findViewById(android.R.id.content), "切换摄像头"));
                    assertEquals(2, ((ViewGroup) a.findViewById(R.id.training_controls)).getChildCount());
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
                scenario.onActivity(a -> { set(a, "requestGeneration", 100); set(a, "itemIndex", 1); invoke(a, "showRest"); });
                scenario.onActivity(a -> {
                    TextView timer = text(a.findViewById(android.R.id.content), "30 秒"); assertNotNull(timer);
                    assertTrue(timer.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity >= 100);
                });
                screenshot(context, "training_rest");
                scenario.onActivity(a -> text(a.findViewById(android.R.id.content), "跳过休息，进入准备").performClick());
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
}
