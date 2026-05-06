package com.rphone.v3.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.rphone.v3.R
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Channel { CURRENT, VOLTAGE, POWER, ALL }

    var activeChannel: Channel = Channel.CURRENT
        set(value) { field = value; invalidate() }

    var isPaused: Boolean = false

    private val maxPoints  = 300
    private val currentBuf = LinkedList<Float>()
    private val voltageBuf = LinkedList<Float>()
    private val powerBuf   = LinkedList<Float>()

    var peakCurrent: Float = 0f; private set
    var avgCurrent:  Float = 0f; private set
    var minCurrent:  Float = 0f; private set

    var onStatsChanged: ((peak: Float, avg: Float, min: Float) -> Unit)? = null

    // Jika di-set > 0, gunakan nilai ini sebagai ceiling
    // (skala Y tetap) — berguna untuk sinkronisasi dua
    // WaveformView agar skala Y-nya identik.
    // Set ke 0f untuk kembali ke auto-scale.
    var fixedCeiling: Float = 0f
        set(value) { field = value; invalidate() }

    var colorCurrent: Int = Color.parseColor("#00D9FF")
    var colorVoltage: Int = Color.parseColor("#10B981")
    var colorPower:   Int = Color.parseColor("#3B82F6")

    // ── Pan & Zoom State ──────────────────────────────────────────
    // zoomLevel: 1.0 = tampil semua data, >1.0 = zoom in (lebih sedikit data tampil)
    private var zoomLevel: Float = 1f
    private val zoomMin: Float   = 1f
    private val zoomMax: Float   = 8f

    // panOffset: jumlah sample yang di-skip dari kiri (index paling lama)
    // 0 = tampil data terbaru (paling kanan), positif = geser ke kiri (data lebih lama)
    private var panOffset: Int = 0

    // Listener untuk sinkronisasi dengan WaveformView lain
    var onPanZoomChanged: ((panOffset: Int, zoomLevel: Float) -> Unit)? = null

    // Jika true, gesture ditangani internal; jika false, dikontrol eksternal
    var gestureEnabled: Boolean = true

    // ── Scrollbar ─────────────────────────────────────────────────
    private val scrollbarHeight = 4f
    private val scrollbarMarginBottom = 4f
    private val paintScrollbarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2535")
        style = Paint.Style.FILL
    }
    private val paintScrollbarThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D9FF")
        style = Paint.Style.FILL
    }
    private val scrollbarRect  = RectF()
    private val scrollThumbRect = RectF()

    // ── Gesture Detectors ─────────────────────────────────────────
    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!gestureEnabled) return false
                val buf = getActiveBuffer()
                if (buf.isEmpty()) return false
                val visibleCount = getVisibleCount(buf.size)
                val totalData    = buf.size
                // distanceX > 0 → geser kiri (ke data lebih lama)
                val stepX = (width - yAxisWidth) / max(visibleCount - 1, 1).toFloat()
                val delta = (distanceX / stepX).roundToInt()
                val maxPan = max(0, totalData - visibleCount)
                panOffset = (panOffset + delta).coerceIn(0, maxPan)
                onPanZoomChanged?.invoke(panOffset, zoomLevel)
                invalidate()
                return true
            }
        })

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!gestureEnabled) return false
                zoomLevel = (zoomLevel * detector.scaleFactor)
                    .coerceIn(zoomMin, zoomMax)
                // Sesuaikan panOffset agar tidak out of range setelah zoom
                val buf = getActiveBuffer()
                val visibleCount = getVisibleCount(buf.size)
                val maxPan = max(0, buf.size - visibleCount)
                panOffset = panOffset.coerceIn(0, maxPan)
                onPanZoomChanged?.invoke(panOffset, zoomLevel)
                invalidate()
                return true
            }
        })

    // ── Layout ────────────────────────────────────────────────────
    private val yAxisWidth = 52f
    private val ySteps     = 5
    private var rawMin: Float = Float.MAX_VALUE

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F1825")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val paintScaleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2535")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    private val paintYLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A4A5C")
        textSize = 14f
        textAlign = Paint.Align.RIGHT
        typeface = ResourcesCompat.getFont(context, R.font.dseg7)
    }

    private val paintYSep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2535")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val paintWave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintPeak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    private val paintAvg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6366F1")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val paintValueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        textAlign = Paint.Align.RIGHT
        typeface = ResourcesCompat.getFont(context, R.font.dseg7)
    }

    private val wavePath = Path()
    private val fillPath = Path()

    // ── Public API ────────────────────────────────────────────────

    fun addDataPoint(current: Float, voltage: Float, power: Float) {
        if (isPaused) return
        addToBuffer(currentBuf, current)
        addToBuffer(voltageBuf, voltage)
        addToBuffer(powerBuf, power)
        if (current > peakCurrent) peakCurrent = current
        if (current < rawMin) rawMin = current
        minCurrent = if (rawMin == Float.MAX_VALUE) 0f else rawMin
        avgCurrent = if (currentBuf.isNotEmpty())
            currentBuf.sum() / currentBuf.size.toFloat() else 0f
        onStatsChanged?.invoke(peakCurrent, avgCurrent, minCurrent)
        invalidate()
    }

    fun resetData() {
        currentBuf.clear()
        voltageBuf.clear()
        powerBuf.clear()
        peakCurrent = 0f
        avgCurrent = 0f
        minCurrent = 0f
        rawMin = Float.MAX_VALUE
        panOffset = 0
        zoomLevel = 1f
        onStatsChanged?.invoke(0f, 0f, 0f)
        invalidate()
    }

    /** Dipanggil dari luar untuk sinkronisasi pan & zoom */
    fun applyPanZoom(panOffset: Int, zoomLevel: Float) {
        this.panOffset = panOffset
        this.zoomLevel = zoomLevel
        invalidate()
    }

    // ── Touch ─────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gestureEnabled) return false
        var handled = scaleGestureDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled
    }

    // ── Draw ──────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val waveLeft = yAxisWidth
        val waveW    = w - waveLeft

        val ceiling = if (fixedCeiling > 0f) {
            fixedCeiling
        } else {
            val maxVal = when (activeChannel) {
                Channel.ALL -> listOf(
                    currentBuf.maxOrNull() ?: 0f,
                    voltageBuf.maxOrNull() ?: 0f,
                    powerBuf.maxOrNull() ?: 0f
                ).max()
                Channel.CURRENT -> currentBuf.maxOrNull() ?: 0f
                Channel.VOLTAGE -> voltageBuf.maxOrNull() ?: 0f
                Channel.POWER   -> powerBuf.maxOrNull() ?: 0f
            }.coerceAtLeast(0.001f)
            maxVal * 1.2f
        }

        drawGrid(canvas, waveLeft, waveW, h)
        drawYAxis(canvas, waveLeft, h, ceiling)

        when (activeChannel) {
            Channel.CURRENT -> drawChannel(canvas, waveLeft, waveW, h,
                ceiling, currentBuf, colorCurrent, peakCurrent, avgCurrent, true)
            Channel.VOLTAGE -> drawChannel(canvas, waveLeft, waveW, h,
                ceiling, voltageBuf, colorVoltage, 0f, 0f, false)
            Channel.POWER   -> drawChannel(canvas, waveLeft, waveW, h,
                ceiling, powerBuf, colorPower, 0f, 0f, false)
            Channel.ALL -> {
                drawChannel(canvas, waveLeft, waveW, h, ceiling,
                    powerBuf, colorPower, 0f, 0f, false)
                drawChannel(canvas, waveLeft, waveW, h, ceiling,
                    voltageBuf, colorVoltage, 0f, 0f, false)
                drawChannel(canvas, waveLeft, waveW, h, ceiling,
                    currentBuf, colorCurrent, peakCurrent, avgCurrent, true)
            }
        }

        drawScrollbar(canvas, waveLeft, waveW, h)
    }

    private fun getVisibleCount(totalData: Int): Int {
        val count = (maxPoints / zoomLevel).roundToInt().coerceAtLeast(2)
        return min(count, totalData)
    }

    private fun getActiveBuffer(): LinkedList<Float> = when (activeChannel) {
        Channel.CURRENT -> currentBuf
        Channel.VOLTAGE -> voltageBuf
        Channel.POWER   -> powerBuf
        Channel.ALL     -> currentBuf
    }

    private fun drawScrollbar(canvas: Canvas, waveLeft: Float, waveW: Float, h: Float) {
        val buf = getActiveBuffer()
        val totalData    = buf.size
        if (totalData == 0) return
        val visibleCount = getVisibleCount(totalData)
        if (visibleCount >= totalData) return  // semua data tampil, tidak perlu scrollbar

        val sbTop    = h - scrollbarHeight - scrollbarMarginBottom
        val sbBottom = h - scrollbarMarginBottom
        scrollbarRect.set(waveLeft, sbTop, waveLeft + waveW, sbBottom)
        canvas.drawRoundRect(scrollbarRect, 2f, 2f, paintScrollbarBg)

        val thumbRatio  = visibleCount.toFloat() / totalData.toFloat()
        val thumbW      = (waveW * thumbRatio).coerceAtLeast(20f)
        val maxPan      = max(0, totalData - visibleCount)
        val panRatio    = if (maxPan > 0) panOffset.toFloat() / maxPan.toFloat() else 0f
        // panRatio=0 → thumb di kanan (data terbaru), =1 → thumb di kiri (data lama)
        val thumbLeft   = waveLeft + (waveW - thumbW) * (1f - panRatio)
        scrollThumbRect.set(thumbLeft, sbTop, thumbLeft + thumbW, sbBottom)
        canvas.drawRoundRect(scrollThumbRect, 2f, 2f, paintScrollbarThumb)
    }

    private fun drawYAxis(canvas: Canvas, waveLeft: Float, h: Float, ceiling: Float) {
        canvas.drawLine(waveLeft, 0f, waveLeft, h, paintYSep)
        val topPad = h * 0.05f
        val drawH  = h - topPad * 2f
        for (i in 0..ySteps) {
            val ratio  = i.toFloat() / ySteps.toFloat()
            val value  = ceiling * (1f - ratio)
            val y      = topPad + ratio * drawH
            canvas.drawLine(waveLeft, y, width.toFloat(), y, paintScaleLine)
            val labelY = y + paintYLabel.textSize / 3f
            
            val label = if (i == 0) {
                val unit = when (activeChannel) {
                    Channel.CURRENT -> "${formatLabel(value)}A"
                    Channel.VOLTAGE -> "${formatLabel(value)}V"
                    Channel.POWER   -> "${formatLabel(value)}W"
                    Channel.ALL     -> "${formatLabel(value)}"
                }
                unit
            } else {
                formatLabel(value)
            }
            canvas.drawText(label, waveLeft - 8f, labelY, paintYLabel)
        }
    }

    private fun formatLabel(value: Float): String = when {
        value >= 10f -> String.format("%.0f", value)
        value >= 1f  -> String.format("%.1f", value)
        else         -> String.format("%.2f", value)
    }

    private fun drawGrid(canvas: Canvas, waveLeft: Float, waveW: Float, h: Float) {
        val vStep = waveW / 8f
        var x = waveLeft + vStep
        while (x < waveLeft + waveW) {
            canvas.drawLine(x, 0f, x, h, paintGrid)
            x += vStep
        }
    }

    private fun drawChannel(
        canvas: Canvas,
        waveLeft: Float, waveW: Float,
        h: Float, ceiling: Float,
        buffer: LinkedList<Float>,
        color: Int,
        peak: Float, avg: Float,
        drawStats: Boolean
    ) {
        if (buffer.size < 2) return
        val allData      = buffer.toList()
        val totalData    = allData.size
        val visibleCount = getVisibleCount(totalData)

        // Hitung slice yang tampil
        // panOffset = 0 → tampil data paling baru (ujung kanan buffer)
        // panOffset > 0 → geser ke data lebih lama
        val endIdx   = max(0, totalData - panOffset)
        val startIdx = max(0, endIdx - visibleCount)
        val list     = allData.subList(startIdx, endIdx)
        if (list.size < 2) return

        val stepX  = waveW / (list.size - 1).toFloat()
        val topPad = h * 0.05f
        val drawH  = h - topPad * 2f

        fun valueToY(v: Float): Float {
            val ratio = (v / ceiling).coerceIn(0f, 1f)
            return topPad + (1f - ratio) * drawH
        }

        wavePath.reset()
        fillPath.reset()
        val firstY = valueToY(list[0])
        wavePath.moveTo(waveLeft, firstY)
        fillPath.moveTo(waveLeft, h)
        fillPath.lineTo(waveLeft, firstY)

        for (i in 1 until list.size) {
            val x     = waveLeft + i * stepX
            val y     = valueToY(list[i])
            val prevX = waveLeft + (i - 1) * stepX
            val prevY = valueToY(list[i - 1])
            val cpX   = (prevX + x) / 2f
            wavePath.cubicTo(cpX, prevY, cpX, y, x, y)
            fillPath.cubicTo(cpX, prevY, cpX, y, x, y)
        }
        fillPath.lineTo(waveLeft + waveW, h)
        fillPath.close()

        paintWave.color = color
        paintWave.setShadowLayer(16f, 0f, 0f, color)
        canvas.drawPath(wavePath, paintWave)
        paintWave.clearShadowLayer()

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        paintFill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.argb(55, r, g, b), Color.argb(0, r, g, b)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, paintFill)

        if (!drawStats) return
        if (peak > 0f) canvas.drawLine(
            waveLeft, valueToY(peak), width.toFloat(), valueToY(peak), paintPeak)
        if (avg > 0f) canvas.drawLine(
            waveLeft, valueToY(avg), width.toFloat(), valueToY(avg), paintAvg)
        if (list.isNotEmpty()) {
            val lastVal = list.last()
            val safeBottom = maxOf(24f + 1f, h - 8f)
            val labelY = valueToY(lastVal).coerceIn(24f, safeBottom)
            paintValueLabel.color = color
            paintValueLabel.setShadowLayer(8f, 0f, 0f, color)
            canvas.drawText(
                String.format("%.3f", lastVal),
                width.toFloat() - 6f, labelY, paintValueLabel
            )
            paintValueLabel.clearShadowLayer()
        }
    }

    private fun addToBuffer(buffer: LinkedList<Float>, value: Float) {
        if (buffer.size >= maxPoints) buffer.removeFirst()
        buffer.addLast(value)
    }
}
