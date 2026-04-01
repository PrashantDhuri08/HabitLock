package com.example.hlock

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class CheatHoursPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    
    // Range in minutes from midnight (0 to 1440)
    var startMinutes = 21 * 60 // Default 21:00
    var endMinutes = 22 * 60   // Default 22:00
    
    private var isDraggingStart = false
    private var isDraggingEnd = false
    
    private val activeColor = 0xFF81C784.toInt() // accent_green
    private val inactiveColor = 0xFF424242.toInt()
    
    var onRangeChanged: ((Int, Int) -> Unit)? = null

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - 80f
    }

    override fun onDraw(canvas: Canvas) {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        
        // Draw background circle
        paint.color = inactiveColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 40f
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        // Draw active range arc
        paint.color = activeColor
        val startAngle = (startMinutes.toFloat() / 1440f * 360f) - 90f
        var sweepAngle = ((endMinutes.toFloat() - startMinutes.toFloat()) / 1440f * 360f)
        if (sweepAngle < 0) sweepAngle += 360f
        
        canvas.drawArc(
            centerX - radius, centerY - radius, centerX + radius, centerY + radius,
            startAngle, sweepAngle, false, paint
        )
        
        // Draw handles
        drawHandle(canvas, startAngle, true)
        drawHandle(canvas, startAngle + sweepAngle, false)
        
        // Draw labels (0, 2, 4, ... 22)
        for (i in 0 until 24 step 2) {
            val angle = (i.toFloat() / 24f * 360f) - 90f
            val x = centerX + (radius + 60f) * cos(Math.toRadians(angle.toDouble())).toFloat()
            val y = centerY + (radius + 60f) * sin(Math.toRadians(angle.toDouble())).toFloat()
            canvas.drawText(i.toString(), x, y + 10f, textPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, angle: Float, isStart: Boolean) {
        val x = centerX + radius * cos(Math.toRadians(angle.toDouble())).toFloat()
        val y = centerY + radius * sin(Math.toRadians(angle.toDouble())).toFloat()
        
        paint.style = Paint.Style.FILL
        paint.color = if (isStart) Color.WHITE else 0xFF212121.toInt()
        canvas.drawCircle(x, y, 25f, paint)
        
        if (!isStart) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = 4f
            canvas.drawCircle(x, y, 25f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x - centerX
        val y = event.y - centerY
        val angle = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat() + 90f + 360f) % 360f
        val minutes = (angle / 360f * 1440f).toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val handleRadius = 60f
                val startX = radius * cos(Math.toRadians(((startMinutes / 1440f * 360f) - 90f).toDouble())).toFloat()
                val startY = radius * sin(Math.toRadians(((startMinutes / 1440f * 360f) - 90f).toDouble())).toFloat()
                val endX = radius * cos(Math.toRadians(((endMinutes / 1440f * 360f) - 90f).toDouble())).toFloat()
                val endY = radius * sin(Math.toRadians(((endMinutes / 1440f * 360f) - 90f).toDouble())).toFloat()

                if (hypot(x - startX, y - startY) < handleRadius) {
                    isDraggingStart = true
                } else if (hypot(x - endX, y - endY) < handleRadius) {
                    isDraggingEnd = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingStart) {
                    startMinutes = minutes
                    invalidate()
                } else if (isDraggingEnd) {
                    endMinutes = minutes
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingStart || isDraggingEnd) {
                    onRangeChanged?.invoke(startMinutes, endMinutes)
                }
                isDraggingStart = false
                isDraggingEnd = false
            }
        }
        return true
    }
}
