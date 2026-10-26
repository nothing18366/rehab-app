package com.robint.feature.feedback;

import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.ui.component.ChoiceView;
import com.robint.ui.component.UiComponents;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import org.json.JSONObject;

public class FeedbackActivity extends AppCompatActivity {
    private RehabApiClient api;
    private String runId, phase;
    private boolean busy;
    private ChoiceView pain, fatigue, discomfort, difficulty;
    private EditText note;
    private TextView status;
    private MaterialButton submit, skip;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); UiComponents.immersive(this); api = new RehabApiClient(this);
        runId = getIntent().getStringExtra("run_id"); phase = getIntent().getStringExtra("phase");
        LinearLayout outer = UiComponents.column(this); int padding = UiComponents.dp(this, 28); outer.setPadding(padding, padding, padding, padding);
        outer.setBackgroundColor(UiComponents.BACKGROUND);
        UiComponents.journey(this, outer, "pre".equals(phase) ? 0 : 3);
        UiComponents.section(this, outer, "pre".equals(phase) ? "训练前感受" : "训练后感受", "pre".equals(phase) ? "开始前，先关心一下自己。以下是主观感受，可留空或跳过。" : "这一程辛苦了，记录一下身体现在的感受。");
        LinearLayout body = UiComponents.card(this), side = UiComponents.column(this), snapshot = UiComponents.card(this);
        side.addView(snapshot); snapshot.addView(UiComponents.heading(this, "本次训练"));
        TextView patient = UiComponents.text(this, "正在读取训练信息…", 17, UiComponents.MUTED); snapshot.addView(patient);
        UiComponents.tip(this, side, "不适时可以停下来", "若有新的疼痛、麻木、头晕或其他不适，请联系工作人员。本工具不会自动判断是否适宜继续。");
        UiComponents.split(this, outer, body, side, .68f);
        pain = score(body, "疼痛程度 · 0 无疼痛，10 最强", 10);
        fatigue = score(body, "疲劳程度 · 0 无疲劳，10 最强", 10);
        discomfort = select(body, "是否有不适", new String[]{"未填写", "没有不适", "有不适"});
        if ("post".equals(phase)) difficulty = select(body, "本次困难程度", new String[]{"未填写", "很轻松", "较轻松", "适中", "较困难", "很困难"});
        note = new EditText(this); note.setHint("不适说明（选填，最多 200 字）"); note.setTextSize(18);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)}); UiComponents.styleInput(note); body.addView(UiComponents.text(this, "补充说明（选填）", 16, UiComponents.MUTED)); body.addView(note);
        status = UiComponents.text(this, "", 18, UiComponents.DANGER); body.addView(status);
        submit = UiComponents.button(this, "保存并继续", true, v -> save(false)); body.addView(submit);
        skip = UiComponents.button(this, "跳过本页", false, v -> save(true)); body.addView(skip);
        if ("post".equals(phase)) body.addView(UiComponents.button(this, "稍后补填，查看结果", false, v -> done()));
        else body.addView(UiComponents.button(this, "取消本次训练", false, v -> { setResult(RESULT_CANCELED); finish(); }));
        if (saved != null) {
            pain.setSelection(saved.getInt("pain")); fatigue.setSelection(saved.getInt("fatigue")); discomfort.setSelection(saved.getInt("discomfort"));
            if (difficulty != null) difficulty.setSelection(saved.getInt("difficulty")); note.setText(saved.getString("note", ""));
        }
        setContentView(UiComponents.scroll(this, outer));
        api.get("/api/v1/training-runs/" + runId, (data, error) -> {
            if (error != null) { status.setText(error); return; }
            JSONObject run = (JSONObject) data, p = run.optJSONObject("patient");
            patient.setText(p.optString("name") + " · " + p.optString("code") + " · " + RehabApiClient.side(p));
            UiComponents.value(this, snapshot, "训练模式", "plan".equals(run.optString("mode")) ? "按计划训练" : "单项练习");
            UiComponents.value(this, snapshot, "动作数量", run.optJSONArray("items").length() + " 项");
            JSONObject existing = run.optJSONObject("feedback").optJSONObject(phase);
            if (saved == null && existing != null && "submitted".equals(existing.optString("state"))) {
                JSONObject values = existing.optJSONObject("data");
                pain.setSelection(values.isNull("pain") ? 0 : values.optInt("pain") + 1);
                fatigue.setSelection(values.isNull("fatigue") ? 0 : values.optInt("fatigue") + 1);
                discomfort.setSelection(values.isNull("discomfort") ? 0 : values.optBoolean("discomfort") ? 2 : 1);
                if (difficulty != null) difficulty.setSelection(values.optInt("difficulty", 0));
                note.setText(values.isNull("note") ? "" : values.optString("note"));
            }
        });
    }
    private ChoiceView score(LinearLayout body, String label, int max) {
        String[] options = new String[max + 2]; options[0] = "未填写";
        for (int i = 0; i <= max; i++) options[i + 1] = String.valueOf(i);
        return select(body, label, options);
    }
    private ChoiceView select(LinearLayout body, String label, String[] options) {
        int columns = options.length > 6 ? 6 : options.length > 3 ? 3 : options.length;
        ChoiceView choice = new ChoiceView(this, label, options, columns); body.addView(choice); return choice;
    }
    private Object scoreValue(ChoiceView view) { return view.getSelectedItemPosition() == 0 ? JSONObject.NULL : view.getSelectedItemPosition() - 1; }
    private void save(boolean skipped) {
        if (busy) return; busy = true; submit.setEnabled(false); skip.setEnabled(false);
        JSONObject payload = RehabApiClient.object("state", skipped ? "skipped" : "submitted");
        if (!skipped) payload = RehabApiClient.object("state", "submitted", "pain", scoreValue(pain), "fatigue", scoreValue(fatigue),
            "discomfort", discomfort.getSelectedItemPosition() == 0 ? JSONObject.NULL : discomfort.getSelectedItemPosition() == 2,
            "note", note.getText().length() == 0 ? JSONObject.NULL : note.getText().toString(),
            "difficulty", difficulty == null || difficulty.getSelectedItemPosition() == 0 ? JSONObject.NULL : difficulty.getSelectedItemPosition());
        api.request("PUT", "/api/v1/training-runs/" + runId + "/feedback/" + phase, payload, (data, error) -> {
            busy = false; submit.setEnabled(true); skip.setEnabled(true);
            if (error != null) status.setText(error + ("post".equals(phase) ? "。训练结果独立保存，可稍后补填。" : "。请保存或跳过成功后再开始。"));
            else done();
        });
    }
    private void done() { setResult(RESULT_OK, new Intent().putExtra("run_id", runId)); finish(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putInt("pain", pain.getSelectedItemPosition()); saved.putInt("fatigue", fatigue.getSelectedItemPosition()); saved.putInt("discomfort", discomfort.getSelectedItemPosition());
        if (difficulty != null) saved.putInt("difficulty", difficulty.getSelectedItemPosition()); saved.putString("note", note.getText().toString());
        super.onSaveInstanceState(saved);
    }
    @Override protected void onResume() { super.onResume(); SessionStore.require(this); UiComponents.immersive(this); }
    @Override protected void onDestroy() { api.close(); super.onDestroy(); }
}
