package com.robint;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.training.TrainingActivity;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DailyModesTest {
    @Test public void deviceModeSurvivesLogoutAndPatientLogin() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("rehab", 0);
        Map<String, ?> saved = preferences.getAll();
        try {
            preferences.edit().remove("daily_preview_mode").commit();
            assertEquals("keypoints", SessionStore.dailyPreviewMode(context));
            preferences.edit().putString("daily_preview_mode", "annotated").putBoolean("next_remote_diagnostic", true).commit();
            SessionStore.clear(context);
            assertEquals("annotated", SessionStore.dailyPreviewMode(context));
            assertFalse(preferences.getBoolean("next_remote_diagnostic", false));
            SessionStore.save(context, RehabApiClient.object("token", "synthetic-test", "role", "patient", "patient_id", "synthetic", "expires", System.currentTimeMillis() / 1000 + 60));
            assertFalse(SessionStore.staff(context));
            assertEquals("annotated", SessionStore.dailyPreviewMode(context));
            preferences.edit().putString("daily_preview_mode", "invalid").commit();
            assertEquals("keypoints", SessionStore.dailyPreviewMode(context));
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
        }
    }

    @Test public void capabilitiesMatchSelectedModeAndDiagnosticOverride() throws Exception {
        Method check = TrainingActivity.class.getDeclaredMethod("previewCapabilityError", JSONObject.class, String.class);
        check.setAccessible(true);
        JSONObject old = RehabApiClient.object("local_skeleton_v1", true, "clock_sync_v1", true);
        assertNull(check.invoke(null, old, "keypoints"));
        assertNotNull(check.invoke(null, old, "annotated"));
        assertNotNull(check.invoke(null, old, "diagnostic"));
        JSONObject annotated = RehabApiClient.object("annotated_video_v1", true);
        assertNull(check.invoke(null, annotated, "annotated"));
        assertNotNull(check.invoke(null, annotated, "keypoints"));
        old.put("diagnostic_video", true).put("diagnostic_png", true);
        assertNull(check.invoke(null, old, "diagnostic"));
        assertNotNull(check.invoke(null, new Object[]{null, "annotated"}));
    }
}
