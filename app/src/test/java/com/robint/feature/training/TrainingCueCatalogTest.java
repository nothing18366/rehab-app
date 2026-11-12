package com.robint.feature.training;

import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import static org.junit.Assert.*;

public class TrainingCueCatalogTest {
    @Test public void currentCorrectiveTextsHavePlayableAssetsAndNoObsoleteHoldCues() throws Exception {
        File directory = new File("src/main/assets/training_voice");
        JSONObject catalog = new JSONObject(new String(Files.readAllBytes(new File(directory, "index.json").toPath()), StandardCharsets.UTF_8));
        for (String side : new String[]{"左手", "右手", "双手"}) {
            for (String verb : new String[]{"先慢慢张开", "慢慢握拳", "继续握拳", "慢慢张开", "继续张开"})
                assertTrue(catalog.has(side + verb + "，有不适就暂停。"));
        }
        for (String side : new String[]{"左侧手腕", "右侧手腕", "双侧手腕"}) {
            assertTrue(catalog.has(side + "稳住，手指慢慢动。"));
            assertTrue(catalog.has(side + "放松，轻轻回正，有不适就暂停。"));
        }
        assertTrue(catalog.has("请把双手放回镜头前，前臂放稳"));
        for (Iterator<String> keys = catalog.keys(); keys.hasNext();) {
            String text = keys.next();
            assertFalse(text.contains("一秒"));
            assertFalse(text.contains("松不开就休息一下"));
            assertFalse(text.contains("手腕先稳住"));
            assertFalse(text.matches(".*(食指|中指|无名指|小指).*"));
            assertFalse(text.contains("还没到位"));
            File clip = new File(directory, catalog.getString(text));
            assertEquals(directory.getCanonicalFile(), clip.getCanonicalFile().getParentFile());
            assertTrue(clip.isFile() && clip.length() > 0);
        }
    }
}
