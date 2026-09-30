package com.example.wallettransactions.Views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Круглая обрезка иконки: картинку двигают пальцем и масштабируют щипком,
 * в базу уходит то, что попало в круг, — всё остальное прозрачное.
 *
 * Своя реализация вместо чужого «com.android.camera.action.CROP»: сторонний
 * редактор возвращает только прямоугольник с непрозрачным фоном, да и вызывать
 * его на targetSdk 34 нечем — см. комментарий в IconEditDialog.
 */
public class CircleCropView extends View {

    /** Насколько можно приблизить относительно посадки картинки в круг. */
    private static final float MAX_ZOOM = 8f;

    /** Затемнение за кругом: colorTextPrimary, 70% — «этого в иконке не будет». */
    private static final int SHADE_COLOR = 0xB3201E1D;

    private Bitmap source;

    /** Картинка → координаты вью. Ни поворота, ни перекоса, только scale + translate. */
    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shadePath = new Path();

    /** Описанный вокруг круга квадрат в координатах вью. */
    private final RectF window = new RectF();

    /** Масштаб, при котором картинка ровно закрывает круг: меньше нельзя. */
    private float minScale = 1f;

    private ScaleGestureDetector scaleDetector;
    private float lastTouchX;
    private float lastTouchY;

    public CircleCropView(Context context) {
        super(context);
        init(context);
    }

    public CircleCropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CircleCropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        shadePaint.setColor(SHADE_COLOR);
        shadePaint.setStyle(Paint.Style.FILL);

        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    public void setImage(Bitmap bitmap) {
        source = bitmap;
        resetTransform();
        invalidate();
    }

    public boolean hasImage() {
        return source != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        float diameter = Math.min(w, h) - 2 * getResources().getDisplayMetrics().density * 24f;
        if (diameter < 1) diameter = Math.min(w, h);
        float radius = diameter / 2f;
        window.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius);

        resetTransform();
    }

    /** Вписывает картинку так, чтобы она закрывала круг целиком, и центрирует. */
    private void resetTransform() {
        if (source == null || window.isEmpty()) return;

        minScale = Math.max(window.width() / source.getWidth(),
                window.height() / source.getHeight());

        matrix.reset();
        matrix.postScale(minScale, minScale);
        matrix.postTranslate(window.centerX() - source.getWidth() * minScale / 2f,
                window.centerY() - source.getHeight() * minScale / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (window.isEmpty()) return;

        if (source != null) canvas.drawBitmap(source, matrix, imagePaint);

        // Заливка «всё, кроме круга»: прямоугольник вью и круг одним путём с
        // EVEN_ODD — точки внутри круга пересекают два контура и не заливаются.
        shadePath.reset();
        shadePath.setFillType(Path.FillType.EVEN_ODD);
        shadePath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        shadePath.addCircle(window.centerX(), window.centerY(), window.width() / 2f, Path.Direction.CW);
        canvas.drawPath(shadePath, shadePaint);

        canvas.drawCircle(window.centerX(), window.centerY(), window.width() / 2f, ringPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (source == null) return false;

        scaleDetector.onTouchEvent(event);

        // Тянем за середину всех прижатых пальцев: при щипке ScaleGestureDetector
        // держит фокус на месте, а середина двигает картинку — вместе получается
        // ровно то, что ожидает палец.
        int liftedIndex = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                ? event.getActionIndex() : -1;
        float sumX = 0, sumY = 0;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == liftedIndex) continue;
            sumX += event.getX(i);
            sumY += event.getY(i);
            count++;
        }
        if (count == 0) return true;
        float focusX = sumX / count;
        float focusY = sumY / count;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastTouchX = focusX;
                lastTouchY = focusY;
                break;

            case MotionEvent.ACTION_MOVE:
                matrix.postTranslate(focusX - lastTouchX, focusY - lastTouchY);
                lastTouchX = focusX;
                lastTouchY = focusY;
                clampToWindow();
                invalidate();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    /** Не даёт уменьшить картинку меньше круга и вывести её край внутрь круга. */
    private void clampToWindow() {
        if (source == null || window.isEmpty()) return;

        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];

        float bounded = Math.min(Math.max(scale, minScale), minScale * MAX_ZOOM);
        if (bounded != scale) {
            matrix.postScale(bounded / scale, bounded / scale, window.centerX(), window.centerY());
            matrix.getValues(matrixValues);
            scale = bounded;
        }

        float left = matrixValues[Matrix.MTRANS_X];
        float top = matrixValues[Matrix.MTRANS_Y];
        float right = left + source.getWidth() * scale;
        float bottom = top + source.getHeight() * scale;

        float dx = 0, dy = 0;
        if (left > window.left) dx = window.left - left;
        else if (right < window.right) dx = window.right - right;
        if (top > window.top) dy = window.top - top;
        else if (bottom < window.bottom) dy = window.bottom - bottom;

        if (dx != 0 || dy != 0) matrix.postTranslate(dx, dy);
    }

    /**
     * Содержимое круга квадратом size×size: за границей круга альфа 0.
     *
     * Сначала кладём непрозрачный круг, потом рисуем картинку в режиме SRC_IN —
     * пиксели, которых круг не коснулся, так и остаются прозрачными, а край
     * получается сглаженным, без чёрной каймы.
     */
    public Bitmap getCroppedBitmap(int size) {
        if (source == null || window.isEmpty()) return null;

        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));

        Matrix export = new Matrix(matrix);
        export.postTranslate(-window.left, -window.top);
        float toOutput = size / window.width();
        export.postScale(toOutput, toOutput);
        canvas.drawBitmap(source, export, paint);

        return result;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(),
                    detector.getFocusX(), detector.getFocusY());
            clampToWindow();
            invalidate();
            return true;
        }
    }
}
