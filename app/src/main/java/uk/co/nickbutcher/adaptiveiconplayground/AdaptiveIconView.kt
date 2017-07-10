/*
 *  Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package uk.co.nickbutcher.adaptiveiconplayground

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader.TileMode.CLAMP
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.support.annotation.FloatRange
import android.support.annotation.Keep
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.reflect.KProperty

/**
 * A custom view for rendering [AdaptiveIconDrawable]s.
 *
 * Note that this is a prototype implementation; I do not recommend using any of this code in
 * production. The technique employed holds [Bitmap]s of both foreground & background layers, then
 * renders masked versions using [BitmapShader]s. We do not support arbitrary mask paths, just
 * simple rounded rectangle shapes. This is less flexible and uses more memory but is quick and
 * allows animation of the corner radius.
 */
class AdaptiveIconView(
        context: Context,
        attrs: AttributeSet?
) : View(context, attrs, R.attr.adaptiveIconViewStyle) {

    private val foregroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowDY: Float
    private val layerSize: Int
    private val iconSize: Int
    private val layerCenter: Float
    private val viewportOffset: Int
    private val background: Bitmap
    private val foreground: Bitmap
    private var shadowPaint: Paint? = null
    private var left = 0f
    private var top = 0f
    private var foregroundDx by InvalidateDelegate(0f)
    private var foregroundDy by InvalidateDelegate(0f)
    private var backgroundDx by InvalidateDelegate(0f)
    private var backgroundDy by InvalidateDelegate(0f)
    private var backgroundScale by InvalidateDelegate(0f)
    private var foregroundScale by InvalidateDelegate(0f)

    var cornerRadius by InvalidateDelegate(0f)
    // scale & translate factors [0,1]
    var foregroundTranslateFactor by FloatRangeDelegate(0f)
    var backgroundTranslateFactor by FloatRangeDelegate(0f)
    var foregroundScaleFactor by FloatRangeDelegate(0f)
    var backgroundScaleFactor by FloatRangeDelegate(0f)
    var velocityX = 0f
        set(value) {
            val displacementX = velocityToDisplacement(value)
            backgroundDx = backgroundTranslateFactor * displacementX
            foregroundDx = foregroundTranslateFactor * displacementX
        }
    var velocityY = 0f
        set(value) {
            val displacementY = velocityToDisplacement(value)
            backgroundDy = backgroundTranslateFactor * displacementY
            foregroundDy = foregroundTranslateFactor * displacementY
        }
    @Keep // called by @animator/scale
    var scale = 0f
        set(@FloatRange(from = 0.0, to = 1.0) value) {
            field = Math.max(0f, Math.min(1f, value))
            backgroundScale = 1f + backgroundScaleFactor * value
            foregroundScale = 1f + foregroundScaleFactor * value
            scaleX = backgroundScale
            scaleY = backgroundScale
        }

    init {
        val a = context.obtainStyledAttributes(
                attrs, R.styleable.AdaptiveIconView, R.attr.adaptiveIconViewStyle, 0)
        val shadowColor = a.getColor(R.styleable.AdaptiveIconView_shadowColor, Color.TRANSPARENT)
        shadowDY = a.getDimension(R.styleable.AdaptiveIconView_shadowDy, 0f)
        a.recycle()
        if (shadowColor != Color.TRANSPARENT && shadowDY > 0) {
            shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadowColor
            }
        }
        layerSize = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 108f, context.resources.displayMetrics))
        layerCenter = (layerSize / 2).toFloat()
        iconSize = (layerSize / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())).toInt()
        viewportOffset = (layerSize - iconSize) / 2

        background = Bitmap.createBitmap(layerSize, layerSize, Bitmap.Config.ARGB_8888)
        backgroundPaint.shader = BitmapShader(background, CLAMP, CLAMP)
        foreground = Bitmap.createBitmap(layerSize, layerSize, Bitmap.Config.ARGB_8888)
        foregroundPaint.shader = BitmapShader(foreground, CLAMP, CLAMP)
    }

    fun setIcon(icon: AdaptiveIconDrawable) {
        background.eraseColor(Color.TRANSPARENT)
        foreground.eraseColor(Color.TRANSPARENT)
        val c = Canvas()
        rasterize(icon.background, background, c)
        rasterize(icon.foreground, foreground, c)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        left = (w - iconSize) / 2f
        top = (h - iconSize) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // apply any velocity translations or touch scaling to the shaders
        transformLayer(backgroundPaint, backgroundDx, backgroundDy, backgroundScale)
        transformLayer(foregroundPaint, foregroundDx, foregroundDy, foregroundScale)

        canvas.run {
            val saveCount = save()
            translate(left, top)
            if (shadowPaint != null) {
                translate(0f, shadowDY)
                drawRoundRect(0f, 0f, iconSize.toFloat(), iconSize.toFloat(),
                        cornerRadius, cornerRadius, shadowPaint)
                translate(0f, -shadowDY)
            }
            drawRoundRect(0f, 0f, iconSize.toFloat(), iconSize.toFloat(),
                    cornerRadius, cornerRadius, backgroundPaint)
            drawRoundRect(0f, 0f, iconSize.toFloat(), iconSize.toFloat(),
                    cornerRadius, cornerRadius, foregroundPaint)
            restoreToCount(saveCount)
        }
    }

    private fun rasterize(drawable: Drawable, bitmap: Bitmap, canvas: Canvas) {
        drawable.setBounds(0, 0, layerSize, layerSize)
        canvas.setBitmap(bitmap)
        drawable.draw(canvas)
    }

    private fun transformLayer(layer: Paint, dx: Float, dy: Float, layerScale: Float) {
        val shader = layer.shader as BitmapShader
        shader.getLocalMatrix(tempMatrix)
        tempMatrix.setScale(layerScale, layerScale, layerCenter, layerCenter)
        tempMatrix.postTranslate(dx - viewportOffset, dy - viewportOffset)
        shader.setLocalMatrix(tempMatrix)
    }

    private fun velocityToDisplacement(velocity: Float): Float {
        val clampedVelocity = Math.min(Math.max(velocity, -1000f), 1000f)
        return iconSize * clampedVelocity / -1000f
    }

    companion object {
        private val tempMatrix = Matrix()
    }
}

/**
 * A [View] Delegate which [invalidates][View.postInvalidateOnAnimation] it when set.
 */
private class InvalidateDelegate<T : Any>(var value: T) {
    operator fun getValue(thisRef: View, property: KProperty<*>) = value
    operator fun setValue(thisRef: View, property: KProperty<*>, value: T) {
        this.value = value
        thisRef.postInvalidateOnAnimation()
    }
}

/**
 * A [Float] Delegate which constrains its value between a minimum and maximum.
 */
private class FloatRangeDelegate(
        var value: Float,
        val min: Float = 0f,
        val max: Float = 1f) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
        this.value = value.coerceIn(min, max)
    }
}
