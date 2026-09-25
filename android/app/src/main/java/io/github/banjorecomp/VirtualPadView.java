package io.github.banjorecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

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

    private static volatile VirtualPadView instance;

    private static final class PadButton {
        final int id;
        final String label;
        float x;
        float y;
        float radius;
        boolean pressed;

        PadButton(int id, String label) {
            this.id = id;
            this.label = label;
        }

        boolean hit(float px, float py) {
            float dx = px - x;
            float dy = py - y;
            float rr = radius * 1.55f;
            return dx * dx + dy * dy <= rr * rr;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PadButton> buttons = new ArrayList<>();
    private final SparseIntArray pointerButtons = new SparseIntArray();

    private int stickPointer = -1;
    private float stickX;
    private float stickY;
    private float stickCenterX;
    private float stickCenterY;
    private float stickRadius;
    private float knobX;
    private float knobY;
    private boolean gameStarted;

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

        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        stickPaint.setStyle(Paint.Style.FILL);

        buttons.add(new PadButton(BTN_A, "A"));
        buttons.add(new PadButton(BTN_B, "B"));
        buttons.add(new PadButton(BTN_Z, "Z"));
        buttons.add(new PadButton(BTN_L, "L"));
        buttons.add(new PadButton(BTN_R, "R"));
        buttons.add(new PadButton(BTN_START, "START"));
        buttons.add(new PadButton(BTN_C_UP, "C↑"));
        buttons.add(new PadButton(BTN_C_DOWN, "C↓"));
        buttons.add(new PadButton(BTN_C_LEFT, "C←"));
        buttons.add(new PadButton(BTN_C_RIGHT, "C→"));
        buttons.add(new PadButton(BTN_DPAD_UP, "↑"));
        buttons.add(new PadButton(BTN_DPAD_DOWN, "↓"));
        buttons.add(new PadButton(BTN_DPAD_LEFT, "←"));
        buttons.add(new PadButton(BTN_DPAD_RIGHT, "→"));
        buttons.add(new PadButton(BTN_MENU, "MENU"));

        instance = this;
        try {
            nativeInit();
            gameStarted = nativeIsGameStarted();
        } catch (UnsatisfiedLinkError ignored) {
            gameStarted = false;
        }
        setVisibility(gameStarted ? VISIBLE : GONE);
    }

    @SuppressWarnings("unused")
    public static void onGameStarted(boolean started) {
        VirtualPadView view = instance;
        if (view == null) return;
        view.post(() -> {
            view.gameStarted = started;
            view.setVisibility(started ? VISIBLE : GONE);
            if (!started) view.releaseAll();
            view.invalidate();
        });
    }

    private native boolean nativeInit();
    private native void nativeButton(int id, boolean pressed);
    private native void nativeAxis(float x, float y);
    private native boolean nativeIsGameStarted();

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        if (instance == this) instance = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) releaseAll();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float unit = Math.min(w / 1280f, h / 720f);
        if (unit <= 0f) unit = 1f;

        stickCenterX = w * 0.14f;
        stickCenterY = h * 0.68f;
        stickRadius = 92f * unit;

        place(BTN_A, w * 0.89f, h * 0.64f, 53f * unit);
        place(BTN_B, w * 0.80f, h * 0.76f, 42f * unit);
        place(BTN_Z, w * 0.12f, h * 0.12f, 43f * unit);
        place(BTN_L, w * 0.42f, h * 0.10f, 39f * unit);
        place(BTN_R, w * 0.88f, h * 0.12f, 43f * unit);
        place(BTN_START, w * 0.49f, h * 0.88f, 36f * unit);

        float cx = w * 0.77f;
        float cy = h * 0.39f;
        place(BTN_C_UP, cx, cy - 61f * unit, 31f * unit);
        place(BTN_C_DOWN, cx, cy + 61f * unit, 31f * unit);
        place(BTN_C_LEFT, cx - 66f * unit, cy, 31f * unit);
        place(BTN_C_RIGHT, cx + 66f * unit, cy, 31f * unit);

        float dx = w * 0.34f;
        float dy = h * 0.68f;
        place(BTN_DPAD_UP, dx, dy - 54f * unit, 28f * unit);
        place(BTN_DPAD_DOWN, dx, dy + 54f * unit, 28f * unit);
        place(BTN_DPAD_LEFT, dx - 54f * unit, dy, 28f * unit);
        place(BTN_DPAD_RIGHT, dx + 54f * unit, dy, 28f * unit);

        place(BTN_MENU, w * 0.63f, h * 0.90f, 32f * unit);
    }

    private void place(int id, float x, float y, float radius) {
        PadButton button = buttonById(id);
        if (button == null) return;
        button.x = x;
        button.y = y;
        button.radius = Math.max(radius, dp(24));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!gameStarted) return;

        fill.setColor(Color.argb(145, 15, 18, 24));
        outline.setColor(Color.argb(190, 230, 235, 240));
        stickPaint.setColor(Color.argb(125, 15, 18, 24));
        canvas.drawCircle(stickCenterX, stickCenterY, stickRadius, stickPaint);
        outline.setStrokeWidth(dp(2));
        canvas.drawCircle(stickCenterX, stickCenterY, stickRadius, outline);

        float knobRadius = stickRadius * 0.42f;
        float knobCx = stickCenterX + knobX * stickRadius * 0.55f;
        float knobCy = stickCenterY + knobY * stickRadius * 0.55f;
        stickPaint.setColor(Color.argb(185, 75, 84, 98));
        canvas.drawCircle(knobCx, knobCy, knobRadius, stickPaint);

        for (PadButton button : buttons) {
            int tint = tintFor(button.id);
            fill.setColor(withAlpha(tint, button.pressed ? 215 : 135));
            outline.setColor(withAlpha(tint, button.pressed ? 255 : 210));
            outline.setStrokeWidth(button.pressed ? dp(3) : dp(2));
            canvas.drawCircle(button.x, button.y, button.radius * (button.pressed ? 0.94f : 1f), fill);
            canvas.drawCircle(button.x, button.y, button.radius * (button.pressed ? 0.94f : 1f), outline);

            label.setColor(Color.WHITE);
            label.setTextSize(Math.max(dp(12), button.radius * ("START".equals(button.label) || "MENU".equals(button.label) ? 0.43f : 0.66f)));
            Paint.FontMetrics fm = label.getFontMetrics();
            float base = button.y - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(button.label, button.x, base, label);
        }
    }

    private int tintFor(int id) {
        if (id == BTN_A) return Color.rgb(76, 146, 230);
        if (id == BTN_B) return Color.rgb(55, 190, 94);
        if (id >= BTN_C_UP && id <= BTN_C_RIGHT) return Color.rgb(235, 200, 45);
        if (id == BTN_START) return Color.rgb(220, 70, 78);
        return Color.rgb(190, 198, 210);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!gameStarted) return false;
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int pointerId = event.getPointerId(index);
            float x = event.getX(index);
            float y = event.getY(index);

            if (insideStick(x, y) && stickPointer == -1) {
                stickPointer = pointerId;
                updateStick(x, y);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                return true;
            }

            PadButton hit = findButton(x, y);
            if (hit != null && !hit.pressed) {
                hit.pressed = true;
                pointerButtons.put(pointerId, hit.id);
                nativeButton(hit.id, true);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            return action == MotionEvent.ACTION_POINTER_DOWN;
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
            return handled || pointerButtons.size() > 0;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int pointerId = event.getPointerId(index);
            releasePointer(pointerId);
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean insideStick(float x, float y) {
        float dx = x - stickCenterX;
        float dy = y - stickCenterY;
        float r = stickRadius * 1.4f;
        return dx * dx + dy * dy <= r * r;
    }

    private void updateStick(float x, float y) {
        float dx = (x - stickCenterX) / stickRadius;
        float dy = (y - stickCenterY) / stickRadius;
        float mag = (float) Math.sqrt(dx * dx + dy * dy);
        if (mag > 1f) {
            dx /= mag;
            dy /= mag;
        }
        final float deadzone = 0.08f;
        if (mag < deadzone) {
            dx = 0f;
            dy = 0f;
        }
        stickX = dx;
        stickY = -dy;
        knobX = dx;
        knobY = dy;
        nativeAxis(stickX, stickY);
        invalidate();
    }

    private void releasePointer(int pointerId) {
        if (pointerId == stickPointer) {
            stickPointer = -1;
            stickX = stickY = knobX = knobY = 0f;
            nativeAxis(0f, 0f);
            invalidate();
        }
        int id = pointerButtons.get(pointerId, -1);
        if (id >= 0) {
            pointerButtons.delete(pointerId);
            PadButton button = buttonById(id);
            if (button != null && button.pressed) {
                button.pressed = false;
                nativeButton(id, false);
                invalidate();
            }
        }
    }

    private void releaseAll() {
        if (stickPointer != -1 || stickX != 0f || stickY != 0f) {
            stickPointer = -1;
            stickX = stickY = knobX = knobY = 0f;
            try { nativeAxis(0f, 0f); } catch (UnsatisfiedLinkError ignored) {}
        }
        for (PadButton button : buttons) {
            if (button.pressed) {
                button.pressed = false;
                try { nativeButton(button.id, false); } catch (UnsatisfiedLinkError ignored) {}
            }
        }
        pointerButtons.clear();
        invalidate();
    }

    private PadButton findButton(float x, float y) {
        PadButton best = null;
        float bestDistance = Float.MAX_VALUE;
        for (PadButton button : buttons) {
            if (!button.hit(x, y)) continue;
            float dx = x - button.x;
            float dy = y - button.y;
            float d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = button;
            }
        }
        return best;
    }

    private PadButton buttonById(int id) {
        for (PadButton button : buttons) {
            if (button.id == id) return button;
        }
        return null;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
