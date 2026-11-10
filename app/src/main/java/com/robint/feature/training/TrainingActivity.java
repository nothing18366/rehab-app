package com.robint.feature.training;

import com.robint.R;
import com.robint.RehabApplication;
import com.robint.device.RobotScreenAngle;
import com.robint.data.local.SessionStore;
import com.robint.data.local.TeachingMediaCache;
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
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.VideoView;
import android.widget.MediaController;
import android.view.Gravity;
import android.net.Uri;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import android.widget.SeekBar;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public class TrainingActivity extends AppCompatActivity implements WebRtcClient.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RehabApiClient api;
    private WebRtcClient rtc;
    private TextToSpeech speech;
    private TrainingCueAudio cueAudio;
    private boolean speechReady, voice, foreground, permissionRequest, targetAnnounced;
    private boolean flowAbandoned, cameraPermissionDenied, discomfortExit;
    private boolean recordNonstandard;
    private String state = "loading", runId, sessionId = "", diagnostics = "暂无诊断数据";
    private String dailyPreviewMode, sessionPreviewMode = "keypoints";
    private String pendingHint = "", pendingAnnouncement = "", announcementState = "", utteranceId = "";
    private long hintSince, lastResultAt, announcementExpires, utteranceSequence;
    private boolean speechInFlight;
    private final TrainingSpeechPolicy speechPolicy = new TrainingSpeechPolicy();
    private JSONObject run, item, patient, teaching, lastResult;
    private JSONArray catalog;
    private int itemIndex, requestGeneration;
    private TextView countView, passedView, failedView, detailView, phaseView, hintView, fpsView, cameraStatus, framing;
    private MaterialButton primary, preview, mute, diagnosticButton;
    private boolean diagnosticRequested, snapshotPending;
    private LinearLayout controls;
    private RobotScreenAngle screenAngle;
    private MaterialButton angleButton;
    private AlertDialog angleDialog;
    private Runnable refreshAngleDialog;
    private final Runnable angleListener = this::updateScreenAngle;
    private long restDeadline;
    private Runnable restTick;
    private AlertDialog restTeachingDialog;
    private TeachingMediaCache restMedia;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        UiComponents.immersive(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api = new RehabApiClient(this);
        screenAngle = ((RehabApplication) getApplication()).screenAngle();
        runId = getIntent().getStringExtra("run_id");
        dailyPreviewMode = saved == null ? SessionStore.dailyPreviewMode(this) : saved.getString("daily_preview_mode", "keypoints");
        try { catalog = new JSONArray(getIntent().getStringExtra("catalog")); }
        catch (Exception e) { catalog = new JSONArray(); }
        voice = getSharedPreferences("rehab", MODE_PRIVATE).getBoolean("voice", true);
        cueAudio = new TrainingCueAudio(this);
        speech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && speech != null) {
                int availability = speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
                speechReady = availability != TextToSpeech.LANG_MISSING_DATA && availability != TextToSpeech.LANG_NOT_SUPPORTED;
                if (speechReady) speech.setSpeechRate(1.0f);
            }
            getSharedPreferences("rehab", MODE_PRIVATE).edit().putBoolean("tts_zh_available", speechReady || cueAudio.available()).apply();
        });
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { speechFinished(id); }
            @Override public void onError(String id) { speechFinished(id); }
            @Override public void onStop(String id, boolean interrupted) { speechFinished(id); }
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
        closeRestTeaching();
        if (restTick != null) handler.removeCallbacks(restTick);
        closeCamera();
        state = "preparing";
        stopSpeech();
        sessionId = ""; lastResult = null; targetAnnounced = false; pendingHint = ""; recordNonstandard = false;
        item = run.optJSONArray("items").optJSONObject(itemIndex);
        teaching = teaching(item.optString("action"));
        setContentView(R.layout.activity_training);
        angleButton = trainingButton("屏幕角度", false, v -> showScreenAngle());
        angleButton.setId(R.id.training_screen_angle);
        ((LinearLayout) findViewById(R.id.training_toolbar)).addView(angleButton);
        updateScreenAngle();
        updateJourney(0);
        ((TextView) findViewById(R.id.training_title)).setText((itemIndex + 1) + " / "
            + run.optJSONArray("items").length() + "   " + teaching.optString("name"));
        ((TextView) findViewById(R.id.training_patient)).setText(patient.optString("name"));
        ((TextView) findViewById(R.id.training_side)).setText("患侧 · " + RehabApiClient.side(patient));
        TextView cooperation = findViewById(R.id.training_cooperation);
        cooperation.setText(teaching.optString("cooperation"));
        cooperation.setVisibility(cooperation.length() == 0 ? View.GONE : View.VISIBLE);
        countView = findViewById(R.id.training_count);
        passedView = findViewById(R.id.training_passed);
        failedView = findViewById(R.id.training_failed);
        detailView = findViewById(R.id.training_detail);
        phaseView = findViewById(R.id.training_phase);
        hintView = findViewById(R.id.training_hint);
        fpsView = findViewById(R.id.training_fps);
        cameraStatus = findViewById(R.id.training_camera_status);
        framing = findViewById(R.id.training_framing);
        displayCounts(RehabApiClient.object("count", 0, "passed", 0));
        hintView.setText(framingInstruction());
        fpsView.setOnClickListener(v -> showDiagnostics());
        controls = findViewById(R.id.training_controls);
        LinearLayout options = UiComponents.row(this), actions = UiComponents.row(this);
        controls.addView(options, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(actions);
        if ("fist".equals(item.optString("action"))) {
            CheckBox recording = new CheckBox(this);
            recording.setText("保存不合格片段（无录音）"); recording.setTextSize(14); recording.setTextColor(getResources().getColor(R.color.training_text));
            recording.setBackgroundResource(R.drawable.bg_training_overlay);
            recording.setPadding(UiComponents.dp(this, 12), 0, UiComponents.dp(this, 16), 0);
            recording.setMinHeight(UiComponents.dp(this, 48));
            recording.setContentDescription("保存不合格动作及前后缓冲，供工作人员复核；本次可选，默认关闭");
            recording.setButtonTintList(ColorStateList.valueOf(getResources().getColor(R.color.training_mint)));
            recording.setOnCheckedChangeListener((button, checked) -> recordNonstandard = checked);
            options.addView(recording);
        }
        actions.addView(trainingButton("返回", false, v -> confirmExit()));
        primary = trainingButton("开始训练", true, v -> checkAndCountdown()); actions.addView(primary);
        adaptTrainingLayout();
        rtc = new WebRtcClient(this, findViewById(R.id.training_local), findViewById(R.id.training_remote),
                findViewById(R.id.training_skeleton), this);
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
        if (screenAngle.busy()) { hintView.setText("请等待屏幕角度调整完成，再开始训练。"); return; }
        if (rtc == null || !rtc.isPreviewReady()) { hintView.setText("摄像头尚未就绪，请确认权限并等待画面出现"); return; }
        primary.setEnabled(false);
        diagnosticRequested = SessionStore.staff(this) && getSharedPreferences("rehab", MODE_PRIVATE).getBoolean("next_remote_diagnostic", false);
        sessionPreviewMode = diagnosticRequested ? "diagnostic" : dailyPreviewMode;
        hintView.setText("正在检查服务与模型…");
        int ticket = ++requestGeneration;
        api.get("/health", (data, error) -> {
            if (!foreground || ticket != requestGeneration || !"preparing".equals(state)) return;
            if (error != null) { preparationError(error); return; }
            JSONObject health = (JSONObject) data;
            JSONObject capabilities = health.optJSONObject("capabilities");
            String capabilityError = previewCapabilityError(capabilities, sessionPreviewMode);
            if (capabilityError != null) { preparationError(capabilityError); return; }
            if (recordNonstandard && (capabilities == null || !capabilities.optBoolean("nonstandard_clips_v1"))) {
                preparationError("服务端尚不支持不合格片段记录，请升级服务端或关闭片段记录"); return;
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

    private static String previewCapabilityError(JSONObject capabilities, String mode) {
        if ("annotated".equals(mode)) return capabilities != null && capabilities.optBoolean("annotated_video_v1")
                ? null : "服务端尚不支持远端标注，请升级服务端或由工作人员选择本地骨架模式";
        if (capabilities == null || !capabilities.optBoolean("local_skeleton_v1") || !capabilities.optBoolean("clock_sync_v1"))
            return "服务端尚不支持本地骨架画面，请联系工作人员升级服务端";
        if ("diagnostic".equals(mode) && (!capabilities.optBoolean("diagnostic_video") || !capabilities.optBoolean("diagnostic_png")))
            return "服务端尚不支持远端诊断，请升级服务端或使用日常训练模式";
        return null;
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
        api.post("/api/v1/sessions", RehabApiClient.object("run_id", runId, "item_index", itemIndex, "record_nonstandard", recordNonstandard), (data, error) -> {
            if (error != null) { if (foreground && ticket == requestGeneration) onError(error); return; }
            sessionId = ((JSONObject) data).optString("id");
            if (!foreground || ticket != requestGeneration || !"connecting".equals(state)) { interruptRecord(); return; }
            rtc.start(api.base(), RehabApiClient.object("session_id", sessionId,
                    "preview_mode", sessionPreviewMode));
            handler.postDelayed(() -> {
                if (foreground && "connecting".equals(state) && ticket == requestGeneration) onError("视频连接超时，请检查网络后重新开始");
            }, 20000);
        });
    }

    private void liveControls() {
        controls.removeAllViews();
        LinearLayout switches = UiComponents.row(this);
        preview = trainingButton("本地画面", false, v -> rtc.togglePreview());
        mute = trainingButton(voice ? "静音" : "开启语音", false, v -> {
            voice = !voice;
            getSharedPreferences("rehab", MODE_PRIVATE).edit().putBoolean("voice", voice).apply();
            mute.setText(voice ? "静音" : "开启语音");
            if (!voice) stopSpeech();
        });
        switches.addView(preview); switches.addView(mute);
        MaterialButton info = trainingButton("入镜要求", false, v -> new AlertDialog.Builder(this)
            .setTitle("入镜要求").setMessage(framingInstruction()).setPositiveButton("关闭", null).show());
        info.setText(""); info.setIconResource(R.drawable.ic_training_info); info.setIconTint(null);
        info.setIconPadding(0); info.setContentDescription("查看入镜要求");
        switches.addView(info);
        controls.addView(switches, new LinearLayout.LayoutParams(0, -2, 1));
        diagnosticButton = null;
        if (diagnosticRequested && SessionStore.staff(this)) {
            diagnosticButton = trainingButton("工作人员诊断", false, v -> showRemoteDiagnostic());
            switches.addView(diagnosticButton);
        }
        onPreviewChanged(rtc.currentPreview(), rtc.isAnnotatedMode() ? rtc.hasReturnVideo() : rtc.hasDiagnosticVideo());
        LinearLayout actions = UiComponents.row(this);
        controls.addView(actions);
        primary = trainingButton("结束本项", false, v -> finishItem());
        primary.setBackgroundTintList(ColorStateList.valueOf(UiComponents.DANGER));
        MaterialButton safe = trainingButton("因不适结束\n保存部分结果，结束整次训练", false, v -> {
            if (!"training".equals(state)) return;
            discomfortExit = true; flowAbandoned = true; state = "finishing";
            stopSpeech();
            controls.setEnabled(false); primary.setEnabled(false); hintView.setText("已停止动作，正在保存部分结果…");
            rtc.stopForDiscomfort();
            handler.postDelayed(() -> {
                if (!"finishing".equals(state)) return;
                api.post("/api/v1/sessions/" + sessionId + "/interrupt", RehabApiClient.object("reason", "user_discomfort"), (data, error) -> { closeCamera(); recoverSummary(0); });
            }, 3000);
        });
        android.text.SpannableString safeLabel = new android.text.SpannableString(safe.getText());
        safeLabel.setSpan(new android.text.style.RelativeSizeSpan(.65f), safeLabel.toString().indexOf('\n') + 1, safeLabel.length(), 0);
        safe.setText(safeLabel); safe.setTextColor(getResources().getColor(R.color.training_danger));
        safe.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
        safe.setMinHeight(UiComponents.dp(this, 56)); safe.setMinimumHeight(UiComponents.dp(this, 56));
        safe.setPadding(safe.getPaddingLeft(), UiComponents.dp(this, 8), safe.getPaddingRight(), UiComponents.dp(this, 8));
        actions.addView(safe); actions.addView(primary);
        phaseView.setVisibility(View.VISIBLE);
        framing.setText(""); framing.setVisibility(View.GONE);
        adaptTrainingLayout();
    }

    private void adaptTrainingLayout() {
        int width = getResources().getConfiguration().screenWidthDp;
        boolean largeText = getResources().getConfiguration().fontScale > 1.2f;
        boolean compact = width < 1000 || largeText;
        boolean shortScreen = getResources().getConfiguration().screenHeightDp < 600;
        ((TextView) findViewById(R.id.training_title)).setTextSize(compact ? 20 : 26);
        countView.setMaxWidth(UiComponents.dp(this, compact ? 120 : 108));
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(countView, 32, shortScreen || largeText ? 64 : 96, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
        passedView.setTextSize(shortScreen ? 36 : 44); failedView.setTextSize(shortScreen ? 36 : 44);
        findViewById(R.id.training_journey).setVisibility(compact ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams stats = (FrameLayout.LayoutParams) findViewById(R.id.training_stats_group).getLayoutParams();
        stats.topMargin = UiComponents.dp(this, shortScreen ? 146 : 194);
        stats.width = UiComponents.dp(this, compact ? 184 : 172);
        FrameLayout.LayoutParams cue = (FrameLayout.LayoutParams) findViewById(R.id.training_cue).getLayoutParams();
        cue.width = UiComponents.dp(this, Math.min(largeText ? 560 : 480, Math.max(240, width - (compact ? 440 : 560))));
        cue.topMargin = UiComponents.dp(this, "preparing".equals(state) ? (shortScreen ? 124 : 140) : 104);
        hintView.setTextSize(compact ? 20 : 26);
        LinearLayout goalHeader = (LinearLayout) detailView.getParent();
        goalHeader.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        TextView targetLabel = findViewById(R.id.training_target_label);
        targetLabel.setLayoutParams(new LinearLayout.LayoutParams(compact ? -1 : 0, -2, compact ? 0 : 1));
        findViewById(R.id.training_goal).getLayoutParams().width = UiComponents.dp(this, width < 1000 ? 160 : 220);
        controls.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < controls.getChildCount(); i++) {
            LinearLayout group = (LinearLayout) controls.getChildAt(i);
            group.setGravity(i == 0 ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(compact ? -1 : i == 0 ? 0 : -2, -2, compact ? 0 : i == 0 ? 1 : 0);
            size.topMargin = i == 1 && compact ? UiComponents.dp(this, 8) : 0;
            group.setLayoutParams(size);
        }
        View statsGroup = findViewById(R.id.training_stats_group), footer = controls;
        footer.post(() -> {
            int available = footer.getTop() - statsGroup.getTop() - UiComponents.dp(this, 16);
            if (available > 0 && statsGroup.getHeight() > available) {
                statsGroup.getLayoutParams().height = available; statsGroup.requestLayout();
            }
        });
    }

    private void displayCounts(JSONObject data) {
        int total = data.optInt("count"), passed = data.optInt("passed"), target = item.optInt("target_reps");
        countView.setText(String.valueOf(total));
        countView.setContentDescription("完成总次数 " + total + " 次");
        passedView.setText(String.valueOf(passed)); passedView.setContentDescription("合格次数 " + passed);
        int failed = Math.max(0, total - passed);
        failedView.setText(String.valueOf(failed)); failedView.setContentDescription("不合格次数 " + failed);
        TextView targetLabel = findViewById(R.id.training_target_label);
        targetLabel.setText("合格目标");
        android.widget.ProgressBar progress = findViewById(R.id.training_progress);
        int progressCount = RehabApiClient.progressCount(data);
        if (!data.has("progress_count") && !"fist".equals(item.optString("action")))
            targetLabel.setText("完成目标");
        else progressCount = passed;
        detailView.setText(progressCount + " / " + target + " 次");
        progress.setProgress(Math.min(100, progressCount * 100 / Math.max(1, target)));
        progress.setContentDescription(targetLabel.getText() + " " + detailView.getText());
    }

    private void updateJourney(int current) {
        String[] labels = {"训练准备", "动作练习", "训练小结", "感受记录"};
        android.text.SpannableString text = new android.text.SpannableString("01  训练准备    →    02  动作练习    →    03  训练小结    →    04  感受记录");
        int start = text.toString().indexOf(labels[current]) - 4;
        text.setSpan(new android.text.style.ForegroundColorSpan(getResources().getColor(R.color.training_mint)), start, start + 4 + labels[current].length(), 0);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, start + 4 + labels[current].length(), 0);
        ((TextView) findViewById(R.id.training_journey)).setText(text);
    }

    private MaterialButton trainingButton(String label, boolean accent, View.OnClickListener click) {
        MaterialButton button = UiComponents.compact(this, label, accent, click);
        int mint = getResources().getColor(R.color.training_mint), text = getResources().getColor(R.color.training_text);
        int overlay = getResources().getColor(R.color.training_overlay);
        int[][] states = {new int[]{-android.R.attr.state_enabled}, new int[]{}};
        button.setTextColor(new ColorStateList(states, new int[]{getResources().getColor(R.color.training_muted), accent ? UiComponents.FOREST : text}));
        button.setBackgroundTintList(new ColorStateList(states, new int[]{overlay, accent ? mint : overlay}));
        button.setRippleColor(ColorStateList.valueOf(0x30ffffff));
        button.setStrokeColor(ColorStateList.valueOf(getResources().getColor(R.color.training_edge)));
        button.setTextSize(18); button.setMinWidth(UiComponents.dp(this, 48)); button.setMinimumWidth(UiComponents.dp(this, 48));
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) button.getLayoutParams();
        size.height = UiComponents.dp(this, 56); size.setMargins(UiComponents.dp(this, 4), 0, UiComponents.dp(this, 4), 0);
        button.setLayoutParams(size);
        return button;
    }

    private String framingInstruction() {
        return teaching.optString("framing", "双手手掌和手指完整入镜");
    }

    private void finishItem() {
        if (!"training".equals(state)) return;
        stopSpeech();
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
            speak("fist".equals(item.optString("action")) ? "轻轻握起，慢慢松开，不舒服就停下" : "训练开始", true);
        } else if ("result".equals(type) && "training".equals(state)) {
            lastResult = data; lastResultAt = SystemClock.elapsedRealtime();
            int count = RehabApiClient.progressCount(data), target = item.optInt("target_reps");
            displayCounts(data);
            phaseView.setText(phase(data.optString("phase")) + " · " + data.optString("tracking", "等待跟踪"));
            String hint = primaryHint(data);
            hintView.setText(hint);
            JSONObject feedback = data.optJSONObject("feedback");
            String level = feedback == null ? "normal" : feedback.optString("level", "normal");
            hintView.setTextColor(getResources().getColor("severe".equals(level) ? R.color.training_danger
                : "mild".equals(level) ? R.color.training_warning : R.color.training_text));
            fpsView.setText(String.format(Locale.CHINA, "识别 FPS：%.1f", data.optDouble("processed_fps")));
            if (feedback != null) {
                if ("severe".equals(level) && feedback.optBoolean("voice_ready"))
                    trySpeak(feedback.optString("text"), feedback.optString("code"), false);
            } else if (!hint.equals(pendingHint)) { pendingHint = hint; hintSince = lastResultAt; }
            else if (lastResultAt - hintSince >= 2000) speak(hint, false);
            if (count >= target && !targetAnnounced) {
                targetAnnounced = true;
                speak("已达到次数目标，可点击结束本项", true);
            }
            if (targetAnnounced) framing.setText("已达到次数目标，请按自身情况点击“结束本项”");
            JSONObject recording = data.optJSONObject("recording");
            if (recording != null && recording.optBoolean("enabled")) framing.setText(
                (targetAnnounced ? "已达到次数目标，请按自身情况结束训练" : teaching.optString("framing")) + "\n片段：" + recording.optString("text"));
            framing.setVisibility(framing.length() == 0 ? View.GONE : View.VISIBLE);
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
        JSONObject feedback = data.optJSONObject("feedback");
        if (feedback != null && !feedback.optString("text").isEmpty()) return feedback.optString("text");
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
        closeAngleDialog(); angleButton = null;
        LinearLayout outer = UiComponents.column(this);
        outer.setBackgroundColor(getResources().getColor(R.color.rehab_background));
        int padding = Math.max(28, (getResources().getConfiguration().screenWidthDp - 1000) / 2);
        outer.setPadding(UiComponents.dp(this, padding), UiComponents.dp(this, 28), UiComponents.dp(this, padding), UiComponents.dp(this, 28));
        UiComponents.section(this, outer, heading, "");
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
        boolean reached = RehabApiClient.progressCount(summary) >= summary.optInt("target", 1);
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
        if (restTick != null) handler.removeCallbacks(restTick);
        closeRestTeaching(); closeAngleDialog(); angleButton = null;
        closeCamera(); state = "rest";
        JSONObject next = run.optJSONArray("items").optJSONObject(itemIndex);
        if (next == null) { showOverview(); return; }
        JSONObject definition = teaching(next.optString("action"));
        boolean narrow = getResources().getConfiguration().screenWidthDp < 650;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(UiComponents.BACKGROUND);
        ImageView backdrop = UiComponents.poster(this, next.optString("action"));
        backdrop.setAlpha(.06f); backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout body = UiComponents.column(this);
        int gutter = UiComponents.dp(this, narrow ? 24 : 40);
        body.setPadding(gutter, UiComponents.dp(this, 24), gutter, UiComponents.dp(this, 24));
        LinearLayout header = UiComponents.row(this);
        header.addView(UiComponents.heading(this, "休息一下"), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(UiComponents.text(this, "已完成 " + itemIndex + " / " + run.optJSONArray("items").length() + " 项", 16, UiComponents.MUTED));
        body.addView(header);
        android.widget.ProgressBar plan = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        plan.setMax(run.optJSONArray("items").length()); plan.setProgress(itemIndex);
        plan.setProgressTintList(ColorStateList.valueOf(UiComponents.PRIMARY));
        plan.setProgressBackgroundTintList(ColorStateList.valueOf(UiComponents.MINT));
        plan.setContentDescription("计划已完成 " + itemIndex + " 项，共 " + plan.getMax() + " 项");
        LinearLayout.LayoutParams progressSize = new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 6));
        progressSize.topMargin = UiComponents.dp(this, 16); progressSize.bottomMargin = UiComponents.dp(this, 20);
        body.addView(plan, progressSize);
        LinearLayout content = UiComponents.row(this); content.setGravity(Gravity.CENTER_VERTICAL);
        if (narrow) content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout previewColumn = UiComponents.column(this);
        previewColumn.addView(UiComponents.text(this, "倒计时", 18, UiComponents.MUTED));
        TextView timer = UiComponents.heading(this, "00:30"); timer.setId(R.id.training_rest_timer);
        timer.setTextSize(narrow ? 72 : 104); timer.setTextColor(UiComponents.PRIMARY);
        timer.setFontFeatureSettings("tnum"); timer.setContentDescription("休息剩余 30 秒");
        previewColumn.addView(timer);
        LinearLayout card = UiComponents.card(this); card.setPadding(0, 0, 0, 0); card.setClipToOutline(true);
        ImageView poster = UiComponents.poster(this, next.optString("action"));
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setContentDescription("下一动作：" + definition.optString("name") + "，查看动作讲解");
        card.addView(poster, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, narrow ? 180 : 240)));
        card.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            int height = (r - l) * 9 / 16;
            if (height > 0 && poster.getLayoutParams().height != height) { poster.getLayoutParams().height = height; poster.requestLayout(); }
        });
        MaterialButton teachingButton = UiComponents.button(this, "下一动作：" + definition.optString("name") + "  ›", false, v -> showRestTeaching(definition));
        teachingButton.setId(R.id.training_rest_teaching);
        teachingButton.setContentDescription("下一动作：" + definition.optString("name") + "，目标 " + next.optInt("target_reps") + " 次，查看动作讲解");
        poster.setOnClickListener(v -> teachingButton.performClick()); card.addView(teachingButton);
        previewColumn.addView(card);
        LinearLayout.LayoutParams previewSize = new LinearLayout.LayoutParams(narrow ? -1 : 0, -2, narrow ? 0 : 1.4f);
        previewSize.setMarginEnd(narrow ? 0 : UiComponents.dp(this, 64)); content.addView(previewColumn, previewSize);
        LinearLayout actions = UiComponents.column(this);
        MaterialButton nextButton = UiComponents.button(this, "跳过休息", true, v -> prepareItem());
        nextButton.setId(R.id.training_rest_skip); actions.addView(nextButton);
        MaterialButton add = UiComponents.button(this, "+20 秒", false, v -> {
            restDeadline = Math.max(restDeadline, SystemClock.elapsedRealtime()) + 20000;
            handler.removeCallbacks(restTick); restTick.run();
        }); add.setId(R.id.training_rest_add); actions.addView(add);
        actions.addView(UiComponents.button(this, "结束本次计划", false, v ->
            new AlertDialog.Builder(this).setTitle("结束本次计划？")
                .setMessage("未完成的动作将不再继续，已保存的训练记录会保留。")
                .setNegativeButton("继续休息", null).setPositiveButton("结束计划", (d, w) -> endRun()).show()));
        for (int i = 0; i < actions.getChildCount(); i++) {
            MaterialButton button = (MaterialButton) actions.getChildAt(i);
            button.setTextSize(22);
            LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) button.getLayoutParams();
            size.height = UiComponents.dp(this, 72); size.topMargin = UiComponents.dp(this, 16); button.setLayoutParams(size);
        }
        content.addView(actions, new LinearLayout.LayoutParams(narrow ? -1 : 0, -2, narrow ? 0 : 1));
        body.addView(content); root.addView(UiComponents.scroll(this, body), new FrameLayout.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels, UiComponents.dp(this, 1160)), -1, Gravity.CENTER));
        setContentView(root);
        restDeadline = SystemClock.elapsedRealtime() + 30000;
        restTick = new Runnable() {
            @Override public void run() {
                if (!foreground || !"rest".equals(state)) return;
                int seconds = Math.max(0, (int) Math.ceil((restDeadline - SystemClock.elapsedRealtime()) / 1000.0));
                timer.setText(String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60));
                timer.setContentDescription("休息剩余 " + seconds + " 秒");
                nextButton.setText(seconds > 0 ? "跳过休息" : "进入下一项准备");
                if (seconds > 0) handler.postDelayed(this, 250);
            }
        }; restTick.run();
    }

    private void showRestTeaching(JSONObject definition) {
        if (restTeachingDialog != null || !"rest".equals(state)) return;
        LinearLayout body = UiComponents.column(this);
        int padding = UiComponents.dp(this, 24); body.setPadding(padding, padding, padding, padding);
        body.addView(UiComponents.heading(this, definition.optString("name")));
        VideoView player = new VideoView(this);
        player.setId(R.id.training_rest_video);
        body.addView(player, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 240)));
        MediaController controller = new MediaController(this); controller.setAnchorView(player); player.setMediaController(controller);
        TextView status = UiComponents.text(this, "正在读取示范视频…", 14, UiComponents.MUTED); body.addView(status);
        JSONArray steps = definition.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) body.addView(UiComponents.text(this, (i + 1) + ". " + steps.optString(i), 16, UiComponents.INK));
        body.addView(UiComponents.button(this, "返回休息", true, v -> closeRestTeaching()));
        restTeachingDialog = new AlertDialog.Builder(this).setView(UiComponents.scroll(this, body)).create();
        restTeachingDialog.setOnDismissListener(d -> {
            player.stopPlayback(); controller.hide(); restTeachingDialog = null;
            if (restMedia != null) { restMedia.close(); restMedia = null; }
        });
        player.setOnPreparedListener(mp -> { mp.setVolume(0, 0); status.setVisibility(View.GONE); player.start(); });
        player.setOnErrorListener((mp, what, extra) -> { status.setText("示范视频暂不可用"); status.setVisibility(View.VISIBLE); return true; });
        restTeachingDialog.show();
        if (restTeachingDialog.getWindow() != null) {
            restTeachingDialog.getWindow().setBackgroundDrawable(UiComponents.background(this, android.graphics.Color.WHITE, 20));
            restTeachingDialog.getWindow().setLayout(Math.min(UiComponents.dp(this, 720), getResources().getDisplayMetrics().widthPixels - UiComponents.dp(this, 32)), -2);
        }
        restMedia = new TeachingMediaCache(this);
        restMedia.video(api.base(), definition, (cached, error) -> {
            if (restTeachingDialog == null || !foreground || !"rest".equals(state)) return;
            if (cached == null) { status.setText("示范视频暂不可用"); return; }
            if (!cached.item.optString("content_version").equals(definition.optString("content_version"))) {
                status.setText("教学已更新，请在动作库同步后观看"); return;
            }
            player.setVideoURI(Uri.fromFile(cached.video));
            if (error != null) status.setText("使用已缓存示范");
        }, message -> { });
    }

    private void closeRestTeaching() { if (restTeachingDialog != null) restTeachingDialog.dismiss(); }

    private void showOverview() {
        closeRestTeaching();
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
        if (!important) { trySpeak(text, text, false); return; }
        if (!voice || !foreground) return;
        pendingAnnouncement = text; announcementState = state;
        announcementExpires = SystemClock.elapsedRealtime() + 8000;
        handler.removeCallbacks(playAnnouncement); handler.post(playAnnouncement);
    }

    private boolean trySpeak(String text, String code, boolean announcement) {
        if (!voice || !foreground || text.isEmpty() || (!speechReady && !cueAudio.has(text))
            || (!announcement && !pendingAnnouncement.isEmpty())) return false;
        long now = SystemClock.elapsedRealtime();
        if (!speechPolicy.allows(code, now, speechInFlight || cueAudio.isPlaying() || (speechReady && speech.isSpeaking()), announcement)) return false;
        String id = "rehab_" + (++utteranceSequence);
        utteranceId = id; speechInFlight = true;
        boolean accepted = speechReady ? speech.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.ERROR
            : cueAudio.play(text, () -> speechFinished(id));
        if (!accepted) { speechInFlight = false; return false; }
        speechPolicy.spoken(code, now);
        return true;
    }

    private final Runnable playAnnouncement = new Runnable() {
        @Override public void run() {
            if (!foreground || !voice || !state.equals(announcementState) || SystemClock.elapsedRealtime() > announcementExpires) {
                pendingAnnouncement = ""; return;
            }
            if (trySpeak(pendingAnnouncement, "notice:" + pendingAnnouncement, true)) pendingAnnouncement = "";
            else handler.postDelayed(this, 250);
        }
    };

    private void speechFinished(String id) {
        handler.post(() -> { if (id.equals(utteranceId)) speechInFlight = false; });
    }

    private void stopSpeech() {
        pendingAnnouncement = ""; utteranceId = ""; speechInFlight = false;
        handler.removeCallbacks(playAnnouncement);
        if (cueAudio != null) cueAudio.stop();
        if (speech != null) speech.stop();
    }

    @Override public void onCameraStatus(String message) {
        if (cameraStatus != null && foreground) cameraStatus.setText(message);
        if ("preparing".equals(state) && primary != null && message.contains("已就绪")) {
            primary.setEnabled(true);
            hintView.setText(framingInstruction());
        }
    }
    @Override public void onPreviewChanged(String view, boolean alternateAvailable) {
        boolean annotated = rtc != null && rtc.isAnnotatedMode();
        boolean skeleton = "skeleton".equals(view);
        if (preview != null) {
            preview.setEnabled(!annotated || alternateAvailable);
            preview.setText(annotated ? ("annotated".equals(view) ? "本地画面" : "远端标注画面") : skeleton ? "本地画面" : "骨架画面");
            preview.setContentDescription("切换到" + preview.getText());
        }
        if (diagnosticButton != null) {
            diagnosticButton.setEnabled(alternateAvailable);
            diagnosticButton.setText(rtc != null && rtc.showingDiagnostic() ? "诊断中 · 返回训练画面" : "工作人员诊断");
        }
        if (cameraStatus != null) {
            cameraStatus.setVisibility(annotated ? View.VISIBLE : View.GONE);
            cameraStatus.setText("diagnostic".equals(view) ? "工作人员远端诊断"
                : "annotated".equals(view) ? "服务端标注视频" : skeleton ? "本地视频与骨架" : "本地视频");
        }
    }
    @Override public void onStats(String message) { diagnostics = message; }
    private void showDiagnostics() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("实时诊断")
                .setMessage(diagnostics).setPositiveButton("关闭", null);
        if (rtc != null) dialog.setNeutralButton(rtc.isExternalDisplayEnabled() ? "关闭副屏测试" : "开启副屏测试", (d, w) -> {
            if (rtc != null) android.widget.Toast.makeText(this,
                    rtc.setExternalDisplayEnabled(!rtc.isExternalDisplayEnabled()), android.widget.Toast.LENGTH_LONG).show();
        });
        dialog.show();
    }

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
        stopSpeech();
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
    private void updateScreenAngle() {
        if (angleButton != null) {
            angleButton.setText(screenAngle.busy() ? "角度调整中…" : screenAngle.error() != null ? "屏幕角度 · 重试" : "屏幕角度 · " + screenAngle.pitch());
            angleButton.setEnabled(!screenAngle.busy());
        }
        if (refreshAngleDialog != null) refreshAngleDialog.run();
    }

    private void showScreenAngle() {
        if (angleDialog != null || screenAngle.busy()) return;
        int[] selected = {screenAngle.pitch()};
        LinearLayout body = UiComponents.column(this);
        int padding = UiComponents.dp(this, 24); body.setPadding(padding, padding, padding, padding);
        body.addView(UiComponents.heading(this, "屏幕角度"));
        body.addView(UiComponents.text(this, "选择适合观看的俯仰档位，启动默认 10。\n调整时请暂缓动作，完成后重新确认手部入镜。", 15, UiComponents.MUTED));
        TextView selection = UiComponents.heading(this, "");
        selection.setPadding(0, UiComponents.dp(this, 20), 0, UiComponents.dp(this, 8)); body.addView(selection);
        SeekBar slider = new SeekBar(this);
        slider.setId(R.id.training_screen_angle_slider);
        slider.setMax(RobotScreenAngle.MAX_PITCH); slider.setProgress(selected[0]);
        slider.setContentDescription("屏幕俯仰档位，0 至 30");
        slider.setThumbTintList(ColorStateList.valueOf(UiComponents.PRIMARY));
        slider.setProgressTintList(ColorStateList.valueOf(UiComponents.PRIMARY));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(UiComponents.MINT));
        slider.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        body.addView(slider, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 56)));
        LinearLayout limits = UiComponents.row(this);
        limits.addView(UiComponents.text(this, "0", 14, UiComponents.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        limits.addView(UiComponents.text(this, "30", 14, UiComponents.MUTED)); body.addView(limits);
        TextView status = UiComponents.text(this, "", 15, UiComponents.PRIMARY);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, UiComponents.dp(this, 16), 0, UiComponents.dp(this, 8)); body.addView(status);
        MaterialButton apply = UiComponents.button(this, "应用角度", true, v -> screenAngle.apply(selected[0])); body.addView(apply);
        body.addView(UiComponents.button(this, "关闭", false, v -> angleDialog.dismiss()));
        refreshAngleDialog = () -> {
            selection.setText("已选档位 " + selected[0]); slider.setEnabled(!screenAngle.busy());
            status.setText(screenAngle.status()); status.setTextColor(screenAngle.error() == null ? UiComponents.PRIMARY : UiComponents.DANGER);
            apply.setText(screenAngle.busy() ? "正在调整…" : "应用角度 " + selected[0]); apply.setEnabled(!screenAngle.busy());
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) { selected[0] = value; refreshAngleDialog.run(); }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
        });
        angleDialog = new AlertDialog.Builder(this).setView(UiComponents.scroll(this, body)).create();
        angleDialog.setOnDismissListener(d -> { angleDialog = null; refreshAngleDialog = null; });
        refreshAngleDialog.run(); angleDialog.show();
        if (angleDialog.getWindow() != null) {
            angleDialog.getWindow().setBackgroundDrawable(UiComponents.background(this, android.graphics.Color.WHITE, 20));
            angleDialog.getWindow().setLayout(Math.min(UiComponents.dp(this, 480), getResources().getDisplayMetrics().widthPixels - UiComponents.dp(this, 32)), -2);
        }
    }

    private void closeAngleDialog() { if (angleDialog != null) angleDialog.dismiss(); }

    @Override protected void onResume() {
        super.onResume(); foreground = true; UiComponents.immersive(this);
        screenAngle.addListener(angleListener);
        if (!SessionStore.require(this)) return;
        if ("ready_to_prepare".equals(state)) prepareItem();
        else if ("ready_overview".equals(state)) showOverview();
        else if ("preparing".equals(state)) openPreview();
        else if ("interrupted".equals(state)) showInterrupted("本次训练已停止，请查询结果或返回重新开始");
    }
    @Override protected void onPause() {
        closeRestTeaching();
        screenAngle.removeListener(angleListener); closeAngleDialog();
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
        stopSpeech();
        super.onPause();
    }
    @Override protected void onDestroy() {
        closeRestTeaching();
        handler.removeCallbacksAndMessages(null); closeCamera(); api.close();
        if (cueAudio != null) cueAudio.stop();
        if (speech != null) { speech.stop(); speech.shutdown(); }
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putString("session_id", sessionId); saved.putString("daily_preview_mode", dailyPreviewMode); super.onSaveInstanceState(saved);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results); permissionRequest = false;
        if (request == 202 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openPreview();
        else if (request == 202) { cameraPermissionDenied = true; hintView.setText("需要摄像头权限；请在系统设置中允许后重新进入"); primary.setEnabled(false); }
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) UiComponents.immersive(this); }
}
