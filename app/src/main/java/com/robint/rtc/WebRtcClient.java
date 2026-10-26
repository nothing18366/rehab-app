package com.robint.rtc;

import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class WebRtcClient {
    // Native camera/EGL teardown can wait for driver callbacks; never block Activity.onPause.
    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor();
    public interface Listener {
        void onCameraStatus(String message);
        void onMessage(JSONObject message);
        void onError(String message);
        void onStats(String message);
        void onPreviewChanged(boolean remote, boolean available);
        void onCameraSelected(boolean front, boolean switching);
    }

    // Keep pixel count stable; improve compression quality before raising resolution.
    private static final int CAPTURE_WIDTH = 640;
    private static final int CAPTURE_HEIGHT = 480;
    private static final int CAPTURE_FPS = 15;
    private static final int VIDEO_START_BITRATE = 1_500_000;
    private static final int VIDEO_MAX_BITRATE = 2_500_000;
    // Display only: a user facing the camera sees their right hand on the right.
    // Captured frames sent to the pose engine keep their original orientation.
    private static final boolean DISPLAY_MIRROR = true;
    private final Activity activity;
    private final Listener listener;
    private final SurfaceViewRenderer localView;
    private final SurfaceViewRenderer remoteView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final EglBase eglBase;
    private final PeerConnectionFactory factory;
    private final SurfaceTextureHelper textureHelper;
    private CameraVideoCapturer capturer;
    private VideoSource videoSource;
    private VideoTrack localTrack;
    private volatile VideoTrack remoteTrack;
    private PeerConnection peer;
    private DataChannel dataChannel;
    private String serverUrl;
    private JSONObject training;
    private boolean previewRunning;
    private boolean cameraReady;
    private int previewGeneration;
    private volatile int sessionGeneration;
    private boolean frontCamera = true;
    private boolean switchingCamera;
    private volatile boolean stopping;
    private boolean preferRemote;
    private volatile boolean showingRemote;
    private boolean offerSent;
    private volatile boolean destroyed;
    private final AtomicInteger capturedFrames = new AtomicInteger();
    private final AtomicInteger receivedFrames = new AtomicInteger();
    private volatile double processedFps;
    private volatile double serverMs;
    private volatile int droppedInput;
    private volatile int droppedOutput;
    private volatile int remoteWidth;
    private volatile int remoteHeight;

    private final Runnable statsTimer = new Runnable() {
        @Override
        public void run() {
            PeerConnection current = peer;
            if (current == null || stopping) return;
            int captureFps = capturedFrames.getAndSet(0);
            int receiveFps = receivedFrames.getAndSet(0);
            current.getStats(report -> showStats(report, captureFps, receiveFps));
            mainHandler.postDelayed(this, 1000);
        }
    };

    public WebRtcClient(Activity activity, SurfaceViewRenderer localView,
                   SurfaceViewRenderer remoteView, Listener listener) {
        this.activity = activity;
        this.localView = localView;
        this.remoteView = remoteView;
        this.listener = listener;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(activity.getApplicationContext()).createInitializationOptions());
        eglBase = EglBase.create();
        localView.init(eglBase.getEglBaseContext(), null);
        localView.setMirror(DISPLAY_MIRROR);
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remoteView.setZOrderMediaOverlay(true);
        remoteView.init(eglBase.getEglBaseContext(), null);
        remoteView.setMirror(DISPLAY_MIRROR);
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        textureHelper = SurfaceTextureHelper.create("rehab-camera", eglBase.getEglBaseContext());
    }

    public void startPreview() {
        if (previewRunning || destroyed) return;
        final int cameraGeneration = ++previewGeneration;
        cameraReady = false;
        Camera2Enumerator cameras = new Camera2Enumerator(activity);
        for (String name : cameras.getDeviceNames()) {
            if (frontCamera ? cameras.isFrontFacing(name) : cameras.isBackFacing(name)) {
                capturer = cameras.createCapturer(name, new CameraVideoCapturer.CameraEventsHandler() {
                    @Override public void onCameraOpening(String cameraName) { }
                    @Override public void onFirstFrameAvailable() {
                        activity.runOnUiThread(() -> {
                            if (cameraGeneration != previewGeneration) return;
                            cameraReady = true;
                            listener.onCameraStatus((frontCamera ? "前置" : "后置") + "摄像头已就绪");
                        });
                    }
                    @Override public void onCameraClosed() { }
                    @Override public void onCameraError(String error) {
                        activity.runOnUiThread(() -> { if (cameraGeneration == previewGeneration) listener.onError("摄像头错误：" + error); });
                    }
                    @Override public void onCameraDisconnected() {
                        activity.runOnUiThread(() -> { if (cameraGeneration == previewGeneration) listener.onError("摄像头已断开"); });
                    }
                    @Override public void onCameraFreezed(String error) {
                        activity.runOnUiThread(() -> { if (cameraGeneration == previewGeneration) listener.onError("摄像头画面已停止：" + error); });
                    }
                });
                break;
            }
        }
        if (capturer == null) {
            listener.onError("没有可用的" + (frontCamera ? "前置" : "后置") + "摄像头");
            return;
        }
        videoSource = factory.createVideoSource(false);
        capturer.initialize(textureHelper, activity, videoSource.getCapturerObserver());
        localTrack = factory.createVideoTrack("rehab-camera", videoSource);
        localTrack.addSink(frame -> {
            if (destroyed) return;
            capturedFrames.incrementAndGet();
            if (!showingRemote) localView.onFrame(frame);
        });
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
        previewRunning = true;
        listener.onCameraSelected(frontCamera, false);
        listener.onCameraStatus("正在打开" + (frontCamera ? "前置" : "后置") + "摄像头");
    }

    public boolean isPreviewReady() { return previewRunning && cameraReady && !switchingCamera; }

    public void selectCamera(boolean front) {
        if (switchingCamera || front == frontCamera) return;
        if (!previewRunning || capturer == null) {
            frontCamera = front;
            listener.onCameraSelected(frontCamera, false);
            return;
        }
        Camera2Enumerator cameras = new Camera2Enumerator(activity);
        String cameraName = null;
        for (String name : cameras.getDeviceNames()) {
            if (front ? cameras.isFrontFacing(name) : cameras.isBackFacing(name)) {
                cameraName = name;
                break;
            }
        }
        if (cameraName == null) {
            listener.onCameraStatus("此设备没有可用的" + (front ? "前置" : "后置") + "摄像头");
            listener.onCameraSelected(frontCamera, false);
            return;
        }
        switchingCamera = true;
        cameraReady = false;
        listener.onCameraSelected(frontCamera, true);
        CameraVideoCapturer switchingCapturer = capturer;
        switchingCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFrontCamera) {
                activity.runOnUiThread(() -> {
                    if (capturer != switchingCapturer) return;
                    switchingCamera = false;
                    cameraReady = true;
                    frontCamera = isFrontCamera;
                    localView.setMirror(DISPLAY_MIRROR);
                    remoteView.setMirror(DISPLAY_MIRROR);
                    listener.onCameraSelected(frontCamera, false);
                    listener.onCameraStatus((frontCamera ? "前置" : "后置") + "摄像头已打开");
                });
            }

            @Override
            public void onCameraSwitchError(String error) {
                activity.runOnUiThread(() -> {
                    if (capturer != switchingCapturer) return;
                    switchingCamera = false;
                    cameraReady = true;
                    listener.onCameraSelected(frontCamera, false);
                    listener.onCameraStatus("切换摄像头失败：" + error);
                });
            }
        }, cameraName);
    }

    public void stopPreview() {
        closeSession();
        previewGeneration++;
        cameraReady = false;
        switchingCamera = false;
        if (capturer == null) return;
        try {
            capturer.stopCapture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        capturer.dispose();
        capturer = null;
        if (localTrack != null) localTrack.dispose();
        localTrack = null;
        if (videoSource != null) videoSource.dispose();
        videoSource = null;
        previewRunning = false;
    }

    public void start(String url, JSONObject training) {
        if (!previewRunning || localTrack == null) {
            listener.onError("摄像头尚未准备好");
            return;
        }
        closeSession();
        this.serverUrl = url;
        this.training = training;
        stopping = false;
        final int generation = sessionGeneration;
        preferRemote = false;
        offerSent = false;
        processedFps = 0;
        serverMs = 0;
        droppedInput = 0;
        droppedOutput = 0;
        remoteWidth = 0;
        remoteHeight = 0;
        capturedFrames.set(0);
        receivedFrames.set(0);
        localView.setVisibility(View.VISIBLE);
        remoteView.setVisibility(View.GONE);

        PeerConnection.RTCConfiguration config =
                new PeerConnection.RTCConfiguration(Collections.emptyList());
        peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceCandidate(IceCandidate candidate) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(MediaStream stream) { }
            @Override public void onRemoveStream(MediaStream stream) { }
            @Override public void onDataChannel(DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.FAILED
                        || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    activity.runOnUiThread(() -> { if (generation == sessionGeneration) fail("视频连接已断开"); });
                }
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                if (generation == sessionGeneration && state == PeerConnection.IceGatheringState.COMPLETE) {
                    sendOffer();
                }
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                if (transceiver.getReceiver().track() instanceof VideoTrack) {
                    attachRemote((VideoTrack) transceiver.getReceiver().track(), generation);
                }
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                if (receiver.track() instanceof VideoTrack) {
                    attachRemote((VideoTrack) receiver.track(), generation);
                }
            }
        });
        if (peer == null) {
            listener.onError("无法创建视频连接");
            return;
        }
        peer.setBitrate(300_000, VIDEO_START_BITRATE, VIDEO_MAX_BITRATE);
        peer.addTrack(localTrack, Collections.singletonList("rehab"));
        dataChannel = peer.createDataChannel("rehab", new DataChannel.Init());
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long amount) { }
            @Override public void onStateChange() { }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (generation != sessionGeneration || stopping) return;
                byte[] bytes = new byte[buffer.data.remaining()];
                buffer.data.get(bytes);
                try {
                    JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if ("result".equals(message.optString("type"))) {
                        processedFps = message.optDouble("processed_fps");
                        serverMs = message.optDouble("server_total_ms");
                        droppedInput = message.optInt("dropped_input");
                        droppedOutput = message.optInt("dropped_output");
                    }
                    activity.runOnUiThread(() -> {
                        if (generation != sessionGeneration || stopping) return;
                        if (!stopping) listener.onMessage(message);
                        if ("summary".equals(message.optString("type"))) {
                            sendCommand("ack");
                            mainHandler.postDelayed(() -> { if (generation == sessionGeneration) closeSession(); }, 200);
                        }
                    });
                } catch (JSONException e) {
                    activity.runOnUiThread(() -> { if (generation == sessionGeneration) fail("服务端消息格式错误"); });
                }
            }
        });
        peer.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                PeerConnection current = peer;
                if (generation != sessionGeneration || current == null || stopping) return;
                current.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        if (generation == sessionGeneration && peer != null && !stopping && peer.iceGatheringState()
                                == PeerConnection.IceGatheringState.COMPLETE) sendOffer();
                    }
                }, offer);
            }
        }, new MediaConstraints());
        mainHandler.postDelayed(statsTimer, 1000);
    }

    private synchronized void sendOffer() {
        PeerConnection current = peer;
        final int generation = sessionGeneration;
        if (stopping || offerSent || current == null || current.getLocalDescription() == null) return;
        offerSent = true;
        try {
            JSONObject offer = new JSONObject(training.toString())
                    .put("type", "offer")
                    .put("sdp", current.getLocalDescription().description);
            Request request = new Request.Builder().url(serverUrl + "/rtc/offer")
                    .header("Authorization", "Bearer " + SessionStore.token(activity))
                    .post(RequestBody.create(MediaType.parse("application/json"),
                            offer.toString())).build();
            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    activity.runOnUiThread(() -> { if (!call.isCanceled() && generation == sessionGeneration) fail("服务连接失败：" + error.getMessage()); });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (Response result = response) {
                        String body = result.body() == null ? "" : result.body().string();
                        if (!result.isSuccessful()) {
                            activity.runOnUiThread(() -> { if (generation == sessionGeneration) {
                                fail(result.code() == 409 ? "服务端已有设备正在训练" : "服务端拒绝连接：" + body);
                                if (result.code() == 401) SessionStore.login(activity);
                            } });
                            return;
                        }
                        JSONObject answer = new JSONObject(body);
                        String sdp = answer.getString("sdp");
                        activity.runOnUiThread(() -> {
                            if (generation == sessionGeneration && current == peer && !stopping) {
                                current.setRemoteDescription(new SimpleSdpObserver() { },
                                    new SessionDescription(SessionDescription.Type.ANSWER, sdp));
                            }
                        });
                    } catch (JSONException e) {
                        activity.runOnUiThread(() -> { if (generation == sessionGeneration) fail("服务端应答格式错误"); });
                    }
                }
            });
        } catch (JSONException e) {
            activity.runOnUiThread(() -> fail("训练信息格式错误"));
        }
    }

    private void attachRemote(VideoTrack track, int generation) {
        if (generation != sessionGeneration || stopping || remoteTrack != null) return;
        remoteTrack = track;
        activity.runOnUiThread(() -> {
            if (remoteTrack != track || stopping) return;
            updatePreview();
        });
        track.addSink(frame -> {
            if (remoteTrack != track || stopping) return;
            receivedFrames.incrementAndGet();
            remoteWidth = frame.getRotatedWidth();
            remoteHeight = frame.getRotatedHeight();
            if (showingRemote) remoteView.onFrame(frame);
        });
    }

    public void togglePreview() {
        if (remoteTrack == null || stopping) return;
        preferRemote = !preferRemote;
        updatePreview();
    }

    private void updatePreview() {
        localView.setMirror(DISPLAY_MIRROR);
        remoteView.setMirror(DISPLAY_MIRROR);
        boolean available = remoteTrack != null && !stopping;
        showingRemote = available && preferRemote;
        remoteView.setVisibility(showingRemote ? View.VISIBLE : View.GONE);
        localView.setVisibility(showingRemote ? View.GONE : View.VISIBLE);
        listener.onPreviewChanged(showingRemote, available);
    }

    public void stopTraining() {
        sendCommand("stop");
    }

    public void stopForDiscomfort() {
        sendCommand("stop", "user_discomfort");
        CameraVideoCapturer current = capturer;
        if (current != null) CLEANUP_EXECUTOR.execute(() -> {
            try { current.stopCapture(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    private void sendCommand(String type) {
        sendCommand(type, "manual");
    }

    private void sendCommand(String type, String reason) {
        DataChannel channel = dataChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN) {
            if (!"ack".equals(type)) fail("视频连接尚未建立");
            return;
        }
        byte[] bytes = RehabApiClient.object("type", type, "reason", reason).toString().getBytes(StandardCharsets.UTF_8);
        channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    private void showStats(RTCStatsReport report, int captureFps, int receiveFps) {
        double rttMs = 0;
        double encodeMs = 0;
        long lost = 0;
        for (RTCStats stat : report.getStatsMap().values()) {
            Map<String, Object> members = stat.getMembers();
            if ("candidate-pair".equals(stat.getType())
                    && Boolean.TRUE.equals(members.get("nominated"))
                    && members.get("currentRoundTripTime") instanceof Number) {
                rttMs = ((Number) members.get("currentRoundTripTime")).doubleValue() * 1000;
            } else if ("outbound-rtp".equals(stat.getType())
                    && "video".equals(members.get("kind"))
                    && members.get("framesEncoded") instanceof Number
                    && members.get("totalEncodeTime") instanceof Number) {
                double frames = ((Number) members.get("framesEncoded")).doubleValue();
                if (frames > 0) encodeMs = ((Number) members.get("totalEncodeTime")).doubleValue()
                        / frames * 1000;
            } else if ("inbound-rtp".equals(stat.getType())
                    && "video".equals(members.get("kind"))
                    && members.get("packetsLost") instanceof Number) {
                lost = ((Number) members.get("packetsLost")).longValue();
            }
        }
        double finalRttMs = rttMs;
        double finalEncodeMs = encodeMs;
        long finalLost = lost;
        activity.runOnUiThread(() -> {
            if (peer == null || stopping) return;
            listener.onStats(String.format(
                    "采集 %d  服务端 %.1f  回传 %d FPS\n处理 %.0f  编码 %.0f  RTT %.0f ms\n跳帧 %d/%d  丢包 %d  远端 %d×%d",
                    captureFps, processedFps, receiveFps, serverMs, finalEncodeMs,
                    finalRttMs, droppedInput, droppedOutput, finalLost, remoteWidth, remoteHeight));
        });
    }

    private void fail(String message) {
        if (stopping || peer == null) return;
        closeSession();
        listener.onError(message);
    }

    public void closeSession() {
        stopping = true;
        sessionGeneration++;
        http.dispatcher().cancelAll();
        mainHandler.removeCallbacks(statsTimer);
        DataChannel oldChannel = dataChannel;
        PeerConnection oldPeer = peer;
        dataChannel = null;
        peer = null;
        remoteTrack = null;
        if (!destroyed) {
            updatePreview();
            remoteView.clearImage();
        }
        CLEANUP_EXECUTOR.execute(() -> {
            if (oldChannel != null) {
                oldChannel.unregisterObserver();
                oldChannel.close();
                oldChannel.dispose();
            }
            if (oldPeer != null) { oldPeer.close(); oldPeer.dispose(); }
        });
    }

    public void close() {
        if (destroyed) return;
        destroyed = true;
        previewGeneration++;
        cameraReady = false;
        previewRunning = false;
        closeSession();
        CameraVideoCapturer oldCapturer = capturer;
        VideoTrack oldTrack = localTrack;
        VideoSource oldSource = videoSource;
        capturer = null;
        localTrack = null;
        videoSource = null;
        CLEANUP_EXECUTOR.execute(() -> {
            if (oldCapturer != null) {
                try { oldCapturer.stopCapture(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                oldCapturer.dispose();
            }
            if (oldTrack != null) oldTrack.dispose();
            if (oldSource != null) oldSource.dispose();
            localView.release();
            remoteView.release();
            textureHelper.dispose();
            factory.dispose();
            eglBase.release();
        });
        http.dispatcher().executorService().shutdown();
    }

    private abstract class SimpleSdpObserver implements SdpObserver {
        private final int generation = sessionGeneration;
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) {
            activity.runOnUiThread(() -> { if (generation == sessionGeneration) fail("创建视频会话失败：" + error); });
        }
        @Override public void onSetFailure(String error) {
            activity.runOnUiThread(() -> { if (generation == sessionGeneration) fail("设置视频会话失败：" + error); });
        }
    }
}
