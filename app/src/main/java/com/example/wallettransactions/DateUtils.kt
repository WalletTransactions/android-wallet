package com.example.wallettransactions

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date

object DateUtils {

    private const val dateFormat = "yyyy-MM-dd HH:mm"
    @JvmField
    var formatDB = SimpleDateFormat(dateFormat)

    private const val dateOnlyFormat = "yyyy-MM-dd"
    @JvmField
    var formatDateOnly = SimpleDateFormat(dateOnlyFormat)

    @JvmStatic
    fun DateToStr(dt: Date): String = formatDB.format(dt)

    /** Formats without the time component, matching how rates are actually stored (yyyy-MM-dd). */
    @JvmStatic
    fun DateToDateStr(dt: Date): String = formatDateOnly.format(dt)

    @JvmStatic
    fun StrToDate(st: String): Date {
        var s = st
        if (s.length == 10)
            s = "$s 00:00"
        return try {
            formatDB.parse(s)!!
        } catch (e: ParseException) {
            Date(0)
        }
    }
}
