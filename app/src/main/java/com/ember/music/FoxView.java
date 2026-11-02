package com.ember.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ember's little fox. It bops and wags its tail while music plays, sleeps when it's paused,
 * blinks, reacts when tapped, can be dragged anywhere, and opens the settings on long-press.
 */
public class FoxView extends View {

    private static final int ORANGE = 0xFFFF7A1A;
    private static final int ORANGE_DARK = 0xFFD9590B;
    private static final int CREAM = 0xFFFFF4E6;
    private static final int INK = 0xFF1A1110;
    private static final int BLUSH = 0x66FF5A7A;

    private static final String[] YIPS = {"yip!", "♪♪", "hehe", "more music!", "boop", "^-^", "fox mode", "🔥"};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final float d;                     // 1dp in px
    private final SharedPreferences prefs;
    private final int touchSlop;

    private Runnable onLongPress;

    // animation state
    private long nextBlinkAt;
    private long blinkStart = -1;
    private long jumpStart = -1;
    private long spinStart = -1;
    private String bubble;
    private long bubbleUntil;
    private final List<float[]> hearts = new ArrayList<>(); // x, y, vx, vy, born

    // touch state
    private float downRawX, downRawY, startX, startY;
    private boolean dragging, longPressed;
    private final Runnable longPressCheck = () -> {
        if (!dragging) {
            longPressed = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (onLongPress != null) onLongPress.run();
        }
    };

    public FoxView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences(MusicService.PREFS, Context.MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Ember fox");
        nextBlinkAt = SystemClock.uptimeMillis() + 2000;
    }

    public void setOnFoxLongPress(Runnable r) {
        onLongPress = r;
    }

