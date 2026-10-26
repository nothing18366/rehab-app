package com.robint.ui.component;

import com.robint.R;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import com.google.android.material.button.MaterialButton;

public final class UiComponents {
    public static final int INK = 0xff203c32, PRIMARY = 0xff245f4b, FOREST = 0xff173f35;
    public static final int MUTED = 0xff5f7067, DANGER = 0xffb33f3f;
    public static final int MINT = 0xffe6eee5, BACKGROUND = 0xfff3f5f1;
    public static final int BORDER = 0xffdfe6dc, ORANGE = 0xff916124, WARM = 0xfff5efe2;
    public static final int NAV_TEXT = 0xffd1dfd5;
    public static boolean portrait(Context context) { return context.getResources().getConfiguration().screenWidthDp <= 600; }
    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    public static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    public static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    public static TextView text(Context context, String content, int size, int color) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(context, 3), 1);
        view.setPadding(0, dp(context, 3), 0, dp(context, 3));
        return view;
    }
    public static TextView heading(Context context, String content) {
        TextView view = text(context, content, 22, INK);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    public static MaterialButton button(Context context, String text, boolean primary, View.OnClickListener click) {
        MaterialButton view = new MaterialButton(context);
        view.setText(text);
        view.setTextSize(16);
        view.setLetterSpacing(0);
        view.setAllCaps(false);
        view.setMinHeight(dp(context, 56));
        view.setMinimumHeight(dp(context, 56));
        view.setCornerRadius(dp(context, 12));
        view.setInsetTop(0);
        view.setInsetBottom(0);
        view.setStateListAnimator(null); view.setElevation(0);
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{MUTED, primary ? Color.WHITE : PRIMARY}));
        view.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_pressed}, new int[]{}}, new int[]{MINT, primary ? FOREST : MINT, primary ? PRIMARY : Color.WHITE}));
        view.setRippleColor(ColorStateList.valueOf(primary ? 0x30ffffff : 0x18245f4b));
        if (!primary) {
            view.setStrokeColor(ColorStateList.valueOf(BORDER));
            view.setStrokeWidth(dp(context, 1));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(context, 56));
        params.topMargin = dp(context, 8);
        view.setLayoutParams(params);
        view.setOnClickListener(click);
        return view;
    }
    public static LinearLayout card(Context context) {
        LinearLayout view = column(context);
        int padding = dp(context, portrait(context) ? 18 : 24);
        view.setPadding(padding, padding, padding, padding);
        view.setBackground(background(context, Color.WHITE, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 20);
        view.setLayoutParams(params);
        return view;
    }
    public static GradientDrawable background(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(context, radius));
        if (color == Color.WHITE) shape.setStroke(dp(context, 1), BORDER);
        return shape;
    }
    public static MaterialButton compact(Context context, String label, boolean primary, View.OnClickListener click) {
        MaterialButton button = button(context, label, primary, click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(context, 48));
        params.setMargins(0, dp(context, 4), dp(context, 8), dp(context, 4));
        button.setLayoutParams(params); button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        return button;
    }
    public static TextView badge(Context context, String label, boolean warning) {
        TextView badge = text(context, label, 13, warning ? ORANGE : PRIMARY);
        badge.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        badge.setBackground(background(context, warning ? 0xfffbf1e3 : MINT, 8));
        badge.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); return badge;
    }
    public static void section(Context context, LinearLayout body, String heading, String subtitle) {
        TextView title = heading(context, heading); title.setTextSize(portrait(context) ? 26 : 32); body.addView(title);
        TextView sub = text(context, subtitle, 15, MUTED);
        sub.setPadding(0, dp(context, 9), 0, dp(context, 26)); body.addView(sub);
    }
    public static LinearLayout split(Context context, LinearLayout parent, View left, View right, float leftWeight) {
        boolean stacked = context.getResources().getConfiguration().screenWidthDp <= 1000;
        LinearLayout row = row(context); row.setGravity(Gravity.TOP);
        if (stacked) row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : leftWeight);
        first.rightMargin = stacked ? 0 : dp(context, 22);
        row.addView(left, first); row.addView(right, new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1 - leftWeight));
        parent.addView(row); return row;
    }
    public static void value(Context context, LinearLayout parent, String label, String value) {
        LinearLayout line = row(context);
        if (portrait(context)) line.setOrientation(LinearLayout.VERTICAL);
        line.addView(text(context, label, 15, MUTED), new LinearLayout.LayoutParams(portrait(context) ? -1 : 0, -2, portrait(context) ? 0 : 1));
        TextView data = text(context, value, 16, INK); data.setTypeface(null, Typeface.BOLD);
        line.addView(data); line.setPadding(0, dp(context, 9), 0, dp(context, 9)); parent.addView(line);
    }
    public static void tip(Context context, LinearLayout parent, String title, String message) {
        LinearLayout card = card(context); card.setBackground(background(context, MINT, 16));
        card.addView(text(context, title, 17, PRIMARY)); card.addView(text(context, message, 15, MUTED)); parent.addView(card);
    }
    public static ImageView poster(Context context, String key) {
        ImageView image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(key.startsWith("home_hands") ? "动作教学示范，非实时摄像头" : "动作示范封面");
        try (java.io.InputStream input = context.getAssets().open("teaching_images/" + key + ".jpg")) {
            image.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(input, null));
        } catch (java.io.IOException ignored) { image.setBackgroundColor(MINT); }
        return image;
    }
    public static void styleInput(android.widget.EditText input) {
        Context context = input.getContext();
        input.setTextSize(16); input.setTextColor(INK); input.setHintTextColor(MUTED);
        input.setMinHeight(dp(context, 52)); input.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = background(context, 0xfffafbf8, 12); focused.setStroke(dp(context, 2), PRIMARY);
        GradientDrawable normal = background(context, 0xfffafbf8, 12); normal.setStroke(dp(context, 1), 0xff7e907e);
        states.addState(new int[]{android.R.attr.state_focused}, focused); states.addState(new int[]{}, normal);
        input.setBackground(states);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(context, 6), 0, dp(context, 12)); input.setLayoutParams(params);
    }
    public static void summary(Context context, LinearLayout card, org.json.JSONObject summary, double elapsed) {
        if (summary == null) { card.addView(text(context, "尚无识别结果", 18, MUTED)); return; }
        LinearLayout metrics = row(context); metrics.setPadding(0, dp(context, 24), 0, dp(context, 24));
        String[] labels = {"完成 / 目标", "算法合格次数", "需复核次数"};
        String[] values = {summary.optInt("count") + " / " + summary.optInt("target"), String.valueOf(summary.optInt("passed")), String.valueOf(summary.optInt("review"))};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout metric = column(context); metric.setGravity(Gravity.CENTER);
            TextView number = heading(context, values[i]); number.setTextSize(portrait(context) ? 24 : 32); number.setTextColor(i == 2 ? ORANGE : PRIMARY);
            metric.addView(number); metric.addView(text(context, labels[i], 14, MUTED)); metrics.addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
        }
        card.addView(metrics);
        value(context, card, "算法合格率", summary.optInt("count") == 0 ? "暂无判定" : String.format(java.util.Locale.CHINA, "%.1f%%（%d / %d）", summary.optDouble("rate"), summary.optInt("passed"), summary.optInt("count")));
        value(context, card, "实际训练用时", String.format(java.util.Locale.CHINA, "%.1f 秒", elapsed));
        value(context, card, "识别动作累计 / 单次平均", String.format(java.util.Locale.CHINA, "%.1f / %.1f 秒", summary.optDouble("total"), summary.optDouble("avg")));
        if (!summary.optString("detail").isEmpty()) card.addView(text(context, summary.optString("detail"), 16, MUTED));
    }
    public static ScrollView scroll(Context context, View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(child, new ViewGroup.LayoutParams(-1, -2));
        return scroll;
    }
    public static void divider(Context context, LinearLayout parent) {
        View line = new View(context); line.setBackgroundColor(BORDER); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(context, 1)));
    }
    public static void journey(Context context, LinearLayout parent, int current) {
        LinearLayout steps = row(context);
        steps.setPadding(0, 0, 0, dp(context, 20));
        String[] labels = {"训练准备", "动作练习", "训练小结", "感受记录"};
        for (int i = 0; i < labels.length; i++) {
            TextView step = text(context, String.format(java.util.Locale.CHINA, "%02d  %s", i + 1, labels[i]), portrait(context) ? 12 : 14, i == current ? FOREST : MUTED);
            if (i == current) step.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            steps.addView(step, new LinearLayout.LayoutParams(0, -2, 1));
        }
        parent.addView(steps);
    }
    public static final class ProgressRing extends android.widget.FrameLayout {
        private final TextView value;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int completed, scheduled;
        public ProgressRing(Context context) {
            super(context); setWillNotDraw(false);
            LinearLayout copy = column(context); copy.setGravity(Gravity.CENTER);
            value = heading(context, "— / —"); value.setTextSize(32); value.setGravity(Gravity.CENTER); copy.addView(value);
            TextView label = text(context, "本周计划完成 / 天", 12, MUTED); label.setGravity(Gravity.CENTER); copy.addView(label);
            addView(copy, new LayoutParams(-1, -1));
        }
        public void update(int done, int total) {
            completed = done; scheduled = total;
            value.setText(total == 0 ? "暂无统计" : done + " / " + total);
            value.setTextSize(total == 0 ? 20 : 32); invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas); float inset = dp(getContext(), 5);
            android.graphics.RectF oval = new android.graphics.RectF(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(dp(getContext(), 8));
            paint.setColor(MINT); canvas.drawOval(oval, paint);
            paint.setColor(PRIMARY); canvas.drawArc(oval, -90, scheduled == 0 ? 0 : Math.min(360, 360f * completed / scheduled), false, paint);
        }
    }
    public static void immersive(Activity activity) {
        activity.getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
