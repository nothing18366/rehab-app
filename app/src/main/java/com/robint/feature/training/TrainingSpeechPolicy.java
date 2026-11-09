package com.robint.feature.training;

import java.util.HashMap;
import java.util.Map;

/** Correction cooldown is independent of the Android TTS engine. */
final class TrainingSpeechPolicy {
    private long lastAt;
    private final Map<String, Long> spokenAt = new HashMap<>();

    boolean allows(String code, long now, boolean busy, boolean announcement) {
        if (busy || code.isEmpty()) return false;
        if (announcement || spokenAt.isEmpty()) return true;
        Long sameReasonAt = spokenAt.get(code);
        return now - lastAt >= 5000 && (sameReasonAt == null || now - sameReasonAt >= 12000);
    }

    void spoken(String code, long now) { spokenAt.put(code, now); lastAt = now; }
}