    /** Puts the fox where the user last left it (or bottom-right the first time). */
    public void restorePosition() {
        post(() -> {
            View parent = (View) getParent();
            if (parent == null) return;
            float fx = prefs.getFloat("foxX", -1f), fy = prefs.getFloat("foxY", -1f);
            float maxX = parent.getWidth() - getWidth(), maxY = parent.getHeight() - getHeight();
            setX(fx < 0 ? maxX - 4 * d : Math.max(0, Math.min(maxX, fx * maxX)));
            setY(fy < 0 ? maxY - 150 * d : Math.max(0, Math.min(maxY, fy * maxY)));
        });
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean playing = PlayerBridge.last != null && PlayerBridge.last.playing;
        float t = now / 1000f;
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float base = h - 6 * d;             // where the fox sits

        // body motion
        float bop = playing ? (float) Math.abs(Math.sin(t * Math.PI * 2.1)) * 5 * d : 0;
        float breathe = (float) Math.sin(t * 2) * (playing ? 0 : 1.2f * d);
        float jump = 0;
        if (jumpStart > 0) {
            float p = (now - jumpStart) / 450f;
            if (p >= 1) jumpStart = -1; else jump = (float) Math.sin(p * Math.PI) * 18 * d;
        }
        float spin = 0;
        if (spinStart > 0) {
            float p = (now - spinStart) / 600f;
            if (p >= 1) spinStart = -1; else spin = p * 360;
        }

        c.save();
        c.translate(0, -bop - jump);
        c.rotate(spin, cx, base - 24 * d);

        // tail (behind the body), wagging
        float wag = playing ? (float) Math.sin(t * 9) * 16 : (float) Math.sin(t * 1.5) * 4;
        c.save();
        c.rotate(wag, cx + 10 * d, base - 10 * d);
        path.reset();
        path.moveTo(cx + 8 * d, base - 6 * d);
        path.cubicTo(cx + 34 * d, base - 2 * d, cx + 38 * d, base - 30 * d, cx + 26 * d, base - 38 * d);
        path.cubicTo(cx + 24 * d, base - 24 * d, cx + 18 * d, base - 14 * d, cx + 6 * d, base - 14 * d);
        path.close();
        fill.setColor(ORANGE);
        c.drawPath(path, fill);
        fill.setColor(CREAM);
        c.drawCircle(cx + 27 * d, base - 35 * d, 5 * d, fill);
        c.restore();

        // body
        fill.setColor(ORANGE);
        c.drawOval(new RectF(cx - 15 * d, base - 26 * d + breathe, cx + 15 * d, base), fill);
        fill.setColor(CREAM);
        c.drawOval(new RectF(cx - 8 * d, base - 20 * d + breathe, cx + 8 * d, base - 1 * d), fill);
        // paws
        fill.setColor(ORANGE_DARK);
        c.drawOval(new RectF(cx - 12 * d, base - 5 * d, cx - 3 * d, base + 1 * d), fill);
        c.drawOval(new RectF(cx + 3 * d, base - 5 * d, cx + 12 * d, base + 1 * d), fill);

        // head
        float hy = base - 38 * d + breathe;           // head centre
        float tilt = playing ? (float) Math.sin(t * Math.PI * 2.1) * 6 : 0;
        c.save();
        c.rotate(tilt, cx, hy + 8 * d);
        // ears
        fill.setColor(ORANGE);
        drawTri(c, cx - 17 * d, hy - 4 * d, cx - 13 * d, hy - 24 * d, cx - 3 * d, hy - 10 * d);
        drawTri(c, cx + 17 * d, hy - 4 * d, cx + 13 * d, hy - 24 * d, cx + 3 * d, hy - 10 * d);
        fill.setColor(INK);
        drawTri(c, cx - 14 * d, hy - 7 * d, cx - 12.5f * d, hy - 18 * d, cx - 7 * d, hy - 10 * d);
        drawTri(c, cx + 14 * d, hy - 7 * d, cx + 12.5f * d, hy - 18 * d, cx + 7 * d, hy - 10 * d);
        // face
        fill.setColor(ORANGE);
        c.drawOval(new RectF(cx - 18 * d, hy - 14 * d, cx + 18 * d, hy + 14 * d), fill);
        fill.setColor(CREAM);
        path.reset();
        path.moveTo(cx - 17 * d, hy + 1 * d);
        path.quadTo(cx - 8 * d, hy - 1 * d, cx, hy + 3 * d);
        path.quadTo(cx + 8 * d, hy - 1 * d, cx + 17 * d, hy + 1 * d);
        path.quadTo(cx + 12 * d, hy + 15 * d, cx, hy + 14 * d);
        path.quadTo(cx - 12 * d, hy + 15 * d, cx - 17 * d, hy + 1 * d);
        c.drawPath(path, fill);
        // cheeks
        fill.setColor(BLUSH);
        c.drawCircle(cx - 11 * d, hy + 5 * d, 3 * d, fill);
        c.drawCircle(cx + 11 * d, hy + 5 * d, 3 * d, fill);
        // eyes: closed when asleep or blinking
        boolean asleep = !playing && jumpStart < 0 && spinStart < 0 && now > bubbleUntil;
        if (now >= nextBlinkAt && blinkStart < 0) blinkStart = now;
        float open = 1;
        if (blinkStart > 0) {
            float p = (now - blinkStart) / 160f;
            if (p >= 1) { blinkStart = -1; nextBlinkAt = now + 2500 + rnd.nextInt(3000); }
            else open = (float) Math.abs(1 - 2 * p);
        }
        fill.setColor(INK);
        if (asleep) {
            fill.setStrokeWidth(1.8f * d);
            fill.setStyle(Paint.Style.STROKE);
            c.drawArc(new RectF(cx - 10 * d, hy - 4 * d, cx - 4 * d, hy + 1 * d), 20, 140, false, fill);
            c.drawArc(new RectF(cx + 4 * d, hy - 4 * d, cx + 10 * d, hy + 1 * d), 20, 140, false, fill);
            fill.setStyle(Paint.Style.FILL);
        } else {
            float eh = Math.max(0.6f * d, 3.2f * d * open);
            c.drawOval(new RectF(cx - 9.5f * d, hy - 2 * d - eh, cx - 4.5f * d, hy - 2 * d + eh), fill);
            c.drawOval(new RectF(cx + 4.5f * d, hy - 2 * d - eh, cx + 9.5f * d, hy - 2 * d + eh), fill);
            if (open > 0.5f) {
                fill.setColor(Color.WHITE);
                c.drawCircle(cx - 6 * d, hy - 3.5f * d, 1.1f * d, fill);
                c.drawCircle(cx + 8 * d, hy - 3.5f * d, 1.1f * d, fill);
            }
        }
        // nose + mouth
        fill.setColor(INK);
        c.drawOval(new RectF(cx - 2.5f * d, hy + 3 * d, cx + 2.5f * d, hy + 6 * d), fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(1.2f * d);
        c.drawArc(new RectF(cx - 4 * d, hy + 4 * d, cx, hy + 8.5f * d), 0, 160, false, fill);
        c.drawArc(new RectF(cx, hy + 4 * d, cx + 4 * d, hy + 8.5f * d), 20, 160, false, fill);
        fill.setStyle(Paint.Style.FILL);
        c.restore(); // head tilt
        c.restore(); // body motion

        // Zzz while asleep
        if (asleep) {
            text.setColor(0xCCFFFFFF);
            for (int i = 0; i < 3; i++) {
                float p = ((t * 0.5f) + i / 3f) % 1f;
                text.setTextSize((8 + 5 * p) * d);
                text.setAlpha((int) (220 * (1 - p)));
                c.drawText("z", cx + 16 * d + p * 12 * d, base - 50 * d - p * 22 * d, text);
            }
        }
        // music notes while playing
        if (playing) {
            text.setColor(0xFFFF4D6A);
            float p = (t * 0.6f) % 1f;
            text.setTextSize(11 * d);
            text.setAlpha((int) (255 * (1 - p)));
            c.drawText("♪", cx - 22 * d - p * 6 * d, base - 46 * d - p * 26 * d, text);
        }

        // hearts from taps
        for (int i = hearts.size() - 1; i >= 0; i--) {
            float[] hh = hearts.get(i);
            float age = (now - hh[4]) / 1000f;
            if (age > 1.2f) { hearts.remove(i); continue; }
            text.setColor(0xFFFF3355);
            text.setTextSize(12 * d);
            text.setAlpha((int) (255 * (1 - age / 1.2f)));
            c.drawText("♥", hh[0] + hh[2] * age, hh[1] + hh[3] * age, text);
        }

        // speech bubble
        if (bubble != null && now < bubbleUntil) {
            text.setTextSize(11 * d);
            text.setAlpha(255);
            float tw = text.measureText(bubble) + 12 * d;
            float by = base - 66 * d - jump - bop;
            RectF r = new RectF(cx - tw / 2, by - 14 * d, cx + tw / 2, by + 4 * d);
            fill.setColor(Color.WHITE);
            c.drawRoundRect(r, 8 * d, 8 * d, fill);
            drawTri(c, cx - 4 * d, by + 3 * d, cx + 4 * d, by + 3 * d, cx, by + 9 * d);
            text.setColor(INK);
            c.drawText(bubble, cx, by, text);
        }

        // Smooth while something moves; a gentle 20fps while just sleeping (saves battery)
        boolean busy = playing || jumpStart > 0 || spinStart > 0 || !hearts.isEmpty() || now < bubbleUntil || blinkStart > 0;
        if (busy) postInvalidateOnAnimation(); else postInvalidateDelayed(50);
    }

    private void drawTri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        c.drawPath(path, fill);
    }

