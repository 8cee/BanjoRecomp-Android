package io.github.banjorecomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;

public final class VirtualPadView extends View {
    public static final int BTN_A = 0;
    public static final int BTN_B = 1;
    public static final int BTN_Z = 2;
    public static final int BTN_L = 3;
    public static final int BTN_R = 4;
    public static final int BTN_START = 5;
    public static final int BTN_C_UP = 6;
    public static final int BTN_C_DOWN = 7;
    public static final int BTN_C_LEFT = 8;
    public static final int BTN_C_RIGHT = 9;
    public static final int BTN_DPAD_UP = 10;
    public static final int BTN_DPAD_DOWN = 11;
    public static final int BTN_DPAD_LEFT = 12;
    public static final int BTN_DPAD_RIGHT = 13;
    public static final int BTN_MENU = 14;

    private static final String PREFS = "virtual_pad";
    private static final String PREF_VISIBLE = "hud_visible";
    private static final int GLASS = Color.rgb(10, 13, 18);
    private static final int ACCENT = Color.rgb(155, 211, 43);
    private static final int TINT_A = Color.rgb(127, 178, 229);
    private static final int TINT_B = Color.rgb(99, 196, 107);
    private static final int TINT_C = Color.rgb(246, 220, 122);
    private static final int TINT_START = Color.rgb(242, 112, 127);
    private static final int TINT_NEUTRAL = Color.rgb(232, 236, 239);
    private static volatile VirtualPadView instance;

    private static final class RoundButton {
        final int id;
        final String label;
        final int tint;
        float x, y, r;
        int pointer = -1;
        float press;
        Shader glass;
        Shader shadow;
        Shader glow;

        RoundButton(int id, String label, int tint) {
            this.id = id;
            this.label = label;
            this.tint = tint;
        }

        boolean hit(float px, float py) {
            float dx = px - x, dy = py - y;
            float rr = Math.max(r * 1.45f, r + 18f);
            return dx * dx + dy * dy <= rr * rr;
        }
    }

    private static final class PillButton {
        final int id;
        final String label;
        final RectF rect = new RectF();
        final RectF hit = new RectF();
        int pointer = -1;
        float press;
        Shader glass;

        PillButton(int id, String label) {
            this.id = id;
            this.label = label;
        }

        boolean contains(float x, float y) {
            return hit.contains(x, y);
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RoundButton> roundButtons = new ArrayList<>();
    private final SparseIntArray pointerToRound = new SparseIntArray();
    private final SparseIntArray pointerToPill = new SparseIntArray();
    private final PillButton z = new PillButton(BTN_Z, "Z");
    private final PillButton l = new PillButton(BTN_L, "L");
    private final PillButton r = new PillButton(BTN_R, "R");
    private final PillButton[] pills = { z, l, r };
    private final SharedPreferences prefs;

    private int safeLeft, safeTop, safeRight, safeBottom;
    private float unit = 1f;
    private boolean gameStarted;
    private boolean padVisible;
    private float hudAlpha;
    private long lastFrame;

    private int stickPointer = -1;
    private float stickX, stickY;
    private float stickCenterX, stickCenterY, stickOuterR, stickKnobR;
    private float knobX, knobY;
    private Shader stickGlass, stickShadow, stickGlow, stickKnob;

    private float toggleX, toggleY, toggleR;
    private int togglePointer = -1;
    private float togglePress;

    public VirtualPadView(Context context) {
        this(context, null);
    }

    public VirtualPadView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VirtualPadView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);
        setClickable(true);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);

