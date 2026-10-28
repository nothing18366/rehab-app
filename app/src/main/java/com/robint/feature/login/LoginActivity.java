package com.robint.feature.login;

import com.robint.R;
import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.home.HomeActivity;
import com.robint.ui.component.IconDrawable;
import com.robint.ui.component.UiComponents;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import org.json.JSONObject;

public class LoginActivity extends AppCompatActivity {
    private RehabApiClient api;
    private final Handler handler = new Handler();
    private TextView notice;
    private boolean staff, emailLogin, resetting, busy;
    private long resendAt;
    private EditText identity, secret, email, code, confirmation;
    private MaterialButton send, submit;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { staff = saved.getBoolean("staff"); emailLogin = saved.getBoolean("emailLogin"); resetting = saved.getBoolean("resetting"); resendAt = saved.getLong("resendAt"); }
        api = new RehabApiClient(this); UiComponents.immersive(this); show();
        if (!SessionStore.token(this).isEmpty()) api.get("/api/v1/auth/me", (data, error) -> {
            if (isFinishing()) return;
            if (error == null) enter(); else message(error + "；可查看已缓存教学");
        });
    }

    private EditText field(String hint, int type) {
        EditText view = new EditText(this); view.setSingleLine(); view.setHint(hint);
        view.setInputType(type); UiComponents.styleInput(view); return view;
    }

    private void icon(TextView view, String name, int size, int color) {
        IconDrawable drawable = new IconDrawable(name, color);
        drawable.setBounds(0, 0, UiComponents.dp(this, size), UiComponents.dp(this, size));
        view.setCompoundDrawables(drawable, null, null, null); view.setCompoundDrawablePadding(UiComponents.dp(this, 10));
    }

    private MaterialButton link(String text, View.OnClickListener click) {
        MaterialButton view = UiComponents.compact(this, text, false, click);
        view.setStrokeWidth(0); view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        view.setPadding(UiComponents.dp(this, 8), 0, UiComponents.dp(this, 8), 0); view.setTextSize(14);
        return view;
    }

    private void password(LinearLayout parent, EditText input) {
        FrameLayout box = new FrameLayout(this);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.setMargins(0, UiComponents.dp(this, 6), 0, UiComponents.dp(this, 12)); box.setLayoutParams(size);
        input.setPadding(UiComponents.dp(this, 14), UiComponents.dp(this, 10), UiComponents.dp(this, 60), UiComponents.dp(this, 10));
        icon(input, "lock", 20, UiComponents.MUTED); input.setMinHeight(UiComponents.dp(this, 52));
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));
        MaterialButton toggle = link("", v -> {
            boolean hidden = input.getTransformationMethod() instanceof PasswordTransformationMethod;
            input.setTransformationMethod(hidden ? null : PasswordTransformationMethod.getInstance());
            input.setSelection(input.length()); v.setContentDescription(hidden ? "隐藏密码" : "显示密码");
        });
        toggle.setContentDescription("显示密码"); toggle.setMinWidth(0);
        toggle.setIcon(new IconDrawable("eye", UiComponents.MUTED)); toggle.setIconSize(UiComponents.dp(this, 22)); toggle.setIconPadding(0);
        box.addView(toggle, new FrameLayout.LayoutParams(UiComponents.dp(this, 52), UiComponents.dp(this, 52), Gravity.END | Gravity.CENTER_VERTICAL)); parent.addView(box);
    }

    private void show() {
        handler.removeCallbacksAndMessages(null); send = null;
        setContentView(R.layout.activity_login);
        boolean compact = getResources().getConfiguration().screenHeightDp < 600;
        boolean portrait = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        int pad = UiComponents.dp(this, portrait || compact ? 24 : 40);
        LinearLayout intro = findViewById(R.id.login_intro), panel = findViewById(R.id.login_panel);
        int storyPad = UiComponents.dp(this, portrait || compact ? 24 : getResources().getConfiguration().screenWidthDp * .055f);
        intro.setPadding(storyPad, pad, storyPad, pad); panel.setPadding(pad, UiComponents.dp(this, 20), pad, UiComponents.dp(this, 16));
        LinearLayout brand = UiComponents.row(this);
        TextView brandMark = UiComponents.text(this, "", 20, Color.WHITE); icon(brandMark, "hand", 28, Color.WHITE);
        brandMark.setCompoundDrawablePadding(0); brandMark.setPadding(UiComponents.dp(this, 8), 0, 0, 0); brandMark.setGravity(Gravity.CENTER_VERTICAL);
        brandMark.setBackground(UiComponents.background(this, UiComponents.FOREST, 14));
        brand.addView(brandMark, new LinearLayout.LayoutParams(UiComponents.dp(this, 44), UiComponents.dp(this, 44)));
        LinearLayout brandCopy = UiComponents.column(this); brandCopy.setPadding(UiComponents.dp(this, 12), 0, 0, 0);
        TextView brandName = UiComponents.heading(this, "复健"); brandName.setTextSize(27); brandCopy.addView(brandName);
        brandCopy.addView(UiComponents.text(this, "R E H A B  T O G E T H E R", 8, UiComponents.MUTED)); brand.addView(brandCopy); intro.addView(brand);
        TextView eyebrow = UiComponents.text(this, "—  术后康复辅助", 14, UiComponents.PRIMARY);
        eyebrow.setPadding(0, UiComponents.dp(this, portrait ? 20 : compact ? 24 : 64), 0, UiComponents.dp(this, 16)); intro.addView(eyebrow);
        TextView title = UiComponents.heading(this, "陪伴每一步，\n让康复更从容。"); title.setTextSize(portrait ? 28 : compact ? 30 : 44); intro.addView(title);
        TextView description = UiComponents.text(this, "从看懂动作，到完成练习。\n用清晰的指引，陪你认真对待每一次训练。", 16, UiComponents.MUTED);
        description.setPadding(0, UiComponents.dp(this, 20), 0, UiComponents.dp(this, 24)); intro.addView(description);
        FrameLayout photoBox = new FrameLayout(this); photoBox.setBackground(UiComponents.background(this, UiComponents.MINT, 16)); photoBox.setClipToOutline(true);
        photoBox.addView(UiComponents.poster(this, "home_hands"), new FrameLayout.LayoutParams(-1, -1));
        TextView photoLabel = UiComponents.badge(this, "从双手开始，循序练习", false); icon(photoLabel, "hand", 16, UiComponents.PRIMARY);
        photoLabel.setBackground(UiComponents.background(this, Color.WHITE, 8));
        FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        caption.setMargins(UiComponents.dp(this, 14), 0, 0, UiComponents.dp(this, 14)); photoBox.addView(photoLabel, caption);
        int photoHeight = compact ? 160 : 225;
        if (!portrait) intro.addView(photoBox, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, photoHeight)));
        TextView steps = UiComponents.text(this, "01  观看示范   —   02  按计划练习   —   03  记录训练", 12, UiComponents.MUTED);
        steps.setPadding(0, UiComponents.dp(this, 20), 0, UiComponents.dp(this, 20)); if (!portrait) intro.addView(steps);
        if (!portrait) intro.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        TextView care = UiComponents.text(this, "以患者为中心，让每一次练习更清晰。", 12, UiComponents.MUTED); icon(care, "shield", 16, UiComponents.MUTED); if (!portrait) intro.addView(care);

        LinearLayout top = UiComponents.row(this);
        MaterialButton connection = link(compact ? "连接服务" : "康复训练站 · 连接服务", v -> serverDialog());
        connection.setTag("login_server"); connection.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        top.addView(connection, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(link("使用帮助", v -> showInformation(false))); panel.addView(top);
        LinearLayout center = UiComponents.column(this); center.setGravity(Gravity.CENTER);
        LinearLayout form = UiComponents.column(this);
        LinearLayout.LayoutParams formSize = new LinearLayout.LayoutParams(-1, -2);
        int available = Math.round(getResources().getConfiguration().screenWidthDp * (portrait ? 1 : .48f)) - (portrait || compact ? 48 : 80);
        if (available > 390) formSize.width = UiComponents.dp(this, 390);
        center.addView(form, formSize); panel.addView(center, new LinearLayout.LayoutParams(-1, -2, 1));
        TextView mark = UiComponents.badge(this, "", false); icon(mark, "lock", 24, UiComponents.PRIMARY); form.addView(mark);
        TextView heading = UiComponents.heading(this, resetting ? "找回患者密码" : "欢迎回来"); heading.setTextSize(portrait || compact ? 30 : 36);
        heading.setPadding(0, UiComponents.dp(this, 12), 0, UiComponents.dp(this, 8)); form.addView(heading);
        TextView subtitle = UiComponents.text(this, resetting ? "验证绑定邮箱后设置新密码，也可联系管理员重置。" : staff ? "登录工作人员账号，继续今天的康复陪伴。" : "使用管理员分配的账号，进入你的康复空间。", 15, UiComponents.MUTED);
        subtitle.setPadding(0, 0, 0, UiComponents.dp(this, 20)); form.addView(subtitle);

        form.addView(UiComponents.text(this, staff ? "工作人员账号" : emailLogin && !resetting ? "登录邮箱" : "患者账号", 15, UiComponents.INK));
        identity = field(staff ? "工作人员账号" : emailLogin && !resetting ? "已绑定的邮箱" : "管理员分配的患者账号",
            InputType.TYPE_CLASS_TEXT | (emailLogin && !resetting ? InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS : InputType.TYPE_TEXT_VARIATION_NORMAL));
        identity.setId(R.id.login_identity);
        icon(identity, "users", 20, UiComponents.MUTED); form.addView(identity);
        if (resetting) {
            form.addView(UiComponents.text(this, "绑定邮箱", 15, UiComponents.INK));
            email = field("患者档案中的绑定邮箱", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); email.setId(R.id.login_email); form.addView(email);
        }
        if (emailLogin || resetting) {
            form.addView(UiComponents.text(this, "邮箱验证码", 15, UiComponents.INK));
            code = field("六位邮箱验证码", InputType.TYPE_CLASS_NUMBER); code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
            code.setId(R.id.login_code);
            LinearLayout challenge = UiComponents.row(this); challenge.addView(code, new LinearLayout.LayoutParams(0, -2, 1));
            send = UiComponents.compact(this, "获取验证码", false, v -> sendCode());
            LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(-2, UiComponents.dp(this, 52)); sendSize.setMarginStart(UiComponents.dp(this, 8)); challenge.addView(send, sendSize); form.addView(challenge);
        }
        if (!emailLogin || resetting) {
            form.addView(UiComponents.text(this, resetting ? "新密码" : "密码", 15, UiComponents.INK));
            secret = field(resetting ? "新密码（8–128 个字符）" : "密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); secret.setId(R.id.login_secret); password(form, secret);
            if (resetting) {
                form.addView(UiComponents.text(this, "确认新密码", 15, UiComponents.INK));
                confirmation = field("再次输入新密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); confirmation.setId(R.id.login_confirmation); password(form, confirmation);
            }
        }
        if (!resetting) {
            LinearLayout recovery = UiComponents.row(this);
            recovery.addView(UiComponents.text(this, "共享设备，请使用本人账号", 12, UiComponents.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            recovery.addView(link("忘记密码？", v -> {
                if (busy) return;
                if (staff) new AlertDialog.Builder(this).setTitle("工作人员忘记密码")
                    .setMessage("请联系机构负责人或服务维护人员处理。患者密码可在工作人员登录后，通过“患者管理 → 编辑 → 患者登录账号”重置。")
                    .setPositiveButton("知道了", null).show();
                else { resetting = true; emailLogin = false; show(); }
            })); form.addView(recovery);
        }
        notice = UiComponents.text(this, "", 14, UiComponents.DANGER); notice.setVisibility(View.GONE); form.addView(notice);
        submit = UiComponents.button(this, resetting ? "重置密码" : "登录", true, v -> authenticate());
        submit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); submit.setIcon(new IconDrawable("arrow", Color.WHITE));
        submit.setIconSize(UiComponents.dp(this, 22));
        submit.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE)); submit.setIconGravity(MaterialButton.ICON_GRAVITY_END);
        form.addView(submit);
        EditText last = resetting ? confirmation : emailLogin ? code : secret; last.setImeOptions(EditorInfo.IME_ACTION_DONE);
        last.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { authenticate(); return true; } return false; });
        TextView divider = UiComponents.text(this, resetting ? "收不到邮件？请联系管理员核对绑定邮箱" : "——  使用这台训练设备  ——", 12, UiComponents.MUTED);
        divider.setGravity(Gravity.CENTER); divider.setPadding(0, UiComponents.dp(this, 24), 0, UiComponents.dp(this, 12)); form.addView(divider);
        form.addView(UiComponents.button(this, resetting ? "返回患者登录" : staff ? "患者训练入口" : "管理员入口", false, v -> {
            if (busy) return; if (!resetting) staff = !staff; resetting = false; emailLogin = false; show();
        }));
        if (!staff && !resetting) {
            form.addView(link(emailLogin ? "使用账号密码登录" : "使用邮箱验证码登录", v -> { if (!busy) { emailLogin = !emailLogin; show(); } }));
            form.addView(UiComponents.text(this, "账号由管理员分配，无需自行注册。", 12, UiComponents.MUTED));
        }
        if (!getSharedPreferences("rehab", 0).getString("action_catalog_" + api.base(), "").isEmpty())
            form.addView(link("离线查看已缓存教学", v -> startActivity(new Intent(this, HomeActivity.class).putExtra("offline_teaching", true))));
        MaterialButton privacy = link("隐私与使用告知 · 训练辅助工具，不替代专业指导", v -> showInformation(true)); privacy.setTextSize(11); panel.addView(privacy);
        countdown();
    }

    private void showInformation(boolean privacy) {
        String title = privacy ? "隐私与使用告知" : "使用帮助";
        String[][] sections = privacy ? new String[][]{
            {"数据保存与传输", "登录信息与训练数据保存在机构服务端。识别画面经局域网传输，本项目不保存摄像头视频。"},
            {"保护自己的账号", "请使用本人账号，离开共享设备时退出登录。"},
            {"训练辅助，不替代专业指导", "算法反馈不代表临床疗效结论。"}
        } : new String[][]{
            {"先连接训练服务", "请先设置训练服务地址。"},
            {"使用管理员分配的账号", "患者账号与初始密码由管理员分配；忘记密码可用绑定邮箱重置，或联系工作人员协助。"},
            {"感到不适时，立即结束", "训练中如感到不适，请立即结束并联系工作人员。"}
        };
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        LinearLayout body = UiComponents.column(this);
        int padding = UiComponents.dp(this, 24);
        body.setPadding(padding, padding, padding, padding);
        body.setBackground(UiComponents.background(this, Color.WHITE, 24));
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(body, title);
        LinearLayout header = UiComponents.row(this);
        TextView mark = UiComponents.text(this, "", 20, UiComponents.PRIMARY);
        icon(mark, privacy ? "shield" : "file", 26, UiComponents.PRIMARY);
        mark.setCompoundDrawablePadding(0); mark.setGravity(Gravity.CENTER);
        mark.setPadding(UiComponents.dp(this, 11), 0, 0, 0);
        mark.setBackground(UiComponents.background(this, UiComponents.MINT, 14));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, new LinearLayout.LayoutParams(UiComponents.dp(this, 48), UiComponents.dp(this, 48)));
        LinearLayout heading = UiComponents.column(this); heading.setPadding(UiComponents.dp(this, 16), 0, 0, 0);
        TextView label = UiComponents.heading(this, title); label.setTextSize(26); heading.addView(label);
        heading.addView(UiComponents.text(this, privacy ? "了解数据使用，安心开始训练" : "开始训练前，请先了解这些事项", 14, UiComponents.MUTED));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(header);
        LinearLayout content = UiComponents.column(this);
        content.setPadding(0, UiComponents.dp(this, 20), 0, UiComponents.dp(this, 8));
        for (int i = 0; i < sections.length; i++) {
            LinearLayout section = UiComponents.column(this);
            int inset = UiComponents.dp(this, 16);
            section.setPadding(inset, inset, inset, inset);
            if (i == sections.length - 1) section.setBackground(UiComponents.background(this, UiComponents.MINT, 16));
            TextView name = UiComponents.heading(this, sections[i][0]); name.setTextSize(18); section.addView(name);
            TextView copy = UiComponents.text(this, sections[i][1], 16, UiComponents.MUTED);
            copy.setPadding(0, UiComponents.dp(this, 8), 0, 0); section.addView(copy);
            content.addView(section);
            if (i == 0) UiComponents.divider(this, content);
        }
        body.addView(UiComponents.scroll(this, content), new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(UiComponents.button(this, "知道了", true, v -> dialog.dismiss()));
        dialog.setContentView(body);
        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(.4f);
        dialog.show();
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        window.setLayout(Math.min(UiComponents.dp(this, 640), screen.widthPixels - UiComponents.dp(this, 48)),
            Math.min(UiComponents.dp(this, 560), screen.heightPixels - UiComponents.dp(this, 48)));
        UiComponents.immersive(this);
    }

    private void message(String text) { message(text, UiComponents.DANGER); }
    private void message(String text, int color) { notice.setTextColor(color); notice.setText(text); notice.setVisibility(View.VISIBLE); }
    private void setBusy(boolean value) { busy = value; submit.setEnabled(!value); countdown(); }

    private void sendCode() {
        if (busy || System.currentTimeMillis() < resendAt) return;
        JSONObject payload = resetting ? RehabApiClient.object("username", identity.getText().toString().trim(), "email", email.getText().toString().trim()) : RehabApiClient.object("email", identity.getText().toString().trim());
        setBusy(true); message("正在发送邮件…", UiComponents.MUTED);
        api.post("/api/v1/auth/" + (resetting ? "password-reset-code" : "email-code"), payload, (data, error) -> {
            if (isFinishing()) return;
            if (error == null) resendAt = System.currentTimeMillis() + 60000;
            setBusy(false); message(error == null ? "验证码已发送，五分钟内有效，请检查收件箱或垃圾邮件。" : error, error == null ? UiComponents.PRIMARY : UiComponents.DANGER);
        });
    }

    private void authenticate() {
        if (busy) return;
        String username = identity.getText().toString().trim();
        if (username.isEmpty()) { identity.setError("请填写登录信息"); return; }
        String path; JSONObject payload;
        if (resetting) {
            String password = secret.getText().toString();
            if (password.length() < 8 || password.length() > 128) { secret.setError("密码需为 8–128 个字符"); return; }
            if (!password.equals(confirmation.getText().toString())) { confirmation.setError("两次密码不一致"); return; }
            path = "password-reset"; payload = RehabApiClient.object("username", username, "email", email.getText().toString().trim(), "code", code.getText().toString().trim(), "password", password);
        } else if (emailLogin) {
            path = "email-login"; payload = RehabApiClient.object("email", username, "code", code.getText().toString().trim());
        } else {
            if (secret.length() == 0) { secret.setError("请输入密码"); return; }
            path = staff ? "staff-login" : "patient-login"; payload = RehabApiClient.object("username", username, "password", secret.getText().toString());
        }
        setBusy(true); message(resetting ? "正在重置密码…" : "正在登录…", UiComponents.MUTED);
        api.post("/api/v1/auth/" + path, payload, (data, error) -> {
            if (isFinishing()) return;
            setBusy(false);
            if (error != null) { message(error); return; }
            if (resetting) { resetting = false; emailLogin = false; show(); identity.setText(username); message("密码已重置，请使用新密码登录。", UiComponents.PRIMARY); return; }
            try { SessionStore.save(this, (JSONObject) data); enter(); }
            catch (Exception e) { message("设备安全存储不可用，无法保存登录，请联系工作人员"); }
        });
    }

    private void serverDialog() {
        if (busy) return;
        LinearLayout body = UiComponents.column(this); int pad = UiComponents.dp(this, 24); body.setPadding(pad, pad, pad, 0);
        body.addView(UiComponents.text(this, "连接电脑或 Jetson 上的康复训练服务。", 16, UiComponents.MUTED));
        EditText address = field("http://电脑或 Jetson IP:8000", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); address.setText(api.base()); body.addView(address);
        TextView state = UiComponents.text(this, "", 14, UiComponents.DANGER); body.addView(state);
        MaterialButton test = UiComponents.button(this, "保存并测试连接", true, null); body.addView(test);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接服务").setView(UiComponents.scroll(this, body)).setNegativeButton("关闭", null).create();
        test.setOnClickListener(v -> {
            String base = RehabApiClient.normalize(address.getText().toString());
            if (base.isEmpty()) { address.setError("请输入有效服务地址"); return; }
            if (!base.equals(api.base())) { SessionStore.clear(this); resendAt = 0; }
            getSharedPreferences("rehab", 0).edit().putString("server_url", base).apply(); address.setText(base);
            test.setEnabled(false); state.setText("正在检查连接…");
            api.get("/health", (data, error) -> {
                test.setEnabled(true); state.setText(error != null ? error : ((JSONObject) data).optBoolean("model_loaded") ? "连接成功 · 模型就绪" : "业务服务可用 · 模型尚未就绪");
            });
        }); dialog.show();
    }

    private void countdown() {
        handler.removeCallbacksAndMessages(null);
        if (send == null) return;
        int left = Math.max(0, (int) Math.ceil((resendAt - System.currentTimeMillis()) / 1000.0));
        send.setEnabled(left == 0 && !busy); send.setText(left > 0 ? left + " 秒后重发" : "获取验证码");
        if (left > 0) handler.postDelayed(this::countdown, 1000);
    }
    private void enter() { startActivity(new Intent(this, HomeActivity.class)); finish(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("staff", staff); saved.putBoolean("emailLogin", emailLogin); saved.putBoolean("resetting", resetting); saved.putLong("resendAt", resendAt);
        super.onSaveInstanceState(saved);
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); api.close(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) UiComponents.immersive(this); }
}
