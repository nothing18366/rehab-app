package com.robint;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.robint.feature.training.TrainingCueAudio;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit opt-in: this test plays a phrase on the connected device. */
@RunWith(AndroidJUnit4.class)
public class TrainingVoiceTest {
    @Test public void fixedChineseCuePlaysOnceCompletesAndStops() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("allowPhysicalVoice")));
        TrainingCueAudio[] audio = new TrainingCueAudio[1];
        CountDownLatch finished = new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                audio[0] = new TrainingCueAudio(InstrumentationRegistry.getInstrumentation().getTargetContext());
                assertTrue(audio[0].available());
                assertTrue(audio[0].play("轻轻握起，慢慢松开，不舒服就停下", finished::countDown));
                assertFalse(audio[0].play("本项训练已结束", () -> {}));
            });
            assertTrue("Chinese audio completion callback missing", finished.await(15, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertFalse(audio[0].isPlaying());
                assertTrue(audio[0].play("本项训练已结束", () -> {}));
                audio[0].stop();
                assertFalse(audio[0].isPlaying());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (audio[0] != null) audio[0].stop(); });
        }
    }
}
