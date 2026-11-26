package com.example.wallettransactions.Views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Pair
import android.view.MotionEvent
import android.view.View
import com.example.wallettransactions.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RateChartView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {

    fun interface OnRangeChangedListener {
        fun onRangeChanged(from: Date, to: Date)
    }

    private var data: MutableList<Pair<Date, Double>> = ArrayList()

    // Cached bounds — computed in setData()
    private var minTime = 0L
    private var timeRange = 1L
    private var vMin = 0.0
    private var vMax = 1.0

    // Selection as fractions [0..1] of timeRange
    private var selLeftFrac = 0f
    private var selRightFrac = 1f

    private var rangeListener: OnRangeChangedListener? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()
    private val selLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleHole = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var colorProfit = 0
    private var colorExpanse = 0

    private var dp = 0f
    private var padL = 0f
    private var padR = 0f
    private var padT = 0f
    private var padB = 0f

    private val fmtDay = SimpleDateFormat("dd MMM", Locale.getDefault())
    private val fmtMon = SimpleDateFormat("MMM ''yy", Locale.getDefault())
    private val fmtYear = SimpleDateFormat("yyyy", Locale.getDefault())

    // Touch state
    private enum class Drag { NONE, LEFT, RIGHT, BOTH, NEW }

    private var dragMode = Drag.NONE
    private var dragStartX = 0f
    private var dragStartL = 0f
    private var dragStartR = 0f
    private var newAnchorFrac = 0f

    // ──────────────────────────────────────────────────────────────────────────

    init {
        val context = c
        dp = context.resources.displayMetrics.density
        padL = 60f * dp
        padR = 14f * dp
        padT = 10f * dp
        padB = 30f * dp

        val primary = context.resources.getColor(R.color.colorPrimary)
        val secondary = context.resources.getColor(R.color.colorTextSecondary)
        val divider = context.resources.getColor(R.color.colorDivider)
        colorProfit = context.resources.getColor(R.color.colorprofit)
        colorExpanse = context.resources.getColor(R.color.colorexpanse)

        linePaint.color = primary
        linePaint.strokeWidth = 2.5f * dp
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.strokeCap = Paint.Cap.ROUND

        fillPaint.color = primary
        fillPaint.alpha = 35
        fillPaint.style = Paint.Style.FILL

        gridPaint.color = divider
        gridPaint.strokeWidth = 1f
        gridPaint.style = Paint.Style.STROKE

        dimPaint.color = Color.BLACK
        dimPaint.alpha = 60
        dimPaint.style = Paint.Style.FILL

        selLine.color = primary
        selLine.strokeWidth = 2f * dp
        selLine.style = Paint.Style.STROKE

        handleFill.color = primary
        handleFill.style = Paint.Style.FILL

        // «Дырка» в кружке — не белый, а цвет карточки под графиком (bg_card): в
        // тёмной теме белая точка светилась бы посреди тёмной карточки.
        handleHole.color = context.resources.getColor(R.color.colorSurface)
        handleHole.style = Paint.Style.FILL

        textPaint.color = secondary
        textPaint.textSize = 10f * dp

        pctPaint.textSize = 12f * dp
        pctPaint.isFakeBoldText = true
        pctPaint.textAlign = Paint.Align.RIGHT

        isClickable = true
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setOnRangeChangedListener(l: OnRangeChangedListener?) {
        rangeListener = l
    }

    /** rawData sorted in any order — will be reordered ASC internally. */
    fun setData(rawData: List<Pair<Date, Double>>) {
        data = ArrayList(rawData)
        data.sortWith { a, b -> a.first.compareTo(b.first) }
        computeBounds()
        invalidate()
    }

    /** Set the highlighted selection range (does not fire the listener). */
    fun setSelection(from: Date, to: Date) {
        if (data.isEmpty() || timeRange == 0L) return
        selLeftFrac = clamp(((from.time - minTime) / timeRange.toDouble()).toFloat())
        selRightFrac = clamp(((to.time - minTime) / timeRange.toDouble()).toFloat())
        if (selRightFrac - selLeftFrac < 0.005f)
            selRightFrac = Math.min(1f, selLeftFrac + 0.005f)
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (data.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.rates_chart_no_data), width / 2f, height / 2f, textPaint)
            return
        }

        val cW = width - padL - padR
        val cH = height - padT - padB

        // Grid + Y labels
        val g = 4
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..g) {
            val y = padT + cH - cH * i / g
            canvas.drawLine(padL, y, padL + cW, y, gridPaint)
            val v = vMin + (vMax - vMin) * i / g
            canvas.drawText(fmtRate(v), padL - 5f * dp, y + textPaint.textSize * 0.35f, textPaint)
        }

        // Axes
        canvas.drawLine(padL, padT, padL, padT + cH, gridPaint)
        canvas.drawLine(padL, padT + cH, padL + cW, padT + cH, gridPaint)

        // Line + fill
        val linePath = Path()
        val fillPath = Path()
        val xs = FloatArray(data.size)
        val ys = FloatArray(data.size)
        var first = true
        for (i in data.indices) {
            val p = data[i]
            val x = padL + ((p.first.time - minTime) / timeRange.toDouble()).toFloat() * cW
            val y = padT + cH - ((p.second - vMin) / (vMax - vMin)).toFloat() * cH
            xs[i] = x
            ys[i] = y
            if (first) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, padT + cH)
                fillPath.lineTo(x, y)
                first = false
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(xs[data.size - 1], padT + cH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Dots for small datasets
        if (data.size <= 62) {
            val dOut = Paint(Paint.ANTI_ALIAS_FLAG)
            dOut.color = resources.getColor(R.color.colorPrimary)
            dOut.style = Paint.Style.FILL
            val dIn = Paint(Paint.ANTI_ALIAS_FLAG)
            dIn.color = resources.getColor(R.color.colorSurface)
            dIn.style = Paint.Style.FILL
            for (i in data.indices) {
                canvas.drawCircle(xs[i], ys[i], 4f * dp, dOut)
                canvas.drawCircle(xs[i], ys[i], 2f * dp, dIn)
            }
        }

        // Dim non-selected regions
        val selL = padL + selLeftFrac * cW
        val selR = padL + selRightFrac * cW
        if (selLeftFrac > 0.001f)
            canvas.drawRect(padL, padT, selL, padT + cH, dimPaint)
        if (selRightFrac < 0.999f)
            canvas.drawRect(selR, padT, padL + cW, padT + cH, dimPaint)

        // Selection boundary lines
        canvas.drawLine(selL, padT, selL, padT + cH, selLine)
        canvas.drawLine(selR, padT, selR, padT + cH, selLine)

        // Drag handles at vertical centre
        val hY = padT + cH * 0.5f
        canvas.drawCircle(selL, hY, 9f * dp, handleFill)
        canvas.drawCircle(selL, hY, 4f * dp, handleHole)
        canvas.drawCircle(selR, hY, 9f * dp, handleFill)
        canvas.drawCircle(selR, hY, 4f * dp, handleHole)

        // Изменение за выделение больше не рисуется здесь: ту же величину показывает
        // rateDelta в шапке карточки (activity_currency_pair_rate_list.xml), и две
        // одинаковые цифры рядом только путали. Обновляется по отпусканию ручки.

        // X labels
        textPaint.textAlign = Paint.Align.CENTER
        val fmt = if (timeRange < 90L * 86_400_000L) fmtDay
        else if (timeRange < 730L * 86_400_000L) fmtMon else fmtYear
        val step = Math.max(1, (data.size - 1) / 5)
        val lblY = padT + cH + 20f * dp

        val last = data.size - 1
        val lastText = fmt.format(data[last].first)
        // Последняя дата рисуется всегда, поэтому промежуточную подпись пропускаем,
        // если она с ней сливается — иначе на правом краю получалась каша.
        val lastHalfWidth = textPaint.measureText(lastText) / 2f

        var i = 0
        while (i < last) {
            val text = fmt.format(data[i].first)
            val halfWidth = textPaint.measureText(text) / 2f
            if (xs[i] + halfWidth > xs[last] - lastHalfWidth - 4f * dp) {
                i += step
                continue
            }
            canvas.drawText(text, xs[i], lblY, textPaint)
            i += step
        }
        canvas.drawText(lastText, xs[last], lblY, textPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (data.size < 2) return false

        val cW = width - padL - padR
        val x = event.x
        val frac = clamp((x - padL) / cW)
        val selL = padL + selLeftFrac * cW
        val selR = padL + selRightFrac * cW
        val hz = 22f * dp

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (Math.abs(x - selL) <= hz) dragMode = Drag.LEFT
                else if (Math.abs(x - selR) <= hz) dragMode = Drag.RIGHT
                else if (x > selL + hz && x < selR - hz) {
                    dragMode = Drag.BOTH
                    dragStartX = x
                    dragStartL = selLeftFrac
                    dragStartR = selRightFrac
                } else {
                    dragMode = Drag.NEW
                    newAnchorFrac = frac
                    selLeftFrac = frac
                    selRightFrac = frac
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    Drag.LEFT ->
                        selLeftFrac = clamp(Math.min(frac, selRightFrac - 0.01f))

                    Drag.RIGHT ->
                        selRightFrac = clamp(Math.max(frac, selLeftFrac + 0.01f))

                    Drag.BOTH -> {
                        val d = (x - dragStartX) / cW
                        val sel = dragStartR - dragStartL
                        var nL = dragStartL + d
                        var nR = dragStartR + d
                        if (nL < 0) {
                            nL = 0f
                            nR = sel
                        }
                        if (nR > 1) {
                            nR = 1f
                            nL = 1 - sel
                        }
                        selLeftFrac = nL
                        selRightFrac = nR
                    }

                    Drag.NEW ->
                        if (frac >= newAnchorFrac) {
                            selLeftFrac = newAnchorFrac
                            selRightFrac = clamp(Math.max(frac, newAnchorFrac + 0.01f))
                        } else {
                            selLeftFrac = clamp(Math.min(frac, newAnchorFrac - 0.01f))
                            selRightFrac = newAnchorFrac
                        }

                    Drag.NONE -> {}
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val listener = rangeListener
                if (dragMode != Drag.NONE && listener != null) {
                    var from = Date(minTime + (selLeftFrac * timeRange).toLong())
                    var to = Date(minTime + (selRightFrac * timeRange).toLong())

                    // Normalize dates to be inclusive
                    val cal = Calendar.getInstance()

                    // Set 'from' to start of day (00:00:00)
                    cal.time = from
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    from = cal.time

                    // Set 'to' to end of day next day (23:59:59)
                    cal.time = to
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                    to = cal.time

                    listener.onRangeChanged(from, to)
                }
                dragMode = Drag.NONE
                return true
            }
        }
        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun computeBounds() {
        if (data.isEmpty()) return
        var minVal = Double.MAX_VALUE
        var maxVal = -Double.MAX_VALUE
        var minT = Long.MAX_VALUE
        var maxT = Long.MIN_VALUE
        for (p in data) {
            if (p.second < minVal) minVal = p.second
            if (p.second > maxVal) maxVal = p.second
            val t = p.first.time
            if (t < minT) minT = t
            if (t > maxT) maxT = t
        }
        var vr = maxVal - minVal
        if (vr < 1e-9) vr = Math.max(maxVal * 0.01, 0.0001)
        vMin = minVal - vr * 0.07
        vMax = maxVal + vr * 0.07
        minTime = minT
        timeRange = maxT - minT
        if (timeRange == 0L) timeRange = 86_400_000L
    }

    private fun clamp(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /**
     * The last known rate at or before a given [0..1] fraction of the chart's time range —
     * no interpolation/averaging across gaps where no rate exists; the rate simply doesn't
     * change until the next known point.
     */
    private fun valueAtFraction(frac: Float): Double {
        if (data.isEmpty()) return 0.0
        val t = minTime + (frac * timeRange).toLong()
        var result: Double = data[0].second
        for (p in data) {
            if (p.first.time > t) break // data is sorted ascending
            result = p.second
        }
        return result
    }

    private fun fmtPct(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmtRate(v: Double): String {
        if (v >= 1000) return String.format(Locale.US, "%.0f", v)
        if (v >= 100) return String.format(Locale.US, "%.1f", v)
        if (v >= 10) return String.format(Locale.US, "%.2f", v)
        return String.format(Locale.US, "%.4f", v)
    }
}
