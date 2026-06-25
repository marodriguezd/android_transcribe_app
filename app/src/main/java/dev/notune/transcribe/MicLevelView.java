package dev.notune.transcribe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class MicLevelView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float current = 0f;   // 0..1
    private float target = 0f;    // 0..1

    public MicLevelView(Context c) { super(c); init(); }
    public MicLevelView(Context c, AttributeSet a) { super(c, a); init(); }
    public MicLevelView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // white, we set the alpha dynamically
    }


    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** level: 0..1 */
    public void setLevel(float level) {
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        target = level;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Exponential smoothing (low-pass filter)
        // 0.25f provides a fast, smooth transition (~60ms response)
        current = current + 0.25f * (target - current);
        if (Math.abs(target - current) > 0.005f) {
            postInvalidateOnAnimation();
        } else {
            current = target;
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float min = Math.min(getWidth(), getHeight()) / 2f;

        // Radius: base size + level
        float base = min * 0.42f;
        float extra = min * 0.28f * current;
        float r = base + extra;

        // Inner bright circle (makes the center brighter)
        int alphaInner = (int) (50 + 90 * current);  // 50..140
        paint.setAlpha(alphaInner);
        canvas.drawCircle(cx, cy, r, paint);

        // Outer soft glow (larger, more transparent)
        int alphaOuter = (int) (18 + 45 * current);  // 18..63
        paint.setAlpha(alphaOuter);
        canvas.drawCircle(cx, cy, r * 1.25f, paint);
    }


}
