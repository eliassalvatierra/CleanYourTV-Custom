package com.gathin.tvhome;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds one video surface and keeps the picture's proportions: the child is fitted inside this frame (black bars
 * where the shapes differ) once the stream reports its size. Until then, or for aspect 0, it fills the frame.
 * A bare TextureView would stretch a 16:9 camera over whatever cell it is given.
 */
final class AspectFrame extends FrameLayout {
    /** Width over height of the picture; 0 means unknown. */
    private float aspect;

    AspectFrame(Context context) {
        super(context);
    }

    void setAspect(float aspect) {
        if (Math.abs(aspect - this.aspect) < 0.001f) return;
        this.aspect = aspect;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        View child = getChildAt(0);
        if (child == null) return;
        int cw = w;
        int ch = h;
        if (aspect > 0 && w > 0 && h > 0) {
            if (w / (float) h > aspect) cw = Math.round(h * aspect);
            else ch = Math.round(w / aspect);
        }
        child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View child = getChildAt(0);
        if (child == null) return;
        int cw = child.getMeasuredWidth();
        int ch = child.getMeasuredHeight();
        int x = ((right - left) - cw) / 2;
        int y = ((bottom - top) - ch) / 2;
        child.layout(x, y, x + cw, y + ch);
    }
}
