package com.robint.feature.records;

import com.robint.data.remote.RehabApiClient;
import com.robint.feature.home.HomeActivity;
import com.robint.ui.component.UiComponents;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Embedded in the history workspace; its requests end when the patient or page changes. */
public class TrendsView extends LinearLayout {
    public interface OpenRun { void open(String id); }
    private final RehabApiClient api;
    private final JSONArray catalog;
    private final String patientId;
    private final OpenRun openRun;
    private final Bundle filtersState;
    private final LinearLayout results;
    private final Spinner action, type;
    private final EditText start, end, revision;
    private final TextView notice;
    private final MaterialButton query;
    private int requestGeneration;
    private boolean closed;

    public TrendsView(Context context, String patientId, JSONArray catalog, Bundle filtersState, OpenRun openRun) {
        super(context);
        this.patientId = patientId; this.catalog = catalog; this.filtersState = filtersState; this.openRun = openRun;
        setTag("training_trends");
        api = new RehabApiClient(context);
        setOrientation(VERTICAL); setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        LinearLayout filters = UiComponents.card(context); addView(filters);
        LinearLayout row = UiComponents.row(context); row.setGravity(Gravity.BOTTOM); filters.addView(row);
        boolean stacked = UiComponents.portrait(context);
        if (stacked) row.setOrientation(VERTICAL);
        LinearLayout actions = filterColumn(row, stacked, 1.4f), types = filterColumn(row, stacked, 1);
        String[] names = new String[catalog.length()];
        for (int i = 0; i < names.length; i++) names[i] = catalog.optJSONObject(i).optString("name");
        actions.addView(UiComponents.text(context, "训练动作", 14, UiComponents.MUTED)); action = select(actions, names);
        types.addView(UiComponents.text(context, "训练类型", 14, UiComponents.MUTED)); type = select(types, new String[]{"全部训练类型", "计划训练", "单项练习"});
        action.setContentDescription("趋势动作"); type.setContentDescription("趋势训练类型");
        action.setSelection(Math.min(filtersState.getInt("action", 0), Math.max(0, catalog.length() - 1)));
        type.setSelection(filtersState.getInt("type", 0));
        LinearLayout submit = filterColumn(row, stacked, .8f);
        query = UiComponents.button(context, "查询趋势", true, v -> load());
        query.setMinHeight(UiComponents.dp(context, 52)); query.setMinimumHeight(UiComponents.dp(context, 52));
        LinearLayout.LayoutParams querySize = new LinearLayout.LayoutParams(-1, UiComponents.dp(context, 52));
        querySize.topMargin = stacked ? UiComponents.dp(context, 12) : 0;
        submit.addView(query, querySize);
        LinearLayout period = UiComponents.row(context); filters.addView(period);
        for (int days : new int[]{7, 30, 90}) period.addView(UiComponents.compact(context, "近 " + days + " 天", false, v -> { dates(days); load(); }));
        LinearLayout advanced = UiComponents.column(context);
        LinearLayout dates = UiComponents.row(context); dates.setGravity(Gravity.TOP); advanced.addView(dates);
        if (stacked) dates.setOrientation(VERTICAL);
        LinearLayout first = filterColumn(dates, stacked, 1), last = filterColumn(dates, stacked, 1), version = filterColumn(dates, stacked, 1);
        start = input(first, "开始日期", ""); end = input(last, "结束日期", "");
        start.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        end.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        revision = input(version, "计划版本（全部可留空）", ""); revision.setInputType(InputType.TYPE_CLASS_NUMBER);
        dates(30);
        if (filtersState.containsKey("start")) {
            start.setText(filtersState.getString("start")); end.setText(filtersState.getString("end")); revision.setText(filtersState.getString("revision"));
        }
        MaterialButton custom = UiComponents.compact(context, "自定义日期与版本  ▾", false, v -> {
            boolean expand = advanced.getVisibility() != VISIBLE;
            advanced.setVisibility(expand ? VISIBLE : GONE);
            ((MaterialButton) v).setText(expand ? "收起日期与版本  ▴" : "自定义日期与版本  ▾");
        });
        if (stacked) filters.addView(custom); else period.addView(custom);
        advanced.setVisibility(GONE); filters.addView(advanced);
        notice = UiComponents.text(context, "", 14, UiComponents.MUTED); addView(notice);
        results = UiComponents.column(context); addView(results);
        load();
    }

