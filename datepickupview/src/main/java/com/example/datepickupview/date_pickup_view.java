package com.example.datepickupview;

import android.app.DatePickerDialog;
import android.content.Context;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Calendar;

public class date_pickup_view extends LinearLayout {

    private Calendar mCalDateTime;
    private EditText editDate;
    private String cCalendarFormat = "%1$td %1$tb '%1$ty";

    private void initialize(Context context){ inflate(context, R.layout.date_pickup_view, this); }

    public date_pickup_view(Context context) {
        super(context);
        initialize(context);
    }

}
