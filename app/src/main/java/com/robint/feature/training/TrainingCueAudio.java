package com.robint.feature.training;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Fixed Mandarin cues for robots without an Android TTS service. */
public final class TrainingCueAudio {
    private final Context context;
    private JSONObject clips = new JSONObject();
    private MediaPlayer player;

    public TrainingCueAudio(Context context) {
        this.context = context.getApplicationContext();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("training_voice/index.json"), "UTF-8"))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            clips = new JSONObject(json.toString());
        } catch (Exception ignored) { }
    }

    public boolean available() { return clips.length() > 0; }
    public boolean has(String text) { return clips.has(text); }
    public boolean isPlaying() { return player != null; }

    public boolean play(String text, Runnable finished) {
        if (player != null || !has(text)) return false;
        MediaPlayer next = new MediaPlayer();
        try (AssetFileDescriptor asset = context.getAssets().openFd("training_voice/" + clips.getString(text))) {
            next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            next.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            next.setOnCompletionListener(mp -> complete(mp, finished));
            next.setOnErrorListener((mp, what, extra) -> { complete(mp, finished); return true; });
            player = next;
            next.prepare(); next.start();
            return true;
        } catch (Exception error) {
            if (player == next) player = null;
            next.release();
            return false;
        }
    }

    private void complete(MediaPlayer completed, Runnable finished) {
        if (player != completed) return;
        player = null; completed.release(); finished.run();
    }

    public void stop() {
        if (player == null) return;
        MediaPlayer previous = player; player = null;
        previous.setOnCompletionListener(null); previous.setOnErrorListener(null); previous.release();
    }
}
