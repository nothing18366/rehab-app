package com.robint.rtc;

import android.os.SystemClock;
import org.json.JSONObject;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import java.util.HashMap;
import java.util.Map;
import com.robint.data.remote.RehabApiClient;

/** Interval rates use elapsed time; RTC byte counters measure payload, not Wi-Fi overhead. */
final class PerformanceStats {
    private final Map<String, Double> previous = new HashMap<>();
    private long lastAt = SystemClock.elapsedRealtime();

    JSONObject sample(RTCStatsReport report, int captured, int received) {
        long at = SystemClock.elapsedRealtime();
        double seconds = Math.max(.001, (at - lastAt) / 1000.0);
        lastAt = at;
        Map<String, Double> totals = new HashMap<>();
        double rtt = 0;
        for (RTCStats stat : report.getStatsMap().values()) {
            Map<String, Object> m = stat.getMembers();
            String type = stat.getType();
            if ("candidate-pair".equals(type) && Boolean.TRUE.equals(m.get("nominated")))
                rtt = number(m, "currentRoundTripTime") * 1000;
            String prefix = "";
            if ("outbound-rtp".equals(type) && "video".equals(m.get("kind"))) prefix = "upload_";
            if ("inbound-rtp".equals(type) && "video".equals(m.get("kind"))) prefix = "download_";
            if ("remote-inbound-rtp".equals(type) && "video".equals(m.get("kind"))) prefix = "upload_remote_";
            if ("data-channel".equals(type)) prefix = "data_";
            if (prefix.isEmpty()) continue;
            for (String key : new String[]{"bytesSent", "bytesReceived", "packetsSent", "packetsReceived",
                    "packetsLost", "framesEncoded", "framesDecoded", "totalEncodeTime"}) {
                if (m.get(key) instanceof Number) {
                    String name = prefix + key;
                    totals.put(name, totals.getOrDefault(name, 0.0) + number(m, key));
                }
            }
        }
        JSONObject sample = RehabApiClient.object("monotonic_ms", at, "sample_seconds", seconds,
                "capture_fps", captured / seconds, "return_fps", received / seconds, "rtt_ms", rtt);
        try {
            for (Map.Entry<String, Double> counter : totals.entrySet()) {
                String key = counter.getKey();
                double value = counter.getValue();
                sample.put(key, value);
                Double old = previous.put(key, value);
                sample.put(key + "_delta", old == null || value < old ? JSONObject.NULL : value - old);
                if (key.contains("bytes")) sample.put(key + "_bps", old == null || value < old
                        ? JSONObject.NULL : (value - old) * 8 / seconds);
            }
            double frames = totals.getOrDefault("upload_framesEncoded", 0.0);
            sample.put("encode_cumulative_ms", frames == 0 ? JSONObject.NULL
                    : totals.getOrDefault("upload_totalEncodeTime", 0.0) * 1000 / frames);
            double intervalFrames = sample.optDouble("upload_framesEncoded_delta", 0);
            sample.put("upload_encoded_fps", sample.isNull("upload_framesEncoded_delta") ? JSONObject.NULL : intervalFrames / seconds);
            sample.put("encode_interval_ms", intervalFrames <= 0 ? JSONObject.NULL
                    : sample.optDouble("upload_totalEncodeTime_delta") * 1000 / intervalFrames);
            for (String prefix : new String[]{"download_", "upload_remote_"}) {
                double lost = sample.optDouble(prefix + "packetsLost_delta", Double.NaN);
                double packets = sample.optDouble(prefix + "packetsReceived_delta", Double.NaN);
                sample.put(prefix + "loss_fraction", Double.isNaN(lost + packets) || lost + packets <= 0
                        ? JSONObject.NULL : Math.max(0, lost) / (packets + Math.max(0, lost)));
            }
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return sample;
    }

    private static double number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }
}
