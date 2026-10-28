package com.robint;

import com.robint.data.local.SessionStore;
import com.robint.data.local.TeachingMediaCache;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.feedback.FeedbackActivity;
import com.robint.feature.home.HomeActivity;
import com.robint.feature.login.LoginActivity;
import com.robint.media.Subtitles;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import android.os.ParcelFileDescriptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** Run only on a test device, against a disposable database at -e server URL. */
@RunWith(AndroidJUnit4.class)
public class RehabFlowTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String server = InstrumentationRegistry.getArguments().getString("server", "http://10.0.2.2:18080");
    private final OkHttpClient http = new OkHttpClient();
    private String token = InstrumentationRegistry.getArguments().getString("staffToken", "");

    private Object api(String method, String path, JSONObject body) throws Exception {
        if (token.isEmpty() && !path.startsWith("/auth/")) {
            JSONObject login = (JSONObject) api("POST", "/auth/staff-login", RehabApiClient.object("username", "admin", "password", "123"));
            token = login.getString("token");
        }
        RequestBody payload = body == null ? null : RequestBody.create(MediaType.parse("application/json"), body.toString());
        Request.Builder request = new Request.Builder().url(server + "/api/v1" + path).method(method, payload);
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        try (Response response = http.newCall(request.build()).execute()) {
            String text = response.body().string();
            assertTrue(text, response.isSuccessful());
            return new org.json.JSONTokener(text).nextValue();
        }
    }

    private void waitText(String text) { waitFor(withText(text), 25000); }
    private void waitCameraReady() {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            try {
                onView(withId(R.id.training_camera_status)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(containsString("已就绪"))));
                return;
            } catch (AssertionError | RuntimeException ignored) { SystemClock.sleep(250); }
        }
        onView(withId(R.id.training_camera_status)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(containsString("已就绪"))));
    }
    private void waitFor(org.hamcrest.Matcher<View> matcher, int timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            try {
                try { onView(matcher).perform(scrollTo()); } catch (RuntimeException | AssertionError ignored) {}
                onView(matcher).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed())); return;
            }
            catch (AssertionError | RuntimeException ignored) { SystemClock.sleep(250); }
        }
        onView(matcher).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
    }
    private void clickText(String text) {
        onView(withText(text)).perform(scrollTo());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(400);
        onView(withText(text)).perform(click());
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(350);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File directory = new File(context.getFilesDir(), "qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        bitmap.recycle();
    }
    private void shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) { }
        }
    }
    private void configure(String patient) throws Exception {
        context.getSharedPreferences("rehab", 0).edit().putString("server_url", server)
            .putString("patient_id", patient).putBoolean("voice", false).commit();
        JSONObject login;
        if (token.isEmpty()) login = (JSONObject) api("POST", "/auth/staff-login", RehabApiClient.object("username", "admin", "password", "123"));
        else { login = (JSONObject) api("GET", "/auth/me", null); login.put("token", token); }
        token = login.getString("token"); SessionStore.save(context, login);
        context.getSharedPreferences("rehab", 0).edit().putString("patient_id", patient).commit();
        shell("pm grant " + context.getPackageName() + " " + Manifest.permission.CAMERA);
    }

    @Test public void prototypePagesAndNullableFeedback() throws Exception {
        JSONObject patient = (JSONObject) api("POST", "/patients", RehabApiClient.object("name", "页面验收患者", "affected_side", "right", "forearm_cm", 25));
        String id = patient.getString("id");
        api("PUT", "/patients/" + id + "/plan", RehabApiClient.object("items", new JSONArray().put(RehabApiClient.object("action", "rub", "target_reps", 10)).put(RehabApiClient.object("action", "cup", "target_reps", 10)), "weekdays", new JSONArray().put(1).put(3).put(5)));
        configure(id);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            waitFor(allOf(withId(R.id.rehab_page_title), withText(containsString("患者管理"))), 15000); screenshot("prototype_patients");
            onView(withTagValue(is("训练"))).perform(click()); waitText("开始今日训练"); screenshot("prototype_home");
            onView(withTagValue(is("训练计划"))).perform(click()); waitText("开始计划训练"); screenshot("prototype_plan");
            clickText("每周日程"); waitText("每周康复日程"); screenshot("prototype_schedule");
            onView(withTagValue(is("动作库"))).perform(click()); waitText("摩擦手背"); screenshot("prototype_library");
            onView(withHint("搜索动作名称、配合方式")).perform(replaceText("端放"), closeSoftKeyboard());
            onView(withText("摩擦手背")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist()); waitText("端放水杯");
            onView(withTagValue(is("设置"))).perform(click()); waitText("阅读与提示"); screenshot("prototype_settings");
            clickText("隐私与使用告知"); waitText("01   这是训练辅助工具"); screenshot("prototype_privacy");
            onView(withTagValue(is("人工复核"))).perform(scrollTo(), click()); waitText("待复核队列"); screenshot("prototype_review");
            onView(withText("提交复核结论 · 待接入")).check(androidx.test.espresso.assertion.ViewAssertions.matches(not(isEnabled())));
        }
        JSONObject run = (JSONObject) api("POST", "/training-runs", RehabApiClient.object("patient_id", id, "mode", "plan"));
        Intent feedback = new Intent(context, FeedbackActivity.class).putExtra("run_id", run.getString("id")).putExtra("phase", "pre");
        try (ActivityScenario<FeedbackActivity> scenario = ActivityScenario.launch(feedback)) {
            waitText("训练前感受");
            onView(withContentDescription("疼痛程度 · 0 无疼痛，10 最强：0")).perform(scrollTo(), click()); screenshot("prototype_feedback"); clickText("保存并继续");
        }
        JSONObject saved = (JSONObject) api("GET", "/training-runs/" + run.getString("id"), null);
        JSONObject values = saved.getJSONObject("feedback").getJSONObject("pre").getJSONObject("data");
        assertEquals(0, values.getInt("pain")); assertTrue(values.isNull("fatigue")); assertTrue(values.isNull("discomfort"));
        api("POST", "/training-runs/" + run.getString("id") + "/finish", RehabApiClient.object());
    }

    @Test public void v2PatientSwitchUsesEachRealPlan() throws Exception {
        String stamp = String.valueOf(System.currentTimeMillis() % 100000);
        JSONObject first = (JSONObject) api("POST", "/patients", RehabApiClient.object("name", "林女士" + stamp, "affected_side", "right", "forearm_cm", 25));
        JSONObject second = (JSONObject) api("POST", "/patients", RehabApiClient.object("name", "陈先生" + stamp, "affected_side", "left", "forearm_cm", 26));
        for (JSONObject person : new JSONObject[]{first, second}) {
            api("PUT", "/patients/" + person.getString("id") + "/plan", RehabApiClient.object("items", new JSONArray().put(RehabApiClient.object("action", person == first ? "rub" : "cup", "target_reps", person == first ? 10 : 6)), "weekdays", new JSONArray().put(1).put(3).put(5)));
        }
        configure(first.getString("id"));
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            onView(withTagValue(is("训练"))).perform(click()); waitText("开始今日训练"); waitText("10 次");
            scenario.onActivity(activity -> {
                android.view.ViewGroup content = activity.findViewById(R.id.rehab_content);
                if (content.getChildAt(0) instanceof android.widget.ScrollView) ((android.widget.ScrollView) content.getChildAt(0)).scrollTo(0, 0);
            });
            screenshot("v2_home");
            onView(withId(R.id.rehab_patient_button)).perform(click()); waitText("确认并进入");
            onView(withHint("搜索姓名或患者编号")).perform(scrollTo(), replaceText(second.getString("name")), closeSoftKeyboard());
            waitFor(allOf(isAssignableFrom(com.google.android.material.button.MaterialButton.class), withText(containsString(second.getString("name") + "    "))), 15000);
            onView(allOf(isAssignableFrom(com.google.android.material.button.MaterialButton.class), withText(containsString(second.getString("name") + "    ")))).perform(scrollTo(), click());
            clickText("确认并进入"); waitText("6 次"); screenshot("v2_switched_patient");
            assertEquals(second.getString("id"), context.getSharedPreferences("rehab", 0).getString("patient_id", ""));
            onView(withText("10 次")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withTagValue(is("动作库"))).perform(click()); waitText("端放水杯"); screenshot("v2_library");
            onView(withTagValue(is("teach:cup"))).perform(scrollTo(), click()); waitText("先看示范，再开始练习"); screenshot("v2_teaching");
        }
    }

    @Test public void patientPlanTeachingAndTwoActions() throws Exception {
        configure("");
        String name = "界面联调" + System.currentTimeMillis() % 10000;
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            waitText("新建患者"); clickText("新建患者");
            onView(withHint("患者姓名")).perform(replaceText(name), closeSoftKeyboard());
            clickText("保存档案");
            onView(withTagValue(is("训练计划"))).perform(click()); waitText("编辑计划"); clickText("编辑计划");
            waitText("＋ 添加 端放水杯"); clickText("＋ 添加 端放水杯");
            clickText("＋ 添加 手指点按"); clickText("保存计划");
            waitText("开始今日训练"); screenshot("home");
            onView(withTagValue(is("动作库"))).perform(click());
            waitText("端放水杯"); screenshot("library");
            onView(withTagValue(is("teach:cup"))).perform(scrollTo(), click());
            waitText("先看示范，再开始练习"); screenshot("teaching");
            onView(withTagValue(is("训练"))).perform(click()); waitText("开始今日训练"); clickText("开始今日训练");
            waitText("训练前感受"); screenshot("pre_feedback"); clickText("跳过本页");
            waitText("开始训练");
            waitCameraReady();
            screenshot("preparation");
            onView(withText("开始训练")).perform(click()); waitText("结束本项");
            waitFor(allOf(withId(R.id.training_fps), withText(startsWith("识别 FPS：")), not(withText(containsString("--")))), 15000);
            onView(withId(R.id.training_hint)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isCompletelyDisplayed()));
            onView(withId(R.id.training_count)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isCompletelyDisplayed()));
            onView(withId(R.id.training_phase)).check(androidx.test.espresso.assertion.ViewAssertions.matches(isCompletelyDisplayed()));
            onView(withText("因不适结束")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isCompletelyDisplayed()));
            screenshot("training_local");
            onView(withText("本地画面")).perform(click()); SystemClock.sleep(1000); screenshot("training_local_only");
            onView(withText("骨架画面")).perform(click()); onView(withText("结束本项")).perform(click());
            waitText("本项训练结果"); screenshot("result"); clickText("休息并准备下一项");
            waitText("休息一下"); screenshot("rest"); clickText("跳过休息，进入准备");
            waitText("开始训练"); waitCameraReady();
            onView(withText("开始训练")).perform(click()); waitText("结束本项");
            SystemClock.sleep(1500); onView(withText("结束本项")).perform(click()); waitText("本项训练结果");
            clickText("填写训练后感受"); waitText("训练后感受"); clickText("跳过本页"); waitText("本次训练汇总"); screenshot("overview"); clickText("返回训练记录");
            waitText("训练详情"); screenshot("history");
            clickText("返回训练记录"); onView(allOf(withText("训练趋势"), isAssignableFrom(com.google.android.material.button.MaterialButton.class))).perform(scrollTo(), click());
            onView(withContentDescription("趋势动作")).perform(click());
            androidx.test.espresso.Espresso.onData(allOf(is(instanceOf(String.class)), is("端放水杯"))).perform(click());
            clickText("查询趋势"); waitFor(withText("比较组 1"), 15000); screenshot("trends");
        }
        JSONArray patients = (JSONArray) api("GET", "/patients?q=" + name, null);
        assertEquals(1, patients.length());
        JSONArray runs = (JSONArray) api("GET", "/training-runs?patient_id=" + patients.getJSONObject(0).getString("id"), null);
        JSONObject run = runs.getJSONObject(0);
        assertEquals("completed", run.getString("status"));
        assertEquals(2, run.getJSONArray("sessions").length());
        assertFalse(run.getJSONArray("sessions").getJSONObject(0).getBoolean("target_reached"));
    }

    @Test public void backgroundStopsSessionAndKeepsPartialRecord() throws Exception {
        JSONObject patient = (JSONObject) api("POST", "/patients", RehabApiClient.object("name", "后台退出测试", "affected_side", "right", "forearm_cm", 26));
        String id = patient.getString("id");
        api("PUT", "/patients/" + id + "/plan", RehabApiClient.object("items", new JSONArray().put(RehabApiClient.object("action", "cup", "target_reps", 10))));
        configure(id);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            onView(withTagValue(is("训练"))).perform(click()); waitText("开始今日训练"); clickText("开始今日训练");
            waitText("训练前感受"); clickText("跳过本页");
            waitText("开始训练"); waitCameraReady();
            onView(withText("开始训练")).perform(click()); waitText("结束本项"); SystemClock.sleep(1500);
            shell("input keyevent KEYCODE_HOME");
            SystemClock.sleep(2500);
            Intent resume = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            resume.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(resume);
            waitFor(anyOf(withText("本次训练已停止"), withText("今天的训练"), allOf(withId(R.id.rehab_page_title), withText(containsString("患者管理")))), 25000); screenshot("interrupted");
        }
        JSONArray runs = (JSONArray) api("GET", "/training-runs?patient_id=" + id, null);
        JSONObject run = runs.getJSONObject(0);
        assertEquals("interrupted", run.getString("status"));
        assertEquals("interrupted", run.getJSONArray("sessions").getJSONObject(0).getString("status"));
    }

    @Test public void unavailableServiceShowsChineseError() throws Exception {
        context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).commit(); SessionStore.clear(context);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            onView(withTagValue(is("login_server"))).perform(scrollTo(), click());
            onView(withHint("http://电脑或 Jetson IP:8000")).perform(scrollTo(), replaceText("http://127.0.0.1:9"), closeSoftKeyboard());
            clickText("保存并测试连接");
            waitFor(withText(containsString("连接失败")), 15000);
            screenshot("connection_error");
        } finally { context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).commit(); }
    }

    @Test public void staffLoginAndLogoutProtectsPages() throws Exception {
        context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).commit(); SessionStore.clear(context);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            clickText("管理员入口");
            onView(withHint("工作人员账号")).perform(scrollTo(), replaceText("admin"), closeSoftKeyboard());
            onView(withHint("密码")).perform(scrollTo(), replaceText("123"), closeSoftKeyboard()); clickText("登录");
            waitFor(allOf(withId(R.id.rehab_page_title), withText(containsString("患者管理"))), 25000); screenshot("staff_home");
            assertFalse(SessionStore.token(context).isEmpty());
            assertFalse(context.getSharedPreferences("rehab", 0).getString("auth_token", "").equals(SessionStore.token(context)));
            onView(withTagValue(is("设置"))).perform(click()); clickText("退出登录"); waitText("欢迎回来");
            assertTrue(SessionStore.token(context).isEmpty()); screenshot("login");
        }
    }

    @Test public void patientAccountManagementLoginAndRecovery() throws Exception {
        JSONObject person = (JSONObject) api("POST", "/patients", RehabApiClient.object("name", "账号流程患者", "affected_side", "left", "forearm_cm", 25, "email", "account-" + System.currentTimeMillis() + "@example.com"));
        String id = person.getString("id"), username = "qa." + System.currentTimeMillis();
        configure(id);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            waitFor(allOf(withId(R.id.rehab_page_title), withText(containsString("患者管理"))), 15000);
            // Open the current patient's editor through settings, independent of other fixtures.
            onView(withTagValue(is("设置"))).perform(click()); clickText("设备与服务"); clickText("编辑当前患者"); waitText("尚未分配账号密码。");
            onView(withHint("患者账号")).perform(scrollTo(), replaceText(username), closeSoftKeyboard());
            onView(withHint("初次设置必填；留空保留原密码")).perform(scrollTo(), replaceText("Patient-pass-1"), closeSoftKeyboard());
            clickText("保存账号 / 重置密码"); clickText("确认保存"); waitText("账号已保存，请告知患者使用账号与密码登录。"); screenshot("account_management");
        }
        context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).commit(); SessionStore.clear(context);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            screenshot("login_redesign_patient"); clickText("管理员入口"); screenshot("login_redesign_staff"); clickText("患者训练入口");
            onView(withHint("管理员分配的患者账号")).perform(scrollTo(), replaceText(username), closeSoftKeyboard());
            scenario.recreate();
            onView(withHint("管理员分配的患者账号")).perform(scrollTo()).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(username)));
            onView(withHint("密码")).perform(scrollTo(), replaceText("Patient-pass-1"), closeSoftKeyboard());
            clickText("登录"); waitFor(allOf(withId(R.id.rehab_page_title), withText(containsString("今日康复"))), 20000);
            assertEquals("patient", context.getSharedPreferences("rehab", 0).getString("auth_role", ""));
            onView(withText("患者管理")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withTagValue(is("设置"))).perform(click()); clickText("退出登录"); waitText("欢迎回来");
            clickText("忘记密码？"); waitText("找回患者密码"); screenshot("login_redesign_recovery");
            onView(withHint("管理员分配的患者账号")).perform(scrollTo(), replaceText(username), closeSoftKeyboard());
            onView(withHint("患者档案中的绑定邮箱")).perform(scrollTo(), replaceText(person.getString("email")), closeSoftKeyboard());
            clickText("获取验证码"); waitFor(withText(containsString("邮件服务尚未配置")), 15000);
            onView(withHint("新密码（8–128 个字符）")).perform(scrollTo(), replaceText("Patient-pass-2"), closeSoftKeyboard());
            onView(withHint("再次输入新密码")).perform(scrollTo(), replaceText("Does-not-match"), closeSoftKeyboard()); clickText("重置密码");
            onView(withHint("再次输入新密码")).check(androidx.test.espresso.assertion.ViewAssertions.matches(hasErrorText("两次密码不一致")));
            clickText("返回患者登录"); clickText("使用邮箱验证码登录");
            onView(withHint("已绑定的邮箱")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
        }
    }

    @Test public void serviceSwitchDiscardsOldResponse() throws Exception {
        configure("");
        RehabApiClient api = new RehabApiClient(context);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        Object[] received = new Object[2];
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                api.getArray("/api/v1/actions", (data, error) -> {
                    received[0] = data; received[1] = error; finished.countDown();
                });
                context.getSharedPreferences("rehab", 0).edit().putString("server_url", "http://127.0.0.1:9").commit();
            });
            assertTrue(finished.await(15, java.util.concurrent.TimeUnit.SECONDS));
            assertNull("Do not deliver another service's data", received[0]);
            assertTrue(String.valueOf(received[1]).contains("切换"));
        } finally {
            context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).commit();
            api.close();
        }
    }

    @Test public void issuedRecoveryCodeSetsNewPatientPassword() throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String username = args.getString("resetUsername", "");
        org.junit.Assume.assumeFalse("Provide a disposable recovery challenge fixture", username.isEmpty());
        context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).commit(); SessionStore.clear(context);
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            clickText("忘记密码？");
            onView(withHint("管理员分配的患者账号")).perform(scrollTo(), replaceText(username), closeSoftKeyboard());
            onView(withHint("患者档案中的绑定邮箱")).perform(scrollTo(), replaceText(args.getString("resetEmail")), closeSoftKeyboard());
            onView(withHint("六位邮箱验证码")).perform(scrollTo(), replaceText(args.getString("resetCode")), closeSoftKeyboard());
            onView(withHint("新密码（8–128 个字符）")).perform(scrollTo(), replaceText("Recovered-pass-2"), closeSoftKeyboard());
            onView(withHint("再次输入新密码")).perform(scrollTo(), replaceText("Recovered-pass-2"), closeSoftKeyboard());
            clickText("重置密码"); waitText("密码已重置，请使用新密码登录。");
            onView(withHint("密码")).perform(scrollTo(), replaceText("Recovered-pass-2"), closeSoftKeyboard()); clickText("登录");
            waitFor(allOf(withId(R.id.rehab_page_title), withText(containsString("今日康复"))), 20000);
            assertEquals("patient", context.getSharedPreferences("rehab", 0).getString("auth_role", ""));
            onView(withTagValue(is("设置"))).perform(click()); clickText("退出登录"); waitText("欢迎回来");
        }
    }

    @Test public void patientPlanOnlyAndDiscomfortExit() throws Exception {
        String patientToken = InstrumentationRegistry.getArguments().getString("patientToken", "");
        org.junit.Assume.assumeFalse("Provide an OTP-authenticated test fixture token", patientToken.isEmpty());
        token = patientToken;
        JSONObject me = (JSONObject) api("GET", "/auth/me", null);
        assertEquals("patient", me.getString("role"));
        context.getSharedPreferences("rehab", 0).edit().putString("server_url", server).putBoolean("voice", false).commit();
        SessionStore.save(context, RehabApiClient.object("token", token, "role", "patient", "patient_id", me.getString("patient_id"), "expires", me.getDouble("expires")));
        shell("pm grant " + context.getPackageName() + " " + Manifest.permission.CAMERA);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            waitText("开始今日训练"); screenshot("patient_home");
            onView(withText("配置计划 · 工作人员")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withTagValue(is("动作库"))).perform(click()); waitText("手指点按");
            onView(withTagValue(is("teach:press"))).perform(scrollTo(), click());
            waitFor(withText(containsString("本动作不在当前生效计划内")), 15000);
            onView(withText("进入训练准备")).check(androidx.test.espresso.assertion.ViewAssertions.matches(not(isEnabled())));
            onView(withTagValue(is("训练"))).perform(click()); waitText("开始今日训练"); clickText("开始今日训练");
            waitText("训练前感受"); clickText("跳过本页"); waitText("开始训练");
            waitCameraReady();
            onView(withText("开始训练")).perform(click()); waitText("因不适结束"); SystemClock.sleep(1500);
            onView(withText("因不适结束")).perform(click()); waitText("训练后感受"); screenshot("post_feedback"); clickText("跳过本页");
            waitText("本次训练汇总"); screenshot("discomfort_summary");
        }
        JSONArray runs = (JSONArray) api("GET", "/training-runs?patient_id=" + me.getString("patient_id"), null);
        JSONObject run = runs.getJSONObject(0);
        assertEquals("unfinished", run.getString("status")); assertEquals("user_discomfort", run.getString("end_reason"));
        assertEquals("skipped", run.getJSONObject("feedback").getJSONObject("pre").getString("state"));
        assertEquals("skipped", run.getJSONObject("feedback").getJSONObject("post").getString("state"));
    }

    @Test public void teachingCacheRejectsCorruptUpdateAndKeepsOldContent() throws Exception {
        configure("");
        JSONArray catalog = (JSONArray) api("GET", "/actions", null);
        JSONObject item = catalog.getJSONObject(0);
        TeachingMediaCache media = new TeachingMediaCache(context);
        try {
            java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
            TeachingMediaCache.Cached[] saved = new TeachingMediaCache.Cached[1];
            media.video(server, item, (cached, error) -> { saved[0] = cached; first.countDown(); }, text -> {});
            assertTrue(first.await(60, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(saved[0]); assertTrue(saved[0].video.isFile());
            Subtitles subtitles = Subtitles.read(saved[0].subtitle);
            assertFalse(subtitles.at(0).isEmpty()); assertTrue(subtitles.at(999999).isEmpty());
            JSONObject changed = new JSONObject(item.toString()); changed.put("content_version", "0000000000000000");
            changed.getJSONObject("files").getJSONObject("video").put("sha256", "invalid-digest");
            java.util.concurrent.CountDownLatch second = new java.util.concurrent.CountDownLatch(1);
            String[] message = new String[1]; TeachingMediaCache.Cached[] fallback = new TeachingMediaCache.Cached[1];
            media.video(server, changed, (cached, error) -> { fallback[0] = cached; message[0] = error; second.countDown(); }, text -> {});
            assertTrue(second.await(60, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(fallback[0]);
            assertEquals(item.getString("content_version"), fallback[0].item.getString("content_version"));
            assertTrue(message[0].contains("旧版缓存")); assertTrue(saved[0].video.isFile()); assertTrue(saved[0].subtitle.isFile());
        } finally { media.close(); }
        context.getSharedPreferences("rehab", 0).edit().putString("action_catalog_" + server, catalog.toString()).commit();
        SessionStore.clear(context);
        Intent offline = new Intent(context, HomeActivity.class).putExtra("offline_teaching", true);
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(offline)) {
            waitText("摩擦手背");
            onView(withText("训练记录")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withTagValue(is("teach:rub"))).perform(scrollTo(), click());
            waitText("先看示范，再开始练习");
            waitFor(withText(containsString("已缓存 · 版本")), 15000); screenshot("offline_teaching");
            onView(withText("进入训练准备")).check(androidx.test.espresso.assertion.ViewAssertions.matches(not(isEnabled())));
        }
    }
}
