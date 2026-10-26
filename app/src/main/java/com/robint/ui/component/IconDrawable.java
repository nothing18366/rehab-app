package com.robint.ui.component;



import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.PathParser;

/** Outline icons from the supplied rehab prototype. */
public final class IconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path;
    public IconDrawable(String name, int color) {
        String data;
        switch (name) {
            case "home": data = "M3 10 12 3l9 7v10h-6v-7H9v7H3Z"; break;
            case "calendar": data = "M3 5h18v16H3ZM7 3v4m10-4v4M3 11h18m-14 4h1m4 0h1m4 0h1"; break;
            case "hand": data = "M8 12V5a1.5 1.5 0 0 1 3 0v6V3a1.5 1.5 0 0 1 3 0v8V5a1.5 1.5 0 0 1 3 0v7V9a1.5 1.5 0 0 1 3 0v6c0 4-3 7-7 7-2 0-4-1-5-3l-4-6a1.5 1.5 0 0 1 2-2l3 3"; break;
            case "chart": data = "M4 3v17h17M8 15l4-5 4 2 5-7"; break;
            case "settings": data = "m9 3-1 3-3 1-2 3 2 3-1 3 3 2 3-1 3 2 3-2 1-3 3-2-1-3-3-1-1-3ZM15 11a3 3 0 1 1-6 0 3 3 0 0 1 6 0"; break;
            case "users": data = "M15 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M3 21v-2a8 8 0 0 1 14 0v2m1-18a4 4 0 0 1 0 8m2 4a6 6 0 0 1 2 5"; break;
            case "file": data = "M5 3h9l5 5v13H5Zm9 0v5h5M8 12h8m-8 4h6"; break;
            case "clock": data = "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v5l3 2"; break;
            case "shield": data = "M12 3 3 7v6c0 5 9 9 9 9s9-4 9-9V7Zm-4 9 3 3 5-6"; break;
            case "lock": data = "M5 10h14v11H5ZM8 10V7a4 4 0 0 1 8 0v3m-4 4v3"; break;
            case "eye": data = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0"; break;
            case "play": data = "m8 5 11 7-11 7Z"; break;
            case "arrow": data = "M4 12h16m-6-6 6 6-6 6"; break;
            case "chevron": data = "m9 5 7 7-7 7"; break;
            case "camera": data = "M3 7h5l2-3h4l2 3h5v14H3Zm13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0"; break;
            case "database": data = "M20 6c0 2-4 3-8 3S4 8 4 6s4-3 8-3 8 1 8 3Zm0 0v12c0 2-4 3-8 3s-8-1-8-3V6m0 6c0 2 4 3 8 3s8-1 8-3"; break;
            default: data = "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 11v6m0-10v1";
        }
        path = PathParser.createPathFromPathData(data);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setColor(color);
        setBounds(0, 0, 24, 24);
    }
    @Override public void draw(Canvas canvas) {
        canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f); canvas.drawPath(path, paint); canvas.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
