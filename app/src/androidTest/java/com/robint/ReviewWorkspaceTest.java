package com.robint;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.data.local.SessionStore;
import com.robint.feature.home.HomeActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Only the isolated loopback fixture is permitted; restores the full encrypted login preferences. */
@RunWith(AndroidJUnit4.class)
public class ReviewWorkspaceTest {
    private Object review(HomeActivity activity) {
        try { Field f = HomeActivity.class.getDeclaredField("reviewWorkspace"); f.setAccessible(true); return f.get(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private WebView web(HomeActivity activity) {
        try { Object review = review(activity); Field f = review.getClass().getDeclaredField("web"); f.setAccessible(true); return (WebView) f.get(review); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private String js(ActivityScenario<HomeActivity> scenario, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        scenario.onActivity(a -> web(a).evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue("WebView did not respond", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private void waitFor(ActivityScenario<HomeActivity> scenario, String condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        do { if ("true".equals(js(scenario, "Boolean(" + condition + ")"))) return; SystemClock.sleep(100); }
        while (SystemClock.elapsedRealtime() < deadline);
        fail("Review condition timed out: " + condition);
    }

    private void openReview(ActivityScenario<HomeActivity> scenario) throws Exception {
        scenario.onActivity(a -> {
            View entry = a.findViewById(R.id.rehab_navigation).findViewWithTag("人工复核");
            assertNotNull(entry); entry.performClick();
        });
        waitFor(scenario, "document.getElementById('reviewPanels') && !document.getElementById('reviewPanels').hidden && document.querySelectorAll('.event-card').length===3");
        waitFor(scenario, "document.getElementById('clipPlayer').readyState>=2 && document.getElementById('clipPlayer').videoWidth>0");
    }

    private void assertExited(ActivityScenario<HomeActivity> scenario) {
        SystemClock.sleep(500);
        scenario.onActivity(a -> {
            assertNull("Review must stay closed after returning", review(a));
            assertTrue(a.findViewById(R.id.rehab_navigation).isShown());
            assertFalse(a.isFinishing());
        });
    }

    private void clickDialog(String label) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (true) {
            try {
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(label))
                    .perform(androidx.test.espresso.action.ViewActions.click());
                return;
            } catch (androidx.test.espresso.NoMatchingViewException missing) {
                if (SystemClock.elapsedRealtime() >= deadline) throw missing;
                SystemClock.sleep(100);
            }
        }
    }

    private void screenshot(Context context, String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File folder = new File(context.getFilesDir(), "benchmarks");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }

    @Test public void embeddedReviewVideoManualSheetAndUnsavedBack() throws Exception {
        String base = InstrumentationRegistry.getArguments().getString("reviewFixture");
        assumeTrue("http://127.0.0.1:18123".equals(base));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("rehab", 0);
        Map<String, ?> saved = preferences.getAll();
        OkHttpClient http = new OkHttpClient();
        try {
            Request login = new Request.Builder().url(base + "/api/v1/auth/staff-login").post(
                RequestBody.create(MediaType.parse("application/json"), "{\"username\":\"admin\",\"password\":\"123\"}")).build();
            try (Response response = http.newCall(login).execute()) {
                assertTrue(response.isSuccessful());
                preferences.edit().putString("server_url", base).commit();
                SessionStore.save(context, new JSONObject(response.body().string()));
            }
            try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(new Intent(context, HomeActivity.class))) {
                openReview(scenario);
                scenario.onActivity(a -> {
                    View menu = a.findViewById(R.id.rehab_navigation);
                    assertTrue(menu.isShown()); assertTrue(menu.getWidth() > 0);
                    int[] menuPosition = new int[2], webPosition = new int[2];
                    menu.getLocationOnScreen(menuPosition); web(a).getLocationOnScreen(webPosition);
                    assertTrue("App menu must remain beside the review content", webPosition[0] >= menuPosition[0] + menu.getWidth());
                    assertTrue(web(a).getHeight() > 400); assertTrue(web(a).isShown());
                });
                assertEquals("\"\"", js(scenario, "document.querySelector('[name=patient_name]').value"));
                assertEquals("\"none\"", js(scenario, "getComputedStyle(document.querySelector('.sidebar')).display"));
                assertEquals("\"none\"", js(scenario, "getComputedStyle(document.getElementById('loginScreen')).display"));
                assertEquals("false", js(scenario, "document.documentElement.scrollWidth>innerWidth"));
                waitFor(scenario, "document.getElementById('clipPlayer').readyState>=2 && document.getElementById('clipPlayer').videoWidth>0");
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(300);
                screenshot(context, "review-workspace-normal");
                scenario.onActivity(a -> web(a).getSettings().setTextZoom(150));
                SystemClock.sleep(300);
                assertEquals("false", js(scenario, "document.documentElement.scrollWidth>innerWidth"));
                screenshot(context, "review-workspace-large-text");
                scenario.onActivity(a -> web(a).getSettings().setTextZoom(100));
                js(scenario, "document.querySelector('[name=patient_name]').value='机器人夹具手填';document.querySelector('[name=patient_name]').dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('saveDraft').click()");
                waitFor(scenario, "document.getElementById('saveStatus').textContent.startsWith('已保存草稿')");
                assertEquals("false", js(scenario, "window.rehabHasUnsavedChanges()"));
                js(scenario, "document.getElementById('eventNote').value='尚未保存的复核依据';document.getElementById('eventNote').dispatchEvent(new Event('input',{bubbles:true}))");
                assertEquals("true", js(scenario, "window.rehabHasUnsavedChanges()"));
                scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED);
                assertEquals("\"尚未保存的复核依据\"", js(scenario, "document.getElementById('eventNote').value"));
                scenario.onActivity(HomeActivity::onBackPressed);
                clickDialog("继续填写");
                assertEquals("true", js(scenario, "window.rehabHasUnsavedChanges()"));
                scenario.onActivity(a -> a.findViewById(R.id.rehab_navigation).findViewWithTag("训练记录").performClick());
                clickDialog("放弃并离开");
                assertExited(scenario);
                openReview(scenario);
                assertEquals("\"机器人夹具手填\"", js(scenario, "document.querySelector('[name=patient_name]').value"));
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("返回"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
                assertExited(scenario);
                openReview(scenario);
                scenario.onActivity(HomeActivity::onBackPressed);
                assertExited(scenario);
            }
        } finally {
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                String key = entry.getKey(); Object value = entry.getValue();
                if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else if (value instanceof Float) restore.putFloat(key, (Float) value);
                else if (value instanceof Set) restore.putStringSet(key, new HashSet<>((Set<String>) value));
            }
            assertTrue(restore.commit());
            http.dispatcher().executorService().shutdown();
        }
    }
}
