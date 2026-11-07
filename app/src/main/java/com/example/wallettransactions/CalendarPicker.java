package com.example.wallettransactions;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;

import java.util.Calendar;
import java.util.Date;

/**
 * Наш календарь — единственный способ ввести дату в приложении.
 *
 * До этого каждый экран собирал DatePickerDialog сам, а в «Курсах» дату вообще
 * набирали строкой с клавиатуры. Теперь точка одна, и кнопку «Сегодня» видно
 * везде, где выбирают дату.
 *
 * Отдаёт год/месяц/день, а не готовый Date: время в датах разное — операция
 * хранит свои часы и минуты, период фильтра округляется до начала и конца суток.
 * Как склеить дату со временем, решает вызывающий.
 */
public final class CalendarPicker {

    public interface OnDatePicked {
        /** month нумеруется с нуля — как Calendar.MONTH. */
        void onDatePicked(int year, int month, int day);
    }

    private CalendarPicker() { }

    public static void show(Context context, Date initial, OnDatePicked callback) {
        Calendar start = Calendar.getInstance();
        // DateUtils.StrToDate отдаёт эпоху при неразобранной строке — на такой
        // дате календарь открылся бы в 1970 году вместо сегодняшнего дня.
        if (initial != null && initial.getTime() > 0) start.setTime(initial);
        show(context, start, callback);
    }

    public static void show(Context context, Calendar initial, OnDatePicked callback) {
        Calendar start = initial != null ? initial : Calendar.getInstance();

        DatePickerDialog dialog = new DatePickerDialog(context,
                (view, year, month, day) -> callback.onDatePicked(year, month, day),
                start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));

        // «Сегодня» третьей кнопкой: самая частая дата ставится одним тапом, без
        // прокрутки календаря к текущему месяцу.
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, context.getString(R.string.calendar_today), (d, which) -> {
            Calendar today = Calendar.getInstance();
            callback.onDatePicked(today.get(Calendar.YEAR), today.get(Calendar.MONTH),
                    today.get(Calendar.DAY_OF_MONTH));
        });

        dialog.show();
    }
}
