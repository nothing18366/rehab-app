package com.robint.feature.training;

import com.robint.R;
import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.feedback.FeedbackActivity;
import com.robint.feature.home.HomeActivity;
import com.robint.rtc.WebRtcClient;
import com.robint.ui.component.UiComponents;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public class TrainingActivity extends AppCompatActivity implements WebRtcClient.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RehabApiClient api;
    private WebRtcClient rtc;
    private TextToSpeech speech;
    private boolean speechReady, voice, foreground, permissionRequest, targetAnnounced;
    private boolean flowAbandoned, cameraPermissionDenied, discomfortExit;
    private String state = "loading", runId, sessionId = "", diagnostics = "暂无诊断数据";
    private String lastSpoken = "", pendingHint = "";
    private long lastSpeechAt, hintSince, lastResultAt;
    private JSONObject run, item, patient, teaching, lastResult;
    private JSONArray catalog;
    private int itemIndex, requestGeneration;
    private TextView countView, passedView, detailView, phaseView, hintView, fpsView, cameraStatus, framing;
    private MaterialButton primary, preview, mute, diagnosticButton;
    private boolean diagnosticRequested, snapshotPending;
    private LinearLayout controls;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        UiComponents.immersive(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api = new RehabApiClient(this);
        runId = getIntent().getStringExtra("run_id");
        try { catalog = new JSONArray(getIntent().getStringExtra("catalog")); }
        catch (Exception e) { catalog = new JSONArray(); }
        voice = getSharedPreferences("rehab", MODE_PRIVATE).getBoolean("voice", true);
        speech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && speech != null) {
                int availability = speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
                speechReady = availability != TextToSpeech.LANG_MISSING_DATA && availability != TextToSpeech.LANG_NOT_SUPPORTED;
                if (speechReady) speech.setSpeechRate(0.9f);
            }
            getSharedPreferences("rehab", MODE_PRIVATE).edit().putBoolean("tts_zh_available", speechReady).apply();
        });
        if (saved != null && !saved.getString("session_id", "").isEmpty()) {
            flowAbandoned = true;
            sessionId = saved.getString("session_id");
            state = "interrupted";
            loadRun(false);
        } else loadRun(true);
    }

    private void loadRun(boolean prepare) {
        api.get("/api/v1/training-runs/" + runId, (data, error) -> {
            if (error != null) { simpleError(error); return; }
            run = (JSONObject) data;
            patient = run.optJSONObject("patient");
            itemIndex = run.optInt("completed_items");
            if (!prepare) { interruptRecord(); showInterrupted("应用已重新打开，本次训练不会自动续接"); }
            else if (!"active".equals(run.optString("status")) || itemIndex >= run.optJSONArray("items").length()) showOverview();
            else if (!run.optJSONObject("feedback").has("pre")) feedback("pre");
            else if (foreground) prepareItem();
            else state = "ready_to_prepare";
        });
    }

    private JSONObject teaching(String key) {
        for (int i = 0; i < catalog.length(); i++) if (key.equals(catalog.optJSONObject(i).optString("key"))) return catalog.optJSONObject(i);
        return RehabApiClient.object("key", key, "name", key);
    }

    private void prepareItem() {
        closeCamera();
        state = "preparing";
        sessionId = ""; lastResult = null; targetAnnounced = false; lastSpoken = ""; pendingHint = "";
        item = run.optJSONArray("items").optJSONObject(itemIndex);
        teaching = teaching(item.optString("action"));
        setContentView(R.layout.activity_training);
        updateJourney(0);
        ((TextView) findViewById(R.id.training_title)).setText(teaching.optString("name"));
        ((TextView) findViewById(R.id.training_patient)).setText(patient.optString("name") + " · " + RehabApiClient.side(patient)
            + " · " + (itemIndex + 1) + "/" + run.optJSONArray("items").length() + "项");
        countView = findViewById(R.id.training_count);
        passedView = findViewById(R.id.training_passed);
        detailView = findViewById(R.id.training_detail);
        phaseView = findViewById(R.id.training_phase);
        hintView = findViewById(R.id.training_hint);
        fpsView = findViewById(R.id.training_fps);
        cameraStatus = findViewById(R.id.training_camera_status);
        framing = findViewById(R.id.training_framing);
        displayCount(0, item.optInt("target_reps"));
        detailView.setText(teaching.optString("counting"));
        framing.setText(teaching.optString("framing"));
        hintView.setText("确认患者、患侧与入镜范围后，点击开始。" + teaching.optString("cooperation"));
        fpsView.setOnClickListener(v -> showDiagnostics());
        controls = findViewById(R.id.training_controls);
        primary = UiComponents.button(this, "开始训练", true, v -> checkAndCountdown()); controls.addView(primary);
        controls.addView(UiComponents.button(this, "返回", false, v -> confirmExit()));
        adaptTrainingLayout();
        rtc = new WebRtcClient(this, findViewById(R.id.training_local), findViewById(R.id.training_remote),
                findViewById(R.id.training_skeleton), this);
        rtc.selectCamera(false);
        openPreview();
    }

    private void openPreview() {
        if (!foreground || rtc == null || permissionRequest || cameraPermissionDenied) return;
        if (android.os.Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { rtc.startPreview(); } catch (RuntimeException e) { onError("摄像头启动失败，请检查权限与设备占用"); }
        } else {
            permissionRequest = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 202);
        }
    }

    private void checkAndCountdown() {
        if (!"preparing".equals(state)) return;
        if (rtc == null || !rtc.isPreviewReady()) { hintView.setText("摄像头尚未就绪，请确认权限并等待画面出现"); return; }
        primary.setEnabled(false);
        hintView.setText("正在检查服务与模型…");
        int ticket = ++requestGeneration;
        api.get("/health", (data, error) -> {
            if (!foreground || ticket != requestGeneration || !"preparing".equals(state)) return;
            if (error != null) { preparationError(error); return; }
            JSONObject health = (JSONObject) data;
            JSONObject capabilities = health.optJSONObject("capabilities");
            if (capabilities == null || !capabilities.optBoolean("local_skeleton_v1") || !capabilities.optBoolean("clock_sync_v1")) {
                preparationError("服务端尚不支持本地骨架画面，请联系工作人员升级服务端"); return;
            }
            if (SessionStore.staff(this) && getSharedPreferences("rehab", MODE_PRIVATE).getBoolean("next_remote_diagnostic", false)
                    && (!capabilities.optBoolean("diagnostic_video") || !capabilities.optBoolean("diagnostic_png"))) {
                preparationError("服务端尚不支持远端诊断，请升级服务端或关闭下一次诊断开关"); return;
            }
            if (!health.optBoolean("model_loaded")) { preparationError("模型尚未就绪，请联系工作人员检查服务"); return; }
            if (health.optBoolean("busy")) { preparationError("另一个设备正在训练，请稍后重试"); return; }
            state = "countdown";
            countdown(3);
        });
    }

    private void preparationError(String message) {
        state = "preparing"; primary.setEnabled(true); hintView.setText(message);
    }

    private void countdown(int seconds) {
        if (!foreground || !"countdown".equals(state)) return;
        if (seconds == 0) { connectSession(); return; }
        findViewById(R.id.training_info).setVisibility(View.GONE);
        findViewById(R.id.training_countdown).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.training_countdown_number)).setText(String.valueOf(seconds));
        handler.postDelayed(() -> countdown(seconds - 1), 1000);
    }

    private void connectSession() {
        state = "connecting";
        findViewById(R.id.training_countdown).setVisibility(View.GONE);
        findViewById(R.id.training_info).setVisibility(View.VISIBLE);
        primary.setText("正在连接"); hintView.setText("正在建立视频连接…");
        int ticket = ++requestGeneration;
        api.post("/api/v1/sessions", RehabApiClient.object("run_id", runId, "item_index", itemIndex), (data, error) -> {
            if (error != null) { if (foreground && ticket == requestGeneration) onError(error); return; }
            sessionId = ((JSONObject) data).optString("id");
            if (!foreground || ticket != requestGeneration || !"connecting".equals(state)) { interruptRecord(); return; }
            diagnosticRequested = SessionStore.staff(this) && getSharedPreferences("rehab", MODE_PRIVATE).getBoolean("next_remote_diagnostic", false);
            rtc.start(api.base(), RehabApiClient.object("session_id", sessionId,
                    "preview_mode", diagnosticRequested ? "diagnostic" : "keypoints"));
            handler.postDelayed(() -> {
                if (foreground && "connecting".equals(state) && ticket == requestGeneration) onError("视频连接超时，请检查网络后重新开始");
            }, 20000);
        });
    }

    private void liveControls() {
        controls.removeAllViews();
        LinearLayout switches = UiComponents.row(this);
        preview = UiComponents.button(this, "本地画面", false, v -> rtc.togglePreview());
        mute = UiComponents.button(this, voice ? "静音" : "开启语音", false, v -> {
            voice = !voice;
            getSharedPreferences("rehab", MODE_PRIVATE).edit().putBoolean("voice", voice).apply();
            mute.setText(voice ? "静音" : "开启语音");
            if (!voice && speech != null) speech.stop();
        });
        for (MaterialButton button : new MaterialButton[]{preview, mute}) {
            button.setLayoutParams(new LinearLayout.LayoutParams(0, UiComponents.dp(this, 56), 1)); switches.addView(button);
        }
        controls.addView(switches);
        diagnosticButton = null;
        if (diagnosticRequested && SessionStore.staff(this)) {
            diagnosticButton = UiComponents.button(this, "工作人员诊断", false, v -> showRemoteDiagnostic());
            controls.addView(diagnosticButton);
        }
        onPreviewChanged(rtc.isSkeletonEnabled(), rtc.hasDiagnosticVideo());
        primary = UiComponents.button(this, "结束本项", true, v -> finishItem());
        primary.setBackgroundTintList(ColorStateList.valueOf(UiComponents.DANGER)); controls.addView(primary);
        MaterialButton safe = UiComponents.button(this, "因不适结束", false, v -> {
            if (!"training".equals(state)) return;
            discomfortExit = true; flowAbandoned = true; state = "finishing";
            controls.setEnabled(false); primary.setEnabled(false); hintView.setText("已停止动作，正在保存部分结果…");
            rtc.stopForDiscomfort();
            handler.postDelayed(() -> {
                if (!"finishing".equals(state)) return;
                api.post("/api/v1/sessions/" + sessionId + "/interrupt", RehabApiClient.object("reason", "user_discomfort"), (data, error) -> { closeCamera(); recoverSummary(0); });
            }, 3000);
        });
        safe.setTextColor(UiComponents.DANGER); controls.addView(safe);
        adaptTrainingLayout();
    }

    private void adaptTrainingLayout() {
        if (getResources().getConfiguration().screenHeightDp >= 600) return;
        int padding = UiComponents.dp(this, 12);
        findViewById(R.id.training_root).setPadding(padding, padding, padding, padding);
        findViewById(R.id.training_info).setPadding(padding, padding, padding, padding);
        ((TextView) findViewById(R.id.training_title)).setTextSize(18);
        TextView patientLabel = findViewById(R.id.training_patient); patientLabel.setTextSize(12); patientLabel.setPadding(0, 4, 0, 8);
        ((TextView) findViewById(R.id.training_count_label)).setTextSize(12);
        boolean largeText = getResources().getConfiguration().fontScale > 1.2f;
        countView.setTextSize(largeText ? 34 : 44); passedView.setTextSize(13); detailView.setTextSize(13); phaseView.setTextSize(13);
        findViewById(R.id.training_count_label).setVisibility(largeText ? View.GONE : View.VISIBLE);
        TextView journey = findViewById(R.id.training_journey); journey.setTextSize(12); journey.setPadding(0, 0, 0, UiComponents.dp(this, 10));
        hintView.setTextSize(14); hintView.setPadding(12, 10, 12, 10); framing.setTextSize(13);
        View progress = findViewById(R.id.training_progress);
        LinearLayout.LayoutParams progressSize = (LinearLayout.LayoutParams) progress.getLayoutParams();
        progressSize.topMargin = UiComponents.dp(this, 2); progressSize.bottomMargin = UiComponents.dp(this, 6); progress.setLayoutParams(progressSize);
        if (controls.getChildCount() == 3 && controls.getChildAt(1) instanceof MaterialButton && controls.getChildAt(2) instanceof MaterialButton) {
            View first = controls.getChildAt(1), second = controls.getChildAt(2);
            controls.removeView(first); controls.removeView(second);
            LinearLayout actions = UiComponents.row(this);
            LinearLayout.LayoutParams firstSize = new LinearLayout.LayoutParams(0, UiComponents.dp(this, 48), 1); firstSize.setMarginEnd(UiComponents.dp(this, 8));
            actions.addView(first, firstSize); actions.addView(second, new LinearLayout.LayoutParams(0, UiComponents.dp(this, 48), 1)); controls.addView(actions);
        }
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (child instanceof MaterialButton) compactTrainingButton((MaterialButton) child);
            else if (child instanceof LinearLayout) {
                ((LinearLayout) child).setGravity(android.view.Gravity.TOP);
                for (int j = 0; j < ((LinearLayout) child).getChildCount(); j++) {
                    View button = ((LinearLayout) child).getChildAt(j); if (button instanceof MaterialButton) compactTrainingButton((MaterialButton) button);
                }
            }
        }
    }

    private void displayCount(int count, int target) {
        android.text.SpannableString text = new android.text.SpannableString(count + " / " + target);
        text.setSpan(new android.text.style.RelativeSizeSpan(.45f), String.valueOf(count).length(), text.length(), 0);
        countView.setText(text);
    }

    private void updateJourney(int current) {
        String[] labels = {"训练准备", "动作练习", "训练小结", "感受记录"};
        android.text.SpannableString text = new android.text.SpannableString("01  训练准备    →    02  动作练习    →    03  训练小结    →    04  感受记录");
        int start = text.toString().indexOf(labels[current]) - 4;
        text.setSpan(new android.text.style.ForegroundColorSpan(UiComponents.PRIMARY), start, start + 4 + labels[current].length(), 0);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, start + 4 + labels[current].length(), 0);
        ((TextView) findViewById(R.id.training_journey)).setText(text);
    }

    private void compactTrainingButton(MaterialButton button) {
        button.setTextSize(14); button.setPadding(4, 0, 4, 0); button.setMinWidth(0);
        button.setMinHeight(UiComponents.dp(this, 48)); button.setMinimumHeight(UiComponents.dp(this, 48));
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) button.getLayoutParams();
        size.height = UiComponents.dp(this, 48); size.topMargin = UiComponents.dp(this, 4); button.setLayoutParams(size);
    }

    private void finishItem() {
        if (!"training".equals(state)) return;
        state = "finishing"; primary.setEnabled(false); hintView.setText("正在保存训练结果…");
        rtc.stopTraining();
        handler.postDelayed(() -> { if (foreground && "finishing".equals(state)) recoverSummary(0); }, 5000);
    }

    @Override public void onMessage(JSONObject data) {
        if (!foreground || (data.has("session_id") && !sessionId.equals(data.optString("session_id")))) return;
        String type = data.optString("type");
        if ("started".equals(type) && "connecting".equals(state)) {
            if ("diagnostic".equals(data.optString("preview_mode")))
                getSharedPreferences("rehab", MODE_PRIVATE).edit().remove("next_remote_diagnostic").apply();
            state = "training"; updateJourney(1); phaseView.setText("训练中"); liveControls();
            hintView.setText("请按示范开始，保持动作自然、平稳");
            lastResultAt = SystemClock.elapsedRealtime(); handler.postDelayed(watchResults, 1000);
            speak("训练开始", true);
        } else if ("result".equals(type) && "training".equals(state)) {
            lastResult = data; lastResultAt = SystemClock.elapsedRealtime();
            int count = data.optInt("count"), target = item.optInt("target_reps");
            displayCount(count, target);
            ((android.widget.ProgressBar) findViewById(R.id.training_progress)).setProgress(Math.min(100, count * 100 / Math.max(1, target)));
            passedView.setText("完成次数 · 合格 " + data.optInt("passed") + " 次");
            detailView.setText("-".equals(data.optString("detail")) ? teaching.optString("counting") : data.optString("detail", teaching.optString("counting")));
            phaseView.setText(phase(data.optString("phase")) + " · " + data.optString("tracking", "等待跟踪"));
            String hint = primaryHint(data);
            hintView.setText(hint);
            fpsView.setText(String.format(Locale.CHINA, "识别 FPS：%.1f", data.optDouble("processed_fps")));
            if (!hint.equals(pendingHint)) { pendingHint = hint; hintSince = lastResultAt; }
            else if (lastResultAt - hintSince >= 2000 && !hint.matches(".*[0-9].*")) speak(hint, false);
            if (count >= target && !targetAnnounced) {
                targetAnnounced = true;
                speak("已达到次数目标，可点击结束本项", true);
            }
            if (targetAnnounced) framing.setText("已达到次数目标，请按自身情况点击“结束本项”");
        } else if ("summary".equals(type) && ("finishing".equals(state) || "training".equals(state))) {
            showResult(data, "completed".equals(data.optString("status", "completed")));
        } else if ("error".equals(type)) {
            String message = data.optString("message", "服务端返回错误"); onError(message);
            if (message.contains("登录已失效")) SessionStore.login(this);
        }
    }

    private final Runnable watchResults = new Runnable() {
        @Override public void run() {
            if (!foreground || !"training".equals(state)) return;
            long age = SystemClock.elapsedRealtime() - lastResultAt;
            if (age > 10000) { onError("超过 10 秒未收到识别结果，本次训练已中断"); return; }
            if (age > 3000) { hintView.setText("识别结果暂时中断；本地画面不代表识别正常"); fpsView.setText("识别 FPS：--（结果中断）"); }
            handler.postDelayed(this, 1000);
        }
    };

    private String primaryHint(JSONObject data) {
        String tracking = data.optString("tracking");
        if (!tracking.isEmpty() && !"正常".equals(tracking)) return "跟踪提示：" + tracking;
        String position = data.optString("position_hint");
        if (!position.isEmpty()) return position;
        String hint = data.optString("hint");
        if (!hint.isEmpty()) return hint;
        return data.optString("live", "请按示范缓慢完成动作");
    }

    private String phase(String value) {
        switch (value) {
            case "idle": return "等待准备";
            case "open": return "手指张开";
            case "closed": return "握拳";
            case "mid": return "动作转换";
            case "contact": return "接触准备";
            default: return value;
        }
    }

    private void recoverSummary(int attempt) {
        if (sessionId.isEmpty() || !foreground) return;
        api.get("/api/v1/sessions/" + sessionId, (data, error) -> {
            if (!foreground || !("finishing".equals(state) || "interrupted".equals(state))) return;
            if (error == null) {
                JSONObject record = (JSONObject) data;
                String status = record.optString("status");
                if ("completed".equals(status) || "interrupted".equals(status)) {
                    JSONObject summary = record.optJSONObject("summary");
                    if (summary == null) summary = RehabApiClient.object("name", teaching == null ? "本次训练" : teaching.optString("name"),
                        "target", record.optInt("target"), "count", 0, "passed", 0);
                    try { summary.put("elapsed_seconds", record.optDouble("elapsed_seconds")); } catch (Exception ignored) { }
                    showResult(summary, "completed".equals(status)); return;
                }
            }
            if (attempt < 4) handler.postDelayed(() -> recoverSummary(attempt + 1), 1500);
            else showInterrupted(error == null ? "结果尚未确认，请稍后查询；不会自动恢复旧训练" : error);
        });
    }

    private LinearLayout document(String heading) {
        LinearLayout outer = UiComponents.column(this);
        outer.setBackgroundColor(getResources().getColor(R.color.rehab_background));
        int padding = Math.max(28, (getResources().getConfiguration().screenWidthDp - 1000) / 2);
        outer.setPadding(UiComponents.dp(this, padding), UiComponents.dp(this, 28), UiComponents.dp(this, padding), UiComponents.dp(this, 28));
        UiComponents.section(this, outer, heading, "按自己的节奏，完成每一步。");
        if (patient != null) outer.addView(UiComponents.text(this, patient.optString("name") + " · " + RehabApiClient.side(patient)
            + " · " + patient.optString("code"), 18, UiComponents.MUTED));
        setContentView(UiComponents.scroll(this, outer));
        return outer;
    }

    private void showResult(JSONObject summary, boolean completed) {
        if (discomfortExit) { closeCamera(); feedback("post"); return; }
        state = completed ? "result" : "interrupted";
        handler.removeCallbacks(watchResults);
        WebRtcClient previous = rtc;
        if (previous != null) handler.postDelayed(() -> { if (rtc == previous) closeCamera(); }, 300);
        LinearLayout body = document(completed ? "本项训练结果" : "训练中断 · 部分结果");
        LinearLayout card = UiComponents.card(this);
        card.addView(UiComponents.heading(this, summary.optString("name", "本次训练")));
        boolean reached = summary.optInt("count") >= summary.optInt("target", 1);
        card.addView(UiComponents.text(this, reached ? "已达到次数目标" : "未达到次数目标", 22, reached ? UiComponents.PRIMARY : UiComponents.MUTED));
        UiComponents.summary(this, card, summary, summary.optDouble("elapsed_seconds"));
        card.addView(UiComponents.text(this, "合格与复核为算法判定，请结合工作人员指导。", 18, UiComponents.MUTED));
        body.addView(card);
        if (completed && !flowAbandoned) {
            speak("本项训练已结束", true);
            itemIndex++;
            if (itemIndex < run.optJSONArray("items").length()) {
                body.addView(UiComponents.button(this, "休息并准备下一项", true, v -> showRest()));
                body.addView(UiComponents.button(this, "结束本次计划", false, v -> endRun()));
            } else body.addView(UiComponents.button(this, "填写训练后感受", true, v -> feedback("post")));
        } else {
            body.addView(UiComponents.button(this, "重新查询已保存结果", false, v -> recoverSummary(0)));
            body.addView(UiComponents.button(this, "返回训练记录", true, v -> leave()));
            body.addView(UiComponents.button(this, "填写训练后感受", false, v -> feedback("post")));
        }
    }

    private void showRest() {
        closeCamera(); state = "rest";
        LinearLayout body = document("休息一下");
        UiComponents.tip(this, body, "慢一点，也很好", "放松肩膀和双手。这是建议休息时间，准备好后点击继续。");
        JSONObject next = run.optJSONArray("items").optJSONObject(itemIndex);
        body.addView(UiComponents.text(this, "下一项：" + teaching(next.optString("action")).optString("name") + " · " + next.optInt("target_reps") + " 次", 24, UiComponents.INK));
        TextView timer = UiComponents.heading(this, "30 秒"); timer.setTextSize(104); timer.setGravity(android.view.Gravity.CENTER);
        timer.setMinHeight(UiComponents.dp(this, getResources().getConfiguration().screenHeightDp >= 600 ? 280 : 180));
        timer.setBackground(UiComponents.background(this, UiComponents.MINT, 24)); timer.setPadding(20, UiComponents.dp(this, 32), 20, UiComponents.dp(this, 32)); body.addView(timer);
        MaterialButton nextButton = UiComponents.button(this, "跳过休息，进入准备", true, v -> prepareItem()); body.addView(nextButton);
        body.addView(UiComponents.button(this, "结束本次计划", false, v -> endRun()));
        long end = SystemClock.elapsedRealtime() + 30000;
        handler.post(new Runnable() {
            @Override public void run() {
                if (!foreground || !"rest".equals(state)) return;
                int seconds = Math.max(0, (int) Math.ceil((end - SystemClock.elapsedRealtime()) / 1000.0));
                timer.setText(seconds > 0 ? seconds + " 秒" : "休息结束");
                if (seconds > 0) handler.postDelayed(this, 1000); else nextButton.setText("进入下一项准备");
            }
        });
    }

    private void showOverview() {
        closeCamera(); state = "overview";
        LinearLayout body = document("本次训练汇总");
        api.get("/api/v1/training-runs/" + runId, (data, error) -> {
            if (!"overview".equals(state) || !foreground) return;
            if (error != null) { body.addView(UiComponents.text(this, error, 20, UiComponents.DANGER)); return; }
            run = (JSONObject) data;
            body.addView(UiComponents.heading(this, HomeActivity.runStatus(run.optString("status"))));
            if ("user_discomfort".equals(run.optString("end_reason"))) body.addView(UiComponents.text(this, "患者主动因不适结束 · 未计为计划完成", 20, UiComponents.DANGER));
            body.addView(UiComponents.text(this, String.format(Locale.CHINA, "已结束 %d / %d 项 · 实际训练 %.1f 分钟",
                run.optInt("completed_items"), run.optJSONArray("items").length(), run.optDouble("elapsed_seconds") / 60), 24, UiComponents.INK));
            JSONArray sessions = run.optJSONArray("sessions");
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                LinearLayout card = UiComponents.card(this);
                card.addView(UiComponents.heading(this, teaching(session.optString("action")).optString("name")));
                card.addView(UiComponents.text(this, HomeActivity.sessionStatus(session.optString("status")), 18, UiComponents.MUTED));
                UiComponents.summary(this, card, session.optJSONObject("summary"), session.optDouble("elapsed_seconds"));
                body.addView(card);
            }
        });
        body.addView(UiComponents.button(this, "返回训练记录", true, v -> leave()));
    }

    private void endRun() {
        closeCamera();
        api.post("/api/v1/training-runs/" + runId + "/finish", RehabApiClient.object(), (data, error) -> {
            if (error != null) simpleError(error); else feedback("post");
        });
    }

    private void interruptRecord() {
        if (!sessionId.isEmpty()) RehabApiClient.cleanup(this, "/api/v1/sessions/" + sessionId + "/interrupt");
        else if (runId != null) RehabApiClient.cleanup(this, "/api/v1/training-runs/" + runId + "/finish");
    }

    private void showInterrupted(String message) {
        state = "interrupted";
        LinearLayout body = document("本次训练已停止");
        LinearLayout warning = UiComponents.card(this); warning.setBackground(UiComponents.background(this, 0xfffff0ee, 18));
        warning.addView(UiComponents.text(this, "识别已中断，次数停止更新", 24, UiComponents.DANGER));
        warning.addView(UiComponents.text(this, message, 18, UiComponents.DANGER)); body.addView(warning);
        body.addView(UiComponents.text(this, "重新开始将建立新会话，不会继续旧计数。", 18, UiComponents.MUTED));
        if (!sessionId.isEmpty()) body.addView(UiComponents.button(this, "查询保存结果", true, v -> recoverSummary(0)));
        body.addView(UiComponents.button(this, "返回训练记录", false, v -> leave()));
    }

    private void simpleError(String message) {
        LinearLayout body = document("暂时无法继续");
        body.addView(UiComponents.text(this, message, 22, UiComponents.DANGER));
        body.addView(UiComponents.button(this, "重新读取训练", true, v -> loadRun(true)));
        body.addView(UiComponents.button(this, "返回", false, v -> leave()));
    }

    private void speak(String text, boolean important) {
        if (!voice || !speechReady || !foreground || text.isEmpty() || text.equals(lastSpoken)) return;
        long clock = SystemClock.elapsedRealtime();
        if (!important && (clock - lastSpeechAt < 8000 || text.equals(teaching == null ? "" : teaching.optString("counting")))) return;
        lastSpoken = text; lastSpeechAt = clock;
        speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rehab_prompt");
    }

    @Override public void onCameraStatus(String message) {
        if (cameraStatus != null && foreground) cameraStatus.setText(message);
        if ("preparing".equals(state) && primary != null && message.contains("已就绪")) {
            primary.setEnabled(true);
            hintView.setText("确认患者、患侧与入镜范围后，点击开始。" + teaching.optString("cooperation"));
        }
    }
    @Override public void onCameraSelected(boolean front, boolean switching) { }
    @Override public void onPreviewChanged(boolean skeleton, boolean diagnosticAvailable) {
        if (preview != null) {
            preview.setEnabled(true); preview.setText(skeleton ? "本地画面" : "骨架画面");
            preview.setContentDescription(skeleton ? "切换到本地画面，隐藏骨架" : "切换到骨架画面");
        }
        if (diagnosticButton != null) {
            diagnosticButton.setEnabled(diagnosticAvailable);
            diagnosticButton.setText(rtc != null && rtc.showingDiagnostic() ? "诊断中 · 返回训练画面" : "工作人员诊断");
        }
        if (cameraStatus != null) cameraStatus.setText(rtc != null && rtc.showingDiagnostic()
                ? "工作人员远端诊断" : skeleton ? "本地视频与骨架" : "本地视频");
    }
    @Override public void onStats(String message) { diagnostics = message; }
    private void showDiagnostics() { new AlertDialog.Builder(this).setTitle("实时诊断").setMessage(diagnostics).setPositiveButton("关闭", null).show(); }

    private void showRemoteDiagnostic() {
        if (!SessionStore.staff(this) || rtc == null || !rtc.hasDiagnosticVideo()) return;
        new AlertDialog.Builder(this).setTitle("工作人员诊断")
            .setMessage("远端画面为服务端解码后的原图，回传仍有压缩。无损 PNG 可用于检查上传失真；查看方式不改变训练计数。")
            .setPositiveButton(rtc.showingDiagnostic() ? "返回训练画面" : "查看远端画面", (d, w) -> rtc.toggleDiagnostic())
            .setNeutralButton("抓取无损 PNG", (d, w) -> {
                if (snapshotPending || rtc == null) return;
                snapshotPending = true;
                rtc.diagnosticFrame((data, error) -> {
                    snapshotPending = false;
                    if (!foreground || isFinishing()) { if (data instanceof android.graphics.Bitmap) ((android.graphics.Bitmap) data).recycle(); return; }
                    if (error != null) { new AlertDialog.Builder(this).setMessage(error).setPositiveButton("关闭", null).show(); return; }
                    android.graphics.Bitmap bitmap = (android.graphics.Bitmap) data;
                    ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setAdjustViewBounds(true);
                    image.setContentDescription("当前诊断会话服务端解码后的无损抓帧");
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("服务端原图 · 无损 PNG").setView(image).setPositiveButton("关闭", null).create();
                    dialog.setOnDismissListener(closed -> image.setImageDrawable(null));
                    dialog.show();
                });
            }).setNegativeButton("关闭", null).show();
    }
    @Override public void onError(String message) {
        if (!foreground) return;
        if ("result".equals(state) || "rest".equals(state) || "overview".equals(state) || "interrupted".equals(state)) return;
        requestGeneration++;
        if ("preparing".equals(state)) { hintView.setText(message); primary.setEnabled(false); return; }
        if ("finishing".equals(state)) {
            closeCamera();
            LinearLayout body = document("正在确认训练结果");
            body.addView(UiComponents.text(this, "连接已关闭，正在查询服务端已保存的结果…", 22, UiComponents.MUTED));
            recoverSummary(0);
            return;
        }
        flowAbandoned = true; interruptRecord(); closeCamera(); showInterrupted(message);
    }

    private void closeCamera() {
        snapshotPending = false;
        if (rtc != null) { WebRtcClient old = rtc; rtc = null; old.close(); }
        getSharedPreferences("rehab", MODE_PRIVATE).edit().putString("last_diagnostics", diagnostics).apply();
    }
    private void confirmExit() {
        if ("training".equals(state) || "connecting".equals(state) || "finishing".equals(state) || "countdown".equals(state)) {
            new AlertDialog.Builder(this).setTitle("结束并退出？").setMessage("当前动作将记录为中断，不保留续训状态。")
                .setNegativeButton("继续训练", null).setPositiveButton("结束并退出", (d, w) -> { interruptRecord(); closeCamera(); leave(); }).show();
        } else {
            if (run != null && "active".equals(run.optString("status"))) RehabApiClient.cleanup(this, "/api/v1/training-runs/" + runId + "/finish");
            leave();
        }
    }
    private void leave() { setResult(RESULT_OK, new Intent().putExtra("run_id", runId)); finish(); }
    private void feedback(String phase) {
        closeCamera(); state = "feedback_" + phase;
        startActivityForResult(new Intent(this, FeedbackActivity.class).putExtra("run_id", runId).putExtra("phase", phase), "pre".equals(phase) ? 301 : 302);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 301) {
            if (result == RESULT_OK) state = "ready_to_prepare";
            else { RehabApiClient.cleanup(this, "/api/v1/training-runs/" + runId + "/finish"); leave(); }
        } else if (request == 302) { discomfortExit = false; state = "ready_overview"; }
    }
    @Override public void onBackPressed() { confirmExit(); }
    @Override protected void onResume() {
        super.onResume(); foreground = true; UiComponents.immersive(this);
        if (!SessionStore.require(this)) return;
        if ("ready_to_prepare".equals(state)) prepareItem();
        else if ("ready_overview".equals(state)) showOverview();
        else if ("preparing".equals(state)) openPreview();
        else if ("interrupted".equals(state)) showInterrupted("本次训练已停止，请查询结果或返回重新开始");
    }
    @Override protected void onPause() {
        foreground = false;
        requestGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (!permissionRequest && ("preparing".equals(state) || "countdown".equals(state) || "connecting".equals(state)
            || "training".equals(state) || "finishing".equals(state) || "rest".equals(state) || "result".equals(state))) {
            flowAbandoned = true;
            if (!"finishing".equals(state)) interruptRecord();
            if ("result".equals(state) || "rest".equals(state)) RehabApiClient.cleanup(this, "/api/v1/training-runs/" + runId + "/finish");
            state = "interrupted";
        }
        if (!permissionRequest) closeCamera();
        if (speech != null) speech.stop();
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); closeCamera(); api.close();
        if (speech != null) { speech.stop(); speech.shutdown(); }
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle saved) { saved.putString("session_id", sessionId); super.onSaveInstanceState(saved); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results); permissionRequest = false;
        if (request == 202 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openPreview();
        else if (request == 202) { cameraPermissionDenied = true; hintView.setText("需要摄像头权限；请在系统设置中允许后重新进入"); primary.setEnabled(false); }
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) UiComponents.immersive(this); }
}
