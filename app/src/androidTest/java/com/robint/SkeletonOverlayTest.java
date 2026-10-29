package com.robint;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.data.remote.RehabApiClient;
import com.robint.rtc.SkeletonOverlayView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SkeletonOverlayTest {
    @Test public void pipelineTimingUsesTheSameSequenceAndOnlyItsFirstDraw() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SkeletonOverlayView view = new SkeletonOverlayView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            view.setSession("test"); view.layout(0, 0, 640, 480);
            JSONObject message = packet("test", 7, 640, 480);
            try {
                message.put("decoded_at_ms", 1000).put("timing", RehabApiClient.object("send_started_at_ms", 1060));
                long at = SystemClock.elapsedRealtime();
                view.accept(message, at - 12, 80, at - 8);
                Bitmap pixels = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(pixels)); view.draw(new Canvas(pixels));
                JSONArray records = view.takePerformance().getJSONArray("pipeline");
                assertEquals(1, records.length());
                JSONObject timing = records.getJSONObject(0);
                assertEquals(7, timing.getLong("seq"));
                assertEquals(4, timing.getLong("parse_ms"));
                assertTrue(timing.getLong("ui_queue_ms") >= 8);
                assertEquals(20., timing.getDouble("return_estimate_ms"), .001);
                assertTrue(timing.getDouble("decoded_to_first_draw_ms") >= 92);
                assertEquals(0, view.takePerformance().getJSONArray("pipeline").length());
                pixels.recycle();
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    private JSONObject packet(String session, int seq, int width, int height) {
        JSONArray body = new JSONArray();
        for (int i = 0; i < 17; i++) body.put(i == 9 ? new JSONArray().put(Double.valueOf(.25)).put(Double.valueOf(.25)).put(Double.valueOf(.9)) : JSONObject.NULL);
        return RehabApiClient.object("type", "pose", "version", 1, "session_id", session, "seq", seq,
                "width", width, "height", height, "rotation", 0, "body", body, "hands", new JSONArray());
    }

    @Test public void coordinatesMatchFitMirrorAndPortraitWithoutStretching() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SkeletonOverlayView view = new SkeletonOverlayView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            view.setSession("test"); view.layout(0, 0, 800, 800);
            view.accept(packet("test", 1, 640, 480), SystemClock.elapsedRealtime(), Double.NaN);
            Bitmap pixels = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(pixels));
            assertTrue(pixels.getPixel(600, 250) != 0);
            assertEquals(0, pixels.getPixel(200, 250));
            assertEquals(0, pixels.getPixel(600, 80));
            pixels.eraseColor(0); view.setMirror(false); view.draw(new Canvas(pixels));
            assertTrue(pixels.getPixel(200, 250) != 0);
            pixels.eraseColor(0); view.accept(packet("test", 2, 480, 640), SystemClock.elapsedRealtime(), Double.NaN);
            view.draw(new Canvas(pixels));
            assertTrue(pixels.getPixel(250, 200) != 0);
            assertEquals(0, pixels.getPixel(80, 200));
            pixels.recycle();
        });
    }

    @Test public void staleWrongSessionOldSequenceAndPredictedHandsCannotPersist() throws Exception {
        SkeletonOverlayView[] holder = new SkeletonOverlayView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SkeletonOverlayView view = new SkeletonOverlayView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            holder[0] = view; view.layout(0, 0, 375, 250); view.setSession("test");
            view.accept(packet("test", 2, 640, 480), SystemClock.elapsedRealtime(), Double.NaN);
            assertTrue(view.hasSkeleton());
            view.accept(packet("other", 9, 640, 480), SystemClock.elapsedRealtime(), Double.NaN);
            view.accept(packet("test", 1, 640, 480), SystemClock.elapsedRealtime(), Double.NaN);
            assertEquals(2, view.sequence());
        });
        SystemClock.sleep(350);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SkeletonOverlayView view = holder[0];
            Bitmap pixels = Bitmap.createBitmap(375, 250, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(pixels)); assertFalse(view.hasSkeleton());
            view.accept(packet("test", 3, 640, 480), SystemClock.elapsedRealtime(), 301);
            assertFalse(view.hasSkeleton());
            view.accept(packet("test", 4, 640, 480), SystemClock.elapsedRealtime() - 301, Double.NaN);
            assertFalse(view.hasSkeleton());
            JSONObject predicted = packet("test", 5, 640, 480);
            JSONArray points = new JSONArray();
            for (int i = 0; i < 21; i++) points.put(new JSONArray().put(Double.valueOf(.5)).put(Double.valueOf(.5)));
            try {
                predicted.put("body", JSONObject.NULL).put("hands", new JSONArray().put(RehabApiClient.object(
                    "side", "right", "state", "bridged", "predicted", true, "points", points)));
                view.accept(predicted, SystemClock.elapsedRealtime(), Double.NaN);
                assertFalse(view.hasSkeleton());
                predicted.put("seq", 6).put("hands", new JSONArray().put(RehabApiClient.object(
                    "side", "right", "state", "fresh", "predicted", false, "points", points)));
                view.accept(predicted, SystemClock.elapsedRealtime(), 20);
                assertTrue(view.hasSkeleton());
                view.setSession("next"); assertFalse(view.hasSkeleton());
            } catch (Exception e) { throw new AssertionError(e); }
            pixels.recycle();
        });
    }
}
