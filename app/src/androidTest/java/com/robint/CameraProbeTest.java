package com.robint;

import android.content.Context;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.feature.login.LoginActivity;
import com.robint.rtc.WebRtcClient;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CapturerObserver;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame;
import org.webrtc.SurfaceViewRenderer;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Opt-in hardware check: -e class com.robint.CameraProbeTest -e cameraId 102. */
@RunWith(AndroidJUnit4.class)
public class CameraProbeTest {
    @Test public void failedPreviewCanSwitchToAnotherCamera() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("probeSwitch")));
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<String> lastError = new AtomicReference<>("");
        AtomicReference<WebRtcClient> client = new AtomicReference<>();
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    // Login may already have redirected an authenticated user to HomeActivity.
                    android.app.Activity activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .stream().filter(a -> a.getPackageName().equals("com.robint.sdk.demo")).findFirst().orElseThrow(() -> new AssertionError("No foreground app Activity"));
                    SurfaceViewRenderer local = new SurfaceViewRenderer(activity), remote = new SurfaceViewRenderer(activity);
                    WebRtcClient rtc = new WebRtcClient(activity, local, remote, null, new WebRtcClient.Listener() {
                        @Override public void onCameraStatus(String message) {
                            Log.i("RehabCameraProbe", message);
                            if (message.contains("后置") && client.get().isPreviewReady()) ready.countDown();
                        }
                        @Override public void onError(String message) {
                            lastError.set(message);
                            if (errors.incrementAndGet() == 1) client.get().selectCamera(false);
                        }
                        @Override public void onMessage(JSONObject message) { }
                        @Override public void onStats(String message) { }
                        @Override public void onPreviewChanged(boolean remote, boolean available) { }
                        @Override public void onCameraSelected(boolean front, boolean switching) { }
                    });
                    client.set(rtc); rtc.startPreview();
                });
                assertTrue("Failed preview did not recover: " + lastError.get(), ready.await(15, TimeUnit.SECONDS));
                assertEquals(1, errors.get());
            } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (client.get() != null) client.get().close(); }); }
        }
    }

    @Test public void cameraDeliversFrames() throws Exception {
        String id = InstrumentationRegistry.getArguments().getString("cameraId");
        assumeNotNull(id);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            Camera2Enumerator cameras = new Camera2Enumerator(context);
            Log.i("RehabCameraProbe", "Camera2 " + id + " formats=" + cameras.getSupportedFormats(id));
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger frames = new AtomicInteger();
            AtomicReference<String> error = new AtomicReference<>("");
            CameraVideoCapturer capturer = cameras.createCapturer(id, new CameraVideoCapturer.CameraEventsHandler() {
                @Override public void onCameraOpening(String name) { }
                @Override public void onFirstFrameAvailable() { }
                @Override public void onCameraClosed() { }
                @Override public void onCameraDisconnected() { error.set("disconnected"); done.countDown(); }
                @Override public void onCameraError(String message) { error.set(message); done.countDown(); }
                @Override public void onCameraFreezed(String message) { error.set(message); done.countDown(); }
            });
            assertNotNull(capturer);
            EglBase egl = EglBase.create();
            SurfaceTextureHelper helper = SurfaceTextureHelper.create("camera-probe", egl.getEglBaseContext());
            try {
                capturer.initialize(helper, context, new CapturerObserver() {
                    @Override public void onCapturerStarted(boolean success) { }
                    @Override public void onCapturerStopped() { }
                    @Override public void onFrameCaptured(VideoFrame frame) {
                        if (frames.incrementAndGet() == 3) {
                            Log.i("RehabCameraProbe", "Camera2 " + id + " delivered " + frame.getBuffer().getWidth() + "x" + frame.getBuffer().getHeight());
                            done.countDown();
                        }
                    }
                });
                capturer.startCapture(640, 480, 15);
                assertTrue("Timed out: " + error.get(), done.await(12, TimeUnit.SECONDS));
                assertTrue("Camera " + id + ": " + error.get(), frames.get() >= 3);
            } finally {
                capturer.stopCapture(); capturer.dispose(); helper.dispose(); egl.release();
            }
        }
    }
}
