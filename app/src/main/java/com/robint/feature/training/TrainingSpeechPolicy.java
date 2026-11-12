package com.robint.feature.training;

import java.util.HashMap;
import java.util.Map;

/** Correction cooldown is independent of the Android TTS engine. */
final class TrainingSpeechPolicy {
    private long lastAt;
    private final Map<String, Long> spokenAt = new HashMap<>();
    private String correctionCode = "", correctionText = "";

    boolean allows(String code, long now, boolean busy, boolean announcement) {
        if (busy || code.isEmpty()) return false;
        if (announcement || spokenAt.isEmpty()) return true;
        Long sameReasonAt = spokenAt.get(code);
        return now - lastAt >= 5000 && (sameReasonAt == null || now - sameReasonAt >= 12000);
    }

    void spoken(String code, String text, long now, boolean announcement) {
        spokenAt.put(code, now); lastAt = now;
        correctionCode = announcement ? "" : code;
        correctionText = announcement ? "" : text;
    }

    boolean correctionExpired(String code, String text) {
        return !correctionCode.isEmpty() && (!correctionCode.equals(code) || !correctionText.equals(text));
    }

    void playbackFinished() { correctionCode = ""; correctionText = ""; }
}
