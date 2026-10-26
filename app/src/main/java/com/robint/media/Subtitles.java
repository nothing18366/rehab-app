package com.robint.media;



import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class Subtitles {
    private static class Cue { long start, end; String text; }
    private final List<Cue> cues = new ArrayList<>();
    public static Subtitles read(File file) {
        Subtitles subtitles = new Subtitles();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains(" --> ")) continue;
                String[] times = line.split(" --> "); Cue cue = new Cue(); cue.start = time(times[0]); cue.end = time(times[1]);
                StringBuilder text = new StringBuilder();
                while ((line = reader.readLine()) != null && !line.trim().isEmpty()) { if (text.length() > 0) text.append('\n'); text.append(line); }
                cue.text = text.toString(); subtitles.cues.add(cue);
            }
        } catch (Exception ignored) { }
        return subtitles;
    }
    private static long time(String value) {
        String[] parts = value.trim().split("[:,]"); return Long.parseLong(parts[0])*3600000 + Long.parseLong(parts[1])*60000 + Long.parseLong(parts[2])*1000 + Long.parseLong(parts[3]);
    }
    public String at(long milliseconds) { for (Cue cue : cues) if (cue.start <= milliseconds && milliseconds < cue.end) return cue.text; return ""; }
}
