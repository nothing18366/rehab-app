package com.robint.rtc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import com.robint.data.remote.RehabApiClient;

/** Coordinates refer to the decoded upright image; FIT and mirroring match the local renderer. */
public final class SkeletonOverlayView extends View {
    private static final long MAX_AGE_MS = 300;
    private static final int[][] BODY_LINKS = {{15,13},{13,11},{16,14},{14,12},{11,12},{5,11},{6,12},
        {5,6},{5,7},{6,8},{7,9},{8,10},{1,2},{0,1},{0,2},{1,3},{2,4},{3,5},{4,6}};
    private static final int[][] HAND_LINKS = {{0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},
        {5,9},{9,10},{10,11},{11,12},{9,13},{13,14},{14,15},{15,16},{13,17},{17,18},{18,19},{19,20},{0,17}};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[][] points = {new float[17 * 3], new float[21 * 3], new float[21 * 3]};
    private final float[] screen = new float[21 * 2];
    private final double[] drawCpuMs = new double[128], drawAgeMs = new double[128];
    private String sessionId = "";
    private long sequence = -1, receivedAt, expiresAt, updates, timeouts, draws;
    private int imageWidth, imageHeight, sampleCount;
    private boolean hasPoints, mirror = true;
    private double ageAtReceive = Double.NaN;
    private final Runnable expire = () -> {
        if (hasPoints && SystemClock.elapsedRealtime() >= expiresAt) {
            timeouts++;
            clearPoints();
        }
    };

    public SkeletonOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        clearPoints();
    }

    public void setSession(String identity) {
        sessionId = identity;
        sequence = -1;
        updates = timeouts = draws = 0;
        sampleCount = 0;
        clearPoints();
    }

    public void setMirror(boolean enabled) { mirror = enabled; invalidate(); }
    public boolean hasSkeleton() { return hasPoints; }
    public long sequence() { return sequence; }

    public void accept(JSONObject message, long at, double decodedAgeAtReceive) {
        long now = SystemClock.elapsedRealtime();
        long seq = message.optLong("seq", -1);
        int width = message.optInt("width"), height = message.optInt("height");
        if (!sessionId.equals(message.optString("session_id")) || sessionId.isEmpty() || seq <= sequence
                || message.optInt("version") != 1 || message.optInt("rotation", -1) != 0
                || width <= 0 || height <= 0 || width > 8192 || height > 8192) return;
        double age = Double.isNaN(decodedAgeAtReceive) ? now - at : decodedAgeAtReceive + now - at;
        sequence = seq;
        if (now - at >= MAX_AGE_MS || age >= MAX_AGE_MS || age < -100) {
            expire.run();
            return;
        }
        for (float[] group : points) Arrays.fill(group, Float.NaN);
        boolean usable = readPoints(message.optJSONArray("body"), points[0], true);
        JSONArray hands = message.optJSONArray("hands");
        if (hands != null && hands.length() <= 2) for (int i = 0; i < hands.length(); i++) {
            JSONObject hand = hands.optJSONObject(i);
            if (hand == null || !"fresh".equals(hand.optString("state")) || hand.optBoolean("predicted")) continue;
            int side = "left".equals(hand.optString("side")) ? 1 : "right".equals(hand.optString("side")) ? 2 : -1;
            if (side != -1) usable |= readPoints(hand.optJSONArray("points"), points[side], false);
        }
        removeCallbacks(expire);
        imageWidth = width; imageHeight = height;
        hasPoints = usable;
        if (usable) {
            receivedAt = at;
            ageAtReceive = decodedAgeAtReceive;
            expiresAt = now + Math.max(1, (long) (MAX_AGE_MS - Math.max(0, age)));
            updates++;
            postDelayed(expire, expiresAt - now);
        }
        postInvalidateOnAnimation();
    }

    private boolean readPoints(JSONArray input, float[] output, boolean scored) {
        if (input == null || input.length() * 3 != output.length) return false;
        boolean usable = false;
        for (int i = 0; i < input.length(); i++) {
            JSONArray point = input.optJSONArray(i);
            if (point == null || point.length() != (scored ? 3 : 2)) continue;
            double x = point.optDouble(0), y = point.optDouble(1), confidence = scored ? point.optDouble(2) : 1;
            if (Double.isNaN(x + y + confidence) || Double.isInfinite(x + y + confidence)
                    || x < 0 || x > 1 || y < 0 || y > 1 || confidence < .25 || confidence > 1) continue;
            output[i * 3] = (float) x; output[i * 3 + 1] = (float) y; output[i * 3 + 2] = (float) confidence;
            usable = true;
        }
        return usable;
    }

    private void clearPoints() {
        removeCallbacks(expire);
        hasPoints = false;
        for (float[] group : points) Arrays.fill(group, Float.NaN);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long cpuStarted = Debug.threadCpuTimeNanos();
        long now = SystemClock.elapsedRealtime();
        if (hasPoints && now >= expiresAt) expire.run();
        if (!hasPoints || imageWidth == 0) return;
        float scale = Math.min(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float width = imageWidth * scale, height = imageHeight * scale;
        float left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
        for (int group = 0; group < points.length; group++) {
            float[] input = points[group];
            for (int i = 0; i < input.length / 3; i++) {
                screen[i * 2] = left + (mirror ? 1 - input[i * 3] : input[i * 3]) * width;
                screen[i * 2 + 1] = top + input[i * 3 + 1] * height;
            }
            paint.setColor(group == 0 ? Color.rgb(255, 210, 65) : group == 1 ? Color.rgb(50, 230, 115) : Color.rgb(75, 185, 255));
            for (int[] link : group == 0 ? BODY_LINKS : HAND_LINKS) {
                if (!Float.isNaN(screen[link[0] * 2]) && !Float.isNaN(screen[link[1] * 2]))
                    canvas.drawLine(screen[link[0] * 2], screen[link[0] * 2 + 1], screen[link[1] * 2], screen[link[1] * 2 + 1], paint);
            }
            for (int i = 0; i < input.length / 3; i++) if (!Float.isNaN(screen[i * 2]))
                canvas.drawCircle(screen[i * 2], screen[i * 2 + 1], 2.5f * getResources().getDisplayMetrics().density, paint);
        }
        int index = sampleCount++ % drawCpuMs.length;
        drawCpuMs[index] = (Debug.threadCpuTimeNanos() - cpuStarted) / 1_000_000.0;
        drawAgeMs[index] = Double.isNaN(ageAtReceive) ? Double.NaN : ageAtReceive + now - receivedAt;
        draws++;
    }

    public JSONObject takePerformance() {
        JSONArray cpu = new JSONArray(), age = new JSONArray();
        for (int i = 0; i < Math.min(sampleCount, drawCpuMs.length); i++) {
            cpu.put(Double.valueOf(drawCpuMs[i]));
            age.put(Double.isNaN(drawAgeMs[i]) ? JSONObject.NULL : drawAgeMs[i]);
        }
        sampleCount = 0;
        return RehabApiClient.object("draw_cpu_ms", cpu, "decoded_to_draw_ms", age,
                "updates_total", updates, "timeouts_total", timeouts, "draws_total", draws);
    }

    @Override protected void onDetachedFromWindow() {
        clearPoints();
        super.onDetachedFromWindow();
    }
}
