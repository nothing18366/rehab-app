package com.robint.rtc;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoFrame;

/** Test-only viewer: shares the existing capture and pose packets, with no second camera. */
final class ExternalTrainingPresentation extends Presentation {
    final SurfaceViewRenderer video;
    final SkeletonOverlayView skeleton;
    private final EglBase.Context eglContext;
    private boolean released;

    ExternalTrainingPresentation(Activity activity, Display display, EglBase.Context eglContext) {
        super(activity, display);
        this.eglContext = eglContext;
        video = new SurfaceViewRenderer(getContext());
        skeleton = new SkeletonOverlayView(getContext(), null);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);
        root.addView(video, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(skeleton, new FrameLayout.LayoutParams(-1, -1));
        TextView label = new TextView(getContext());
        label.setText("副屏测试 · 请在触摸屏操作");
        label.setTextColor(Color.WHITE);
        label.setTextSize(18);
        label.setPadding(16, 16, 16, 16);
        root.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        video.init(eglContext, null);
        video.setMirror(true);
        video.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        skeleton.setMirror(true);
        getWindow().setLayout(-1, -1);
    }

    synchronized void onFrame(VideoFrame frame) {
        if (!released) video.onFrame(frame);
    }

    @Override protected void onStop() {
        synchronized (this) {
            if (!released) { released = true; video.release(); }
        }
        skeleton.setSession("");
        super.onStop();
    }
}
