package com.robint.feature.home;

import com.robint.R;
import com.robint.data.local.SessionStore;
import com.robint.data.local.TeachingMediaCache;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.feedback.FeedbackActivity;
import com.robint.feature.records.TrendsActivity;
import com.robint.feature.training.TrainingActivity;
import com.robint.media.Subtitles;
import com.robint.ui.component.IconDrawable;
import com.robint.ui.component.UiComponents;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class HomeActivity extends AppCompatActivity {
    private RehabApiClient api;
    private TeachingMediaCache media;
    private SharedPreferences preferences;
    private FrameLayout content;
    private TextView title, notice;
    private android.widget.Button patientButton;
    private final List<MaterialButton> navigation = new ArrayList<>();
    private JSONArray actions = new JSONArray();
    private JSONObject patient;
    private VideoView video;
    private String page = "训练";
    private int generation;
    private int historyDays = 7, historyOffset;
    private int patientSearchGeneration;
    private boolean foreground;
    private boolean creatingRun;
    private String reportToOpen;
    private JSONObject planDraft;
    private String draftDate;
    private final boolean[] draftDays = new boolean[7];
    private final android.os.Handler subtitleHandler = new android.os.Handler();
    private boolean offlineTeaching;
    private String libraryFilter = "全部动作", libraryQuery = "";
    private boolean archivedPatients;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiComponents.immersive(this);
        setContentView(R.layout.activity_home);
        preferences = getSharedPreferences("rehab", MODE_PRIVATE);
        offlineTeaching = getIntent().getBooleanExtra("offline_teaching", false);
        api = new RehabApiClient(this);
        media = new TeachingMediaCache(this);
        content = findViewById(R.id.rehab_content);
        title = findViewById(R.id.rehab_page_title);
        notice = findViewById(R.id.rehab_notice);
        patientButton = findViewById(R.id.rehab_patient_button);
        patientButton.setOnClickListener(v -> { if (!offlineTeaching && SessionStore.staff(this)) choosePatient(); });
        try (InputStream input = getAssets().open("rehab_actions.json")) {
            byte[] bytes = new byte[input.available()];
            int read = input.read(bytes);
            actions = new JSONArray(new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { notice.setText("教学内容尚未安装"); }
        LinearLayout rail = findViewById(R.id.rehab_navigation);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 850 && getResources().getConfiguration().screenHeightDp >= 600;
        boolean portrait = UiComponents.portrait(this);
        MaterialButton patientChip = (MaterialButton) patientButton;
        patientChip.setStateListAnimator(null); patientChip.setElevation(0);
        patientChip.setAllCaps(false); patientChip.setLetterSpacing(0); patientChip.setCornerRadius(UiComponents.dp(this, 28));
        patientChip.setStrokeWidth(UiComponents.dp(this, 1)); patientChip.setStrokeColor(android.content.res.ColorStateList.valueOf(UiComponents.BORDER));
        patientChip.setIcon(new IconDrawable("users", UiComponents.PRIMARY)); patientChip.setIconTint(android.content.res.ColorStateList.valueOf(UiComponents.PRIMARY));
        patientChip.setIconSize(UiComponents.dp(this, 20));
        if (portrait) {
            LinearLayout shell = findViewById(R.id.rehab_shell), workspace = findViewById(R.id.rehab_workspace);
            shell.setOrientation(LinearLayout.VERTICAL); shell.removeView(rail);
            workspace.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
            rail.setOrientation(LinearLayout.HORIZONTAL); rail.setPadding(4, 4, 4, 4);
            shell.addView(rail, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 76)));
            LinearLayout topbar = findViewById(R.id.rehab_topbar);
            topbar.getLayoutParams().height = UiComponents.dp(this, 72); topbar.setPadding(UiComponents.dp(this, 18), 0, UiComponents.dp(this, 18), 0);
            title.setTextSize(13); patientChip.setTextSize(13); patientChip.setMaxWidth(UiComponents.dp(this, 196));
        } else if (!wide) {
            rail.getLayoutParams().width = UiComponents.dp(this, 80);
            rail.setPadding(UiComponents.dp(this, 8), UiComponents.dp(this, 12), UiComponents.dp(this, 8), UiComponents.dp(this, 12));
        }
        TextView dateLabel = findViewById(R.id.rehab_date);
        dateLabel.setText(new SimpleDateFormat("yyyy年M月d日  EEEE", Locale.CHINA).format(new Date()));
        if (!wide) dateLabel.setVisibility(View.GONE);
        LinearLayout brand = UiComponents.row(this);
        TextView mark = UiComponents.text(this, "", 20, Color.WHITE);
        mark.setBackground(UiComponents.background(this, 0xffd9e8ce, 14));
        IconDrawable hand = new IconDrawable("hand", UiComponents.FOREST); hand.setBounds(0, 0, UiComponents.dp(this, 26), UiComponents.dp(this, 26));
        mark.setCompoundDrawables(hand, null, null, null); mark.setPadding(UiComponents.dp(this, 7), 0, 0, 0); mark.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(mark, new LinearLayout.LayoutParams(UiComponents.dp(this, 40), UiComponents.dp(this, 40)));
        if (wide) {
            LinearLayout name = UiComponents.column(this); name.setPadding(UiComponents.dp(this, 10), 0, 0, 0);
            TextView nameText = UiComponents.heading(this, "复健"); nameText.setTextColor(Color.WHITE); nameText.setTextSize(27);
            name.addView(nameText); name.addView(UiComponents.text(this, "REHAB TOGETHER", 8, UiComponents.NAV_TEXT)); brand.addView(name);
        }
        brand.setGravity(Gravity.CENTER); if (!portrait) rail.addView(brand, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, wide ? 132 : 76)));
        if (wide) { TextView label = UiComponents.text(this, "我的康复空间", 12, UiComponents.NAV_TEXT); label.setPadding(12, 8, 0, 16); rail.addView(label); }
        LinearLayout links = UiComponents.column(this);
        if (portrait) {
            links.setOrientation(LinearLayout.HORIZONTAL);
            rail.addView(links, new LinearLayout.LayoutParams(-1, -1));
        } else {
            android.widget.ScrollView navScroll = UiComponents.scroll(this, links);
            navScroll.setVerticalScrollBarEnabled(false); rail.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        if (SessionStore.staff(this)) page = "患者管理";
        if (offlineTeaching) page = "动作库";
        for (String label : offlineTeaching ? new String[]{"动作库"} : SessionStore.staff(this) && !portrait ? new String[]{"训练", "训练计划", "动作库", "训练记录", "设置", "患者管理", "人工复核"} : new String[]{"训练", "训练计划", "动作库", "训练记录", "设置"}) {
            MaterialButton button = UiComponents.button(this, "训练".equals(label) ? "今日康复" : label, false, v -> navigate(label));
            button.setMinWidth(0); button.setMinimumWidth(0);
            button.setTag(label); button.setStrokeWidth(0); button.setTextSize(wide ? 16 : 12);
            button.setPadding(UiComponents.dp(this, wide ? 14 : 2), 0, UiComponents.dp(this, wide ? 10 : 2), 0);
            String icon = "训练".equals(label) ? "home" : "训练计划".equals(label) ? "calendar" : "动作库".equals(label) ? "hand" : "训练记录".equals(label) ? "chart" : "设置".equals(label) ? "settings" : "患者管理".equals(label) ? "users" : "file";
            button.setIcon(new IconDrawable(icon, UiComponents.NAV_TEXT)); button.setIconTint(android.content.res.ColorStateList.valueOf(UiComponents.NAV_TEXT));
            button.setIconSize(UiComponents.dp(this, 22)); button.setIconPadding(UiComponents.dp(this, wide ? 12 : 2));
            button.setGravity(wide ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
            if (!wide) button.setIconGravity(MaterialButton.ICON_GRAVITY_TOP);
            LinearLayout.LayoutParams navSize = new LinearLayout.LayoutParams(portrait ? 0 : -1, UiComponents.dp(this, wide ? 54 : 64), portrait ? 1 : 0);
            if (wide) navSize.bottomMargin = UiComponents.dp(this, 9);
            button.setLayoutParams(navSize); button.setContentDescription("训练".equals(label) ? "今日康复" : label);
            button.setMaxLines(1);
            navigation.add(button);
            links.addView(button);
        }
        TextView role = UiComponents.text(this, offlineTeaching ? "离线教学" : SessionStore.staff(this) ? "工作人员模式" : "患者空间", wide ? 14 : 11, UiComponents.NAV_TEXT);
        role.setGravity(Gravity.CENTER); if (!portrait) rail.addView(role, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 48)));
        if (wide) { TextView station = UiComponents.text(this, "机构内康复训练\n局域网 · 共享设备", 11, UiComponents.NAV_TEXT); station.setGravity(Gravity.CENTER); rail.addView(station); }
        if (state != null) page = state.getString("page", "训练");
        else if (!offlineTeaching && getIntent().hasExtra("page")) page = getIntent().getStringExtra("page");
        reportToOpen = getIntent().getStringExtra("open_run");
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        UiComponents.immersive(this);
        if (offlineTeaching) {
            try { actions = new JSONArray(preferences.getString("action_catalog_" + api.base(), "[]")); } catch (Exception ignored) {}
            patientButton.setText("离线教学 · 返回登录"); patientButton.setOnClickListener(v -> finish());
            navigate("动作库"); notice.setText("离线教学：仅可观看缓存素材，患者、计划与记录需登录联网后查看。"); return;
        }
        if (!SessionStore.require(this)) return;
        if (!SessionStore.staff(this)) preferences.edit().putString("patient_id", preferences.getString("auth_patient", "")).apply();
        api.get("/api/v1/auth/me", (data, error) -> { if (error != null && foreground) notice.setText(error + "；离线时仅可观看已缓存教学"); });
        refreshPatient();
        String cache = preferences.getString("action_catalog_" + api.base(), "");
        try { if (!cache.isEmpty()) actions = new JSONArray(cache); } catch (Exception ignored) { }
        final String catalogBase = api.base();
        if (!api.base().isEmpty()) api.getArray("/api/v1/actions", (data, error) -> {
            if (!catalogBase.equals(api.base())) return;
            if (error == null && data instanceof JSONArray && ((JSONArray) data).length() == 6) {
                boolean changed = !actions.toString().equals(data.toString());
                actions = (JSONArray) data;
                preferences.edit().putString("action_catalog_" + api.base(), actions.toString()).apply();
                if (changed && foreground && "动作库".equals(page) && video == null) showLibrary();
            }
        });
    }

    private void refreshPatient() {
        String identity = preferences.getString("patient_id", "");
        final String patientBase = api.base();
        if (identity.isEmpty()) { patient = null; updatePatientBadge(); navigate(page); return; }
        api.get("/api/v1/patients/" + identity, (data, error) -> {
            if (!foreground || !identity.equals(preferences.getString("patient_id", "")) || !patientBase.equals(api.base())) return;
            if (error == null && data instanceof JSONObject) {
                patient = (JSONObject) data;
            } else {
                patient = null;
                if (error == null) preferences.edit().remove("patient_id").apply();
            }
            updatePatientBadge();
            if (reportToOpen != null) {
                String id = reportToOpen; reportToOpen = null; showRun(id);
            } else navigate(page);
            if (error != null) notice.setText(error);
        });
    }

    private void updatePatientBadge() {
        patientButton.setText(patient == null ? "选择患者" : patient.optString("name") + " · " + RehabApiClient.side(patient)
            + (patient.optBoolean("archived") ? " · 已归档" : "") + (SessionStore.staff(this) ? " ▾" : ""));
    }

    private LinearLayout begin(String heading) {
        setRequestedOrientation(heading.contains("编辑") || heading.contains("新建") || heading.contains("配置训练") ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        generation++;
        stopVideo();
        title.setText("康复空间  ›  " + ("训练".equals(heading) ? "今日康复" : heading));
        notice.setText(patient == null ? "请先选择患者，训练数据将按患者独立保存" :
            patient.optString("code") + " · " + RehabApiClient.side(patient) + "患侧 · 前臂 " + patient.optDouble("forearm_cm") + " cm");
        content.removeAllViews();
        LinearLayout body = UiComponents.column(this);
        int padding = UiComponents.dp(this, UiComponents.portrait(this) ? 18 : getResources().getConfiguration().screenWidthDp >= 1150 ? 38 : 24);
        body.setPadding(padding, UiComponents.dp(this, 26), padding, UiComponents.dp(this, 32));
        if (!"训练".equals(heading)) UiComponents.section(this, body, heading, pageSubtitle(heading));
        body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        content.addView(UiComponents.scroll(this, body), new FrameLayout.LayoutParams(-1, -1));
        if (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
            content.setAlpha(.5f); content.animate().alpha(1).setDuration(160).start();
        }
        return body;
    }

    private void navigate(String destination) {
        page = destination;
        for (MaterialButton button : navigation) {
            boolean selected = destination.equals(button.getTag());
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? 0xff2c5146 : UiComponents.FOREST));
            button.setTextColor(selected ? Color.WHITE : UiComponents.NAV_TEXT);
            button.setSelected(selected);
            button.setIconTint(android.content.res.ColorStateList.valueOf(selected ? Color.WHITE : UiComponents.NAV_TEXT));
        }
        if ("患者管理".equals(page) && SessionStore.staff(this)) showPatientManagement();
        else if ("动作库".equals(page)) showLibrary();
        else if ("训练记录".equals(page)) { historyOffset = 0; showHistory(); }
        else if ("设置".equals(page)) showSettings();
        else if ("训练计划".equals(page)) showPlan();
        else if ("每周日程".equals(page)) showSchedule();
        else if ("设备与服务".equals(page) && SessionStore.staff(this)) showDevice();
        else if ("隐私与使用告知".equals(page)) showPrivacy();
        else if (SessionStore.staff(this) && ("人工复核".equals(page) || "备份与恢复".equals(page) || "操作日志".equals(page))) showUnconnected(page);
        else showHome();
    }

    private String pageSubtitle(String heading) {
        if (heading.contains("教学")) return "先看清示范，再跟着自己的节奏慢慢练习。";
        if (heading.contains("患者")) return "管理患者档案，核对编号与患侧，为每个人安排合适的练习。";
        if (heading.contains("计划")) return "由工作人员安排动作与目标，计划调整不会改变过去的记录。";
        if (heading.contains("记录") || heading.contains("详情")) return "回顾每一次练习，完成、达标与算法判定分别呈现。";
        if ("动作库".equals(heading)) return "看清步骤，再开始练习。六个动作均附原有教学视频。";
        if ("设置".equals(heading)) return "让声音、设备和操作更适合你。";
        return "机构内康复辅助 · 按自身节奏完成。";
    }

    private boolean needPatient(LinearLayout body) {
        if (patient != null && !patient.optBoolean("archived")) return false;
        LinearLayout card = UiComponents.card(this);
        if (patient != null) {
            card.addView(UiComponents.heading(this, "当前患者已归档"));
            card.addView(UiComponents.text(this, "历史记录保留；恢复患者后可以继续安排新的训练。", 18, UiComponents.MUTED));
            card.addView(UiComponents.button(this, "查看训练记录", true, v -> navigate("训练记录")));
            if (SessionStore.staff(this)) {
                card.addView(UiComponents.button(this, "编辑并恢复患者", false, v -> editPatient(patient)));
                card.addView(UiComponents.button(this, "选择其他患者", false, v -> choosePatient()));
            }
            body.addView(card); return true;
        }
        card.addView(UiComponents.heading(this, "为这次训练选择患者"));
        card.addView(UiComponents.text(this, "选择后可查看专属计划、保存训练结果。首次使用请先在设置中连接服务。", 18, UiComponents.MUTED));
        if (SessionStore.staff(this)) card.addView(UiComponents.button(this, "选择或新建患者", true, v -> choosePatient()));
        else card.addView(UiComponents.text(this, "未能读取本人档案，请检查连接或联系工作人员。", 18, UiComponents.DANGER));
        body.addView(card);
        return true;
    }

    private void showHome() {
        LinearLayout body = begin("训练");
        UiComponents.section(this, body, patient == null ? "每一次练习，都是向前一步" : patient.optString("name") + "，今天也一起加油", "放松双手，按自己的节奏慢慢开始。");
        if (needPatient(body)) return;
        FrameLayout heroBox = new FrameLayout(this);
        heroBox.setBackground(UiComponents.background(this, UiComponents.MINT, 24)); heroBox.setClipToOutline(true);
        LinearLayout heroLayout = UiComponents.row(this); heroLayout.setGravity(Gravity.TOP);
        LinearLayout hero = UiComponents.column(this); int heroPadding = UiComponents.dp(this, UiComponents.portrait(this) ? 24 : 30); hero.setPadding(heroPadding, heroPadding, heroPadding, heroPadding);
        hero.addView(UiComponents.text(this, "今日训练计划", 14, UiComponents.PRIMARY));
        TextView greeting = UiComponents.heading(this, "每一次练习，\n都是向前一步。"); greeting.setTextSize(UiComponents.portrait(this) ? 28 : 34);
        greeting.setPadding(0, UiComponents.dp(this, 18), 0, UiComponents.dp(this, 16)); hero.addView(greeting);
        TextView today = UiComponents.text(this, "正在读取你的训练计划…", 15, UiComponents.MUTED);
        today.setLineSpacing(UiComponents.dp(this, 6), 1);
        hero.addView(today);
        TextView meta = UiComponents.text(this, RehabApiClient.side(patient) + "患侧 · 按自身节奏完成", 14, UiComponents.PRIMARY);
        meta.setPadding(0, UiComponents.dp(this, 18), 0, UiComponents.dp(this, 12)); hero.addView(meta);
        MaterialButton start = UiComponents.compact(this, "开始今日训练", true, v -> createRun("plan", null, 0)); start.setEnabled(false);
        start.setIcon(new IconDrawable("play", Color.WHITE)); start.setIconSize(UiComponents.dp(this, 20)); hero.addView(start);
        heroLayout.addView(hero, new LinearLayout.LayoutParams(0, -2, 1.2f));
        FrameLayout picture = new FrameLayout(this);
        picture.addView(UiComponents.poster(this, "home_hands"), new FrameLayout.LayoutParams(-1, -1));
        TextView caption = UiComponents.badge(this, "动作教学示范", false); caption.setBackground(UiComponents.background(this, Color.WHITE, 8));
        FrameLayout.LayoutParams captionSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        captionSize.setMargins(UiComponents.dp(this, 12), 0, 0, UiComponents.dp(this, 16)); picture.addView(caption, captionSize);
        if (!UiComponents.portrait(this)) {
            heroLayout.addView(picture, new LinearLayout.LayoutParams(0, -1, 1));
            heroLayout.setBaselineAligned(false);
        } else {
            heroLayout.setOrientation(LinearLayout.VERTICAL);
            heroLayout.removeView(hero);
            heroLayout.addView(picture, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 140)));
            heroLayout.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        }
        heroBox.addView(heroLayout, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout progressCard = UiComponents.card(this);
        progressCard.addView(UiComponents.heading(this, "练习的每一步"));
        UiComponents.ProgressRing weekRing = new UiComponents.ProgressRing(this);
        LinearLayout.LayoutParams ringSize = new LinearLayout.LayoutParams(UiComponents.dp(this, 144), UiComponents.dp(this, 144));
        ringSize.gravity = Gravity.CENTER_HORIZONTAL; ringSize.setMargins(0, UiComponents.dp(this, 16), 0, UiComponents.dp(this, 14)); progressCard.addView(weekRing, ringSize);
        TextView completed = UiComponents.text(this, "今日已结束训练  — 项", 15, UiComponents.MUTED); progressCard.addView(completed);
        TextView duration = UiComponents.text(this, "今日训练用时  — 分钟", 15, UiComponents.MUTED); progressCard.addView(duration);
        TextView encouragement = UiComponents.text(this, "按计划练习，也记得好好休息。", 13, UiComponents.MUTED); encouragement.setPadding(0, UiComponents.dp(this, 16), 0, 0); progressCard.addView(encouragement);
        LinearLayout top = UiComponents.split(this, body, heroBox, progressCard, .70f);
        LinearLayout.LayoutParams topSize = new LinearLayout.LayoutParams(-1, -2); topSize.bottomMargin = UiComponents.dp(this, 24); top.setLayoutParams(topSize);
        if (top.getOrientation() == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) heroBox.getLayoutParams(); size.height = UiComponents.dp(this, 380); heroBox.setLayoutParams(size);
            heroLayout.getLayoutParams().height = -1;
            progressCard.getLayoutParams().height = UiComponents.dp(this, 380);
        } else ((LinearLayout.LayoutParams) heroBox.getLayoutParams()).bottomMargin = UiComponents.dp(this, 20);
        LinearLayout planColumn = UiComponents.column(this), weekColumn = UiComponents.column(this);
        UiComponents.split(this, body, planColumn, weekColumn, .61f);
        int ticket = generation;
        LinearLayout calendar = UiComponents.card(this); weekColumn.addView(calendar);
        calendar.addView(UiComponents.heading(this, "本周安排"));
        calendar.addView(UiComponents.compact(this, "查看周日程", false, v -> navigate("每周日程")));
        LinearLayout warm = UiComponents.card(this); warm.setBackground(UiComponents.background(this, UiComponents.WARM, 16));
        warm.addView(UiComponents.text(this, "让练习舒适一点", 18, UiComponents.INK));
        warm.addView(UiComponents.text(this, "保持舒适坐姿。如果出现疼痛或不适，请停止并联系工作人员。", 15, UiComponents.MUTED)); weekColumn.addView(warm);
        api.get("/api/v1/patients/" + patient.optString("id") + "/calendar", (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { calendar.addView(UiComponents.text(this, error, 18, UiComponents.DANGER)); return; }
            JSONObject week = (JSONObject) data;
            weekRing.update(week.optInt("completed_days"), week.optInt("scheduled_days"));
            calendar.addView(UiComponents.text(this, week.isNull("adherence") ? "暂无统计" : "截至今日应训练 " + week.optInt("due_days") + " 天 · 完成 " + week.optDouble("adherence") + "%", 13, UiComponents.MUTED));
            LinearLayout days = UiComponents.row(this); calendar.addView(days);
            JSONArray dates = week.optJSONArray("days");
            for (int i = 0; i < dates.length(); i++) {
                JSONObject d = dates.optJSONObject(i);
                String label = new String[]{"一", "二", "三", "四", "五", "六", "日"}[i] + "\n\n" + d.optString("date").substring(8) + "\n\n" +
                    (d.optBoolean("completed") ? "完成" : !d.optBoolean("scheduled") ? "休息" : "待练");
                TextView cell = UiComponents.text(this, label, 12, d.optBoolean("completed") ? UiComponents.PRIMARY : UiComponents.MUTED);
                if (d.optString("date").equals(week.optString("today"))) cell.setBackground(UiComponents.background(this, UiComponents.MINT, 10));
                cell.setGravity(Gravity.CENTER); days.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
                if (d.optString("date").equals(week.optString("today"))) calendar.addView(UiComponents.text(this, d.optBoolean("scheduled") ? "今天是训练日" : "今天是休息日，练习记为额外训练", 13, UiComponents.PRIMARY));
            }
        });
        int offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        api.get("/api/v1/patients/" + patient.optString("id") + "/overview?utc_offset_minutes=" + offset, (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { today.setText(error); return; }
            JSONObject stats = (JSONObject) data;
            completed.setText("今日已结束训练    " + stats.optInt("completed_sessions") + " 项");
            duration.setText(String.format(Locale.CHINA, "今日训练用时    %.1f 分钟", stats.optDouble("elapsed_seconds") / 60));
        });
        api.get("/api/v1/patients/" + patient.optString("id") + "/plan", (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { notice.setText(error); return; }
            JSONObject current = (JSONObject) data;
            JSONArray items = current.optJSONArray("items");
            LinearLayout plan = UiComponents.card(this);
            plan.addView(UiComponents.heading(this, "今天的训练"));
            if (!current.isNull("pending")) plan.addView(UiComponents.text(this, "待生效版本：" + current.optJSONObject("pending").optString("effective_from") + " 起生效，当前训练仍使用旧版", 18, UiComponents.MUTED));
            if (!current.optBoolean("scheduled")) plan.addView(UiComponents.text(this, "尚未配置每周日程，不生成历史应训练次数", 18, UiComponents.MUTED));
            if (items == null || items.length() == 0) {
                plan.addView(UiComponents.text(this, "尚未配置计划，请由工作人员选择动作和次数。", 18, UiComponents.MUTED));
            } else {
                start.setEnabled(true);
                today.setText("你的训练计划已准备好。" + items.length() + " 个动作，循序练习。");
                meta.setText(RehabApiClient.side(patient) + "患侧    ·    " + items.length() + " 个动作");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (i > 0) UiComponents.divider(this, plan);
                    plan.addView(actionLine(item, i));
                }
            }
            LinearLayout controls = UiComponents.row(this); plan.addView(controls);
            controls.addView(UiComponents.compact(this, "完整计划", false, v -> navigate("训练计划")));
            controls.addView(UiComponents.compact(this, "单项练习", false, v -> navigate("动作库")));
            planColumn.addView(plan);
        });
        body.addView(UiComponents.text(this, "训练辅助与算法动作反馈 · 不用于诊断或疗效评估", 12, UiComponents.MUTED));
    }

    private View actionLine(JSONObject item, int index) {
        JSONObject definition = action(item.optString("action"));
        LinearLayout line = UiComponents.row(this); line.setPadding(0, UiComponents.dp(this, 14), 0, UiComponents.dp(this, 14));
        android.widget.ImageView image = UiComponents.poster(this, definition.optString("key"));
        image.setBackground(UiComponents.background(this, UiComponents.MINT, 8)); image.setClipToOutline(true);
        line.addView(image, new LinearLayout.LayoutParams(UiComponents.dp(this, UiComponents.portrait(this) ? 54 : 74), UiComponents.dp(this, 60)));
        LinearLayout copy = UiComponents.column(this); copy.setPadding(UiComponents.dp(this, 14), 0, UiComponents.dp(this, 8), 0);
        copy.addView(UiComponents.text(this, UiComponents.portrait(this) ? definition.optString("name") : String.format(Locale.CHINA, "%02d  %s", index + 1, definition.optString("name")), 16, UiComponents.INK));
        copy.addView(UiComponents.text(this, definition.optString("cooperation"), 13, UiComponents.MUTED)); line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView target = UiComponents.text(this, item.optInt("target_reps") + " 次", 16, UiComponents.INK); target.setSingleLine(); line.addView(target);
        MaterialButton open = UiComponents.compact(this, "›", false, v -> showTeaching(definition));
        open.setText(""); open.setIcon(new IconDrawable("chevron", UiComponents.PRIMARY)); open.setIconTint(android.content.res.ColorStateList.valueOf(UiComponents.PRIMARY));
        open.setIconSize(UiComponents.dp(this, 18)); open.setIconPadding(0); open.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START); open.setPadding(0, 0, 0, 0);
        open.setContentDescription("查看" + definition.optString("name") + "教学"); open.setMinWidth(0);
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(UiComponents.dp(this, 48), UiComponents.dp(this, 48));
        openParams.setMarginStart(UiComponents.dp(this, 16));
        line.addView(open, openParams);
        return line;
    }

    private JSONObject action(String key) {
        for (int i = 0; i < actions.length(); i++) if (key.equals(actions.optJSONObject(i).optString("key"))) return actions.optJSONObject(i);
        return RehabApiClient.object("key", key, "name", key);
    }

    private void showPlan() {
        LinearLayout body = begin("我的训练计划");
        if (needPatient(body)) return;
        LinearLayout list = UiComponents.card(this), info = UiComponents.card(this);
        list.addView(UiComponents.heading(this, "按顺序，慢慢完成")); info.addView(UiComponents.heading(this, "计划说明"));
        UiComponents.split(this, body, list, info, .64f);
        int ticket = generation;
        api.get("/api/v1/patients/" + patient.optString("id") + "/plan", (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { notice.setText(error); return; }
            JSONObject plan = (JSONObject) data; JSONArray items = plan.optJSONArray("items");
            if (items == null || items.length() == 0) list.addView(UiComponents.text(this, "尚未配置计划，请联系工作人员。", 18, UiComponents.MUTED));
            else {
                for (int i = 0; i < items.length(); i++) list.addView(actionLine(items.optJSONObject(i), i));
                list.addView(UiComponents.button(this, "开始计划训练", true, v -> createRun("plan", null, 0)));
            }
            UiComponents.value(this, info, "执行患侧", RehabApiClient.side(patient));
            UiComponents.value(this, info, "计划版本", plan.optString("revision", "—"));
            UiComponents.value(this, info, "生效日期", plan.optString("effective_from", "尚未配置"));
            JSONArray days = plan.optJSONArray("weekdays");
            info.addView(UiComponents.text(this, days == null || days.length() == 0 ? "尚未设置每周日程" : "每周训练 " + days.length() + " 天", 16, UiComponents.MUTED));
            if (!plan.isNull("pending")) info.addView(UiComponents.badge(this, "有待生效版本 · " + plan.optJSONObject("pending").optString("effective_from"), true));
            info.addView(UiComponents.button(this, "每周日程", false, v -> navigate("每周日程")));
            if (SessionStore.staff(this)) info.addView(UiComponents.button(this, "编辑计划", false, v -> editPlan()));
            info.addView(UiComponents.button(this, "前往动作库", false, v -> navigate("动作库")));
        });
        UiComponents.tip(this, body, "循序练习", "根据工作人员指导完成，不自行增加强度。每次训练会保留开始时的计划快照。");
    }

    private void showSchedule() {
        LinearLayout body = begin("每周康复日程");
        if (needPatient(body)) return;
        LinearLayout card = UiComponents.card(this); body.addView(card);
        card.addView(UiComponents.heading(this, "本周 · 北京时间"));
        int ticket = generation;
        api.get("/api/v1/patients/" + patient.optString("id") + "/calendar", (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { notice.setText(error); return; }
            JSONObject week = (JSONObject) data; JSONArray days = week.optJSONArray("days");
            card.addView(UiComponents.badge(this, "已完成 " + week.optInt("completed_days") + " / " + week.optInt("scheduled_days") + " 天", false));
            LinearLayout calendar = UiComponents.row(this); calendar.setGravity(Gravity.TOP); card.addView(calendar);
            for (int i = 0; days != null && i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                LinearLayout cell = UiComponents.column(this); cell.setGravity(Gravity.CENTER);
                int pad = UiComponents.dp(this, 14); cell.setPadding(4, pad, 4, pad);
                cell.addView(UiComponents.text(this, "周" + new String[]{"一", "二", "三", "四", "五", "六", "日"}[i], 14, UiComponents.MUTED));
                TextView date = UiComponents.heading(this, day.optString("date").substring(8)); date.setTextSize(32); cell.addView(date);
                boolean today = day.optString("date").equals(week.optString("today"));
                if (today) cell.setBackground(UiComponents.background(this, UiComponents.MINT, 14));
                cell.addView(UiComponents.text(this, day.optBoolean("completed") ? "已完成" : !day.optBoolean("scheduled") ? "休息日" : today ? "今日训练" : day.optBoolean("future") ? "待训练" : "未完成", 14, day.optBoolean("completed") ? UiComponents.PRIMARY : UiComponents.MUTED));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1); size.setMargins(2, pad, 2, pad); calendar.addView(cell, size);
            }
            UiComponents.value(this, card, "截至今天应训练", week.optInt("due_days") + " 天");
            UiComponents.value(this, card, "已达到次数目标", week.optInt("target_reached_days") + " 天");
            UiComponents.value(this, card, "计划完成率", week.isNull("adherence") ? "暂无统计" : week.optDouble("adherence") + "%");
        });
        UiComponents.tip(this, body, "给身体留出休息时间", "未来日期不算漏练，同日重复训练与单项练习作为额外记录。计划完成与次数达标分别记录。");
        LinearLayout controls = UiComponents.row(this); body.addView(controls);
        controls.addView(UiComponents.compact(this, "查看训练记录", false, v -> navigate("训练记录")));
        if (SessionStore.staff(this)) controls.addView(UiComponents.compact(this, "调整日程", true, v -> editPlan()));
    }

    private void showUnconnected(String heading) {
        LinearLayout body = begin(heading);
        body.addView(UiComponents.badge(this, "页面已设计 · 服务暂未接入", true));
        LinearLayout left = UiComponents.card(this), right = UiComponents.card(this);
        UiComponents.split(this, body, left, right, .58f);
        if ("人工复核".equals(heading)) {
            left.addView(UiComponents.heading(this, "待复核队列"));
            left.addView(UiComponents.text(this, "暂无可读取的复核队列", 18, UiComponents.MUTED));
            UiComponents.tip(this, left, "保留算法原始统计", "训练视频不保存，复核以原始统计、现场观察与记录为依据。");
            right.addView(UiComponents.heading(this, "填写复核结论"));
            right.addView(UiComponents.text(this, "选择一条复核记录后，查看关联患者与原始完成 / 合格次数。", 16, UiComponents.MUTED));
            EditText conclusion = input("人工观察与后续指导", "", false); right.addView(conclusion);
            MaterialButton submit = UiComponents.button(this, "提交复核结论 · 待接入", true, null); submit.setEnabled(false); right.addView(submit);
        } else if ("备份与恢复".equals(heading)) {
            left.addView(UiComponents.heading(this, "当前备份状态"));
            UiComponents.value(this, left, "最近备份", "尚未接入"); UiComponents.value(this, left, "数据范围", "患者、计划、训练记录");
            UiComponents.value(this, left, "训练视频", "不保存");
            MaterialButton create = UiComponents.button(this, "创建备份 · 待接入", true, null); create.setEnabled(false); left.addView(create);
            right.addView(UiComponents.heading(this, "恢复前核对"));
            right.addView(UiComponents.text(this, "核对备份时间与记录数量，停止设备训练后再恢复。\n\n当前页面未连接数据库备份或恢复操作。", 17, UiComponents.MUTED));
            body.addView(UiComponents.heading(this, "备份列表")); body.addView(UiComponents.text(this, "暂无可读取的备份文件", 17, UiComponents.MUTED));
        } else {
            left.addView(UiComponents.heading(this, "操作记录"));
            left.addView(UiComponents.text(this, "时间     操作者     操作     关联对象", 16, UiComponents.MUTED));
            left.addView(UiComponents.text(this, "暂无可读取的日志", 18, UiComponents.MUTED));
            right.addView(UiComponents.heading(this, "记录范围"));
            right.addView(UiComponents.text(this, "计划修改、患者档案管理与维护操作。真实审计日志需由服务端生成。", 17, UiComponents.MUTED));
        }
        body.addView(UiComponents.compact(this, "返回患者管理", false, v -> navigate("患者管理")));
    }

    private void showPrivacy() {
        LinearLayout body = begin("隐私与使用告知"), card = UiComponents.card(this); body.addView(card);
        String[][] entries = {{"这是训练辅助工具", "用于查看教学、按计划练习和记录动作统计，不替代康复专业人员的诊断与指导。"},
            {"次数不是临床评分", "算法合格次数、需复核和跟踪提示来自既有算法，不单独证明康复疗效。"},
            {"摄像头与记录", "训练画面在机构局域网内传送用于识别，不保存训练视频；服务保存必要患者资料、计划、统计和主观反馈。"},
            {"不适时可以停下来", "可以跳过反馈或主动结束。出现新的疼痛、麻木、头晕或其他不适，请停止并联系工作人员。"},
            {"共享设备保护", "结束使用后退出账号。患者只能读取本人记录，工作人员负责档案与计划管理。"}};
        for (int i = 0; i < entries.length; i++) {
            card.addView(UiComponents.text(this, "0" + (i + 1) + "   " + entries[i][0], 20, UiComponents.PRIMARY));
            TextView text = UiComponents.text(this, entries[i][1], 17, UiComponents.MUTED); text.setPadding(0, 8, 0, UiComponents.dp(this, 24)); card.addView(text);
        }
        body.addView(UiComponents.compact(this, "返回设置", false, v -> navigate("设置")));
    }

    private void showPatientManagement() {
        LinearLayout body = begin("患者管理");
        LinearLayout toolbar = UiComponents.row(this);
        EditText search = input("搜索患者姓名、编号", "", false);
        toolbar.addView(search, new LinearLayout.LayoutParams(0, UiComponents.dp(this, 52), 1));
        toolbar.addView(UiComponents.compact(this, "新建患者", true, v -> editPatient(null))); body.addView(toolbar);
        LinearLayout tabs = UiComponents.row(this); body.addView(tabs);
        tabs.addView(UiComponents.compact(this, "在用患者", !archivedPatients, v -> { archivedPatients = false; showPatientManagement(); }));
        tabs.addView(UiComponents.compact(this, "已归档", archivedPatients, v -> { archivedPatients = true; showPatientManagement(); }));
        LinearLayout table = UiComponents.card(this);
        if (UiComponents.portrait(this)) {
            android.widget.HorizontalScrollView tableScroll = new android.widget.HorizontalScrollView(this);
            tableScroll.addView(table, new ViewGroup.LayoutParams(UiComponents.dp(this, 700), -2)); body.addView(tableScroll);
            body.addView(UiComponents.text(this, "左右滑动表格查看档案操作", 13, UiComponents.MUTED));
        } else body.addView(table);
        TextView loading = UiComponents.text(this, "正在读取患者档案…", 16, UiComponents.MUTED); table.addView(loading);
        int ticket = generation;
        api.getArray("/api/v1/patients?archived=" + archivedPatients, (data, error) -> {
            if (ticket != generation || !foreground) return;
            table.removeAllViews();
            if (error != null) { table.addView(UiComponents.text(this, error, 16, UiComponents.DANGER)); return; }
            JSONArray records = (JSONArray) data;
            List<View> rows = new ArrayList<>();
            LinearLayout header = UiComponents.row(this); header.setPadding(8, 12, 8, 18); header.setBackgroundColor(UiComponents.BACKGROUND);
            for (String label : new String[]{"患者 / 编号", "患侧", "档案操作"}) header.addView(UiComponents.text(this, label, 14, UiComponents.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            table.addView(header);
            if (records.length() == 0) { UiComponents.tip(this, table, "这里还没有患者", archivedPatients ? "归档后的档案会显示在这里，训练历史仍会保留。" : "点击“新建患者”建立第一份档案，并绑定患者的登录邮箱。"); }
            for (int i = 0; i < records.length(); i++) {
                JSONObject person = records.optJSONObject(i);
                LinearLayout line = UiComponents.row(this); line.setPadding(8, UiComponents.dp(this, 12), 8, UiComponents.dp(this, 12));
                LinearLayout identity = UiComponents.column(this); identity.addView(UiComponents.text(this, person.optString("name"), 18, UiComponents.INK));
                identity.addView(UiComponents.text(this, person.optString("code"), 13, UiComponents.MUTED)); line.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(UiComponents.text(this, RehabApiClient.side(person) + " · " + person.optDouble("forearm_cm") + " cm", 15, UiComponents.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout controls = UiComponents.row(this);
                controls.addView(UiComponents.compact(this, "选择", false, v -> { patient = person; preferences.edit().putString("patient_id", person.optString("id")).apply(); updatePatientBadge(); navigate("训练"); }));
                controls.addView(UiComponents.compact(this, "编辑", false, v -> editPatient(person)));
                line.addView(controls, new LinearLayout.LayoutParams(0, -2, 1));
                line.setTag(person.optString("name") + " " + person.optString("code")); rows.add(line); table.addView(line);
                UiComponents.divider(this, table);
            }
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void afterTextChanged(Editable s) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    for (View row : rows) row.setVisibility(row.getTag().toString().contains(s.toString().trim()) ? View.VISIBLE : View.GONE);
                }
            });
        });
        LinearLayout shortcuts = UiComponents.row(this); if (UiComponents.portrait(this)) shortcuts.setOrientation(LinearLayout.VERTICAL); body.addView(shortcuts);
        for (String label : new String[]{"人工复核", "操作日志", "备份与恢复"}) {
            LinearLayout card = UiComponents.card(this); card.addView(UiComponents.text(this, label, 18, UiComponents.INK));
            card.addView(UiComponents.text(this, "功能界面 · 尚未接入服务", 13, UiComponents.MUTED));
            card.addView(UiComponents.compact(this, "查看入口", false, v -> navigate(label)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(UiComponents.portrait(this) ? -1 : 0, -2, UiComponents.portrait(this) ? 0 : 1); params.setMargins(0, 12, 12, 0); shortcuts.addView(card, params);
        }
    }

    private void showLibrary() {
        LinearLayout body = begin("动作库");
        LinearLayout toolbar = UiComponents.row(this); body.addView(toolbar);
        EditText search = input("搜索动作名称、配合方式", libraryQuery, false);
        boolean stacked = getResources().getConfiguration().screenWidthDp < 960 || getResources().getConfiguration().fontScale > 1.2f;
        if (stacked) toolbar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams searchSize = new LinearLayout.LayoutParams(stacked ? -1 : 0, UiComponents.dp(this, 52), stacked ? 0 : 1); searchSize.rightMargin = UiComponents.dp(this, 12); toolbar.addView(search, searchSize);
        LinearLayout filters = UiComponents.row(this);
        android.widget.HorizontalScrollView filterScroll = new android.widget.HorizontalScrollView(this); filterScroll.setHorizontalScrollBarEnabled(false); filterScroll.addView(filters); toolbar.addView(filterScroll);
        for (String filter : new String[]{"全部动作", "患侧完成", "双手配合"}) filters.addView(UiComponents.compact(this, filter, filter.equals(libraryFilter), v -> { libraryFilter = filter; libraryQuery = search.getText().toString(); showLibrary(); }));
        LinearLayout grid = UiComponents.column(this); body.addView(grid);
        renderLibrary(grid);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { libraryQuery = s.toString(); renderLibrary(grid); }
        });
        body.addView(UiComponents.text(this, "教学步骤沿用项目动作目录，动作适用性请由康复专业人员确认。", 12, UiComponents.MUTED));
    }

    private void renderLibrary(LinearLayout grid) {
        grid.removeAllViews();
        int columns = UiComponents.portrait(this) || getResources().getConfiguration().fontScale > 1.2f ? 1 : getResources().getConfiguration().screenWidthDp >= 1150 ? 3 : 2;
        LinearLayout row = null;
        int visible = 0;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject item = actions.optJSONObject(i);
            String cooperation = item.optString("cooperation");
            if (!(item.optString("name") + cooperation).contains(libraryQuery.trim())) continue;
            if ("患侧完成".equals(libraryFilter) && !"患侧完成".equals(cooperation)) continue;
            if ("双手配合".equals(libraryFilter) && "患侧完成".equals(cooperation)) continue;
            if (visible++ % columns == 0) { row = UiComponents.row(this); row.setGravity(Gravity.TOP); grid.addView(row); }
            LinearLayout card = UiComponents.card(this);
            card.setPadding(0, 0, 0, 0); card.setClipToOutline(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(0, 0, UiComponents.dp(this, 12), UiComponents.dp(this, 12));
            card.setLayoutParams(params);
            card.addView(UiComponents.poster(this, item.optString("key")), new LinearLayout.LayoutParams(-1, UiComponents.dp(this, columns == 3 ? 146 : 132)));
            LinearLayout copy = UiComponents.column(this); int padding = UiComponents.dp(this, 18); copy.setPadding(padding, padding, padding, padding); card.addView(copy);
            LinearLayout names = UiComponents.row(this); copy.addView(names);
            names.addView(UiComponents.text(this, item.optString("name"), 18, UiComponents.INK), new LinearLayout.LayoutParams(0, -2, 1));
            names.addView(UiComponents.badge(this, cooperation, false));
            TextView counting = UiComponents.text(this, item.optString("counting"), 14, UiComponents.MUTED); counting.setMinHeight(UiComponents.dp(this, 44)); copy.addView(counting);
            copy.addView(UiComponents.text(this, "cup".equals(item.optString("key")) ? "器材：轻质水杯 / 按指导空手" : "按教学要求准备器材与摆位", 13, UiComponents.MUTED));
            LinearLayout foot = UiComponents.row(this); copy.addView(foot);
            String version = item.optString("content_version", "待同步");
            TextView versionView = UiComponents.text(this, "教学 · " + (version.length() > 8 ? version.substring(0, 8) : version), 12, UiComponents.MUTED);
            versionView.setMaxLines(1); versionView.setEllipsize(android.text.TextUtils.TruncateAt.END); versionView.setContentDescription("教学版本 " + version);
            foot.addView(versionView, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton teachingButton = UiComponents.compact(this, "查看教学  →", false, v -> showTeaching(item));
            teachingButton.setTag("teach:" + item.optString("key")); foot.addView(teachingButton);
            row.addView(card);
        }
        if (visible == 0) grid.addView(UiComponents.text(this, "没有找到匹配的动作，请调整搜索条件。", 18, UiComponents.MUTED));
        else if (visible % columns != 0) for (int i = visible % columns; i < columns; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void showTeaching(JSONObject item) {
        if (!offlineTeaching && item.optString("content_version").isEmpty()) {
            notice.setText("正在同步教学版本…");
            api.getArray("/api/v1/actions", (data, error) -> {
                if (!foreground) return;
                if (error != null) { notice.setText(error); return; }
                actions = (JSONArray) data;
                preferences.edit().putString("action_catalog_" + api.base(), actions.toString()).apply();
                showTeaching(action(item.optString("key")));
            }); return;
        }
        LinearLayout body = begin(item.optString("name") + " · 动作教学");
        int ticket = generation;
        LinearLayout demo = UiComponents.card(this);
        demo.addView(UiComponents.heading(this, "先看示范，再开始练习"));
        video = new VideoView(this);
        VideoView player = video;
        player.setFocusable(false);
        player.setFocusableInTouchMode(false);
        int width = Math.max(320, (int) ((getResources().getConfiguration().screenWidthDp - (getResources().getConfiguration().screenWidthDp >= 960 ? 280 : 180)) * .6));
        int height = width * 9 / 16;
        FrameLayout playerBox = new FrameLayout(this);
        playerBox.setBackground(UiComponents.background(this, 0xff203b36, 14)); playerBox.setClipToOutline(true);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        playerBox.addView(player, videoParams);
        TextView subtitle = UiComponents.text(this, "", 20, Color.WHITE);
        subtitle.setGravity(Gravity.CENTER); subtitle.setBackgroundColor(0xcc17324d);
        FrameLayout.LayoutParams subtitleParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        playerBox.addView(subtitle, subtitleParams);
        demo.addView(playerBox, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, height)));
        playerBox.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int desired = (r - l) * 9 / 16;
            if (desired > 0 && view.getLayoutParams().height != desired) {
                view.getLayoutParams().height = desired; view.requestLayout();
            }
        });
        TextView state = UiComponents.text(this, "正在读取示范视频…", 14, UiComponents.MUTED);
        demo.addView(state);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(player);
        player.setMediaController(controller);
        player.setOnPreparedListener(mp -> {
            mp.setLooping(false); player.seekTo(1);
            subtitleHandler.postDelayed(() -> { if (video == player && !player.isPlaying()) controller.hide(); }, 200);
        });
        player.setOnErrorListener((mp, what, extra) -> { state.setText("设备无法播放此视频，请检查素材或系统解码器"); return true; });
        media.video(api.base(), item, (cached, error) -> {
            if (ticket != generation || !foreground || video != player) return;
            if (cached == null) { state.setText(error); return; }
            if (!cached.item.optString("content_version").equals(item.optString("content_version"))) {
                showTeaching(cached.item); notice.setText("新版下载失败，已保留并展示旧版本教学。请联网后重新进入动作库同步。"); return;
            }
            player.setVideoURI(Uri.fromFile(cached.video));
            state.setText((error == null ? "已缓存 · " : error + "\n") + "版本 " + cached.item.optString("content_version") + "\n最后同步 " +
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(cached.synced)) + " · 点击播放，可重播");
            Subtitles captions = Subtitles.read(cached.subtitle);
            subtitleHandler.post(new Runnable() {
                @Override public void run() {
                    if (ticket != generation || video != player || !foreground) return;
                    subtitle.setText(captions.at(player.getCurrentPosition())); subtitleHandler.postDelayed(this, 150);
                }
            });
        }, text -> { if (ticket == generation && video == player) state.setText(text); });
        CheckBox captions = new CheckBox(this); captions.setText("显示中文字幕"); captions.setTextSize(16); captions.setChecked(true);
        captions.setOnCheckedChangeListener((b, checked) -> subtitle.setVisibility(checked ? View.VISIBLE : View.GONE)); demo.addView(captions);
        demo.addView(UiComponents.button(this, "重播示范", false, v -> { player.seekTo(0); player.start(); }));
        JSONObject review = item.optJSONObject("review");
        String reviewState = review == null ? "pending" : review.optString("state");
        demo.addView(UiComponents.text(this, "内容版本 " + item.optString("content_version", "待同步") + " · 更新 " + localTime(item.optString("updated_at")) +
            "\n审核：" + ("approved".equals(reviewState) ? "已审核" : "rejected".equals(reviewState) ? "需修订" : "待审核") +
            (review == null || review.isNull("reviewer") ? "" : " · " + review.optString("reviewer") + " · " + localTime(review.optString("reviewed_at"))) +
            (review == null ? "" : "\n" + review.optString("note", "")), 14, UiComponents.MUTED));
        if (!offlineTeaching && SessionStore.staff(this)) demo.addView(UiComponents.button(this, "管理本版本审核信息", false, v -> reviewTeaching(item)));
        LinearLayout instructions = UiComponents.card(this);
        instructions.addView(UiComponents.heading(this, "分步动作指导"));
        instructions.addView(UiComponents.badge(this, item.optString("cooperation"), false));
        JSONArray steps = item.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) instructions.addView(
            UiComponents.text(this, "0" + (i + 1) + "   " + steps.optString(i) + "\n", 17, UiComponents.INK));
        UiComponents.tip(this, instructions, "先看一遍，再跟着做", "动作方向与力度请听从工作人员指导。");
        instructions.addView(UiComponents.text(this, "入镜要求：" + item.optString("framing"), 15, UiComponents.MUTED));
        instructions.addView(UiComponents.text(this, "计数说明：" + item.optString("counting"), 15, UiComponents.MUTED));
        EditText target = input("目标次数（1–100）", String.valueOf(item.optInt("default_target", 10)), true);
        if (SessionStore.staff(this)) { instructions.addView(UiComponents.text(this, "本项目标次数", 14, UiComponents.MUTED)); instructions.addView(target); }
        TextView prescription = UiComponents.text(this, "正在读取生效计划…", 18, UiComponents.MUTED);
        if (!SessionStore.staff(this)) instructions.addView(prescription);
        MaterialButton practice = UiComponents.button(this, "进入训练准备", true, v -> {
            if (patient == null) { choosePatient(); return; }
            try {
                int count = Integer.parseInt(target.getText().toString().trim());
                if (count < 1 || count > 100) throw new NumberFormatException();
                createRun("single", item.optString("key"), count);
            } catch (NumberFormatException e) { target.setError("请输入 1–100 的整数"); }
        }); instructions.addView(practice);
        if (offlineTeaching) practice.setEnabled(false);
        if (!offlineTeaching && !SessionStore.staff(this)) {
            practice.setEnabled(false);
            if (patient != null) api.get("/api/v1/patients/" + patient.optString("id") + "/plan", (data, error) -> {
                if (ticket != generation || !foreground) return;
                if (error != null) { prescription.setText(error); return; }
                JSONArray items = ((JSONObject) data).optJSONArray("items");
                prescription.setText("本动作不在当前生效计划内，可观看教学；练习请联系工作人员配置。");
                for (int i = 0; i < items.length(); i++) if (item.optString("key").equals(items.optJSONObject(i).optString("action"))) {
                    int reps = items.optJSONObject(i).optInt("target_reps"); target.setText(String.valueOf(reps));
                    prescription.setText("当前计划目标 " + reps + " 次 · 由工作人员设置"); practice.setEnabled(true); break;
                }
            });
        }
        UiComponents.split(this, body, demo, instructions, .60f);
        body.addView(UiComponents.compact(this, "返回动作库", false, v -> navigate("动作库")));
    }

    private void reviewTeaching(JSONObject item) {
        if (offlineTeaching || !SessionStore.staff(this)) return;
        LinearLayout form = UiComponents.column(this); form.setPadding(24, 8, 24, 8);
        Spinner state = new Spinner(this); state.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"待审核", "已审核", "需修订"}));
        JSONObject review = item.optJSONObject("review");
        state.setSelection(review != null && "approved".equals(review.optString("state")) ? 1 : review != null && "rejected".equals(review.optString("state")) ? 2 : 0); form.addView(state);
        EditText reviewer = input("审核人", review == null || review.isNull("reviewer") ? "" : review.optString("reviewer"), false); form.addView(reviewer);
        EditText note = input("审核备注（最多 500 字）", review == null ? "" : review.optString("note", ""), false); form.addView(note);
        TextView errorView = UiComponents.text(this, "审核仅关联当前版本，素材更新后自动回到待审核。", 18, UiComponents.MUTED); form.addView(errorView);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("教学内容审核").setView(UiComponents.scroll(this, form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            api.request("PATCH", "/api/v1/actions/" + item.optString("key") + "/review", RehabApiClient.object("content_version", item.optString("content_version"),
                "state", new String[]{"pending", "approved", "rejected"}[state.getSelectedItemPosition()], "reviewer", reviewer.getText().toString(), "note", note.getText().toString()), (data, error) -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (error != null) { errorView.setText(error); return; }
                JSONObject updated = (JSONObject) data;
                try { for (int i = 0; i < actions.length(); i++) if (item.optString("key").equals(actions.optJSONObject(i).optString("key"))) actions.put(i, updated); } catch (Exception ignored) {}
                preferences.edit().putString("action_catalog_" + api.base(), actions.toString()).apply(); dialog.dismiss(); showTeaching(updated);
            });
        })); dialog.show();
    }

    private void createRun(String mode, String key, int target) {
        if (offlineTeaching) { notice.setText("离线模式不能开始训练，请联网登录"); return; }
        if (creatingRun) return;
        if (patient == null) { choosePatient(); return; }
        if (patient.optBoolean("archived")) { notice.setText("当前患者已归档，请先在设置中恢复患者"); return; }
        creatingRun = true;
        stopVideo();
        int ticket = generation;
        JSONObject body = RehabApiClient.object("patient_id", patient.optString("id"), "mode", mode);
        if (key != null) body = RehabApiClient.object("patient_id", patient.optString("id"), "mode", mode, "action", key, "target_reps", target);
        patientButton.setEnabled(false);
        for (MaterialButton button : navigation) button.setEnabled(false);
        notice.setText("正在创建本次训练…");
        api.post("/api/v1/training-runs", body, (data, error) -> {
            creatingRun = false;
            patientButton.setEnabled(true);
            for (MaterialButton button : navigation) button.setEnabled(true);
            if (error != null) { notice.setText(error); return; }
            String id = ((JSONObject) data).optString("id");
            preferences.edit().putString("active_run", id).apply();
            if (ticket != generation || !foreground) { api.post("/api/v1/training-runs/" + id + "/finish", RehabApiClient.object(), (a, b) -> {}); return; }
            Intent intent = new Intent(this, TrainingActivity.class);
            intent.putExtra("run_id", id);
            intent.putExtra("catalog", actions.toString());
            startActivityForResult(intent, 201);
        });
    }

    private EditText input(String hint, String value, boolean numeric) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        UiComponents.styleInput(input);
        input.setSingleLine(true);
        input.setMinHeight(UiComponents.dp(this, 56));
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private void choosePatient() {
        if (!SessionStore.staff(this)) return;
        if (api.base().isEmpty()) { navigate("设置"); notice.setText("请先保存服务端地址，再选择患者"); return; }
        LinearLayout body = begin("选择本次训练的患者");
        int pageTicket = generation;
        EditText search = input("搜索姓名或患者编号", "", false);
        body.addView(search);
        body.addView(UiComponents.compact(this, "新建患者", true, v -> editPatient(null)));
        CheckBox archived = new CheckBox(this);
        archived.setText("查看已归档患者"); archived.setTextSize(18);
        body.addView(archived);
        LinearLayout list = UiComponents.column(this);
        body.addView(list);
        final JSONObject[] selected = {null};
        TextView selection = UiComponents.text(this, "请核对患者编号与患侧后确认。", 17, UiComponents.MUTED); body.addView(selection);
        MaterialButton confirm = UiComponents.compact(this, "确认并进入", true, v -> {
            if (selected[0] == null) return;
            patient = selected[0]; preferences.edit().putString("patient_id", patient.optString("id")).apply();
            updatePatientBadge(); navigate(patient.optBoolean("archived") ? "训练记录" : "训练");
        }); confirm.setEnabled(false); body.addView(confirm);
        Runnable reload = () -> {
            int ticket = ++patientSearchGeneration;
            api.getArray("/api/v1/patients?q=" + Uri.encode(search.getText().toString().trim()) + "&archived=" + archived.isChecked(), (data, error) -> {
                if (ticket != patientSearchGeneration || generation != pageTicket || !foreground) return;
                list.removeAllViews();
                selected[0] = null; confirm.setEnabled(false);
                if (error != null) { list.addView(UiComponents.text(this, error, 18, UiComponents.DANGER)); return; }
                JSONArray patients = (JSONArray) data;
                if (patients.length() == 0) list.addView(UiComponents.text(this, "没有匹配的患者", 18, UiComponents.MUTED));
                for (int i = 0; i < patients.length(); i++) {
                    JSONObject item = patients.optJSONObject(i);
                    MaterialButton pick = UiComponents.button(this, item.optString("name") + "    " + item.optString("code") + "    ·    " + RehabApiClient.side(item) + "患侧 · 前臂 " + item.optDouble("forearm_cm") + " cm", false, v -> {
                        selected[0] = item; confirm.setEnabled(true); selection.setText("已选择：" + item.optString("name") + " · " + item.optString("code"));
                        for (int child = 0; child < list.getChildCount(); child++) if (list.getChildAt(child) instanceof MaterialButton) {
                            MaterialButton b = (MaterialButton) list.getChildAt(child); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(b == v ? UiComponents.MINT : Color.WHITE)); b.setSelected(b == v);
                        }
                    });
                    list.addView(pick);
                }
            });
        };
        archived.setOnCheckedChangeListener((button, checked) -> reload.run());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { reload.run(); }
            public void afterTextChanged(Editable s) { }
        });
        reload.run();
    }

    private void editPatient(JSONObject existing) {
        if (!SessionStore.staff(this)) return;
        LinearLayout body = begin(existing == null ? "新建患者档案" : "编辑患者档案");
        int ticket = generation;
        LinearLayout fields = UiComponents.card(this), guidance = UiComponents.column(this);
        UiComponents.split(this, body, fields, guidance, .65f);
        UiComponents.tip(this, guidance, "核对后再保存", "患侧和前臂长度用于已有算法的标定，请与患者共同确认。");
        UiComponents.tip(this, guidance, "只收集必要资料", "编号由系统维护，避免同名混淆。无需填写身份证号等无关信息。");
        if (existing == null) UiComponents.tip(this, guidance, "分配患者账号", "先保存档案，再在此页分配登录账号和密码。邮箱用于验证码登录与密码找回，可稍后绑定。");
        else patientAccount(guidance, existing, ticket);
        fields.addView(UiComponents.heading(this, "基本资料"));
        EditText name = input("患者姓名", existing == null ? "" : existing.optString("name"), false);
        fields.addView(UiComponents.text(this, "姓名", 18, UiComponents.MUTED)); fields.addView(name);
        EditText email = input("患者登录邮箱（可稍后绑定）", existing == null || existing.isNull("email") ? "" : existing.optString("email"), false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        fields.addView(UiComponents.text(this, "登录邮箱 · 改绑将撤销旧登录", 18, UiComponents.MUTED)); fields.addView(email);
        Spinner side = new Spinner(this);
        side.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"左侧患侧", "右侧患侧"}));
        side.setSelection(existing == null || "left".equals(existing.optString("affected_side")) ? 0 : 1);
        fields.addView(UiComponents.text(this, "执行患侧", 16, UiComponents.MUTED)); fields.addView(side, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 56)));
        EditText forearm = input("前臂长度（10–45 cm）", existing == null ? "25" : String.valueOf(existing.optDouble("forearm_cm")), false);
        forearm.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fields.addView(UiComponents.text(this, "前臂长度（cm）", 18, UiComponents.MUTED)); fields.addView(forearm);
        MaterialButton save = UiComponents.button(this, "保存档案", true, null); fields.addView(save);
        fields.addView(UiComponents.button(this, "取消", false, v -> navigate("患者管理")));
        if (existing != null) fields.addView(UiComponents.compact(this, existing.optBoolean("archived") ? "恢复档案" : "归档患者", false, v -> new AlertDialog.Builder(this).setTitle(existing.optBoolean("archived") ? "恢复患者？" : "归档患者？")
            .setMessage("历史训练记录会保留。").setNegativeButton("取消", null).setPositiveButton("确认", (dialog, which) -> api.request("PATCH", "/api/v1/patients/" + existing.optString("id"), RehabApiClient.object("archived", !existing.optBoolean("archived")), (data, error) -> {
                if (error != null) notice.setText(error); else { if (patient != null && patient.optString("id").equals(existing.optString("id"))) { patient = (JSONObject) data; updatePatientBadge(); } navigate("患者管理"); }
            })).show()));
        save.setOnClickListener(v -> {
            try {
                double cm = Double.parseDouble(forearm.getText().toString().trim());
                String text = name.getText().toString().trim();
                if (text.isEmpty() || text.length() > 60) { name.setError("请填写 1–60 字姓名"); return; }
                if (Double.isNaN(cm) || Double.isInfinite(cm) || cm < 10 || cm > 45) throw new NumberFormatException();
                JSONObject payload = RehabApiClient.object("name", text, "affected_side", side.getSelectedItemPosition() == 0 ? "left" : "right", "forearm_cm", cm, "archived", false,
                    "email", email.getText().toString().trim().isEmpty() ? JSONObject.NULL : email.getText().toString().trim());
                save.setEnabled(false);
                api.request(existing == null ? "POST" : "PATCH", "/api/v1/patients" + (existing == null ? "" : "/" + existing.optString("id")), payload, (data, error) -> {
                    if (ticket != generation || !foreground) return;
                    save.setEnabled(true);
                    if (error != null) { name.setError(error); return; }
                    patient = (JSONObject) data;
                    preferences.edit().putString("patient_id", patient.optString("id")).apply();
                    updatePatientBadge();
                    if (existing == null) { editPatient(patient); notice.setText("档案已保存，请在右侧分配患者登录账号。"); }
                    else navigate("训练");
                });
            } catch (NumberFormatException e) { forearm.setError("请输入 10–45 cm 的长度"); }
        });
    }

    private void patientAccount(LinearLayout parent, JSONObject person, int ticket) {
        LinearLayout card = UiComponents.card(this); parent.addView(card);
        card.addView(UiComponents.heading(this, "患者登录账号"));
        card.addView(UiComponents.text(this, "由管理员分配；修改账号或重置密码后，患者需重新登录。", 15, UiComponents.MUTED));
        card.addView(UiComponents.text(this, "账号 · 3–32 位字母、数字、_.-", 14, UiComponents.MUTED));
        EditText username = input("患者账号", "", false); username.setSingleLine(); card.addView(username);
        card.addView(UiComponents.text(this, "密码 · 8–128 个字符", 14, UiComponents.MUTED));
        EditText password = input("初次设置必填；留空保留原密码", "", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setSingleLine(); card.addView(password);
        CheckBox visible = new CheckBox(this); visible.setText("显示密码");
        visible.setOnCheckedChangeListener((button, checked) -> {
            password.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance()); password.setSelection(password.length());
        }); card.addView(visible);
        TextView state = UiComponents.text(this, "正在读取账号…", 14, UiComponents.MUTED); card.addView(state);
        MaterialButton save = UiComponents.button(this, "保存账号 / 重置密码", true, null); save.setEnabled(false); card.addView(save);
        api.get("/api/v1/patients/" + person.optString("id") + "/account", (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { state.setText(error); return; }
            JSONObject account = (JSONObject) data;
            username.setText(account.isNull("username") ? "" : account.optString("username")); save.setEnabled(true);
            state.setText(account.optBoolean("password_configured") ? "已设置密码；系统不显示原密码，可在此重置。" : "尚未分配账号密码。");
        });
        save.setOnClickListener(v -> {
            String name = username.getText().toString().trim(), value = password.getText().toString();
            if (!name.matches("[A-Za-z0-9_.-]{3,32}")) { username.setError("请输入 3–32 位字母、数字、_.-"); return; }
            if (!value.isEmpty() && (value.length() < 8 || value.length() > 128)) { password.setError("密码需为 8–128 个字符"); return; }
            new AlertDialog.Builder(this).setTitle("确认更新患者账号？").setMessage("当前患者：" + person.optString("name") + "\n请将账号及新密码交给本人。原登录将在账号或密码变更后失效。")
                .setNegativeButton("取消", null).setPositiveButton("确认保存", (dialog, which) -> {
                    JSONObject payload = RehabApiClient.object("username", name);
                    if (!value.isEmpty()) { try { payload.put("password", value); } catch (org.json.JSONException ignored) { } }
                    save.setEnabled(false);
                    api.request("PUT", "/api/v1/patients/" + person.optString("id") + "/account", payload, (data, error) -> {
                        if (ticket != generation || !foreground) return;
                        save.setEnabled(true); state.setText(error == null ? "账号已保存，请告知患者使用账号与密码登录。" : error);
                        if (error == null) { username.setText(((JSONObject) data).optString("username")); password.setText(""); }
                    });
                }).show();
        });
    }

    private void editPlan() {
        if (!SessionStore.staff(this)) return;
        if (patient == null) { choosePatient(); return; }
        String identity = patient.optString("id");
        int ticket = generation;
        api.get("/api/v1/patients/" + identity + "/plan", (data, error) -> {
            if (!foreground || ticket != generation || patient == null || !identity.equals(patient.optString("id"))) return;
            if (error != null) { notice.setText(error); return; }
            List<JSONObject> items = new ArrayList<>();
            JSONObject current = (JSONObject) data;
            planDraft = current.isNull("pending") ? current : current.optJSONObject("pending");
            java.util.Calendar date = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            if (current.optJSONArray("items").length() > 0) date.add(java.util.Calendar.DAY_OF_MONTH, 1);
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US); format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            draftDate = current.isNull("pending") ? format.format(date.getTime()) : planDraft.optString("effective_from");
            java.util.Arrays.fill(draftDays, false);
            JSONArray weekdays = planDraft.optJSONArray("weekdays");
            if (weekdays != null) for (int i = 0; i < weekdays.length(); i++) draftDays[weekdays.optInt(i) - 1] = true;
            JSONArray saved = planDraft.optJSONArray("items");
            if (saved != null) for (int i = 0; i < saved.length(); i++) items.add(saved.optJSONObject(i));
            showPlanEditor(items);
        });
    }

    private void showPlanEditor(List<JSONObject> items) {
        LinearLayout body = begin("配置计划 · " + patient.optString("name"));
        LinearLayout actionPanel = UiComponents.column(this), schedule = UiComponents.card(this);
        actionPanel.addView(UiComponents.heading(this, "动作与目标次数"));
        actionPanel.addView(UiComponents.text(this, "1–6 项，不可重复 · 目标依据完成次数判断", 15, UiComponents.MUTED));
        UiComponents.split(this, body, actionPanel, schedule, .64f);
        schedule.addView(UiComponents.heading(this, "每周训练日与生效日期"));
        LinearLayout weekdays = null;
        for (int i = 0; i < 7; i++) {
            if (i % 4 == 0) { weekdays = UiComponents.row(this); schedule.addView(weekdays); }
            final int day = i; CheckBox check = new CheckBox(this); check.setText("周" + new String[]{"一", "二", "三", "四", "五", "六", "日"}[i]); check.setTextSize(14); check.setPadding(0, 0, 0, 0);
            check.setChecked(draftDays[i]); check.setOnCheckedChangeListener((b, checked) -> draftDays[day] = checked);
            weekdays.addView(check, new LinearLayout.LayoutParams(0, UiComponents.dp(this, 56), 1));
        }
        schedule.addView(UiComponents.text(this, "已有计划调整最早次日生效；保存会替换待生效版本，不改变历史。", 15, UiComponents.MUTED));
        schedule.addView(UiComponents.text(this, "生效日期", 14, UiComponents.MUTED));
        EditText effective = input("生效日期 YYYY-MM-DD", draftDate, false); schedule.addView(effective);
        effective.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { draftDate = s.toString().trim(); }
        });
        for (int i = 0; i < items.size(); i++) {
            int index = i;
            JSONObject item = items.get(i);
            LinearLayout card = UiComponents.card(this);
            LinearLayout line = UiComponents.row(this); card.addView(line);
            line.addView(UiComponents.poster(this, item.optString("action")), new LinearLayout.LayoutParams(UiComponents.dp(this, 76), UiComponents.dp(this, 60)));
            LinearLayout description = UiComponents.column(this); description.setPadding(UiComponents.dp(this, 12), 0, UiComponents.dp(this, 8), 0);
            description.addView(UiComponents.text(this, (i + 1) + ". " + action(item.optString("action")).optString("name"), 18, UiComponents.INK));
            description.addView(UiComponents.text(this, action(item.optString("action")).optString("counting"), 13, UiComponents.MUTED));
            line.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
            EditText target = input("目标次数", String.valueOf(item.optInt("target_reps")), true);
            target.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    int value;
                    try { value = Integer.parseInt(s.toString()); } catch (NumberFormatException e) { value = 0; }
                    try { item.put("target_reps", value); } catch (Exception ignored) { }
                }
                public void afterTextChanged(Editable s) { }
            });
            line.addView(target, new LinearLayout.LayoutParams(UiComponents.dp(this, 64), UiComponents.dp(this, 52)));
            LinearLayout controls = UiComponents.row(this);
            MaterialButton up = UiComponents.button(this, "上移", false, v -> {
                if (index > 0) { java.util.Collections.swap(items, index, index - 1); showPlanEditor(items); }
            });
            MaterialButton down = UiComponents.button(this, "下移", false, v -> {
                if (index + 1 < items.size()) { java.util.Collections.swap(items, index, index + 1); showPlanEditor(items); }
            });
            MaterialButton remove = UiComponents.button(this, "移除", false, v -> { items.remove(index); showPlanEditor(items); });
            for (MaterialButton b : new MaterialButton[]{up, down, remove}) { b.setLayoutParams(new LinearLayout.LayoutParams(0, UiComponents.dp(this, 56), 1)); controls.addView(b); }
            up.setEnabled(index > 0); down.setEnabled(index + 1 < items.size());
            card.addView(controls); actionPanel.addView(card);
        }
        for (int i = 0; i < actions.length(); i++) {
            JSONObject item = actions.optJSONObject(i);
            boolean exists = false;
            for (JSONObject selected : items) if (item.optString("key").equals(selected.optString("action"))) exists = true;
            if (!exists) actionPanel.addView(UiComponents.button(this, "＋ 添加 " + item.optString("name"), false, v -> {
                items.add(RehabApiClient.object("action", item.optString("key"), "target_reps", item.optInt("default_target"))); showPlanEditor(items);
            }));
        }
        schedule.addView(UiComponents.button(this, "保存计划", true, v -> {
            if (items.isEmpty()) { notice.setText("请至少添加一个动作"); return; }
            for (JSONObject item : items) if (item.optInt("target_reps") < 1 || item.optInt("target_reps") > 100) { notice.setText("各项目标必须在 1–100 次之间"); return; }
            JSONArray trainingDays = new JSONArray(); for (int i = 0; i < 7; i++) if (draftDays[i]) trainingDays.put(i + 1);
            api.request("PUT", "/api/v1/patients/" + patient.optString("id") + "/plan", RehabApiClient.object("items", new JSONArray(items), "weekdays", trainingDays, "effective_from", draftDate), (data, error) -> {
                if (error != null) notice.setText(error); else navigate("训练");
            });
        }));
    }

    private void showHistory() {
        LinearLayout body = begin("训练记录");
        if (patient == null && needPatient(body)) return;
        body.addView(UiComponents.compact(this, "查看训练趋势  →", false, v -> startActivity(new Intent(this, TrendsActivity.class).putExtra("patient_id", patient.optString("id")).putExtra("catalog", actions.toString()))));
        LinearLayout filters = UiComponents.row(this);
        for (int days : new int[]{7, 30, 0}) {
            MaterialButton button = UiComponents.button(this, days == 0 ? "全部" : "近 " + days + " 天", days == historyDays,
                v -> { historyDays = days; historyOffset = 0; showHistory(); });
            button.setLayoutParams(new LinearLayout.LayoutParams(0, UiComponents.dp(this, 48), 1)); filters.addView(button);
        }
        body.addView(filters);
        LinearLayout list = UiComponents.column(this); body.addView(list);
        int ticket = generation;
        api.getArray("/api/v1/training-runs?patient_id=" + patient.optString("id") + "&days=" + historyDays + "&offset=" + historyOffset, (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { notice.setText(error); return; }
            JSONArray records = (JSONArray) data;
            if (records.length() == 0) UiComponents.tip(this, list, "练习的每一步，都会留在这里", "这个日期范围内还没有训练记录。完成第一次训练后，就可以查看结果与感受。");
            boolean tableLayout = getResources().getConfiguration().screenWidthDp > 1000;
            LinearLayout table = UiComponents.card(this);
            if (tableLayout && records.length() > 0) {
                list.addView(table);
                LinearLayout header = UiComponents.row(this); header.setBackground(UiComponents.background(this, UiComponents.BACKGROUND, 10));
                String[] labels = {"训练时间", "训练类型", "已结束项目", "实际用时", "结束状态", "操作"};
                for (int i = 0; i < labels.length; i++) header.addView(UiComponents.text(this, labels[i], 13, UiComponents.MUTED), new LinearLayout.LayoutParams(0, UiComponents.dp(this, 48), i == 0 ? 1.8f : 1));
                table.addView(header);
            }
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.optJSONObject(i);
                if (tableLayout) {
                    LinearLayout row = UiComponents.row(this); row.setPadding(0, UiComponents.dp(this, 12), 0, UiComponents.dp(this, 12));
                    String[] values = {localTime(record.optString("created_at")), "plan".equals(record.optString("mode")) ? "计划训练" : "单项练习",
                        record.optInt("completed_items") + " / " + record.optJSONArray("items").length() + " 项",
                        String.format(Locale.CHINA, "%.1f 分钟", record.optDouble("elapsed_seconds") / 60), runStatus(record.optString("status"))};
                    for (int j = 0; j < values.length; j++) row.addView(UiComponents.text(this, values[j], 15, j == 4 && !"completed".equals(record.optString("status")) ? UiComponents.ORANGE : UiComponents.INK), new LinearLayout.LayoutParams(0, -2, j == 0 ? 1.8f : 1));
                    row.addView(UiComponents.compact(this, "查看详情  →", false, v -> showRun(record.optString("id"))), new LinearLayout.LayoutParams(0, UiComponents.dp(this, 48), 1));
                    table.addView(row); UiComponents.divider(this, table); continue;
                }
                LinearLayout card = UiComponents.card(this);
                LinearLayout head = UiComponents.row(this); card.addView(head);
                head.addView(UiComponents.heading(this, "plan".equals(record.optString("mode")) ? "计划训练" : "单项练习"), new LinearLayout.LayoutParams(0, -2, 1));
                head.addView(UiComponents.badge(this, runStatus(record.optString("status")), !"completed".equals(record.optString("status"))));
                card.addView(UiComponents.text(this, localTime(record.optString("created_at")), 14, UiComponents.MUTED));
                card.addView(UiComponents.text(this, String.format(Locale.CHINA, "已结束 %d / %d 项 · 实际训练 %.1f 分钟", record.optInt("completed_items"),
                    record.optJSONArray("items").length(), record.optDouble("elapsed_seconds") / 60), 17, UiComponents.INK));
                card.addView(UiComponents.compact(this, "查看详情  →", false, v -> showRun(record.optString("id"))));
                list.addView(card);
            }
            if (records.length() == 50) list.addView(UiComponents.button(this, "下一页", false, v -> { historyOffset += 50; showHistory(); }));
            if (historyOffset > 0) list.addView(UiComponents.button(this, "上一页", false, v -> { historyOffset = Math.max(0, historyOffset - 50); showHistory(); }));
        });
    }

    private void showRun(String id) {
        LinearLayout body = begin("训练详情");
        int ticket = generation;
        api.get("/api/v1/training-runs/" + id, (data, error) -> {
            if (ticket != generation || !foreground) return;
            if (error != null) { notice.setText(error); return; }
            JSONObject run = (JSONObject) data;
            if ("user_discomfort".equals(run.optString("end_reason"))) body.addView(UiComponents.text(this, "患者主动因不适结束 · 未计为计划完成", 20, UiComponents.DANGER));
            JSONObject snapshot = run.optJSONObject("patient");
            LinearLayout overview = UiComponents.card(this);
            overview.addView(UiComponents.heading(this, runStatus(run.optString("status"))));
            overview.addView(UiComponents.text(this, snapshot.optString("name") + " · " + RehabApiClient.side(snapshot) + " · " + snapshot.optString("code"), 20, UiComponents.INK));
            overview.addView(UiComponents.text(this, localTime(run.optString("created_at")), 18, UiComponents.MUTED));
            overview.addView(UiComponents.text(this, String.format(Locale.CHINA, "结束项目 %d / %d · 实际训练 %.1f 分钟", run.optInt("completed_items"),
                run.optJSONArray("items").length(), run.optDouble("elapsed_seconds") / 60), 20, UiComponents.INK));
            body.addView(overview);
            JSONArray sessions = run.optJSONArray("sessions");
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                LinearLayout card = UiComponents.card(this);
                card.addView(UiComponents.heading(this, action(session.optString("action")).optString("name")));
                card.addView(UiComponents.text(this, sessionStatus(session.optString("status")) + " · " +
                    (session.optBoolean("target_reached") ? "已达到次数目标" : "未达到次数目标"), 18, UiComponents.MUTED));
                JSONObject summary = session.optJSONObject("summary");
                UiComponents.summary(this, card, summary, session.optDouble("elapsed_seconds"));
                body.addView(card);
            }
            JSONObject feedback = run.optJSONObject("feedback");
            LinearLayout feelings = UiComponents.card(this); feelings.addView(UiComponents.heading(this, "训练前后感受"));
            feelings.addView(UiComponents.text(this, feedbackText(feedback), 17, UiComponents.INK)); body.addView(feelings);
            if (SessionStore.staff(this)) body.addView(UiComponents.compact(this, "训练摘要报告", false, v -> showReport(run)));
            if (!"active".equals(run.optString("status"))) body.addView(UiComponents.button(this, "补填或修改训练后感受", false,
                v -> startActivityForResult(new Intent(this, FeedbackActivity.class).putExtra("run_id", id).putExtra("phase", "post"), 202)));
            body.addView(UiComponents.button(this, "返回训练记录", false, v -> navigate("训练记录")));
        });
    }

    private void showReport(JSONObject run) {
        LinearLayout body = begin("训练摘要报告"), report = UiComponents.card(this); body.addView(report);
        report.addView(UiComponents.text(this, "复健   /   TRAINING SUMMARY", 18, UiComponents.PRIMARY));
        report.addView(UiComponents.heading(this, "单次康复训练摘要"));
        JSONObject snapshot = run.optJSONObject("patient");
        UiComponents.value(this, report, "患者", snapshot.optString("name") + " · " + snapshot.optString("code"));
        UiComponents.value(this, report, "执行患侧", RehabApiClient.side(snapshot));
        UiComponents.value(this, report, "训练时间", localTime(run.optString("created_at")));
        UiComponents.value(this, report, "结束类型", runStatus(run.optString("status")));
        UiComponents.value(this, report, "实际训练用时", String.format(Locale.CHINA, "%.1f 分钟", run.optDouble("elapsed_seconds") / 60));
        JSONArray sessions = run.optJSONArray("sessions");
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i); report.addView(UiComponents.heading(this, action(session.optString("action")).optString("name")));
            UiComponents.summary(this, report, session.optJSONObject("summary"), session.optDouble("elapsed_seconds"));
        }
        report.addView(UiComponents.heading(this, "主观反馈")); report.addView(UiComponents.text(this, feedbackText(run.optJSONObject("feedback")), 16, UiComponents.MUTED));
        report.addView(UiComponents.text(this, "工作人员备注（当前未接入保存）", 15, UiComponents.MUTED));
        report.addView(input("交接备注，仅在本页编辑", "", false));
        MaterialButton export = UiComponents.compact(this, "打印 / 导出 · 待接入", true, null); export.setEnabled(false); report.addView(export);
        body.addView(UiComponents.compact(this, "返回训练详情", false, v -> showRun(run.optString("id"))));
    }

    public static String summaryText(JSONObject summary, double elapsed) {
        if (summary == null) return "尚无识别结果";
        return String.format(Locale.CHINA,
            "完成 %d / %d 次   合格 %d 次\n算法合格率 %s   需复核 %d 次\n%s\n实际训练用时 %.1f 秒\n已识别动作累计时长 %.1f 秒 · 单次平均 %.1f 秒",
            summary.optInt("count"), summary.optInt("target"), summary.optInt("passed"), summary.optInt("count") == 0 ? "暂无判定" : String.format(Locale.CHINA, "%.1f%%（%d/%d）", summary.optDouble("rate"), summary.optInt("passed"), summary.optInt("count")),
            summary.optInt("review"), summary.optString("detail", ""), elapsed, summary.optDouble("total"), summary.optDouble("avg"));
    }

    public static String feedbackText(JSONObject feedback) {
        StringBuilder text = new StringBuilder();
        for (String phase : new String[]{"pre", "post"}) {
            text.append("pre".equals(phase) ? "训练前：" : "训练后：");
            JSONObject f = feedback == null ? null : feedback.optJSONObject(phase);
            if (f == null) text.append("未填写");
            else if ("skipped".equals(f.optString("state"))) text.append("已跳过");
            else {
                JSONObject d = f.optJSONObject("data");
                text.append("疼痛 ").append(d.isNull("pain") ? "未填写" : d.optInt("pain") + "/10");
                text.append(" · 疲劳 ").append(d.isNull("fatigue") ? "未填写" : d.optInt("fatigue") + "/10");
                text.append(" · 不适 ").append(d.isNull("discomfort") ? "未填写" : d.optBoolean("discomfort") ? "有" : "无");
                if (!d.isNull("difficulty")) text.append(" · ").append(new String[]{"", "很轻松", "较轻松", "适中", "较困难", "很困难"}[d.optInt("difficulty")]);
                if (!d.isNull("note")) text.append("\n").append(d.optString("note"));
            }
            if (f != null) text.append("\n填写时间 ").append(localTime(f.optString("updated_at")));
            text.append("\n");
        }
        return text.toString();
    }

    public static String runStatus(String status) {
        if ("completed".equals(status)) return "正常结束";
        if ("interrupted".equals(status)) return "训练中断";
        return "未完成";
    }
    public static String sessionStatus(String status) {
        return "completed".equals(status) ? "正常结束" : "interrupted".equals(status) ? "中断 · 部分结果" : "尚未结束";
    }
    public static String localTime(String stamp) {
        try {
            String normalized = stamp.replaceFirst("(\\.\\d{3})\\d+(?=[Z+-])", "$1");
            Date date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(normalized);
            return new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(date);
        } catch (Exception e) { return stamp; }
    }

    private void showSettings() {
        LinearLayout body = begin("设置");
        LinearLayout reading = UiComponents.card(this), menu = UiComponents.card(this);
        reading.addView(UiComponents.heading(this, "阅读与提示")); menu.addView(UiComponents.heading(this, "使用与设备"));
        UiComponents.split(this, body, reading, menu, .58f);
        settingSwitch(reading, "训练语音提示", "播报开始、关键纠正、达到目标与结束，不逐次报数。", "voice", true);
        settingSwitch(reading, "默认前置摄像头", "进入准备页后仍可切换前后摄像头。", "front_camera", true);
        reading.addView(UiComponents.text(this, "字号与显示对比度跟随设备系统的辅助功能设置。", 15, UiComponents.MUTED));
        reading.addView(UiComponents.button(this, "系统显示与辅助功能", false, v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { notice.setText("设备未提供辅助功能设置，请联系工作人员"); }
        }));
        reading.addView(UiComponents.button(this, "中文语音设置", false, v -> {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (Exception e) { notice.setText("设备未提供语音设置，请联系工作人员"); }
        }));
        menu.addView(UiComponents.button(this, "隐私与使用告知", false, v -> navigate("隐私与使用告知")));
        if (SessionStore.staff(this)) {
            menu.addView(UiComponents.button(this, "设备与服务", false, v -> navigate("设备与服务")));
            menu.addView(UiComponents.button(this, "备份与恢复", false, v -> navigate("备份与恢复")));
            menu.addView(UiComponents.button(this, "患者管理", false, v -> navigate("患者管理")));
        }
        menu.addView(UiComponents.heading(this, "关于复健"));
        menu.addView(UiComponents.text(this, "术后康复辅助 · 机构局域网试用\n机器人 16:9 横屏 / 手机横屏\n" + (SessionStore.staff(this) ? "当前身份：工作人员" : "当前身份：患者 · 仅本人数据"), 15, UiComponents.MUTED));
        menu.addView(UiComponents.button(this, "退出登录", false, v -> SessionStore.logout(this)));
        UiComponents.tip(this, body, "按自己的节奏，循序练习", "主观反馈不自动调整训练强度。动作是否适合当前恢复阶段，请与工作人员确认。");
    }

    private void settingSwitch(LinearLayout parent, String label, String description, String key, boolean initial) {
        LinearLayout row = UiComponents.row(this); row.setPadding(0, UiComponents.dp(this, 20), 0, UiComponents.dp(this, 20));
        LinearLayout copy = UiComponents.column(this); copy.addView(UiComponents.text(this, label, 18, UiComponents.INK)); copy.addView(UiComponents.text(this, description, 14, UiComponents.MUTED));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(this);
        toggle.setContentDescription(label); toggle.setChecked(preferences.getBoolean(key, initial)); toggle.setMinHeight(UiComponents.dp(this, 48));
        toggle.setOnCheckedChangeListener((v, checked) -> preferences.edit().putBoolean(key, checked).apply()); row.addView(toggle); parent.addView(row);
    }

    private void showDevice() {
        LinearLayout body = begin("设备与服务");
        if (SessionStore.staff(this)) {
        LinearLayout connection = UiComponents.card(this);
        connection.addView(UiComponents.heading(this, "服务连接"));
        EditText address = input("http://电脑或 Jetson IP:8000", api.base(), false);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        connection.addView(address);
        connection.addView(UiComponents.button(this, "保存并测试连接", true, v -> {
            String base = RehabApiClient.normalize(address.getText().toString());
            if (base.isEmpty()) { address.setError("请输入有效的服务地址"); return; }
            if (!base.equals(api.base())) {
                api.post("/api/v1/auth/logout", RehabApiClient.object(), (d, e) -> {
                    preferences.edit().putString("server_url", base).apply(); SessionStore.login(this);
                }); return;
            }
            preferences.edit().putString("server_url", base).apply(); address.setText(base);
            notice.setText("正在检查服务与模型…");
            api.get("/health", (data, error) -> {
                if (error != null) { notice.setText(error); return; }
                JSONObject status = (JSONObject) data;
                notice.setText(status.optBoolean("model_loaded") ? "服务连接成功 · " + status.optString("device") + (status.optBoolean("busy") ? " · 正在训练" : " · 可开始训练") :
                    "业务服务可用，模型" + ("error".equals(status.optString("status")) ? "加载失败：" + status.optString("model_error") : "正在加载"));
            });
        })); body.addView(connection);
        }
        LinearLayout device = UiComponents.card(this);
        device.addView(UiComponents.heading(this, "设备与语音"));
        CheckBox front = new CheckBox(this); front.setText("默认使用前置摄像头"); front.setTextSize(18);
        front.setChecked(preferences.getBoolean("front_camera", true));
        front.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("front_camera", checked).apply()); device.addView(front);
        CheckBox voice = new CheckBox(this); voice.setText("播报关键提示与训练结束"); voice.setTextSize(18);
        voice.setChecked(preferences.getBoolean("voice", true));
        voice.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("voice", checked).apply()); device.addView(voice);
        boolean missingVoice = preferences.contains("tts_zh_available") && !preferences.getBoolean("tts_zh_available", false);
        device.addView(UiComponents.text(this, missingVoice ? "本机中文语音不可用，请安装中文 TTS 语音包。当前使用文字引导。" :
            "语音需要设备安装中文 TTS 语音包；不可用时仍可通过文字完成训练。", 18, missingVoice ? UiComponents.DANGER : UiComponents.MUTED));
        device.addView(UiComponents.button(this, "中文语音设置", false, v -> {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (Exception e) { notice.setText("设备未提供系统语音设置，请联系设备工作人员"); }
        }));
        device.addView(UiComponents.button(this, "诊断说明", false, v -> new AlertDialog.Builder(this).setTitle("连接与性能诊断")
            .setMessage("训练页显示服务端实际识别 FPS。点击 FPS 可查看采集、回传、处理、编码与 RTT。\n\n" + preferences.getString("last_diagnostics", "尚无训练诊断数据"))
            .setPositiveButton("知道了", null).show())); body.addView(device);
        if (SessionStore.staff(this)) {
        LinearLayout staff = UiComponents.card(this); staff.addView(UiComponents.heading(this, "工作人员"));
        staff.addView(UiComponents.button(this, "选择或新建患者", false, v -> choosePatient()));
        if (patient != null) {
            staff.addView(UiComponents.button(this, patient.optBoolean("archived") ? "编辑并恢复当前患者" : "编辑当前患者", false, v -> editPatient(patient)));
            if (!patient.optBoolean("archived")) staff.addView(UiComponents.button(this, "配置当前患者计划", false, v -> editPlan()));
            if (!patient.optBoolean("archived")) staff.addView(UiComponents.button(this, "归档当前患者", false, v -> new AlertDialog.Builder(this).setTitle("归档患者？")
                .setMessage("患者将从常用列表移出，历史记录保留，可在归档列表恢复。")
                .setNegativeButton("取消", null).setPositiveButton("归档", (dialog, which) -> api.request("PATCH", "/api/v1/patients/" + patient.optString("id"), RehabApiClient.object("archived", true), (data, error) -> {
                    if (error != null) notice.setText(error); else { patient = null; preferences.edit().remove("patient_id").apply(); updatePatientBadge(); showSettings(); }
                })).show()));
        }
        staff.addView(UiComponents.text(this, "训练结果为算法参考，请按工作人员指导练习。", 18, UiComponents.MUTED)); body.addView(staff);
        }
        body.addView(UiComponents.text(this, SessionStore.staff(this) ? "当前身份：工作人员" : "当前身份：患者 · 仅本人数据", 18, UiComponents.MUTED));
        body.addView(UiComponents.button(this, "退出登录", false, v -> SessionStore.logout(this)));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 201 && data != null && data.hasExtra("run_id")) {
            page = "训练记录";
            reportToOpen = data.getStringExtra("run_id");
        }
        if (request == 202) reportToOpen = data != null ? data.getStringExtra("run_id") : null;
    }
    private void stopVideo() { subtitleHandler.removeCallbacksAndMessages(null); if (video != null) { video.stopPlayback(); video = null; } if (media != null) media.cancel(); }
    @Override protected void onPause() { foreground = false; stopVideo(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("page", page); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { generation++; api.close(); media.close(); super.onDestroy(); }
    @Override public void onBackPressed() { if (offlineTeaching) { if (video != null) navigate("动作库"); else finish(); } else if (!"训练".equals(page) || video != null) navigate("训练"); else super.onBackPressed(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) UiComponents.immersive(this); }
}
