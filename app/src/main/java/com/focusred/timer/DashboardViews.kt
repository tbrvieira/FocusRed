package com.focusred.timer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
}

/** Bar chart of total social media time per day; the last bar is today. */
class WeeklyBarChartView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private var days: List<DayUsage> = emptyList()
    private val dayFormat = SimpleDateFormat("EEE", Locale.ENGLISH)
    private val rect = RectF()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density
        color = ColorUtils.setAlphaComponent(context.themeColor(android.R.attr.textColorSecondary), 0x55)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = context.themeColor(android.R.attr.textColorSecondary)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        color = context.themeColor(android.R.attr.textColorPrimary)
    }

    fun setDays(days: List<DayUsage>) {
        this.days = days
        contentDescription = days.indices.joinToString {
            "${dayLabel(it)}: ${UsageStatsHelper.formatDuration(days[it].total)}"
        }
        invalidate()
    }

    private fun dayLabel(index: Int) =
        if (index == days.lastIndex) "Today" else dayFormat.format(Date(days[index].dayStart))

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((180 * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val chartTop = paddingTop + valuePaint.textSize + 6 * density
        val chartBottom = height - paddingBottom - labelPaint.textSize - 10 * density
        val slot = (width - paddingLeft - paddingRight) / days.size.toFloat()
        val barWidth = minOf(slot * 0.6f, 32 * density)
        val radius = 6 * density
        val max = maxOf(days.maxOf { it.total }, 60_000L)

        canvas.drawLine(
            paddingLeft.toFloat(), chartBottom, (width - paddingRight).toFloat(), chartBottom, axisPaint
        )
        days.forEachIndexed { i, day ->
            val cx = paddingLeft + slot * (i + 0.5f)
            val barHeight = (chartBottom - chartTop) * day.total / max
            if (barHeight > 0f) {
                barPaint.color = if (i == days.lastIndex) TODAY_COLOR else BAR_COLOR
                rect.set(cx - barWidth / 2, chartBottom - barHeight, cx + barWidth / 2, chartBottom)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
                // Square off the bottom corners so bars sit flat on the axis.
                if (barHeight > radius) {
                    rect.top = chartBottom - radius
                    canvas.drawRect(rect, barPaint)
                }
            }
            canvas.drawText(
                UsageStatsHelper.formatDuration(day.total), cx, chartBottom - barHeight - 4 * density, valuePaint
            )
            canvas.drawText(dayLabel(i), cx, height - paddingBottom - 4 * density, labelPaint)
        }
    }

    private companion object {
        val BAR_COLOR = Color.parseColor("#F87171")
        val TODAY_COLOR = Color.parseColor("#DC2626")
    }
}

/** A thin rounded bar filled to [fraction]. */
class HorizontalBarView(context: Context) : View(context) {

    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var color = Color.RED
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackColor =
        ColorUtils.setAlphaComponent(context.themeColor(android.R.attr.textColorSecondary), 0x33)
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((6 * density).toInt() + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(
            paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat()
        )
        val radius = rect.height() / 2f
        paint.color = trackColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (fraction > 0f) {
            rect.right = rect.left + maxOf(rect.width() * fraction, rect.height())
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
