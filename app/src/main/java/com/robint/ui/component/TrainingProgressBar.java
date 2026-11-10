package com.robint.ui.component;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.ProgressBar;

/** Ten visual segments; the value remains a percentage of the actual training target. */
public class TrainingProgressBar extends ProgressBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrainingProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs, android.R.attr.progressBarStyleHorizontal);
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        float gap = UiComponents.dp(getContext(), 4), width = (getWidth() - gap * 9) / 10f;
        float radius = UiComponents.dp(getContext(), 2), completed = getProgress() / (float) getMax() * 10;
        for (int i = 0; i < 10; i++) {
            float left = i * (width + gap), right = left + width;
            paint.setColor(getProgressBackgroundTintList() == null ? UiComponents.MINT : getProgressBackgroundTintList().getDefaultColor());
            canvas.drawRoundRect(left, 0, right, getHeight(), radius, radius, paint);
            float filled = Math.min(1, Math.max(0, completed - i));
            if (filled > 0) {
                canvas.save(); canvas.clipRect(left, 0, left + width * filled, getHeight());
                paint.setColor(getProgressTintList() == null ? UiComponents.PRIMARY : getProgressTintList().getDefaultColor());
                canvas.drawRoundRect(left, 0, right, getHeight(), radius, radius, paint);
                canvas.restore();
            }
        }
    }
}
