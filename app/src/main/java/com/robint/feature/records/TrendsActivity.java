package com.robint.feature.records;

import com.robint.R;
import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;
import com.robint.feature.home.HomeActivity;
import com.robint.ui.component.UiComponents;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class TrendsActivity extends AppCompatActivity {
    private RehabApiClient api;
    private JSONArray catalog;
    private LinearLayout results;
    private Spinner action, type;
    private EditText start, end, revision;
    private TextView notice;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); api = new RehabApiClient(this); UiComponents.immersive(this);
        try { catalog = new JSONArray(getIntent().getStringExtra("catalog")); } catch (Exception e) { catalog = new JSONArray(); }
        LinearLayout body = UiComponents.column(this); int padding = UiComponents.dp(this, 28); body.setPadding(padding, padding, padding, padding); body.setBackgroundColor(UiComponents.BACKGROUND);
        UiComponents.section(this, body, "训练趋势 · 同条件比较", "只比较同一条件的练习，让每个数字都有清楚的含义。");
        LinearLayout filters = UiComponents.card(this); body.addView(filters);
        LinearLayout left = UiComponents.column(this), middle = UiComponents.column(this), right = UiComponents.column(this);
        LinearLayout filterRow = UiComponents.row(this); filterRow.setGravity(android.view.Gravity.TOP); filters.addView(filterRow);
        boolean portrait = UiComponents.portrait(this); if (portrait) filterRow.setOrientation(LinearLayout.VERTICAL);
        for (LinearLayout column : new LinearLayout[]{left, middle, right}) { LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(portrait ? -1 : 0, -2, portrait ? 0 : 1); size.rightMargin = portrait ? 0 : UiComponents.dp(this, 16); filterRow.addView(column, size); }
        String[] names = new String[catalog.length()]; for (int i = 0; i < names.length; i++) names[i] = catalog.optJSONObject(i).optString("name");
        left.addView(UiComponents.text(this, "动作", 14, UiComponents.MUTED)); action = select(left, names);
        left.addView(UiComponents.text(this, "训练类型", 14, UiComponents.MUTED)); type = select(left, new String[]{"全部训练类型", "计划训练", "单项练习"});
        action.setContentDescription("趋势动作"); type.setContentDescription("趋势训练类型");
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US); format.setTimeZone(today.getTimeZone());
        String last = format.format(today.getTime()); today.add(Calendar.DAY_OF_MONTH, -29);
        start = input(middle, "开始日期 YYYY-MM-DD", format.format(today.getTime())); end = input(middle, "结束日期 YYYY-MM-DD", last);
        revision = input(right, "计划版本（留空为全部）", ""); revision.setInputType(InputType.TYPE_CLASS_NUMBER);
        right.addView(UiComponents.button(this, "查询趋势", true, v -> load()));
        notice = UiComponents.text(this, "", 15, UiComponents.MUTED); body.addView(notice);
        results = UiComponents.column(this); body.addView(results); body.addView(UiComponents.button(this, "返回", false, v -> finish()));
        setContentView(UiComponents.scroll(this, body)); load();
    }
    private Spinner select(LinearLayout body, String[] names) {
        Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        body.addView(spinner, new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 56))); return spinner;
    }
    private EditText input(LinearLayout body, String hint, String value) {
        body.addView(UiComponents.text(this, hint, 14, UiComponents.MUTED)); EditText edit = new EditText(this);
        edit.setSingleLine(); edit.setHint(hint); edit.setText(value); UiComponents.styleInput(edit); body.addView(edit); return edit;
    }
    private void load() {
        if (catalog.length() == 0) { notice.setText("暂无动作目录，请返回联网同步"); return; }
        String path = "/api/v1/patients/" + getIntent().getStringExtra("patient_id") + "/trends?action=" + catalog.optJSONObject(action.getSelectedItemPosition()).optString("key") +
            "&start=" + android.net.Uri.encode(start.getText().toString().trim()) + "&end=" + android.net.Uri.encode(end.getText().toString().trim());
        if (type.getSelectedItemPosition() > 0) path += "&mode=" + (type.getSelectedItemPosition() == 1 ? "plan" : "single");
        if (revision.getText().length() > 0) path += "&revision=" + android.net.Uri.encode(revision.getText().toString());
        notice.setText("正在统计…");
        api.get(path, (data, error) -> {
            if (isFinishing()) return; results.removeAllViews();
            if (error != null) { notice.setText(error); return; }
            notice.setText("无数据日期留空；合格率为算法判定，不代表康复疗效。"); JSONObject response = (JSONObject) data;
            JSONArray changes = response.optJSONArray("plan_changes");
            for (int i = 0; i < changes.length(); i++) {
                JSONObject change = changes.optJSONObject(i); StringBuilder text = new StringBuilder("计划变更 · ").append(change.optString("effective_from")).append(" · 版本 ").append(change.optInt("revision"));
                JSONArray items = change.optJSONArray("items");
                for (int j = 0; j < items.length(); j++) text.append("\n").append(name(items.optJSONObject(j).optString("action"))).append(" ").append(items.optJSONObject(j).optInt("target_reps")).append(" 次");
                text.append("\n训练日（1=周一）：").append(change.optJSONArray("weekdays")); results.addView(UiComponents.text(this, text.toString(), 18, UiComponents.PRIMARY));
            }
            JSONArray groups = response.optJSONArray("groups");
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i), c = group.optJSONObject("conditions"); LinearLayout card = UiComponents.card(this); results.addView(card);
                card.addView(UiComponents.heading(this, "条件组 " + (i + 1) + " · 目标 " + c.optInt("target_reps") + " 次"));
                card.addView(UiComponents.text(this, RehabApiClient.side(c) + " · 前臂 " + c.optDouble("forearm_cm") + " cm · 计划版本 " + c.optString("plan_revision") + " · " + ("plan".equals(c.optString("mode")) ? "计划训练" : "单项练习") + "\n算法版本 " + c.optString("algorithm_version"), 18, UiComponents.MUTED));
                card.addView(UiComponents.text(this, group.isNull("rate") ? "算法合格率：暂无判定（完成次数为零或无正常训练）" : String.format(Locale.CHINA, "正常训练算法合格率 %.1f%% · %d / %d 次", group.optDouble("rate"), group.optInt("passed"), group.optInt("count")), 20, UiComponents.INK));
                card.addView(new CountChart(this, group.optJSONArray("points"), response.optString("start"), response.optString("end"), c.optInt("target_reps")), new LinearLayout.LayoutParams(-1, UiComponents.dp(this, 230)));
                points(card, group.optJSONArray("points"));
            }
            JSONArray legacy = response.optJSONArray("legacy");
            if (legacy.length() > 0) { results.addView(UiComponents.heading(this, "历史条件不完整 · 不参与条件组汇总")); points(results, legacy); }
            if (groups.length() == 0 && legacy.length() == 0) results.addView(UiComponents.text(this, "所选区间暂无训练数据", 22, UiComponents.MUTED));
        });
    }
    private String name(String key) { for (int i = 0; i < catalog.length(); i++) if (key.equals(catalog.optJSONObject(i).optString("key"))) return catalog.optJSONObject(i).optString("name"); return key; }
    private void points(LinearLayout body, JSONArray points) {
        for (int i = 0; i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            body.addView(UiComponents.text(this, p.optString("date") + " · " + (p.optBoolean("partial") ? "部分结果" : "正常结束") + " · 完成 " + p.optInt("count") + "/" + p.optInt("target") + " 次 · 用时 " + String.format(Locale.CHINA, "%.1f 秒", p.optDouble("elapsed_seconds")) +
                "\n算法合格率 " + (p.isNull("rate") ? "暂无判定" : String.format(Locale.CHINA, "%.1f%%（%d/%d）", p.optDouble("rate"), p.optInt("passed"), p.optInt("count"))) + "\n" + HomeActivity.feedbackText(p.optJSONObject("feedback")), 18, p.optBoolean("partial") ? UiComponents.DANGER : UiComponents.INK));
            body.addView(UiComponents.button(this, "查看该次训练详情", false, v -> startActivity(new Intent(this, HomeActivity.class).putExtra("open_run", p.optString("run_id")))));
        }
    }
    public static class CountChart extends View {
        private final JSONArray points; private final long first, last; private final int target;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CountChart(Context context, JSONArray values, String start, String end, int target) { super(context); points = values; first = day(start); last = day(end); this.target = target; }
        static long day(String value) { try { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.parse(value).getTime() / 86400000; } catch (Exception e) { return 0; } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float u = getResources().getDisplayMetrics().density, l = 44*u, r = getWidth()-24*u, top = 38*u, bottom = getHeight()-42*u;
            int max = Math.max(1, target); for (int i = 0; i < points.length(); i++) max = Math.max(max, points.optJSONObject(i).optInt("count"));
            paint.setTextSize(16*u); paint.setColor(UiComponents.MUTED); paint.setStrokeWidth(u); canvas.drawLine(l, top, l, bottom, paint); canvas.drawLine(l, bottom, r, bottom, paint);
            canvas.drawText(String.valueOf(max), 4*u, top+5*u, paint); canvas.drawText("0", 20*u, bottom, paint);
            float ty = bottom-target*(bottom-top)/max; paint.setColor(0xffb4cbd0); canvas.drawLine(l, ty, r, ty, paint);
            paint.setColor(UiComponents.MUTED); canvas.drawText("目标 " + target + " 次 · 蓝绿=正常 / 橙=部分", l, 22*u, paint);
            for (int i = 0; i < points.length(); i++) {
                JSONObject p = points.optJSONObject(i); float x = l+(r-l)*(day(p.optString("date"))-first+0.5f)/Math.max(1,last-first+1);
                float y = bottom-(bottom-top)*p.optInt("count")/max; paint.setColor(p.optBoolean("partial") ? 0xffcc7d2a : UiComponents.PRIMARY); canvas.drawCircle(x,y,5*u,paint);
            }
            paint.setColor(UiComponents.MUTED); SimpleDateFormat f = new SimpleDateFormat("MM-dd", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
            canvas.drawText(f.format(new java.util.Date(first*86400000)), l, bottom+25*u, paint); canvas.drawText(f.format(new java.util.Date(last*86400000)), r-50*u, bottom+25*u, paint);
        }
    }
    @Override protected void onResume() { super.onResume(); SessionStore.require(this); }
    @Override protected void onDestroy() { api.close(); super.onDestroy(); }
}
