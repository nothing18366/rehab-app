package com.robint.feature.training;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import static org.junit.Assert.*;

public class TrainingCueAudioTest {
    @Test public void fixedMandarinCuesAreCompleteAndHaveAudioFiles() throws Exception {
        Path root = Paths.get("src/main/assets/training_voice");
        JSONObject clips = new JSONObject(new String(Files.readAllBytes(root.resolve("index.json")), "UTF-8"));
        assertEquals(9, clips.length());
        HashSet<String> files = new HashSet<>();
        for (Iterator<String> keys = clips.keys(); keys.hasNext();) {
            String name = clips.getString(keys.next());
            assertTrue(name.matches("cue_[0-9]{2}\\.mp3"));
            assertTrue(files.add(name));
            assertTrue(Files.size(root.resolve(name)) > 1000);
        }
        for (String side : new String[]{"左手", "右手"}) {
            assertTrue(clips.has(side + "手腕先稳住，放松一点，手指慢慢动"));
            assertTrue(clips.has(side + "先放松，慢慢松开，松不开就休息一下"));
        }
        assertTrue(clips.has("训练开始"));
        assertTrue(clips.has("已达到次数目标，可点击结束本项"));
        assertTrue(clips.has("本项训练已结束"));
        assertTrue(clips.has("轻轻握起，慢慢松开，不舒服就停下"));
        assertTrue(clips.has("请把双手放回镜头前，前臂放稳"));
        for (Iterator<String> keys = clips.keys(); keys.hasNext();) {
            String text = keys.next();
            assertFalse(text.contains("到位") || text.contains("一秒") || text.contains("用力握"));
        }
    }
}
