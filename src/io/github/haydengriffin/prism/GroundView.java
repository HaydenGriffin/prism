package io.github.haydengriffin.prism;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Behind every page: the theme's ground and, on themes that wash, a soft light that follows the focused tile. */
final class GroundView extends View {
    private final Theme theme;
    private final Paint ground = new Paint(), light = new Paint();
    private final ArgbEvaluator mix = new ArgbEvaluator();
    private final int[] spot = new int[2], self = new int[2];
    private int colour = 0;
    private float x = 0.25f, y = 0.35f;
    private ValueAnimator moving;

    GroundView(Context context, Theme theme) {
        super(context);
        this.theme = theme;
        ground.setDither(true);
        light.setDither(true);
    }

    /** Moves the light to a focused view, in the colour of that view's app. */
    void follow(View focused, int rgb) {
        if (!theme.washes) return;
        if (!isLaidOut() || !focused.isLaidOut()) { // Focus lands before a new page's first layout: measure after it.
            focused.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    v.removeOnLayoutChangeListener(this);
                    v.post(() -> { if (v.hasFocus()) follow(v, rgb); });
                }
            });
            return;
        }
        getLocationInWindow(self);
        focused.getLocationInWindow(spot);
        float toX = (spot[0] - self[0] + focused.getWidth() / 2f) / getWidth();
        float toY = (spot[1] - self[1] + focused.getHeight() / 2f) / getHeight();
        int toColour = Theme.wash(rgb);
        if (colour == 0) {
            colour = toColour & 0xFFFFFF;
            x = toX;
            y = toY;
        }
        float fromX = x, fromY = y;
        int fromColour = colour;
        if (moving != null) moving.cancel();
        moving = ValueAnimator.ofFloat(0, 1).setDuration(420);
        moving.setInterpolator(new DecelerateInterpolator(2f));
        moving.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            x = fromX + (toX - fromX) * t;
            y = fromY + (toY - fromY) * t;
            colour = (int) mix.evaluate(t, fromColour, toColour);
            invalidate();
        });
        moving.start();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        ground.setShader(new LinearGradient(0, 0, 0, h, theme.groundTop, theme.groundBottom, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), ground);
        if (!theme.washes || (colour >>> 24) == 0) return;
        light.setShader(new RadialGradient(x * getWidth(), y * getHeight(), getWidth() * 0.62f, colour, colour & 0xFFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), light);
    }
}
