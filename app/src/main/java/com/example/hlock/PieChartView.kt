package com.example.hlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val label: String, val value: Float, val color: Int)

    private val slices = mutableListOf<Slice>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }
    private val rectF = RectF()

    companion object {
        val CHART_COLORS = intArrayOf(
            Color.parseColor("#00D261"), // Green
            Color.parseColor("#FF6B6B"), // Red
            Color.parseColor("#4ECDC4"), // Teal
            Color.parseColor("#FFE66D"), // Yellow
            Color.parseColor("#A28FD0"), // Purple
            Color.parseColor("#FF8C42"), // Orange
            Color.parseColor("#3BCEAC"), // Mint
            Color.parseColor("#EE4266"), // Rose
            Color.parseColor("#0496FF"), // Blue
            Color.parseColor("#D7263D"), // Crimson
            Color.parseColor("#F49E4C"), // Amber
            Color.parseColor("#AB47BC"), // Violet
            Color.parseColor("#26A69A"), // Aqua
            Color.parseColor("#EC407A"), // Pink
            Color.parseColor("#42A5F5"), // Sky Blue
        )
    }

    fun setData(data: List<Slice>) {
        slices.clear()
        slices.addAll(data)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) return

        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0) return

        val chartSize = minOf(width, height * 2 / 3).toFloat()
        val padding = 20f
        val centerX = width / 2f
        val chartCenterY = chartSize / 2f + padding

        rectF.set(
            centerX - chartSize / 2 + padding,
            padding,
            centerX + chartSize / 2 - padding,
            chartSize - padding
        )

        // Draw the donut chart
        var startAngle = -90f
        val strokeWidth = chartSize * 0.18f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth

        // Inset the rect for stroke drawing
        val inset = strokeWidth / 2
        val donutRect = RectF(
            rectF.left + inset,
            rectF.top + inset,
            rectF.right - inset,
            rectF.bottom - inset
        )

        for (slice in slices) {
            val sweepAngle = (slice.value / total) * 360f
            paint.color = slice.color
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(donutRect, startAngle, sweepAngle - 1f, false, paint)
            startAngle += sweepAngle
        }

        // Center text
        textPaint.textSize = chartSize * 0.09f
        textPaint.color = Color.WHITE
        val totalMins = total.toLong()
        val hrs = totalMins / 60
        val mins = totalMins % 60
        canvas.drawText("${hrs}h ${mins}m", centerX, chartCenterY - 5f, textPaint)

        textPaint.textSize = chartSize * 0.05f
        textPaint.color = Color.parseColor("#9E9E9E")
        canvas.drawText("Total Usage", centerX, chartCenterY + chartSize * 0.08f, textPaint)

        // Draw legend below the chart
        val legendStartY = chartSize + 20f
        val legendItemHeight = 40f
        val dotRadius = 8f
        val legendStartX = padding + 16f
        val maxColumns = 2
        val columnWidth = (width - padding * 2) / maxColumns

        for ((index, slice) in slices.withIndex()) {
            if (index >= 10) break // Max 10 legend items

            val col = index % maxColumns
            val row = index / maxColumns
            val x = legendStartX + col * columnWidth
            val y = legendStartY + row * legendItemHeight

            // Dot
            paint.style = Paint.Style.FILL
            paint.color = slice.color
            canvas.drawCircle(x + dotRadius, y + dotRadius, dotRadius, paint)

            // Label
            labelPaint.textSize = 22f
            labelPaint.color = Color.WHITE
            val mins2 = slice.value.toInt()
            val timeStr = if (mins2 >= 60) "${mins2 / 60}h ${mins2 % 60}m" else "${mins2}m"
            val text = "${slice.label} · $timeStr"
            canvas.drawText(text, x + dotRadius * 3 + 8f, y + dotRadius + 7f, labelPaint)
        }
    }

    // Helper to draw arc without the built-in method issue
    private fun drawArc(canvas: Canvas, rect: RectF, paint: Paint, startAngle: Float, sweepAngle: Float) {
        canvas.drawArc(rect, startAngle, sweepAngle - 1f, false, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val legendRows = (slices.size + 1) / 2
        val chartHeight = width * 2 / 3
        val legendHeight = legendRows * 40 + 40
        val totalHeight = chartHeight + legendHeight
        setMeasuredDimension(width, totalHeight.coerceAtLeast(200))
    }
}