        roundButtons.add(new RoundButton(BTN_A, "A", TINT_A));
        roundButtons.add(new RoundButton(BTN_B, "B", TINT_B));
        roundButtons.add(new RoundButton(BTN_START, "START", TINT_START));
        roundButtons.add(new RoundButton(BTN_C_UP, "", TINT_C));
        roundButtons.add(new RoundButton(BTN_C_DOWN, "", TINT_C));
        roundButtons.add(new RoundButton(BTN_C_LEFT, "", TINT_C));
        roundButtons.add(new RoundButton(BTN_C_RIGHT, "", TINT_C));
        roundButtons.add(new RoundButton(BTN_MENU, "", TINT_NEUTRAL));

        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        instance = this;
        try {
            nativeInit();
            gameStarted = nativeIsGameStarted();
        } catch (UnsatisfiedLinkError ignored) {
            gameStarted = false;
        }
        padVisible = gameStarted && prefs.getBoolean(PREF_VISIBLE, true);
        hudAlpha = padVisible ? 1f : 0f;
    }

    @SuppressWarnings("unused")
    public static void onGameStarted(boolean started) {
        VirtualPadView view = instance;
        if (view == null) return;
        view.post(() -> {
            view.gameStarted = started;
            view.padVisible = started && view.prefs.getBoolean(PREF_VISIBLE, true);
            if (!started) view.releaseAll();
            view.lastFrame = SystemClock.uptimeMillis();
            view.postInvalidateOnAnimation();
        });
    }

    private native boolean nativeInit();
    private native void nativeButton(int id, boolean pressed);
    private native void nativeAxis(float x, float y);
    private native boolean nativeIsGameStarted();

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            safeLeft = insets.getDisplayCutout().getSafeInsetLeft();
            safeTop = insets.getDisplayCutout().getSafeInsetTop();
            safeRight = insets.getDisplayCutout().getSafeInsetRight();
            safeBottom = insets.getDisplayCutout().getSafeInsetBottom();
            applyLayout(getWidth(), getHeight());
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        if (instance == this) instance = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        applyLayout(w, h);
    }

    private void applyLayout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float base = Math.min(h / 720f, w / 1280f);
        float margin = 8f * base;
        float left = safeLeft + margin;
        float top = safeTop + margin;
        float right = w - safeRight - margin;
        float bottom = h - safeBottom - margin;
        float aw = Math.max(1f, right - left);
        float ah = Math.max(1f, bottom - top);
        unit = Math.min(ah / 720f, aw / 1280f);
        if (unit <= 0f) unit = base > 0 ? base : 1f;

        float trigW = 170f * unit;
        float trigH = 66f * unit;
        float trigY = top + ah * 0.035f;
        layoutPill(z, left + aw * 0.035f, trigY, trigW, trigH);
        layoutPill(l, left + aw * 0.50f - trigW / 2f, trigY, trigW, trigH);
        layoutPill(r, right - aw * 0.035f - trigW, trigY, trigW, trigH);

        stickCenterX = left + aw * 0.12f;
        stickCenterY = top + ah * 0.66f;
        stickOuterR = 88f * unit;
        stickKnobR = 41f * unit;

        place(BTN_START, left + aw * 0.475f, top + ah * 0.87f, 31f * unit);
        place(BTN_MENU, left + aw * 0.655f, top + ah * 0.88f, 29f * unit);

        float cX = left + aw * 0.76f;
        float cY = top + ah * 0.42f;
        float spreadX = 76f * unit;
        float spreadY = 62f * unit;
        float cR = 29f * unit;
        place(BTN_C_UP, cX, cY - spreadY, cR);
        place(BTN_C_DOWN, cX, cY + spreadY, cR);
        place(BTN_C_LEFT, cX - spreadX, cY, cR);
        place(BTN_C_RIGHT, cX + spreadX, cY, cR);

        place(BTN_A, left + aw * 0.88f, top + ah * 0.59f, 47f * unit);
        place(BTN_B, left + aw * 0.79f, top + ah * 0.72f, 34f * unit);

        toggleX = left + aw * 0.94f;
        toggleY = top + ah * 0.89f;
        toggleR = 22f * unit;
        rebuildShaders();
    }

    private void layoutPill(PillButton b, float x, float y, float w, float h) {
        b.rect.set(x, y, x + w, y + h);
        b.hit.set(b.rect);
        b.hit.inset(-h * 0.20f, -h * 0.25f);
        b.glass = new LinearGradient(
                b.rect.centerX(), b.rect.top, b.rect.centerX(), b.rect.bottom,
                new int[]{Color.argb(56, 255, 255, 255), Color.argb(12, 255, 255, 255)},
                null, Shader.TileMode.CLAMP);
    }

    private void place(int id, float x, float y, float radius) {
        RoundButton b = button(id);
        if (b == null) return;
        b.x = x;
        b.y = y;
        b.r = Math.max(radius, 22f * unit);
    }

    private void rebuildShaders() {
        for (RoundButton b : roundButtons) {
            b.glass = new LinearGradient(
                    b.x, b.y - b.r, b.x, b.y + b.r,
                    new int[]{Color.argb(62, 255, 255, 255), Color.argb(12, 255, 255, 255)},
                    null, Shader.TileMode.CLAMP);
            b.shadow = new RadialGradient(
                    b.x, b.y, b.r * 1.55f,
                    new int[]{Color.argb(100, 0, 0, 0), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP);
            b.glow = new RadialGradient(
                    b.x, b.y, b.r * 1.38f,
                    new int[]{Color.TRANSPARENT, withAlpha(b.tint, 130), Color.TRANSPARENT},
                    new float[]{0.48f, 0.86f, 1f}, Shader.TileMode.CLAMP);
        }

        stickGlass = new LinearGradient(
                stickCenterX, stickCenterY - stickOuterR,
                stickCenterX, stickCenterY + stickOuterR,
                new int[]{Color.argb(58, 255, 255, 255), Color.argb(10, 255, 255, 255)},
                null, Shader.TileMode.CLAMP);
        stickShadow = new RadialGradient(
                stickCenterX, stickCenterY, stickOuterR * 1.5f,
                new int[]{Color.argb(110, 0, 0, 0), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP);
        stickGlow = new RadialGradient(
                stickCenterX, stickCenterY, stickOuterR * 1.3f,
                new int[]{Color.TRANSPARENT, withAlpha(ACCENT, 120), Color.TRANSPARENT},
                new float[]{0.50f, 0.87f, 1f}, Shader.TileMode.CLAMP);
        stickKnob = new RadialGradient(
                stickCenterX - stickKnobR * 0.3f,
                stickCenterY - stickKnobR * 0.35f,
                stickKnobR * 1.4f,
                new int[]{Color.rgb(88, 96, 109), Color.rgb(42, 48, 58)},
                null, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!gameStarted) return;

        long now = SystemClock.uptimeMillis();
        if (lastFrame == 0L) lastFrame = now;
        float dt = Math.min(50f, now - lastFrame);
        lastFrame = now;
        float target = padVisible ? 1f : 0f;
        float speed = dt / 180f;
        hudAlpha += (target - hudAlpha) * Math.min(1f, speed);
        if (Math.abs(hudAlpha - target) < 0.01f) hudAlpha = target;

        if (hudAlpha > 0.01f) {
            drawStick(canvas);
            for (PillButton b : pills) drawPill(canvas, b);
            for (RoundButton b : roundButtons) drawRound(canvas, b);
        }
        drawToggle(canvas);

        if (Math.abs(hudAlpha - target) > 0.01f || hasAnimatedPress()) {
            postInvalidateOnAnimation();
        }
    }

    private boolean hasAnimatedPress() {
        for (RoundButton b : roundButtons) {
            float target = b.pointer >= 0 ? 1f : 0f;
            if (Math.abs(b.press - target) > 0.01f) return true;
        }
        for (PillButton b : pills) {
            float target = b.pointer >= 0 ? 1f : 0f;
            if (Math.abs(b.press - target) > 0.01f) return true;
        }
        return false;
    }

    private void drawStick(Canvas canvas) {
        float a = hudAlpha;
        fill.setShader(stickShadow);
        fill.setAlpha((int)(170 * a));
        canvas.drawCircle(stickCenterX, stickCenterY, stickOuterR * 1.24f, fill);

        fill.setShader(null);
        fill.setColor(withAlpha(GLASS, (int)(190 * a)));
        canvas.drawCircle(stickCenterX, stickCenterY, stickOuterR, fill);
        fill.setShader(stickGlass);
        fill.setAlpha((int)(255 * a));
        canvas.drawCircle(stickCenterX, stickCenterY, stickOuterR, fill);

        if (stickPointer >= 0) {
            fill.setShader(stickGlow);
            fill.setAlpha((int)(210 * a));
            canvas.drawCircle(stickCenterX, stickCenterY, stickOuterR * 1.16f, fill);
        }

        stroke.setShader(null);
        stroke.setColor(withAlpha(TINT_NEUTRAL, (int)(110 * a)));
        stroke.setStrokeWidth(Math.max(1.5f, 2f * unit));
        canvas.drawCircle(stickCenterX, stickCenterY, stickOuterR, stroke);

        float travel = (stickOuterR - stickKnobR) * 0.88f;
        float kx = stickCenterX + knobX * travel;
        float ky = stickCenterY + knobY * travel;
        fill.setShader(stickKnob);
        fill.setAlpha((int)(255 * a));
        canvas.drawCircle(kx, ky, stickKnobR, fill);
        fill.setShader(null);
    }

    private void drawPill(Canvas canvas, PillButton b) {
        float target = b.pointer >= 0 ? 1f : 0f;
        b.press += (target - b.press) * 0.32f;
        float scale = 1f - 0.045f * b.press;
        float cx = b.rect.centerX();
        float cy = b.rect.centerY();

        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        fill.setShader(null);
        fill.setColor(withAlpha(GLASS, (int)(190 * hudAlpha)));
        canvas.drawRoundRect(b.rect, b.rect.height() / 2f, b.rect.height() / 2f, fill);
        fill.setShader(b.glass);
        fill.setAlpha((int)(255 * hudAlpha));
        canvas.drawRoundRect(b.rect, b.rect.height() / 2f, b.rect.height() / 2f, fill);

        stroke.setShader(null);
        stroke.setColor(withAlpha(b.pointer >= 0 ? ACCENT : TINT_NEUTRAL,
                (int)((b.pointer >= 0 ? 230 : 120) * hudAlpha)));
        stroke.setStrokeWidth(Math.max(1.5f, (b.pointer >= 0 ? 3f : 2f) * unit));
        canvas.drawRoundRect(b.rect, b.rect.height() / 2f, b.rect.height() / 2f, stroke);

        text.setShader(null);
        text.setColor(withAlpha(Color.WHITE, (int)(225 * hudAlpha)));
        text.setTextSize(Math.max(14f * unit, b.rect.height() * 0.34f));
        drawCentered(canvas, b.label, cx, cy, text);
        canvas.restore();
    }

    private void drawRound(Canvas canvas, RoundButton b) {
        float target = b.pointer >= 0 ? 1f : 0f;
        b.press += (target - b.press) * 0.34f;
        float scale = 1f - 0.06f * b.press;

        fill.setShader(b.shadow);
        fill.setAlpha((int)(150 * hudAlpha));
        canvas.drawCircle(b.x, b.y, b.r * 1.28f, fill);

        if (b.pointer >= 0) {
            fill.setShader(b.glow);
            fill.setAlpha((int)(220 * hudAlpha));
            canvas.drawCircle(b.x, b.y, b.r * 1.22f, fill);
        }

        canvas.save();
        canvas.scale(scale, scale, b.x, b.y);
        fill.setShader(null);
        fill.setColor(withAlpha(GLASS, (int)(195 * hudAlpha)));
        canvas.drawCircle(b.x, b.y, b.r, fill);
        fill.setShader(b.glass);
        fill.setAlpha((int)(255 * hudAlpha));
        canvas.drawCircle(b.x, b.y, b.r, fill);

        stroke.setShader(null);
        stroke.setColor(withAlpha(b.tint, (int)((b.pointer >= 0 ? 245 : 150) * hudAlpha)));
        stroke.setStrokeWidth(Math.max(1.5f, (b.pointer >= 0 ? 3f : 2f) * unit));
        canvas.drawCircle(b.x, b.y, b.r, stroke);

        if (b.id >= BTN_C_UP && b.id <= BTN_C_RIGHT) {
            drawCArrow(canvas, b);
        } else if (b.id == BTN_MENU) {
            drawMenuIcon(canvas, b.x, b.y, b.r * 0.42f, hudAlpha);
        } else {
            text.setShader(null);
            text.setColor(withAlpha(Color.WHITE, (int)(230 * hudAlpha)));
            float size = b.id == BTN_START ? b.r * 0.46f : b.r * 0.72f;
            text.setTextSize(Math.max(11f * unit, size));
            drawCentered(canvas, b.label, b.x, b.y, text);
        }
        canvas.restore();
    }

    private void drawCArrow(Canvas canvas, RoundButton b) {
        float s = b.r * 0.38f;
        float dx = 0f, dy = 0f;
        if (b.id == BTN_C_UP) dy = -s * 0.25f;
        if (b.id == BTN_C_DOWN) dy = s * 0.25f;
        if (b.id == BTN_C_LEFT) dx = -s * 0.25f;
        if (b.id == BTN_C_RIGHT) dx = s * 0.25f;

        icon.setColor(withAlpha(TINT_C, (int)(235 * hudAlpha)));
        icon.setStrokeWidth(Math.max(2f, 3f * unit));
        Path path = new Path();
        if (b.id == BTN_C_UP) {
            path.moveTo(b.x - s, b.y + s * 0.35f); path.lineTo(b.x, b.y - s + dy); path.lineTo(b.x + s, b.y + s * 0.35f);
        } else if (b.id == BTN_C_DOWN) {
            path.moveTo(b.x - s, b.y - s * 0.35f); path.lineTo(b.x, b.y + s + dy); path.lineTo(b.x + s, b.y - s * 0.35f);
        } else if (b.id == BTN_C_LEFT) {
            path.moveTo(b.x + s * 0.35f, b.y - s); path.lineTo(b.x - s + dx, b.y); path.lineTo(b.x + s * 0.35f, b.y + s);
        } else {
            path.moveTo(b.x - s * 0.35f, b.y - s); path.lineTo(b.x + s + dx, b.y); path.lineTo(b.x - s * 0.35f, b.y + s);
        }
        canvas.drawPath(path, icon);
    }

    private void drawMenuIcon(Canvas canvas, float cx, float cy, float size, float alpha) {
        icon.setColor(withAlpha(Color.WHITE, (int)(215 * alpha)));
        icon.setStrokeWidth(Math.max(2f, 3f * unit));
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * size * 0.55f;
            canvas.drawLine(cx - size, y, cx + size, y, icon);
        }
    }

    private void drawToggle(Canvas canvas) {
        float alpha = gameStarted ? 0.85f : 0f;
        if (alpha <= 0f) return;
        float scale = togglePointer >= 0 ? 0.92f : 1f;
        canvas.save();
        canvas.scale(scale, scale, toggleX, toggleY);
        fill.setShader(null);
        fill.setColor(Color.argb((int)(170 * alpha), 10, 13, 18));
        canvas.drawCircle(toggleX, toggleY, toggleR, fill);
        stroke.setColor(withAlpha(ACCENT, (int)(180 * alpha)));
        stroke.setStrokeWidth(Math.max(1.5f, 2f * unit));
        canvas.drawCircle(toggleX, toggleY, toggleR, stroke);

        icon.setColor(withAlpha(Color.WHITE, (int)(220 * alpha)));
        icon.setStrokeWidth(Math.max(1.5f, 2.4f * unit));
        float eyeW = toggleR * 1.05f;
        float eyeH = toggleR * 0.55f;
        RectF oval = new RectF(toggleX - eyeW / 2f, toggleY - eyeH / 2f, toggleX + eyeW / 2f, toggleY + eyeH / 2f);
        canvas.drawOval(oval, icon);
        if (padVisible) {
            fill.setShader(null);
            fill.setColor(withAlpha(ACCENT, (int)(220 * alpha)));
            canvas.drawCircle(toggleX, toggleY, toggleR * 0.18f, fill);
        } else {
            canvas.drawLine(toggleX - eyeW * 0.48f, toggleY + eyeH * 0.65f,
                    toggleX + eyeW * 0.48f, toggleY - eyeH * 0.65f, icon);
        }
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!gameStarted) return false;
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int pointerId = event.getPointerId(index);
            float x = event.getX(index), y = event.getY(index);

            if (hitToggle(x, y)) {
                togglePointer = pointerId;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            if (!padVisible) return false;

            if (hitStick(x, y) && stickPointer < 0) {
                stickPointer = pointerId;
                updateStick(x, y);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                return true;
            }

            for (PillButton b : pills) {
                if (b.pointer < 0 && b.contains(x, y)) {
                    b.pointer = pointerId;
                    pointerToPill.put(pointerId, b.id);
                    nativeButton(b.id, true);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    invalidate();
                    return true;
                }
            }

            RoundButton hit = findRound(x, y);
            if (hit != null && hit.pointer < 0) {
                hit.pointer = pointerId;
                pointerToRound.put(pointerId, hit.id);
                nativeButton(hit.id, true);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            boolean handled = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                int pointerId = event.getPointerId(i);
                if (pointerId == stickPointer) {
                    updateStick(event.getX(i), event.getY(i));
                    handled = true;
                }
            }
            return handled || pointerToRound.size() > 0 || pointerToPill.size() > 0 || togglePointer >= 0;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int pointerId = event.getPointerId(index);
            float x = event.getX(index), y = event.getY(index);
            if (pointerId == togglePointer) {
                togglePointer = -1;
                if (hitToggle(x, y)) setPadVisible(!padVisible);
                invalidate();
                return true;
            }
            releasePointer(pointerId);
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void setPadVisible(boolean visible) {
        padVisible = visible;
        prefs.edit().putBoolean(PREF_VISIBLE, visible).apply();
        if (!visible) releaseControlsOnly();
        lastFrame = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    private boolean hitToggle(float x, float y) {
        float rr = Math.max(toggleR * 1.65f, 36f * unit);
        float dx = x - toggleX, dy = y - toggleY;
        return dx * dx + dy * dy <= rr * rr;
    }

    private boolean hitStick(float x, float y) {
        float rr = stickOuterR * 1.35f;
        float dx = x - stickCenterX, dy = y - stickCenterY;
        return dx * dx + dy * dy <= rr * rr;
    }

    private void updateStick(float x, float y) {
        float dx = (x - stickCenterX) / stickOuterR;
        float dy = (y - stickCenterY) / stickOuterR;
        float mag = (float)Math.sqrt(dx * dx + dy * dy);
        if (mag > 1f) {
            dx /= mag;
            dy /= mag;
            mag = 1f;
        }
        final float dead = 0.08f;
        if (mag <= dead) {
            dx = 0f;
            dy = 0f;
        } else if (mag > 0f) {
            float scaled = (mag - dead) / (1f - dead);
            dx = dx / mag * scaled;
            dy = dy / mag * scaled;
        }
        stickX = dx;
        stickY = -dy;
        knobX = dx;
        knobY = dy;
        nativeAxis(stickX, stickY);
        postInvalidateOnAnimation();
    }

    private void releasePointer(int pointerId) {
        if (pointerId == stickPointer) {
            stickPointer = -1;
            stickX = stickY = knobX = knobY = 0f;
            nativeAxis(0f, 0f);
        }

        int roundId = pointerToRound.get(pointerId, -1);
        if (roundId >= 0) {
            pointerToRound.delete(pointerId);
            RoundButton b = button(roundId);
            if (b != null && b.pointer == pointerId) {
                b.pointer = -1;
                nativeButton(b.id, false);
            }
        }

        int pillId = pointerToPill.get(pointerId, -1);
        if (pillId >= 0) {
            pointerToPill.delete(pointerId);
            PillButton b = pill(pillId);
            if (b != null && b.pointer == pointerId) {
                b.pointer = -1;
                nativeButton(b.id, false);
            }
        }
        postInvalidateOnAnimation();
    }

    private void releaseControlsOnly() {
        if (stickPointer >= 0 || stickX != 0f || stickY != 0f) {
            stickPointer = -1;
            stickX = stickY = knobX = knobY = 0f;
            try { nativeAxis(0f, 0f); } catch (UnsatisfiedLinkError ignored) {}
        }
        for (RoundButton b : roundButtons) {
            if (b.pointer >= 0) {
                b.pointer = -1;
                try { nativeButton(b.id, false); } catch (UnsatisfiedLinkError ignored) {}
            }
        }
        for (PillButton b : pills) {
            if (b.pointer >= 0) {
                b.pointer = -1;
                try { nativeButton(b.id, false); } catch (UnsatisfiedLinkError ignored) {}
            }
        }
        pointerToRound.clear();
        pointerToPill.clear();
    }

    private void releaseAll() {
        releaseControlsOnly();
        togglePointer = -1;
        invalidate();
    }

    private RoundButton findRound(float x, float y) {
        RoundButton best = null;
        float bestD = Float.MAX_VALUE;
        for (RoundButton b : roundButtons) {
            if (!b.hit(x, y)) continue;
            float dx = x - b.x, dy = y - b.y;
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    private RoundButton button(int id) {
        for (RoundButton b : roundButtons) if (b.id == id) return b;
        return null;
    }

    private PillButton pill(int id) {
        for (PillButton b : pills) if (b.id == id) return b;
        return null;
    }

    private static void drawCentered(Canvas canvas, String value, float x, float y, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(value, x, y - (fm.ascent + fm.descent) * 0.5f, paint);
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
