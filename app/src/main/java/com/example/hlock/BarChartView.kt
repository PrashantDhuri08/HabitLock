package com.example.hlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    data class BarData(val label: String, val value: Float, val color: Int)
    
    private var data: List<BarData> = emptyList()
    
    init {
        textPaint.color = 0xFF9E9E9E.toInt() // text_secondary
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
    }

    fun setData(newData: List<BarData>) {
        data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (data.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = (width / (data.size * 2)) 
        val maxVal = data.maxOfOrNull { it.value } ?: 1f
        val chartHeight = height - 100f
        
        data.forEachIndexed { index, bar ->
            val barHeight = if (maxVal > 0) (bar.value / maxVal) * chartHeight else 0f
            val left = (index * 2 + 0.5f) * barWidth
            val top = chartHeight - barHeight
            val right = left + barWidth
            val bottom = chartHeight
            
            paint.color = bar.color
            paint.style = Paint.Style.FILL
            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, 12f, 12f, paint)
            
            // Label
            canvas.drawText(bar.label, left + barWidth / 2f, height - 20f, textPaint)
            
            // Value
            textPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(bar.value.toInt().toString(), left + barWidth / 2f, max(top - 10f, 30f), textPaint)
            textPaint.color = 0xFF9E9E9E.toInt()
        }
    }
}
