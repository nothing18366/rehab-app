package com.robint.feature.training;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrainingSpeechPolicyTest {
    @Test public void correctionsWaitForPlaybackAndCooldown() {
        TrainingSpeechPolicy policy = new TrainingSpeechPolicy();
        assertFalse(policy.allows("wrist", 0, true, false));
        assertTrue(policy.allows("wrist", 0, false, false));
        policy.spoken("wrist", "右侧手腕稳住，手指慢慢动。", 0, false);
        assertFalse(policy.allows("close", 4999, false, false));
        assertTrue(policy.allows("close", 5000, false, false));
        assertFalse(policy.allows("wrist", 11999, false, false));
        assertTrue(policy.allows("wrist", 12000, false, false));
        policy.spoken("close", "慢慢松开", 5000, false);
        assertFalse(policy.allows("wrist", 10000, false, false));
        assertTrue(policy.allows("wrist", 12000, false, false));
    }

    @Test public void announcementsNeverInterruptAndStillDelayCorrections() {
        TrainingSpeechPolicy policy = new TrainingSpeechPolicy();
        policy.spoken("wrist", "右侧手腕稳住，手指慢慢动。", 1000, false);
        assertFalse(policy.allows("notice:end", 1500, true, true));
        assertTrue(policy.allows("notice:end", 1500, false, true));
        policy.spoken("notice:end", "训练结束", 1500, true);
        assertFalse(policy.allows("close", 6499, false, false));
        assertTrue(policy.allows("close", 6500, false, false));
    }

    @Test public void recoveryAndSideChangesCancelTheActiveCorrectionWithoutResettingCooldown() {
        TrainingSpeechPolicy policy = new TrainingSpeechPolicy();
        policy.spoken("wrist_move", "右侧手腕稳住，手指慢慢动。", 1000, false);
        assertFalse(policy.correctionExpired("wrist_move", "右侧手腕稳住，手指慢慢动。"));
        assertTrue(policy.correctionExpired("wrist_move", "左侧手腕稳住，手指慢慢动。"));
        assertTrue(policy.correctionExpired("tracking_missing", "请把双手放回镜头前"));
        assertTrue(policy.correctionExpired("", ""));
        policy.playbackFinished();
        assertFalse(policy.correctionExpired("", ""));
        assertFalse(policy.allows("tracking_missing", 2000, false, false));
        assertFalse(policy.allows("wrist_move", 12000, false, false));
        assertTrue(policy.allows("wrist_move", 13000, false, false));
    }

    @Test public void noticesAreUnaffectedByChangingMovementFeedback() {
        TrainingSpeechPolicy policy = new TrainingSpeechPolicy();
        policy.spoken("notice:start", "训练开始", 1000, true);
        assertFalse(policy.correctionExpired("", ""));
        assertFalse(policy.correctionExpired("tracking_missing", "请把双手放回镜头前"));
    }
}