    private LinearLayout filterColumn(LinearLayout row, boolean stacked, float weight) {
        LinearLayout column = UiComponents.column(getContext());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : weight);
        size.setMarginEnd(stacked ? 0 : UiComponents.dp(getContext(), 16)); row.addView(column, size); return column;
    }

    private Spinner select(LinearLayout body, String[] names) {
        Spinner spinner = new Spinner(getContext()); spinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, names));
        FrameLayout field = new FrameLayout(getContext()); field.setBackground(UiComponents.background(getContext(), UiComponents.BACKGROUND, 12));
        spinner.setBackgroundColor(android.graphics.Color.TRANSPARENT); spinner.setPadding(0, 0, UiComponents.dp(getContext(), 32), 0);
        field.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
        TextView arrow = UiComponents.text(getContext(), "▾", 18, UiComponents.PRIMARY); arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(arrow, new FrameLayout.LayoutParams(UiComponents.dp(getContext(), 40), -1, Gravity.END));
        body.addView(field, new LinearLayout.LayoutParams(-1, UiComponents.dp(getContext(), 52))); return spinner;
    }

    private EditText input(LinearLayout body, String hint, String value) {
        body.addView(UiComponents.text(getContext(), hint, 14, UiComponents.MUTED));
        EditText edit = new EditText(getContext()); edit.setSingleLine(); edit.setHint(hint); edit.setText(value);
        UiComponents.styleInput(edit); body.addView(edit); return edit;
    }

    private void dates(int days) {
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US); format.setTimeZone(today.getTimeZone());
        end.setText(format.format(today.getTime())); today.add(Calendar.DAY_OF_MONTH, 1 - days); start.setText(format.format(today.getTime()));
    }

    private void load() {
        if (catalog.length() == 0) { notice.setText("暂无动作目录，请返回联网同步"); return; }
        filtersState.putInt("action", action.getSelectedItemPosition()); filtersState.putInt("type", type.getSelectedItemPosition());
        filtersState.putString("start", start.getText().toString()); filtersState.putString("end", end.getText().toString()); filtersState.putString("revision", revision.getText().toString());
        String key = catalog.optJSONObject(action.getSelectedItemPosition()).optString("key");
        String path = "/api/v1/patients/" + patientId + "/trends?action=" + key +
            "&start=" + android.net.Uri.encode(start.getText().toString().trim()) + "&end=" + android.net.Uri.encode(end.getText().toString().trim());
        if (type.getSelectedItemPosition() > 0) path += "&mode=" + (type.getSelectedItemPosition() == 1 ? "plan" : "single");
        if (revision.getText().length() > 0) path += "&revision=" + android.net.Uri.encode(revision.getText().toString());
        int ticket = ++requestGeneration;
        query.setEnabled(false); notice.setText("正在统计…");
        api.get(path, (data, error) -> {
            if (closed || ticket != requestGeneration) return;
            query.setEnabled(true);
            if (error != null) { results.removeAllViews(); notice.setText(error); return; }
            results.removeAllViews();
            JSONObject response = (JSONObject) data;
            notice.setText(response.optString("start") + " — " + response.optString("end") + " · 仅比较同一条件的训练，合格率为算法判定。无数据日期留空。");
            JSONArray groups = response.optJSONArray("groups"), legacy = response.optJSONArray("legacy"), changes = response.optJSONArray("plan_changes");
            for (int i = 0; i < groups.length(); i++) group(groups.optJSONObject(i), response, key, i);
            if (groups.length() == 0 && legacy.length() == 0) UiComponents.tip(getContext(), results, "这段时间还没有训练数据", "换一个动作或日期范围试试。完成训练后，趋势会显示在这里。");
            if (legacy.length() > 0) {
                LinearLayout card = UiComponents.card(getContext()); results.addView(card);
                card.addView(UiComponents.heading(getContext(), "历史条件不完整"));
                card.addView(UiComponents.text(getContext(), "保留原始记录，不参与同条件统计。", 15, UiComponents.MUTED));
                LinearLayout detail = UiComponents.column(getContext()); points(detail, legacy);
                disclose(card, "查看历史记录 · " + legacy.length() + " 条", detail);
            }
            if (changes.length() > 0) planChanges(changes);
        });
    }

    private void group(JSONObject group, JSONObject response, String key, int index) {
        Context context = getContext(); JSONObject conditions = group.optJSONObject("conditions"); JSONArray points = group.optJSONArray("points");
        LinearLayout card = UiComponents.card(context); results.addView(card);
        LinearLayout head = UiComponents.row(context); card.addView(head);
        head.addView(UiComponents.heading(context, name(key) + " · 目标 " + conditions.optInt("target_reps") + " 次"), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(UiComponents.badge(context, "比较组 " + (index + 1), false));
        card.addView(UiComponents.text(context, RehabApiClient.side(conditions) + " · " + ("plan".equals(conditions.optString("mode")) ? "计划训练" : "单项练习")
            + " · 计划版本 " + conditions.optString("plan_revision", "—"), 14, UiComponents.MUTED));
        LinearLayout metrics = UiComponents.row(context); metrics.setPadding(0, UiComponents.dp(context, 16), 0, UiComponents.dp(context, 16)); card.addView(metrics);
        metric(metrics, "算法合格率", group.isNull("rate") ? "暂无判定" : String.format(Locale.CHINA, "%.1f%%", group.optDouble("rate")));
        metric(metrics, "合格 / 完成次数", group.optInt("passed") + " / " + group.optInt("count"));
        metric(metrics, "训练记录（含部分结果）", points.length() + " 次");
        card.addView(UiComponents.text(context, "合格率仅统计正常结束的训练；不同比较组分别展示。", 13, UiComponents.MUTED));
        CountChart chart = new CountChart(context, points, response.optString("start"), response.optString("end"), conditions.optInt("target_reps"));
        chart.setContentDescription(name(key) + "完成次数趋势。目标 " + conditions.optInt("target_reps") + " 次，共 " + points.length() + " 条记录；具体数值可展开训练明细查看。");
        card.addView(chart, new LinearLayout.LayoutParams(-1, UiComponents.dp(context, 220)));
        card.addView(UiComponents.text(context, "绿色点：正常结束    橙色点：部分结果    横线：目标次数", 13, UiComponents.MUTED));
        LinearLayout detail = UiComponents.column(context);
        detail.addView(UiComponents.text(context, "比较条件：" + RehabApiClient.side(conditions) + " · 前臂 " + conditions.optDouble("forearm_cm") + " cm · 计划版本 "
            + conditions.optString("plan_revision") + "\n算法版本：" + conditions.optString("algorithm_version"), 14, UiComponents.MUTED));
        points(detail, points); disclose(card, "查看训练明细与比较条件 · " + points.length() + " 条", detail);
    }

    private void metric(LinearLayout row, String label, String value) {
        LinearLayout column = UiComponents.column(getContext());
        TextView number = UiComponents.heading(getContext(), value); number.setTextSize(UiComponents.portrait(getContext()) ? 22 : 30); number.setTextColor(UiComponents.PRIMARY);
        column.addView(number); column.addView(UiComponents.text(getContext(), label, 13, UiComponents.MUTED));
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void disclose(LinearLayout parent, String label, LinearLayout detail) {
        MaterialButton toggle = UiComponents.compact(getContext(), label + "  ▾", false, v -> {
            boolean expand = detail.getVisibility() != VISIBLE;
            detail.setVisibility(expand ? VISIBLE : GONE); ((MaterialButton) v).setText(label + (expand ? "  ▴" : "  ▾"));
        });
        parent.addView(toggle); detail.setVisibility(GONE); parent.addView(detail);
    }

    private void planChanges(JSONArray changes) {
        Context context = getContext(); LinearLayout card = UiComponents.card(context); results.addView(card);
        card.addView(UiComponents.heading(context, "计划调整记录"));
        card.addView(UiComponents.text(context, "所选区间内 " + changes.length() + " 次生效记录 · 调整前后分别比较", 15, UiComponents.MUTED));
        LinearLayout detail = UiComponents.column(context);
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.optJSONObject(i);
            LinearLayout head = UiComponents.row(context); detail.addView(head);
            head.addView(UiComponents.text(context, change.optString("effective_from") + " 生效", 18, UiComponents.INK), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(UiComponents.badge(context, "版本 " + change.optInt("revision"), false));
            detail.addView(UiComponents.text(context, weekdays(change.optJSONArray("weekdays")), 15, UiComponents.MUTED));
            JSONArray items = change.optJSONArray("items");
            int columns = getResources().getConfiguration().screenWidthDp > 1000 ? 2 : 1;
            LinearLayout row = null;
            for (int j = 0; j < items.length(); j++) {
                if (j % columns == 0) { row = UiComponents.row(context); row.setGravity(Gravity.TOP); detail.addView(row); }
                JSONObject item = items.optJSONObject(j);
                LinearLayout action = UiComponents.row(context); int pad = UiComponents.dp(context, 14); action.setPadding(pad, pad, pad, pad);
                action.setBackground(UiComponents.background(context, UiComponents.BACKGROUND, 12));
                action.addView(UiComponents.badge(context, String.format(Locale.CHINA, "%02d", j + 1), false));
                TextView name = UiComponents.text(context, name(item.optString("action")), 17, UiComponents.INK); name.setPadding(UiComponents.dp(context, 12), 0, UiComponents.dp(context, 8), 0);
                action.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                action.addView(UiComponents.text(context, item.optInt("target_reps") + " 次", 18, UiComponents.PRIMARY));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1); size.setMargins(0, UiComponents.dp(context, 8), UiComponents.dp(context, 8), 0); row.addView(action, size);
            }
            if (items.length() % columns != 0) row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
            View spacer = new View(context); detail.addView(spacer, new LinearLayout.LayoutParams(-1, UiComponents.dp(context, 20)));
        }
        disclose(card, "查看计划动作与训练日", detail);
    }

    private String weekdays(JSONArray days) {
        if (days == null || days.length() == 0) return "尚未设置训练日";
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"}; StringBuilder text = new StringBuilder("训练日：");
        for (int i = 0; i < days.length(); i++) { if (i > 0) text.append("、"); text.append(names[days.optInt(i) - 1]); }
        return text.toString();
    }

    private String name(String key) { for (int i = 0; i < catalog.length(); i++) if (key.equals(catalog.optJSONObject(i).optString("key"))) return catalog.optJSONObject(i).optString("name"); return key; }

    private void points(LinearLayout body, JSONArray points) {
        for (int i = 0; i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            UiComponents.divider(getContext(), body);
            LinearLayout head = UiComponents.row(getContext()); body.addView(head);
            head.addView(UiComponents.text(getContext(), p.optString("date"), 17, UiComponents.INK), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(UiComponents.badge(getContext(), p.optBoolean("partial") ? "部分结果" : "正常结束", p.optBoolean("partial")));
            body.addView(UiComponents.text(getContext(), "完成 " + p.optInt("count") + "/" + p.optInt("target") + " 次 · 用时 " + String.format(Locale.CHINA, "%.1f 秒", p.optDouble("elapsed_seconds")) +
                " · 算法合格率 " + (p.isNull("rate") ? "暂无判定" : String.format(Locale.CHINA, "%.1f%%（%d/%d）", p.optDouble("rate"), p.optInt("passed"), p.optInt("count"))), 15, UiComponents.INK));
            body.addView(UiComponents.text(getContext(), HomeActivity.feedbackText(p.optJSONObject("feedback")), 14, UiComponents.MUTED));
            body.addView(UiComponents.compact(getContext(), "查看该次训练详情  →", false, v -> openRun.open(p.optString("run_id"))));
        }
    }

    public static class CountChart extends View {
        private final JSONArray points; private final long first, last; private final int target;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        CountChart(Context context, JSONArray values, String start, String end, int target) { super(context); points = values; first = day(start); last = day(end); this.target = target; }
        static long day(String value) { try { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.parse(value).getTime() / 86400000; } catch (Exception e) { return 0; } }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float u = getResources().getDisplayMetrics().density, l = 44*u, r = getWidth()-24*u, top = 20*u, bottom = getHeight()-42*u;
            int max = Math.max(1, target); for (int i = 0; i < points.length(); i++) max = Math.max(max, points.optJSONObject(i).optInt("count"));
            paint.setTextSize(14*u); paint.setColor(UiComponents.MUTED); paint.setStrokeWidth(u); canvas.drawLine(l, top, l, bottom, paint); canvas.drawLine(l, bottom, r, bottom, paint);
            canvas.drawText(String.valueOf(max), 4*u, top+5*u, paint); canvas.drawText("0", 20*u, bottom, paint);
            float ty = bottom-target*(bottom-top)/max; paint.setColor(0xffb4cbd0); canvas.drawLine(l, ty, r, ty, paint);
            for (int i = 0; i < points.length(); i++) {
                JSONObject p = points.optJSONObject(i); float x = l+(r-l)*(day(p.optString("date"))-first+0.5f)/Math.max(1,last-first+1);
                float y = bottom-(bottom-top)*p.optInt("count")/max; paint.setColor(p.optBoolean("partial") ? 0xffcc7d2a : UiComponents.PRIMARY); canvas.drawCircle(x,y,5*u,paint);
            }
            paint.setColor(UiComponents.MUTED); SimpleDateFormat f = new SimpleDateFormat("MM-dd", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
            canvas.drawText(f.format(new java.util.Date(first*86400000)), l, bottom+25*u, paint); canvas.drawText(f.format(new java.util.Date(last*86400000)), r-50*u, bottom+25*u, paint);
        }
    }

    @Override protected void onDetachedFromWindow() { closed = true; requestGeneration++; api.close(); super.onDetachedFromWindow(); }
}
