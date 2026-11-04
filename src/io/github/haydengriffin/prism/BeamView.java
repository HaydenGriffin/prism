package io.github.haydengriffin.prism;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Who has the remote: a beam from the box (amber) to the LG (sky) through the logo's prism, with a dot at the end
 * that has it. The live line beside it always says the same in words.
 */
final class BeamView extends View {
    enum End { NONE, BOX, LG }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path prism = new Path();
    private final int box, lg, ground;
    private final float density;
    private End end = End.NONE;
    private float along = 0;
    private ValueAnimator moving;

    BeamView(Context context, int box, int lg, int ink, int ground) {
        super(context);
        this.box = box;
        this.lg = lg;
        this.ground = ground;
        density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2 * density);
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setColor(ink);
    }

    void show(End next) {
        if (next == end) return;
        boolean fromNowhere = end == End.NONE;
        end = next;
        if (moving != null) moving.cancel();
        if (next == End.NONE) {
            invalidate();
            return;
        }
        float target = next == End.LG ? 1 : 0;
        if (fromNowhere || !isLaidOut()) {
            along = target;
            invalidate();
            return;
        }
        moving = ValueAnimator.ofFloat(along, target).setDuration(320);
        moving.setInterpolator(new DecelerateInterpolator(2f));
        moving.addUpdateListener(a -> {
            along = (float) a.getAnimatedValue();
            invalidate();
        });
        moving.start();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float dot = dot();
        line.setShader(new LinearGradient(dot, 0, w - dot, 0, box, lg, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), cy = getHeight() / 2f, dot = dot();
        line.setAlpha(end == End.NONE ? 90 : 255);
        canvas.drawLine(dot, cy, w - dot, cy, line);
        float size = 7 * density, cx = w / 2;
        prism.reset();
        prism.moveTo(cx, cy - size);
        prism.lineTo(cx + size, cy + size * 0.8f);
        prism.lineTo(cx - size, cy + size * 0.8f);
        prism.close();
        fill.setColor(ground);
        canvas.drawPath(prism, fill);
        canvas.drawPath(prism, edge);
        if (end == End.NONE) return;
        fill.setColor(end == End.LG ? lg : box);
        canvas.drawCircle(dot + (w - 2 * dot) * along, cy, dot, fill);
    }

    private float dot() { return 6 * density; }
}
