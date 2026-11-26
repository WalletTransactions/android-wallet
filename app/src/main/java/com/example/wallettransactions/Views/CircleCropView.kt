package com.example.wallettransactions.Views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Круглая обрезка иконки: картинку двигают пальцем и масштабируют щипком,
 * в базу уходит то, что попало в круг, — всё остальное прозрачное.
 *
 * Своя реализация вместо чужого «com.android.camera.action.CROP»: сторонний
 * редактор возвращает только прямоугольник с непрозрачным фоном, да и вызывать
 * его на targetSdk 34 нечем — см. комментарий в IconEditDialog.
 */
class CircleCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var source: Bitmap? = null

    /** Картинка → координаты вью. Ни поворота, ни перекоса, только scale + translate. */
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePath = Path()

    /** Описанный вокруг круга квадрат в координатах вью. */
    private val window = RectF()

    /** Масштаб, при котором картинка ровно закрывает круг: меньше нельзя. */
    private var minScale = 1f

    private var scaleDetector: ScaleGestureDetector
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        shadePaint.color = SHADE_COLOR
        shadePaint.style = Paint.Style.FILL

        ringPaint.color = Color.WHITE
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = resources.displayMetrics.density * 2f

        scaleDetector = ScaleGestureDetector(context, ScaleListener())
    }

    fun setImage(bitmap: Bitmap?) {
        source = bitmap
        resetTransform()
        invalidate()
    }

    fun hasImage(): Boolean = source != null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        var diameter = Math.min(w, h) - 2 * resources.displayMetrics.density * 24f
        if (diameter < 1) diameter = Math.min(w, h).toFloat()
        val radius = diameter / 2f
        window.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius)

        resetTransform()
    }

    /** Вписывает картинку так, чтобы она закрывала круг целиком, и центрирует. */
    private fun resetTransform() {
        val src = source
        if (src == null || window.isEmpty) return

        minScale = Math.max(
            window.width() / src.width,
            window.height() / src.height
        )

        matrix.reset()
        matrix.postScale(minScale, minScale)
        matrix.postTranslate(
            window.centerX() - src.width * minScale / 2f,
            window.centerY() - src.height * minScale / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (window.isEmpty) return

        source?.let { canvas.drawBitmap(it, matrix, imagePaint) }

        // Заливка «всё, кроме круга»: прямоугольник вью и круг одним путём с
        // EVEN_ODD — точки внутри круга пересекают два контура и не заливаются.
        shadePath.reset()
        shadePath.fillType = Path.FillType.EVEN_ODD
        shadePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        shadePath.addCircle(window.centerX(), window.centerY(), window.width() / 2f, Path.Direction.CW)
        canvas.drawPath(shadePath, shadePaint)

        canvas.drawCircle(window.centerX(), window.centerY(), window.width() / 2f, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false

        scaleDetector.onTouchEvent(event)

        // Тянем за середину всех прижатых пальцев: при щипке ScaleGestureDetector
        // держит фокус на месте, а середина двигает картинку — вместе получается
        // ровно то, что ожидает палец.
        val liftedIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP)
            event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == liftedIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return true
        val focusX = sumX / count
        val focusY = sumY / count

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastTouchX = focusX
                lastTouchY = focusY
            }

            MotionEvent.ACTION_MOVE -> {
                matrix.postTranslate(focusX - lastTouchX, focusY - lastTouchY)
                lastTouchX = focusX
                lastTouchY = focusY
                clampToWindow()
                invalidate()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    /** Не даёт уменьшить картинку меньше круга и вывести её край внутрь круга. */
    private fun clampToWindow() {
        val src = source
        if (src == null || window.isEmpty) return

        matrix.getValues(matrixValues)
        var scale = matrixValues[Matrix.MSCALE_X]

        val bounded = Math.min(Math.max(scale, minScale), minScale * MAX_ZOOM)
        if (bounded != scale) {
            matrix.postScale(bounded / scale, bounded / scale, window.centerX(), window.centerY())
            matrix.getValues(matrixValues)
            scale = bounded
        }

        val left = matrixValues[Matrix.MTRANS_X]
        val top = matrixValues[Matrix.MTRANS_Y]
        val right = left + src.width * scale
        val bottom = top + src.height * scale

        var dx = 0f
        var dy = 0f
        if (left > window.left) dx = window.left - left
        else if (right < window.right) dx = window.right - right
        if (top > window.top) dy = window.top - top
        else if (bottom < window.bottom) dy = window.bottom - bottom

        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    /**
     * Содержимое круга квадратом size×size: за границей круга альфа 0.
     *
     * Сначала кладём непрозрачный круг, потом рисуем картинку в режиме SRC_IN —
     * пиксели, которых круг не коснулся, так и остаются прозрачными, а край
     * получается сглаженным, без чёрной каймы.
     */
    fun getCroppedBitmap(size: Int): Bitmap? {
        val src = source
        if (src == null || window.isEmpty) return null

        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val export = Matrix(matrix)
        export.postTranslate(-window.left, -window.top)
        val toOutput = size / window.width()
        export.postScale(toOutput, toOutput)
        canvas.drawBitmap(src, export, paint)

        return result
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            matrix.postScale(
                detector.scaleFactor, detector.scaleFactor,
                detector.focusX, detector.focusY
            )
            clampToWindow()
            invalidate()
            return true
        }
    }

    companion object {
        /** Насколько можно приблизить относительно посадки картинки в круг. */
        private const val MAX_ZOOM = 8f

        /** Затемнение за кругом: colorTextPrimary, 70% — «этого в иконке не будет». */
        private const val SHADE_COLOR = 0xB3201E1D.toInt()
    }
}
