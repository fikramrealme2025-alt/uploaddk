package top.nativex.blackboxa;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Switch gaya Material 3 (track 52x32, thumb 16 -> 24 dp), Java murni tanpa library. */
public class M3Switch extends View {
    public interface OnChangeListener {
        void onChanged(M3Switch s, boolean checked);
    }

    private static final int TRACK_ON = 0xFF5CC8FF;
    private static final int THUMB_ON = 0xFF07243A;
    private static final int TRACK_OFF = 0x33FFFFFF;
    private static final int OUTLINE_OFF = 0xB3FFFFFF;
    private static final int THUMB_OFF = 0xCCFFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;
    private boolean checked;
    private float progress; // 0 = off, 1 = on
    private ValueAnimator anim;
    private OnChangeListener listener;

    public M3Switch(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
    }

    public void setOnChangeListener(OnChangeListener l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean on, boolean animate) {
        if (checked == on && (progress == (on ? 1f : 0f))) return;
        checked = on;
        if (anim != null) anim.cancel();
        if (!animate) {
            progress = on ? 1f : 0f;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(progress, on ? 1f : 0f);
        anim.setDuration(180);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) (52 * d), (int) (32 * d));
    }

    private static int mix(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = h / 2f;
        float stroke = 2 * d;

        // track
        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mix(TRACK_OFF, TRACK_ON, progress));
        c.drawRoundRect(rect, r, r, paint);
        if (progress < 1f) {
            rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(mix(OUTLINE_OFF, TRACK_ON, progress));
            c.drawRoundRect(rect, r - stroke / 2, r - stroke / 2, paint);
        }

        // thumb: 16dp (off) -> 24dp (on)
        float thumbR = (8 + 4 * progress) * d;
        float cxOff = r;
        float cxOn = w - r;
        float cx = cxOff + (cxOn - cxOff) * progress;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mix(THUMB_OFF, THUMB_ON, progress));
        c.drawCircle(cx, h / 2f, thumbR, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && isEnabled()) {
            performClick();
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        setChecked(!checked, true);
        if (listener != null) listener.onChanged(this, checked);
        return super.performClick();
    }
}