    // ---------------------------------------------------------------- touch

    /** Only the fox itself is touchable; touches around it go through to the page. */
    private boolean onFox(float x, float y) {
        float cx = getWidth() / 2f, base = getHeight() - 6 * d;
        return x > cx - 26 * d && x < cx + 40 * d && y > base - 66 * d && y < base + 4 * d;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!onFox(e.getX(), e.getY())) return false;
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = getX();
                startY = getY();
                dragging = false;
                longPressed = false;
                postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    removeCallbacks(longPressCheck);
                }
                if (dragging) {
                    View parent = (View) getParent();
                    setX(Math.max(0, Math.min(parent.getWidth() - getWidth(), startX + dx)));
                    setY(Math.max(0, Math.min(parent.getHeight() - getHeight(), startY + dy)));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressCheck);
                if (dragging) {
                    View parent = (View) getParent();
                    float maxX = Math.max(1, parent.getWidth() - getWidth());
                    float maxY = Math.max(1, parent.getHeight() - getHeight());
                    prefs.edit().putFloat("foxX", getX() / maxX).putFloat("foxY", getY() / maxY).apply();
                    say("wheee!");
                } else if (!longPressed) {
                    react();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressCheck);
                return true;
        }
        return false;
    }

    private void react() {
        long now = SystemClock.uptimeMillis();
        performClick();
        switch (rnd.nextInt(4)) {
            case 0:
                jumpStart = now;
                say(YIPS[rnd.nextInt(YIPS.length)]);
                break;
            case 1:
                for (int i = 0; i < 5; i++) {
                    hearts.add(new float[]{getWidth() / 2f + (rnd.nextFloat() - .5f) * 20 * d,
                            getHeight() - 50 * d, (rnd.nextFloat() - .5f) * 40 * d, -(30 + rnd.nextFloat() * 30) * d, now});
                }
                jumpStart = now;
                break;
            case 2:
                spinStart = now;
                say("wooo");
                break;
            default:
                boolean playing = PlayerBridge.last != null && PlayerBridge.last.playing;
                say(playing ? "this one slaps" : "play something?");
                jumpStart = now;
        }
        blinkStart = now;
        invalidate();
    }

    private void say(String s) {
        bubble = s;
        bubbleUntil = SystemClock.uptimeMillis() + 1600;
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Size: room for the fox plus its speech bubble / hearts above it. */
    public static ViewGroup.LayoutParams defaultSize(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return new android.widget.FrameLayout.LayoutParams((int) (120 * d), (int) (120 * d));
    }
}
