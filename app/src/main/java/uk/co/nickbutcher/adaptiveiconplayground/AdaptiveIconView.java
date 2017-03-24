/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.nickbutcher.adaptiveiconplayground;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.support.annotation.FloatRange;
import android.support.annotation.Keep;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

public class AdaptiveIconView extends View {

    private final Paint foregroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean drawShadow;
    private final int shadowDY;
    private final int layerSize, iconSize, layerCenter, viewportOffset;
    private @Nullable AdaptiveIconDrawable icon;
    private Bitmap background, foreground;
    private Paint shadowPaint;
    private float left, top, cornerRadius;
    private float foregroundDx, foregroundDy, backgroundDx, backgroundDy;
    // scale & translate factors [0,1]
    private float scale, backgroundScale, foregroundScale = 0f;
    private float foregroundTranslateFactor, backgroundTranslateFactor, foregroundScaleFactor, backgroundScaleFactor;

    // temp
    private static Matrix matrix = new Matrix();

    public AdaptiveIconView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, R.attr.adaptiveIconViewStyle);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AdaptiveIconView, R.attr.adaptiveIconViewStyle, 0);
        int shadowColor = a.getColor(R.styleable.AdaptiveIconView_shadowColor, Color.TRANSPARENT);
        shadowDY = a.getDimensionPixelSize(R.styleable.AdaptiveIconView_shadowDy, 0);
        a.recycle();
        drawShadow = shadowColor != Color.TRANSPARENT && shadowDY > 0;
        if (drawShadow) {
            shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(shadowColor);
        }
        layerSize = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 108, context.getResources().getDisplayMetrics()));
        layerCenter = layerSize / 2;
        iconSize = (int) (layerSize / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction()));
        viewportOffset = (layerSize - iconSize) / 2;
    }

    public void setIcon(@Nullable AdaptiveIconDrawable icon) {
        this.icon = icon;
        init(getWidth(), getHeight());
    }

    public void setCornerRadius(@FloatRange(from = 0f) float cornerRadius) {
        this.cornerRadius = cornerRadius;
        postInvalidateOnAnimation();
    }

    public void setVelocityX(float velocityX) {
        float displacementX = velocityToDisplacement(velocityX);
        backgroundDx = backgroundTranslateFactor * displacementX;
        foregroundDx = foregroundTranslateFactor * displacementX;
        postInvalidateOnAnimation();
    }

    public void setVelocityY(float velocityY) {
        float displacementY = velocityToDisplacement(velocityY);
        backgroundDy = backgroundTranslateFactor * displacementY;
        foregroundDy = foregroundTranslateFactor * displacementY;
        postInvalidateOnAnimation();
    }

    public void setForegroundTranslateFactor(@FloatRange(from = 0f, to = 1f) float foregroundTranslateFactor) {
        this.foregroundTranslateFactor = foregroundTranslateFactor;
    }

    public void setBackgroundTranslateFactor(@FloatRange(from = 0f, to = 1f) float backgroundTranslateFactor) {
        this.backgroundTranslateFactor = backgroundTranslateFactor;
    }

    public void setForegroundScaleFactor(@FloatRange(from = 0f, to = 1f) float foregroundScaleFactor) {
        this.foregroundScaleFactor = foregroundScaleFactor;
        postInvalidateOnAnimation();
    }

    public void setBackgroundScaleFactor(@FloatRange(from = 0f, to = 1f) float backgroundScaleFactor) {
        this.backgroundScaleFactor = backgroundScaleFactor;
        postInvalidateOnAnimation();
    }

    @Keep // called by @animator/scale
    public void setScale(@FloatRange(from = 0f, to = 1f) float scale) {
        if (foregroundPaint.getShader() == null) return;
        this.scale = scale;
        backgroundScale = 1f + backgroundScaleFactor * scale;
        foregroundScale = 1f + foregroundScaleFactor * scale;
        setScaleX(backgroundScale);
        setScaleY(backgroundScale);
        postInvalidateOnAnimation();
    }

    @Keep // called by @animator/scale
    public float getScale() {
        return scale;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (icon != null) init(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (icon == null) return;

        // apply any velocity translations or touch scaling to the shaders
        transformLayer(backgroundPaint, backgroundDx, backgroundDy, backgroundScale);
        transformLayer(foregroundPaint, foregroundDx, foregroundDy, foregroundScale);

        final int saveCount = canvas.save();
        canvas.translate(left, top);
        if (drawShadow) {
            canvas.translate(0, shadowDY);
            canvas.drawRoundRect(0, 0, iconSize, iconSize, cornerRadius, cornerRadius, shadowPaint);
            canvas.translate(0, -shadowDY);
        }
        canvas.drawRoundRect(0, 0, iconSize, iconSize, cornerRadius, cornerRadius, backgroundPaint);
        canvas.drawRoundRect(0, 0, iconSize, iconSize, cornerRadius, cornerRadius, foregroundPaint);
        canvas.restoreToCount(saveCount);
    }

    private void init(int w, int h) {
        if (w == 0 || h == 0) return;
        setBounds(w, h);
        setupDrawing();
    }

    private void setBounds(int w, int h) {
        left = (w - iconSize) / 2f;
        top = (h - iconSize) / 2f;
    }

    private void setupDrawing() {
        if (icon == null) return;
        if (background == null) {
            background = Bitmap.createBitmap(layerSize, layerSize, Bitmap.Config.ARGB_8888);
            backgroundPaint.setShader(new BitmapShader(background, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            background.eraseColor(Color.TRANSPARENT);
        }
        if (foreground == null) {
            foreground = Bitmap.createBitmap(layerSize, layerSize, Bitmap.Config.ARGB_8888);
            foregroundPaint.setShader(new BitmapShader(foreground, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            foreground.eraseColor(Color.TRANSPARENT);
        }
        Canvas c = new Canvas();
        rasterize(icon.getBackground(), background, c);
        rasterize(icon.getForeground(), foreground, c);
    }

    private void rasterize(Drawable drawable, Bitmap bitmap, Canvas canvas) {
        drawable.setBounds(0, 0, layerSize, layerSize);
        canvas.setBitmap(bitmap);
        drawable.draw(canvas);
    }

    private void transformLayer(Paint layer, float dx, float dy, float layerScale) {
        BitmapShader shader = (BitmapShader) layer.getShader();
        shader.getLocalMatrix(matrix);
        matrix.setScale(layerScale, layerScale, layerCenter, layerCenter);
        matrix.postTranslate(dx - viewportOffset, dy - viewportOffset);
        shader.setLocalMatrix(matrix);
    }

    private float velocityToDisplacement(float velocity) {
        float clampedVelocity = Math.min(Math.max(velocity, -1000f), 1000f);
        return iconSize * clampedVelocity / -1000f;
    }
}
