package com.gathin.tvhome;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Fits the video surface inside its cell without distorting the camera's aspect ratio. */
final class AspectFrame extends FrameLayout {
    private float aspect;

    AspectFrame(Context context) {
        super(context);
        setClipChildren(true);
    }

    void setAspect(float value) {
        if (value <= 0 || Math.abs(value - aspect) < 0.001f) return;
        aspect = value;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        View child = getChildAt(0);
        if (child == null) return;
        int childWidth = width;
        int childHeight = height;
        if (aspect > 0 && width > 0 && height > 0) {
            if (width / (float) height > aspect) childWidth = Math.round(height * aspect);
            else childHeight = Math.round(width / aspect);
        }
        child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View child = getChildAt(0);
        if (child == null) return;
        int width = child.getMeasuredWidth();
        int height = child.getMeasuredHeight();
        int x = ((right - left) - width) / 2;
        int y = ((bottom - top) - height) / 2;
        child.layout(x, y, x + width, y + height);
    }
}
