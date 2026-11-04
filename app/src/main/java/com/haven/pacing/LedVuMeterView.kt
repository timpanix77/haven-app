package com.haven.pacing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

class LedVuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class MeterType {
        HR_PROXIMITY,   // 20 pips: Grn (<=75), Yel (76-84), Org (85-87), Red (>=88)
        DAILY_BUDGET,   // 20 pips: Grn (0-10m), Yel (11-15m), Org (16-19m), Red (20m+)
        GRACE_BUFFER    // 12 pips: 10s per pip up to 120s (Amber)
    }

    var meterType: MeterType = MeterType.HR_PROXIMITY
        set(value) {
            field = value
            setupPalette()
            invalidate()
        }

    private var totalSegments: Int = 20
    private var activeLevel: Float = 0f

    // Peak Hold Ballistics (30-second freeze post-recovery)
    private var peakSegment: Int = -1
    private var peakHoldExpiryMs: Long = 0L

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val segmentRect = RectF()

    private var activeColors = IntArray(20)
    private var ghostColors = IntArray(20)

    init {
        setupPalette()
    }

    private fun setupPalette() {
        totalSegments = when (meterType) {
            MeterType.HR_PROXIMITY -> 20
            MeterType.DAILY_BUDGET -> 20
            MeterType.GRACE_BUFFER -> 12
        }

        activeColors = IntArray(totalSegments)
        ghostColors = IntArray(totalSegments)

        when (meterType) {
            MeterType.HR_PROXIMITY -> {
                // 1-10 Grn (<=75), 11-14 Yel (76-84), 15-17 Org (85-87), 18-20 Red (88+)
                for (i in 0 until totalSegments) {
                    val color = when {
                        i < 10 -> Color.parseColor("#00E676") // Neon Green
                        i < 14 -> Color.parseColor("#FFEA00") // Yellow
                        i < 17 -> Color.parseColor("#FF9100") // Orange
                        else   -> Color.parseColor("#FF1744") // High-efficiency Red
                    }
                    activeColors[i] = color
                    ghostColors[i] = darken(color, 0.12f)
                }
            }
            MeterType.DAILY_BUDGET -> {
                for (i in 0 until totalSegments) {
                    val color = when {
                        i < 10 -> Color.parseColor("#00E676")
                        i < 15 -> Color.parseColor("#FFEA00")
                        i < 19 -> Color.parseColor("#FF9100")
                        else   -> Color.parseColor("#FF1744")
                    }
                    activeColors[i] = color
                    ghostColors[i] = darken(color, 0.12f)
                }
            }
            MeterType.GRACE_BUFFER -> {
                for (i in 0 until totalSegments) {
                    val color = Color.parseColor("#FF9100")
                    activeColors[i] = color
                    ghostColors[i] = darken(color, 0.10f)
                }
            }
        }
    }

    fun setLevel(value: Float, max: Float) {
        val normalized = (value / max).coerceIn(0f, 1f)
        activeLevel = normalized * totalSegments
        val currentPip = activeLevel.toInt()

        // Peak Hold logic for live heart rate
        if (meterType == MeterType.HR_PROXIMITY) {
            val now = SystemClock.uptimeMillis()
            if (currentPip > peakSegment) {
                peakSegment = currentPip
                peakHoldExpiryMs = 0L // Latched active
            } else if (value <= 75f && peakSegment > 10 && peakHoldExpiryMs == 0L) {
                // Crossing below floor arms the 30-second hold
                peakHoldExpiryMs = now + 30_000L
            } else if (peakHoldExpiryMs != 0L && now > peakHoldExpiryMs) {
                // Cooldown elapsed, reset peak
                peakSegment = currentPip
                peakHoldExpiryMs = 0L
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val availableWidth = width.toFloat() - paddingLeft - paddingRight
        val availableHeight = height.toFloat() - paddingTop - paddingBottom
        val gap = 4f
        val pipWidth = (availableWidth - (gap * (totalSegments - 1))) / totalSegments
        val cornerRadius = 3f

        val activeCount = activeLevel.toInt()

        for (i in 0 until totalSegments) {
            val left = paddingLeft + i * (pipWidth + gap)
            val top = paddingTop.toFloat()
            val right = left + pipWidth
            val bottom = top + availableHeight
            segmentRect.set(left, top, right, bottom)

            val isLit = i < activeCount
            val isPeak = (meterType == MeterType.HR_PROXIMITY && i == peakSegment && !isLit)

            if (isLit) {
                activePaint.color = activeColors[i]
                canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, activePaint)
            } else if (isPeak) {
                peakPaint.color = activeColors[i]
                canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, peakPaint)
            } else {
                ghostPaint.color = ghostColors[i]
                canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, ghostPaint)
            }
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt()
        val g = (Color.green(color) * factor).toInt()
        val b = (Color.blue(color) * factor).toInt()
        return Color.rgb(r, g, b)
    }
}
