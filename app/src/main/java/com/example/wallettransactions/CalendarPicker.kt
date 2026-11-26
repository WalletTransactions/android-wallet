package com.example.wallettransactions

import android.app.DatePickerDialog
import android.content.Context
import android.content.DialogInterface
import java.util.Calendar
import java.util.Date

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
object CalendarPicker {

    fun interface OnDatePicked {
        /** month нумеруется с нуля — как Calendar.MONTH. */
        fun onDatePicked(year: Int, month: Int, day: Int)
    }

    @JvmStatic
    fun show(context: Context, initial: Date?, callback: OnDatePicked) {
        val start = Calendar.getInstance()
        // DateUtils.StrToDate отдаёт эпоху при неразобранной строке — на такой
        // дате календарь открылся бы в 1970 году вместо сегодняшнего дня.
        if (initial != null && initial.time > 0) start.time = initial
        show(context, start, callback)
    }

    @JvmStatic
    fun show(context: Context, initial: Calendar?, callback: OnDatePicked) {
        val start = initial ?: Calendar.getInstance()

        val dialog = DatePickerDialog(
            context,
            { _, year, month, day -> callback.onDatePicked(year, month, day) },
            start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)
        )

        // «Сегодня» третьей кнопкой: самая частая дата ставится одним тапом, без
        // прокрутки календаря к текущему месяцу.
        dialog.setButton(DialogInterface.BUTTON_NEUTRAL, context.getString(R.string.calendar_today)) { _, _ ->
            val today = Calendar.getInstance()
            callback.onDatePicked(
                today.get(Calendar.YEAR), today.get(Calendar.MONTH),
                today.get(Calendar.DAY_OF_MONTH)
            )
        }

        dialog.show()
    }
}
