package com.robint.rtc;

import com.robint.data.local.SessionStore;
import com.robint.data.remote.RehabApiClient;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.graphics.BitmapFactory;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CapturerObserver;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RendererCommon;
import org.webrtc.RtpCapabilities;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoFrame;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        void onPreviewChanged(String view, boolean alternateAvailable);
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
    private final SkeletonOverlayView skeletonView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final EglBase eglBase;
    private final PeerConnectionFactory factory;
    private final boolean robotSoftwareCodec;
    private final SurfaceTextureHelper textureHelper;
    private CameraVideoCapturer capturer;
    private VideoSource videoSource;
    private VideoTrack localTrack;
    private volatile VideoTrack remoteTrack;
    private PeerConnection peer;
    private DataChannel dataChannel;
    private DataChannel poseChannel;
    private String serverUrl;
    private JSONObject training;
    private boolean previewRunning;
    private boolean cameraReady;
    private int previewGeneration;
    private volatile int sessionGeneration;
    private volatile boolean stopping;
    private boolean preferRemote;
    private boolean skeletonEnabled = true;
    private String previewMode = "keypoints";
    private volatile long lastRemoteFrameAt;
    private boolean remoteTimedOut;
    private volatile long clockRequestedAt, clockSyncedAt;
    private volatile double clockOffset = Double.NaN, clockRtt = Double.NaN;
    private volatile boolean showingRemote;
    private boolean offerSent;
    private volatile boolean destroyed;
    private final AtomicInteger capturedFrames = new AtomicInteger();
    private final AtomicInteger receivedFrames = new AtomicInteger();
    private volatile double processedFps;
    private volatile double serverMs;
    private volatile JSONObject recognitionStats = new JSONObject();
    private volatile int droppedInput;
    private volatile int droppedOutput;
    private volatile int remoteWidth;
    private volatile int remoteHeight;
    private volatile int captureWidth, captureHeight, captureRotation;
    private PerformanceStats performanceStats;
    private volatile JSONObject performanceSample = new JSONObject();

    public JSONObject performanceSample() { return performanceSample; }

    private final Runnable statsTimer = new Runnable() {
        @Override
        public void run() {
            PeerConnection current = peer;
            if (current == null || stopping) return;
            checkRemoteTimeout();
            if (SystemClock.elapsedRealtime() - clockRequestedAt >= 5000) sendClockPing();
            int captureFps = capturedFrames.getAndSet(0);
            int receiveFps = receivedFrames.getAndSet(0);
            int generation = sessionGeneration;
            current.getStats(report -> {
                if (generation == sessionGeneration && !stopping) showStats(report, captureFps, receiveFps);
            });
            mainHandler.postDelayed(this, 1000);
        }
    };

    public WebRtcClient(Activity activity, SurfaceViewRenderer localView,
                   SurfaceViewRenderer remoteView, SkeletonOverlayView skeletonView, Listener listener) {
        this.activity = activity;
        this.localView = localView;
        this.remoteView = remoteView;
        this.skeletonView = skeletonView;
        this.listener = listener;
        robotSoftwareCodec = Build.MODEL.toLowerCase().contains("rk3588")
                || Build.HARDWARE.toLowerCase().contains("rk3588");
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
                .setVideoEncoderFactory(robotSoftwareCodec
                        ? new SoftwareVideoEncoderFactory()
                        : new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
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
            if (cameras.isBackFacing(name)) {
                capturer = cameras.createCapturer(name, new CameraVideoCapturer.CameraEventsHandler() {
                    @Override public void onCameraOpening(String cameraName) { }
                    @Override public void onFirstFrameAvailable() {
                        activity.runOnUiThread(() -> {
                            if (cameraGeneration != previewGeneration) return;
                            cameraReady = true;
                            listener.onCameraStatus("后置摄像头已就绪");
                        });
                    }
                    @Override public void onCameraClosed() { }
                    @Override public void onCameraError(String error) {
                        activity.runOnUiThread(() -> {
                            if (cameraGeneration != previewGeneration) return;
                            cameraReady = false;
                            listener.onError("摄像头错误：" + error);
                        });
                    }
                    @Override public void onCameraDisconnected() {
                        activity.runOnUiThread(() -> {
                            if (cameraGeneration != previewGeneration) return;
                            cameraReady = false;
                            listener.onError("摄像头已断开");
                        });
                    }
                    @Override public void onCameraFreezed(String error) {
                        activity.runOnUiThread(() -> {
                            if (cameraGeneration != previewGeneration) return;
                            cameraReady = false;
                            listener.onError("摄像头画面已停止：" + error);
                        });
                    }
                });
                break;
            }
        }
        if (capturer == null) {
            listener.onError("没有可用的后置摄像头");
            return;
        }
        videoSource = factory.createVideoSource(false);
        CapturerObserver uploadObserver = videoSource.getCapturerObserver();
        // Sender adaptation also drops shared-track preview frames; preview the camera before adaptation.
        capturer.initialize(textureHelper, activity, new CapturerObserver() {
            @Override public void onCapturerStarted(boolean success) { uploadObserver.onCapturerStarted(success); }
            @Override public void onCapturerStopped() { uploadObserver.onCapturerStopped(); }
            @Override public void onFrameCaptured(VideoFrame frame) {
                if (!destroyed && cameraGeneration == previewGeneration) {
                    capturedFrames.incrementAndGet();
                    captureWidth = frame.getBuffer().getWidth();
                    captureHeight = frame.getBuffer().getHeight();
                    captureRotation = frame.getRotation();
                    if (!showingRemote) localView.onFrame(frame);
                }
                uploadObserver.onFrameCaptured(frame);
            }
        });
        localTrack = factory.createVideoTrack("rehab-camera", videoSource);
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
        previewRunning = true;
        listener.onCameraStatus("正在打开后置摄像头");
    }

    public boolean isPreviewReady() { return previewRunning && cameraReady; }

    public void start(String url, JSONObject training) {
        if (!previewRunning || localTrack == null) {
            listener.onError("摄像头尚未准备好");
            return;
        }
        closeSession();
        this.serverUrl = url;
        this.training = training;
        previewMode = training.optString("preview_mode", "keypoints");
        skeletonEnabled = !isAnnotatedMode();
        lastRemoteFrameAt = 0;
        remoteTimedOut = false;
        clockOffset = clockRtt = Double.NaN;
        clockRequestedAt = clockSyncedAt = 0;
        if (skeletonView != null) {
            skeletonView.setSession(training.optString("session_id"));
            skeletonView.setMirror(DISPLAY_MIRROR);
            skeletonView.setVisibility(skeletonEnabled ? View.VISIBLE : View.INVISIBLE);
        }
        stopping = false;
        final int generation = sessionGeneration;
        preferRemote = isAnnotatedMode();
        offerSent = false;
        processedFps = 0;
        serverMs = 0;
        recognitionStats = new JSONObject();
        droppedInput = 0;
        droppedOutput = 0;
        remoteWidth = 0;
        remoteHeight = 0;
        capturedFrames.set(0);
        receivedFrames.set(0);
        performanceStats = new PerformanceStats();
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
        // The RK3588 hardware codecs emit packets that aiortc cannot decode
        // reliably. Use software VP8 on that device; phones keep H.264.
        RtpTransceiver videoTransceiver = peer.addTransceiver(localTrack,
                new RtpTransceiver.RtpTransceiverInit(
                        !"keypoints".equals(previewMode) ? RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                                : RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                        Collections.singletonList("rehab")));
        java.util.ArrayList<RtpCapabilities.CodecCapability> preferred = new java.util.ArrayList<>();
        for (RtpCapabilities.CodecCapability codec : factory
                .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs) {
            if (robotSoftwareCodec ? "video/VP8".equalsIgnoreCase(codec.mimeType)
                    : "video/H264".equalsIgnoreCase(codec.mimeType)) preferred.add(codec);
        }
        if (preferred.isEmpty()) {
            listener.onError(robotSoftwareCodec ? "设备没有可用的软件 VP8 编码器" : "设备没有可用的 H.264 编码器");
            closeSession();
            return;
        }
        videoTransceiver.setCodecPreferences(preferred);
        dataChannel = peer.createDataChannel("rehab", new DataChannel.Init());
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long amount) { }
            @Override public void onStateChange() {
                activity.runOnUiThread(() -> { if (generation == sessionGeneration) sendClockPing(); });
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (generation != sessionGeneration || stopping) return;
                byte[] bytes = new byte[buffer.data.remaining()];
                buffer.data.get(bytes);
                try {
                    JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    if (!thisSession(message)) return;
                    if ("clock_pong".equals(message.optString("type"))) {
                        long now = SystemClock.elapsedRealtime();
                        double echoed = message.optDouble("client_ms"), server = message.optDouble("server_ms");
                        double roundTrip = now - echoed;
                        if (echoed == clockRequestedAt && roundTrip >= 0 && roundTrip <= 500
                                && !Double.isNaN(server) && !Double.isInfinite(server) && (Double.isNaN(clockRtt) || roundTrip <= clockRtt || now - clockSyncedAt > 30000)) {
                            clockOffset = server - (echoed + now) / 2;
                            clockRtt = roundTrip;
                            clockSyncedAt = now;
                        }
                        return;
                    }
                    if ("result".equals(message.optString("type"))) {
                        recognitionStats = message;
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
        if (!isAnnotatedMode()) {
            DataChannel.Init poseInit = new DataChannel.Init();
            poseInit.ordered = false;
            poseInit.maxRetransmits = 0;
            poseChannel = peer.createDataChannel("pose", poseInit);
            poseChannel.registerObserver(new DataChannel.Observer() {
                @Override public void onBufferedAmountChange(long amount) { }
                @Override public void onStateChange() { }
                @Override public void onMessage(DataChannel.Buffer buffer) {
                    if (generation != sessionGeneration || stopping || skeletonView == null || buffer.binary) return;
                    long receivedAt = SystemClock.elapsedRealtime();
                    if (buffer.data.remaining() > 16384) return;
                    byte[] bytes = new byte[buffer.data.remaining()];
                    buffer.data.get(bytes);
                    try {
                        JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                        if (!thisSession(message) || !"pose".equals(message.optString("type"))) return;
                        double decoded = message.optDouble("decoded_at_ms");
                        double age = Double.isNaN(decoded) || Double.isInfinite(decoded) || Double.isNaN(clockOffset)
                                || receivedAt - clockSyncedAt > 60000 ? Double.NaN : receivedAt + clockOffset - decoded;
                        long parsedAt = SystemClock.elapsedRealtime();
                        activity.runOnUiThread(() -> {
                            if (generation == sessionGeneration && !stopping) skeletonView.accept(message, receivedAt, age, parsedAt);
                        });
                    } catch (JSONException ignored) { /* Invalid pose updates expire; reliable control stays usable. */ }
                }
            });
        }
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
                    .put("sdp", robotSoftwareCodec ? current.getLocalDescription().description
                            : h264OnlySdp(current.getLocalDescription().description));
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

    /** Remove robot-incompatible VP8/VP9/AV1 payloads from the offer. */
    private static String h264OnlySdp(String sdp) {
        String[] lines = sdp.split("\\r\\n", -1);
        Set<String> remove = new HashSet<>();
        for (String line : lines) {
            if (!line.startsWith("a=rtpmap:")) continue;
            String[] parts = line.substring(9).split(" ", 2);
            if (parts.length == 2 && (parts[1].startsWith("VP8/") || parts[1].startsWith("VP9/")
                    || parts[1].startsWith("AV1/"))) remove.add(parts[0]);
        }
        for (String line : lines) {
            if (!line.startsWith("a=fmtp:") || !line.contains("apt=")) continue;
            for (String pt : new HashSet<>(remove)) if (line.matches("a=fmtp:[0-9]+ .*apt=" + pt + "(?:;.*)?")) {
                remove.add(line.substring(7, line.indexOf(' ', 7)));
                break;
            }
        }
        StringBuilder out = new StringBuilder(sdp.length());
        for (String line : lines) {
            if (line.startsWith("m=video ")) {
                String[] p = line.split(" ");
                StringBuilder m = new StringBuilder();
                for (int i = 0; i < p.length; i++) {
                    if (i < 3 || !remove.contains(p[i])) m.append(i == 0 ? "" : " ").append(p[i]);
                }
                line = m.toString();
            } else if (line.startsWith("a=rtpmap:") || line.startsWith("a=fmtp:") || line.startsWith("a=rtcp-fb:")) {
                int colon = line.indexOf(':');
                int end = line.indexOf(' ', colon + 1);
                String pt = line.substring(colon + 1, end < 0 ? line.length() : end);
                if (remove.contains(pt)) continue;
            }
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    private void attachRemote(VideoTrack track, int generation) {
        if (generation != sessionGeneration || stopping || remoteTrack != null || "keypoints".equals(previewMode)) return;
        remoteTrack = track;
        activity.runOnUiThread(() -> {
            if (remoteTrack != track || stopping) return;
            updatePreview();
        });
        track.addSink(frame -> {
            if (generation != sessionGeneration || remoteTrack != track || stopping) return;
            long now = SystemClock.elapsedRealtime();
            boolean firstFrame = lastRemoteFrameAt == 0;
            boolean becameAvailable = firstFrame || now - lastRemoteFrameAt >= 3000;
            lastRemoteFrameAt = now;
            receivedFrames.incrementAndGet();
            remoteWidth = frame.getRotatedWidth();
            remoteHeight = frame.getRotatedHeight();
            if (showingRemote) remoteView.onFrame(frame);
            if (becameAvailable) activity.runOnUiThread(() -> {
                if (generation == sessionGeneration && remoteTrack == track && !stopping) {
                    remoteTimedOut = false;
                    if (isAnnotatedMode() && !firstFrame) preferRemote = false;
                    updatePreview();
                }
            });
        });
    }

    public void togglePreview() {
        if (stopping) return;
        if (isAnnotatedMode()) {
            if (!hasReturnVideo()) return;
            preferRemote = !showingRemote;
        } else {
            skeletonEnabled = !skeletonEnabled;
            preferRemote = false;
        }
        updatePreview();
    }

    public boolean isSkeletonEnabled() { return skeletonEnabled; }
    public boolean isAnnotatedMode() { return "annotated".equals(previewMode); }
    public boolean hasReturnVideo() {
        return remoteTrack != null && !stopping && lastRemoteFrameAt > 0 && SystemClock.elapsedRealtime() - lastRemoteFrameAt < 3000;
    }
    public boolean hasDiagnosticVideo() { return "diagnostic".equals(previewMode) && hasReturnVideo(); }
    public boolean showingDiagnostic() { return "diagnostic".equals(previewMode) && showingRemote; }
    public String currentPreview() { return showingRemote ? previewMode : skeletonEnabled ? "skeleton" : "local"; }
    public void toggleDiagnostic() {
        if (!hasDiagnosticVideo()) return;
        preferRemote = !preferRemote;
        updatePreview();
    }

    private void updatePreview() {
        localView.setMirror(DISPLAY_MIRROR);
        remoteView.setMirror(DISPLAY_MIRROR);
        boolean available = hasReturnVideo();
        showingRemote = available && preferRemote;
        remoteView.setVisibility(showingRemote ? View.VISIBLE : View.GONE);
        localView.setVisibility(showingRemote ? View.GONE : View.VISIBLE);
        if (skeletonView != null) skeletonView.setVisibility(!showingRemote && skeletonEnabled ? View.VISIBLE : View.INVISIBLE);
        listener.onPreviewChanged(currentPreview(), available);
        if (isAnnotatedMode() && !stopping && !available) listener.onCameraStatus(lastRemoteFrameAt == 0
                ? "等待远端标注画面，当前为本地预览" : "远端标注画面中断，已显示本地预览");
    }

    private void checkRemoteTimeout() {
        if (isAnnotatedMode() && lastRemoteFrameAt > 0 && !hasReturnVideo() && !remoteTimedOut) {
            remoteTimedOut = true;
            preferRemote = false;
            updatePreview();
        }
    }

    private boolean thisSession(JSONObject message) {
        return training != null && training.optString("session_id").equals(message.optString("session_id"));
    }

    private void sendClockPing() {
        DataChannel channel = dataChannel;
        if (channel == null || channel.state() != DataChannel.State.OPEN || stopping) return;
        clockRequestedAt = SystemClock.elapsedRealtime();
        byte[] bytes = RehabApiClient.object("type", "clock_ping", "client_ms", clockRequestedAt).toString().getBytes(StandardCharsets.UTF_8);
        channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    public void diagnosticFrame(RehabApiClient.Result callback) {
        if (!hasDiagnosticVideo() || !SessionStore.staff(activity)) { callback.done(null, "本次训练未开启远端诊断"); return; }
        final int generation = sessionGeneration;
        Request request = new Request.Builder().url(serverUrl + "/rtc/sessions/" + training.optString("session_id") + "/frame.png")
                .header("Authorization", "Bearer " + SessionStore.token(activity)).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                if (!call.isCanceled()) activity.runOnUiThread(() -> {
                    if (generation == sessionGeneration) callback.done(null, "抓帧失败，请检查连接");
                });
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                android.graphics.Bitmap bitmap = null;
                try (Response incoming = response) {
                    if (incoming.isSuccessful() && incoming.body() != null)
                        bitmap = BitmapFactory.decodeStream(incoming.body().byteStream());
                }
                android.graphics.Bitmap result = bitmap;
                activity.runOnUiThread(() -> {
                    if (generation == sessionGeneration) callback.done(result, result == null ? "抓帧不可用，请确认工作人员身份与诊断会话" : null);
                    else if (result != null) result.recycle();
                });
            }
        });
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
        JSONObject sample = performanceStats.sample(report, captureFps, receiveFps);
        try {
            sample.put("server_fps", processedFps).put("server_total_ms", serverMs)
                    .put("dropped_input", droppedInput).put("dropped_output", droppedOutput)
                    .put("requested_width", CAPTURE_WIDTH).put("requested_height", CAPTURE_HEIGHT)
                    .put("requested_fps", CAPTURE_FPS).put("capture_width", captureWidth)
                    .put("capture_height", captureHeight).put("capture_rotation", captureRotation)
                    .put("return_width", remoteWidth).put("return_height", remoteHeight);
            sample.put("return_enabled", !"keypoints".equals(previewMode)).put("preview_mode", previewMode)
                    .put("clock_rtt_ms", Double.isNaN(clockRtt) || SystemClock.elapsedRealtime() - clockSyncedAt > 60000 ? JSONObject.NULL : clockRtt);
            JSONObject recognition = recognitionStats;
            for (String key : new String[]{"body_fps", "left_hand_fps", "right_hand_fps",
                    "body_updates_total", "left_hand_updates_total", "right_hand_updates_total",
                    "pose_inferred", "pose_inference_ms", "hand_detection_ms", "tracking_ms",
                    "hand_full_detections", "hand_crop_detections"}) {
                sample.put(key, recognition.opt(key));
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
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
            if (skeletonView != null) {
                try { sample.put("skeleton", skeletonView.takePerformance()); }
                catch (JSONException e) { throw new IllegalStateException(e); }
            }
            performanceSample = sample;
            JSONObject overlay = sample.optJSONObject("skeleton");
            org.json.JSONArray ages = overlay == null ? null : overlay.optJSONArray("decoded_to_draw_ms");
            org.json.JSONArray cpu = overlay == null ? null : overlay.optJSONArray("draw_cpu_ms");
            double age = ages == null ? Double.NaN : ages.optDouble(ages.length() - 1);
            double drawCpu = cpu == null ? Double.NaN : cpu.optDouble(cpu.length() - 1);
            String recognition = sample.has("body_fps") ? String.format(
                    "人体新检测 %.1f / 左手 %.1f / 右手 %.1f 次/秒\n最近帧：人体 %.1f%s / 手部检测 %.1f / 跟踪平滑 %.1f ms",
                    sample.optDouble("body_fps"), sample.optDouble("left_hand_fps"), sample.optDouble("right_hand_fps"),
                    sample.optDouble("pose_inference_ms"), sample.optBoolean("pose_inferred") ? "" : "（复用）",
                    sample.optDouble("hand_detection_ms"), sample.optDouble("tracking_ms"))
                    : "关键点更新与分段耗时：服务端未提供";
            listener.onStats(String.format(
                    "采集 %.1f  服务端 %.1f  回传 %s\n处理 %.0f  上传编码 %.0f（区间 %.0f）  RTT %.0f ms\n跳帧 %d/%d  丢包 %d  采集 %d×%d\n最近解码→绘制 %s ms；绘制 CPU %s ms\n时钟误差约 ±%s ms；不含采集和上传",
                    sample.optDouble("capture_fps"), processedFps, !"keypoints".equals(previewMode) ? String.format("%.1f FPS", sample.optDouble("return_fps")) : "未启用",
                    serverMs, finalEncodeMs, sample.optDouble("encode_interval_ms"), finalRttMs,
                    droppedInput, droppedOutput, finalLost, captureWidth, captureHeight,
                    Double.isNaN(age) ? "--" : String.format("%.0f", age),
                    Double.isNaN(drawCpu) ? "--" : String.format("%.2f", drawCpu),
                    sample.isNull("clock_rtt_ms") ? "--" : String.format("%.0f", clockRtt / 2)) + "\n" + recognition);
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
        DataChannel oldPoseChannel = poseChannel;
        PeerConnection oldPeer = peer;
        dataChannel = null;
        poseChannel = null;
        peer = null;
        remoteTrack = null;
        lastRemoteFrameAt = 0;
        skeletonEnabled = false;
        if (skeletonView != null) skeletonView.setSession("");
        if (!destroyed) {
            updatePreview();
            remoteView.clearImage();
        }
        CLEANUP_EXECUTOR.execute(() -> {
            if (oldPoseChannel != null) {
                oldPoseChannel.unregisterObserver(); oldPoseChannel.close(); oldPoseChannel.dispose();
            }
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
