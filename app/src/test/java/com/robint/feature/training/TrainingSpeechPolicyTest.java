package com.robint.feature.training;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrainingSpeechPolicyTest {
    @Test public void correctionsWaitForPlaybackAndCooldown() {
        TrainingSpeechPolicy policy = new TrainingSpeechPolicy();
        assertFalse(policy.allows("wrist", 0, true, false));
        assertTrue(policy.allows("wrist", 0, false, false));
        policy.spoken("wrist", 0);
        assertFalse(policy.allows("close", 4999, false, false));
        assertTrue(policy.allows("close", 5000, false, false));
        assertFalse(policy.allows("wrist", 11999, false, false));
        assertTrue(policy.allows("wrist", 12000, false, false));
        policy.spoken("close", 5000);
        assertFalse(policy.allows("wrist", 10000, false, false));
        assertTrue(policy.allows("wrist", 12000, false, false));
    }

    @Test public void announcementsNeverInterruptAndStillDelayCorrections() {
        TrainingSpeechPolicy policy = new TrainingSpeechPolicy();
        policy.spoken("wrist", 1000);
        assertFalse(policy.allows("notice:end", 1500, true, true));
        assertTrue(policy.allows("notice:end", 1500, false, true));
        policy.spoken("notice:end", 1500);
        assertFalse(policy.allows("close", 6499, false, false));
        assertTrue(policy.allows("close", 6500, false, false));
    }
}
