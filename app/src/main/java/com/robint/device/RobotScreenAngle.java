package com.robint.device;

import android.os.Handler;
import android.os.Looper;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local angle selection; a service acknowledgement is not position telemetry. */
public final class RobotScreenAngle {
    public static final int DEFAULT_PITCH = 10;
    public static final int MIN_PITCH = 0, MAX_PITCH = 30;
    private static final String UPLINK = "homeservice/uplink", DOWNLINK = "homeservice/downlink";
    private final String clientId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private int pitch = DEFAULT_PITCH;
    private boolean busy, acknowledged;
    private String error;

    public RobotScreenAngle(String clientId) { this.clientId = clientId; }
    public int pitch() { return pitch; }
    public boolean busy() { return busy; }
    public String error() { return error; }
    public String status() {
        if (busy) return "正在调整至 " + pitch + "…";
        if (error != null) return error;
        return acknowledged ? "已发送档位 " + pitch + "，请观察屏幕位置。" : "本次启动默认档位 10。";
    }
    public void addListener(Runnable listener) { listeners.add(listener); listener.run(); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : listeners.toArray(new Runnable[0])) listener.run(); }

    public boolean apply(int value) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use the main thread");
        if (!supported(value)) throw new IllegalArgumentException("Unsupported screen angle");
        if (busy) return false;
        pitch = value; busy = true; acknowledged = false; error = null; changed();
        worker.execute(() -> {
            String failure = null;
            try { send(value); }
            catch (Exception e) { failure = e instanceof IOException ? e.getMessage() : "未能确认屏幕角度，请检查机器人连接后重试。"; }
            String result = failure;
            main.post(() -> { busy = false; error = result; acknowledged = result == null; changed(); });
        });
        return true;
    }

    static boolean supported(int value) { return value >= MIN_PITCH && value <= MAX_PITCH; }
    static JSONObject request(int pitch, String clientId) throws Exception {
        if (!supported(pitch)) throw new IllegalArgumentException("Unsupported screen angle");
        return new JSONObject().put("messageId", UUID.randomUUID().toString())
            .put("type", "AGENT_SERVICE_REQUEST").put("fromId", clientId).put("toId", "app_main")
            .put("time", System.currentTimeMillis()).put("service", "afr").put("action", "neck_control")
            .put("version", 1).put("data", new JSONObject().put("pitch", pitch));
    }
    static boolean matches(JSONObject reply, JSONObject request) {
        Object code = reply.opt("code");
        return "AGENT_SERVICE_RESPONSE".equals(reply.optString("type"))
            && request.optString("messageId").equals(reply.optString("requestMessageId"))
            && "app_main".equals(reply.optString("fromId"))
            && request.optString("fromId").equals(reply.optString("toId"))
            && (reply.isNull("service") || "".equals(reply.optString("service")) || "afr".equals(reply.optString("service")))
            && (reply.isNull("action") || "".equals(reply.optString("action")) || "neck_control".equals(reply.optString("action")))
            && (code instanceof Integer || code instanceof Long);
    }

    private void send(int pitch) throws Exception {
        JSONObject request = request(pitch, clientId);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> response = new AtomicReference<>();
        MqttClient mqtt = new MqttClient("tcp://127.0.0.1:1883", clientId, new MemoryPersistence());
        mqtt.setTimeToWait(3000);
        mqtt.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { done.countDown(); }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
            @Override public void messageArrived(String topic, MqttMessage message) {
                if (!UPLINK.equals(topic) || message.isRetained()) return;
                try {
                    JSONObject reply = new JSONObject(new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
                    if (matches(reply, request)) { response.compareAndSet(null, reply); done.countDown(); }
                } catch (org.json.JSONException ignored) { }
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(3); options.setKeepAliveInterval(20);
        options.setCleanSession(true); options.setAutomaticReconnect(false);
        try {
            mqtt.connect(options);
            mqtt.subscribe(UPLINK, 0);
            // Publish once, without retain or retry: a lost acknowledgement may still mean motion.
            mqtt.publish(DOWNLINK, request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, false);
            if (!done.await(8, TimeUnit.SECONDS) || response.get() == null)
                throw new IOException("未收到角度确认，请先观察屏幕位置，再决定是否重试。");
            if (response.get().getLong("code") != 0)
                throw new IOException("机器人未接受角度调整（" + response.get().getLong("code") + "），请稍后重试。");
        } finally {
            try { if (mqtt.isConnected()) mqtt.disconnectForcibly(0, 0, false); }
            catch (MqttException ignored) { }
            try { mqtt.close(); } catch (MqttException ignored) { }
        }
    }
}
