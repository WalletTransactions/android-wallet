package com.example.wallettransactions;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public abstract class DateUtils {

    private static String dateFormat = "yyyy-MM-dd HH:mm";
    public static SimpleDateFormat formatDB = new SimpleDateFormat(dateFormat);

    private static String dateOnlyFormat = "yyyy-MM-dd";
    public static SimpleDateFormat formatDateOnly = new SimpleDateFormat(dateOnlyFormat);

    public static String DateToStr(Date dt)
    {
        return formatDB.format(dt);
    }

    /** Formats without the time component, matching how rates are actually stored (yyyy-MM-dd). */
    public static String DateToDateStr(Date dt)
    {
        return formatDateOnly.format(dt);
    }

    public static Date StrToDate(String st)  {
        if (st.length() == 10)
            st = st+" 00:00";
        try {
            return formatDB.parse(st);
        } catch (ParseException e) {
            return new Date(0);
        }
    }


}
