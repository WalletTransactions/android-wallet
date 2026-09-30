package com.example.wallettransactions.Views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;

import com.example.wallettransactions.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RateChartView extends View {

    public interface OnRangeChangedListener {
        void onRangeChanged(Date from, Date to);
    }

    private List<Pair<Date, Double>> data = new ArrayList<>();

    // Cached bounds — computed in setData()
    private long   minTime   = 0;
    private long   timeRange = 1;
    private double vMin      = 0;
    private double vMax      = 1;

    // Selection as fractions [0..1] of timeRange
    private float selLeftFrac  = 0f;
    private float selRightFrac = 1f;

    private OnRangeChangedListener rangeListener;

    private final Paint linePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint    = new Paint();
    private final Paint selLine     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleHole  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int colorProfit;
    private int colorExpanse;

    private float dp;
    private float padL, padR, padT, padB;

    private final SimpleDateFormat fmtDay  = new SimpleDateFormat("dd MMM",  Locale.getDefault());
    private final SimpleDateFormat fmtMon  = new SimpleDateFormat("MMM ''yy",Locale.getDefault());
    private final SimpleDateFormat fmtYear = new SimpleDateFormat("yyyy",    Locale.getDefault());

    // Touch state
    private enum Drag { NONE, LEFT, RIGHT, BOTH, NEW }
    private Drag  dragMode         = Drag.NONE;
    private float dragStartX;
    private float dragStartL, dragStartR;
    private float newAnchorFrac;

    // ──────────────────────────────────────────────────────────────────────────

    public RateChartView(Context c)              { super(c);    init(c); }
    public RateChartView(Context c, AttributeSet a) { super(c,a); init(c); }

    private void init(Context context) {
        dp   = context.getResources().getDisplayMetrics().density;
        padL = 60f * dp;
        padR = 14f * dp;
        padT = 10f * dp;
        padB = 30f * dp;

        int primary   = context.getResources().getColor(R.color.colorPrimary);
        int secondary = context.getResources().getColor(R.color.colorTextSecondary);
        int divider   = context.getResources().getColor(R.color.colorDivider);
        colorProfit   = context.getResources().getColor(R.color.colorprofit);
        colorExpanse  = context.getResources().getColor(R.color.colorexpanse);

        linePaint.setColor(primary);
        linePaint.setStrokeWidth(2.5f * dp);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setColor(primary);
        fillPaint.setAlpha(35);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(divider);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(60);
        dimPaint.setStyle(Paint.Style.FILL);

        selLine.setColor(primary);
        selLine.setStrokeWidth(2f * dp);
        selLine.setStyle(Paint.Style.STROKE);

        handleFill.setColor(primary);
        handleFill.setStyle(Paint.Style.FILL);

        // «Дырка» в кружке — не белый, а цвет карточки под графиком (bg_card): в
        // тёмной теме белая точка светилась бы посреди тёмной карточки.
        handleHole.setColor(context.getResources().getColor(R.color.colorSurface));
        handleHole.setStyle(Paint.Style.FILL);

        textPaint.setColor(secondary);
        textPaint.setTextSize(10f * dp);

        pctPaint.setTextSize(12f * dp);
        pctPaint.setFakeBoldText(true);
        pctPaint.setTextAlign(Paint.Align.RIGHT);

        setClickable(true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setOnRangeChangedListener(OnRangeChangedListener l) {
        rangeListener = l;
    }

    /** rawData sorted in any order — will be reordered ASC internally. */
    public void setData(List<Pair<Date, Double>> rawData) {
        data = new ArrayList<>(rawData);
        Collections.sort(data, (a, b) -> a.first.compareTo(b.first));
        computeBounds();
        invalidate();
    }

    /** Set the highlighted selection range (does not fire the listener). */
    public void setSelection(Date from, Date to) {
        if (data.isEmpty() || timeRange == 0) return;
        selLeftFrac  = clamp((float)((from.getTime() - minTime) / (double) timeRange));
        selRightFrac = clamp((float)((to.getTime()   - minTime) / (double) timeRange));
        if (selRightFrac - selLeftFrac < 0.005f)
            selRightFrac = Math.min(1f, selLeftFrac + 0.005f);
        invalidate();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (data.size() < 2) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Нет данных", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }

        float cW = getWidth()  - padL - padR;
        float cH = getHeight() - padT - padB;

        // Grid + Y labels
        final int G = 4;
        textPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= G; i++) {
            float y = padT + cH - cH * i / G;
            canvas.drawLine(padL, y, padL + cW, y, gridPaint);
            double val = vMin + (vMax - vMin) * i / G;
            canvas.drawText(fmtRate(val), padL - 5f * dp, y + textPaint.getTextSize() * 0.35f, textPaint);
        }

        // Axes
        canvas.drawLine(padL, padT, padL, padT + cH, gridPaint);
        canvas.drawLine(padL, padT + cH, padL + cW, padT + cH, gridPaint);

        // Line + fill
        Path linePath = new Path();
        Path fillPath = new Path();
        float[] xs = new float[data.size()];
        float[] ys = new float[data.size()];
        boolean first = true;
        for (int i = 0; i < data.size(); i++) {
            Pair<Date, Double> p = data.get(i);
            float x = padL + (float)((p.first.getTime() - minTime) / (double) timeRange) * cW;
            float y = padT + cH - (float)((p.second - vMin) / (vMax - vMin)) * cH;
            xs[i] = x; ys[i] = y;
            if (first) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, padT + cH);
                fillPath.lineTo(x, y);
                first = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(xs[data.size() - 1], padT + cH);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Dots for small datasets
        if (data.size() <= 62) {
            Paint dOut = new Paint(Paint.ANTI_ALIAS_FLAG);
            dOut.setColor(getResources().getColor(R.color.colorPrimary));
            dOut.setStyle(Paint.Style.FILL);
            Paint dIn = new Paint(Paint.ANTI_ALIAS_FLAG);
            dIn.setColor(getResources().getColor(R.color.colorSurface));
            dIn.setStyle(Paint.Style.FILL);
            for (int i = 0; i < data.size(); i++) {
                canvas.drawCircle(xs[i], ys[i], 4f * dp, dOut);
                canvas.drawCircle(xs[i], ys[i], 2f * dp, dIn);
            }
        }

        // Dim non-selected regions
        float selL = padL + selLeftFrac  * cW;
        float selR = padL + selRightFrac * cW;
        if (selLeftFrac > 0.001f)
            canvas.drawRect(padL, padT, selL, padT + cH, dimPaint);
        if (selRightFrac < 0.999f)
            canvas.drawRect(selR, padT, padL + cW, padT + cH, dimPaint);

        // Selection boundary lines
        canvas.drawLine(selL, padT, selL, padT + cH, selLine);
        canvas.drawLine(selR, padT, selR, padT + cH, selLine);

        // Drag handles at vertical centre
        float hY = padT + cH * 0.5f;
        canvas.drawCircle(selL, hY, 9f * dp, handleFill);
        canvas.drawCircle(selL, hY, 4f * dp, handleHole);
        canvas.drawCircle(selR, hY, 9f * dp, handleFill);
        canvas.drawCircle(selR, hY, 4f * dp, handleHole);

        // Изменение за выделение больше не рисуется здесь: ту же величину показывает
        // rateDelta в шапке карточки (activity_currency_pair_rate_list.xml), и две
        // одинаковые цифры рядом только путали. Обновляется по отпусканию ручки.

        // X labels
        textPaint.setTextAlign(Paint.Align.CENTER);
        SimpleDateFormat fmt = timeRange < 90L * 86_400_000L ? fmtDay
                : timeRange < 730L * 86_400_000L ? fmtMon : fmtYear;
        int step = Math.max(1, (data.size() - 1) / 5);
        float lblY = padT + cH + 20f * dp;

        int last = data.size() - 1;
        String lastText = fmt.format(data.get(last).first);
        // Последняя дата рисуется всегда, поэтому промежуточную подпись пропускаем,
        // если она с ней сливается — иначе на правом краю получалась каша.
        float lastHalfWidth = textPaint.measureText(lastText) / 2f;

        for (int i = 0; i < last; i += step) {
            String text = fmt.format(data.get(i).first);
            float halfWidth = textPaint.measureText(text) / 2f;
            if (xs[i] + halfWidth > xs[last] - lastHalfWidth - 4f * dp) continue;
            canvas.drawText(text, xs[i], lblY, textPaint);
        }
        canvas.drawText(lastText, xs[last], lblY, textPaint);
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (data.size() < 2) return false;

        float cW   = getWidth() - padL - padR;
        float x    = event.getX();
        float frac = clamp((x - padL) / cW);
        float selL = padL + selLeftFrac  * cW;
        float selR = padL + selRightFrac * cW;
        float hz   = 22f * dp;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if      (Math.abs(x - selL) <= hz)      dragMode = Drag.LEFT;
                else if (Math.abs(x - selR) <= hz)      dragMode = Drag.RIGHT;
                else if (x > selL + hz && x < selR - hz) {
                    dragMode    = Drag.BOTH;
                    dragStartX  = x;
                    dragStartL  = selLeftFrac;
                    dragStartR  = selRightFrac;
                } else {
                    dragMode       = Drag.NEW;
                    newAnchorFrac  = frac;
                    selLeftFrac    = frac;
                    selRightFrac   = frac;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                switch (dragMode) {
                    case LEFT:
                        selLeftFrac = clamp(Math.min(frac, selRightFrac - 0.01f));
                        break;
                    case RIGHT:
                        selRightFrac = clamp(Math.max(frac, selLeftFrac + 0.01f));
                        break;
                    case BOTH: {
                        float d   = (x - dragStartX) / cW;
                        float sel = dragStartR - dragStartL;
                        float nL  = dragStartL + d;
                        float nR  = dragStartR + d;
                        if (nL < 0) { nL = 0;   nR = sel; }
                        if (nR > 1) { nR = 1;   nL = 1 - sel; }
                        selLeftFrac = nL; selRightFrac = nR;
                        break;
                    }
                    case NEW:
                        if (frac >= newAnchorFrac) {
                            selLeftFrac  = newAnchorFrac;
                            selRightFrac = clamp(Math.max(frac, newAnchorFrac + 0.01f));
                        } else {
                            selLeftFrac  = clamp(Math.min(frac, newAnchorFrac - 0.01f));
                            selRightFrac = newAnchorFrac;
                        }
                        break;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (dragMode != Drag.NONE && rangeListener != null) {
                    Date from = new Date(minTime + (long)(selLeftFrac  * timeRange));
                    Date to   = new Date(minTime + (long)(selRightFrac * timeRange));

                    // Normalize dates to be inclusive
                    java.util.Calendar cal = java.util.Calendar.getInstance();

                    // Set 'from' to start of day (00:00:00)
                    cal.setTime(from);
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                    cal.set(java.util.Calendar.MINUTE, 0);
                    cal.set(java.util.Calendar.SECOND, 0);
                    cal.set(java.util.Calendar.MILLISECOND, 0);
                    from = cal.getTime();

                    // Set 'to' to end of day next day (23:59:59)
                    cal.setTime(to);
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 23);
                    cal.set(java.util.Calendar.MINUTE, 59);
                    cal.set(java.util.Calendar.SECOND, 59);
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
                    to = cal.getTime();

                    rangeListener.onRangeChanged(from, to);
                }
                dragMode = Drag.NONE;
                return true;
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void computeBounds() {
        if (data.isEmpty()) return;
        double minVal = Double.MAX_VALUE, maxVal = -Double.MAX_VALUE;
        long   minT   = Long.MAX_VALUE,   maxT   = Long.MIN_VALUE;
        for (Pair<Date, Double> p : data) {
            if (p.second < minVal) minVal = p.second;
            if (p.second > maxVal) maxVal = p.second;
            long t = p.first.getTime();
            if (t < minT) minT = t;
            if (t > maxT) maxT = t;
        }
        double vr = maxVal - minVal;
        if (vr < 1e-9) vr = Math.max(maxVal * 0.01, 0.0001);
        vMin      = minVal - vr * 0.07;
        vMax      = maxVal + vr * 0.07;
        minTime   = minT;
        timeRange = maxT - minT;
        if (timeRange == 0) timeRange = 86_400_000L;
    }

    private static float clamp(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    /**
     * The last known rate at or before a given [0..1] fraction of the chart's time range —
     * no interpolation/averaging across gaps where no rate exists; the rate simply doesn't
     * change until the next known point.
     */
    private double valueAtFraction(float frac) {
        if (data.isEmpty()) return 0;
        long t = minTime + (long)(frac * timeRange);
        double result = data.get(0).second;
        for (Pair<Date, Double> p : data) {
            if (p.first.getTime() > t) break; // data is sorted ascending
            result = p.second;
        }
        return result;
    }

    private String fmtPct(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private String fmtRate(double v) {
        if (v >= 1000) return String.format(Locale.US, "%.0f",  v);
        if (v >= 100)  return String.format(Locale.US, "%.1f",  v);
        if (v >= 10)   return String.format(Locale.US, "%.2f",  v);
        return             String.format(Locale.US, "%.4f",  v);
    }
}
