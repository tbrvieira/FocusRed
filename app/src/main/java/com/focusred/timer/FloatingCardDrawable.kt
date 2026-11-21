package com.focusred.timer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The floating timer's red card with a soft, blurred shadow towards the bottom right,
 * so it looks lifted off the screen. The card is inset inside the drawable's bounds to
 * leave room for the shadow: [START_INSET_DP] on the left/top and [END_INSET_DP] on the
 * right/bottom (layout_floating_timer.xml adds the same amounts to the views' padding).
 * Its alpha (the timer's opacity setting) applies to the card and shadow alike.
 */
class FloatingCardDrawable(context: Context, cornerRadiusDp: Float) : Drawable() {

    companion object {
        private const val BLUR_DP = 10f
        private const val OFFSET_DP = 5f
        const val START_INSET_DP = 5 // BLUR_DP - OFFSET_DP
        const val END_INSET_DP = 15 // BLUR_DP + OFFSET_DP
        private const val SHADOW_ALPHA = 0x8C // 55% black at full opacity
    }

    private val density = context.resources.displayMetrics.density
    private val radius = cornerRadiusDp * density
    private val blur = BLUR_DP * density
    private val offset = OFFSET_DP * density
    private val strokeWidth = 1.5f * density

    private val card = RectF()
    private val cardPath = Path()
    private var shadow: Bitmap? = null
    private var alpha = 255

    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B91C1C") }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.STROKE
        strokeWidth = this@FloatingCardDrawable.strokeWidth
    }

    override fun onBoundsChange(bounds: Rect) {
        shadow?.recycle()
        shadow = null
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        card.set(
            bounds.left + START_INSET_DP * density, bounds.top + START_INSET_DP * density,
            bounds.right - END_INSET_DP * density, bounds.bottom - END_INSET_DP * density
        )
        cardPath.reset()
        cardPath.addRoundRect(card, radius, radius, Path.Direction.CW)

        // A real Gaussian blur needs software rendering, so draw it once into a bitmap
        // (regenerated only when the timer changes size) and reuse it every frame.
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ALPHA_8)
        val shadowRect = RectF(card).apply { offset(offset - bounds.left, offset - bounds.top) }
        Canvas(bitmap).drawRoundRect(shadowRect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        })
        shadow = bitmap
    }

    override fun draw(canvas: Canvas) {
        shadow?.let {
            // Keep the shadow out from under the translucent card.
            canvas.save()
            canvas.clipOutPath(cardPath)
            shadowPaint.alpha = SHADOW_ALPHA * alpha / 255
            canvas.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), shadowPaint)
            canvas.restore()
        }
        fillPaint.alpha = alpha
        canvas.drawRoundRect(card, radius, radius, fillPaint)
        strokePaint.alpha = alpha
        val half = strokeWidth / 2
        canvas.drawRoundRect(
            card.left + half, card.top + half, card.right - half, card.bottom - half,
            radius, radius, strokePaint
        )
    }

    override fun setAlpha(alpha: Int) {
        if (this.alpha != alpha) {
            this.alpha = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha() = alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
