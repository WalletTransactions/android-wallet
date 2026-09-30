package com.example.wallettransactions.Activities.Fragments;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.R;

import java.util.Calendar;
import java.util.Date;

public class ChooseDateFragment extends Fragment {


    private TextView mEditDate;
    private Calendar mCalDateTime;
    private ImageView btnDate;
    private String cCalendarFormat = "%1$td %1$tb '%1$ty";

    public void setWhiteFonts() {
        mEditDate.setTextColor(ContextCompat.getColor(this.getContext(), R.color.graylight));
    }

    public interface OnDateChangedListener {
        void onDateChanged(ChooseDateFragment parent, Date date);
    }

    private OnDateChangedListener mOnDateChangedListener;

    public static ChooseDateFragment newInstance() {
        return new ChooseDateFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {


        View rootView = inflater.inflate(R.layout.fragment_choose_date, container, false);
        mEditDate =  rootView.findViewById(R.id.editDate);
        mEditDate.setFocusable(false);
        mEditDate.setOnClickListener(this::btnDateClick);

        btnDate = rootView.findViewById(R.id.btnDate);
        btnDate.setOnClickListener(this::btnDateClick);

        mCalDateTime = Calendar.getInstance();
        drawDate();
        return rootView;

    }

    public void setOnDateChangedListener(@Nullable OnDateChangedListener l) {
        mOnDateChangedListener = l;
    }

    public void doDateChanged() {
        if (mOnDateChangedListener != null)
            mOnDateChangedListener.onDateChanged(this, mCalDateTime.getTime());
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

    }

    private void drawDate() {
        mEditDate.setText(String.format(cCalendarFormat, mCalDateTime.getTime()));
    }

    public void setDate(Date dt) {
        mCalDateTime.setTime(dt);
        drawDate();
        doDateChanged();
    }

    public void changeDate(int field, int value) {
        mCalDateTime.add( field, value);
        drawDate();
        doDateChanged();
    }

    public Date getDate()
    {
        return mCalDateTime.getTime();
    }

    public void dateToSetStartOfDay()
    {
        mCalDateTime.set(Calendar.HOUR_OF_DAY, 0);
        mCalDateTime.set(Calendar.MINUTE, 0);
        mCalDateTime.set(Calendar.SECOND, 0);
        mCalDateTime.set(Calendar.MILLISECOND, 0);
        drawDate();
    }

    public void dateToSetEndOfDay()
    {
        mCalDateTime.set(Calendar.HOUR_OF_DAY, 0);
        mCalDateTime.set(Calendar.MINUTE, 0);
        mCalDateTime.set(Calendar.SECOND, 0);
        mCalDateTime.set(Calendar.MILLISECOND, 0);
        mCalDateTime.add(Calendar.DATE, 1);
        mCalDateTime.add(Calendar.SECOND, -1);
        drawDate();
    }

    public void btnDateClick(View view) {
        CalendarPicker.show(getContext(), mCalDateTime, (year, month, day) -> {
            mCalDateTime.set(Calendar.YEAR, year);
            mCalDateTime.set(Calendar.MONTH, month);
            mCalDateTime.set(Calendar.DAY_OF_MONTH, day);
            // Граница периода — начало суток. SECOND тоже обнуляем: Calendar.getInstance()
            // приносит текущие секунды, и «с 1 июля» начиналось на полминуты позже полуночи.
            mCalDateTime.set(Calendar.HOUR_OF_DAY, 0);
            mCalDateTime.set(Calendar.MINUTE, 0);
            mCalDateTime.set(Calendar.SECOND, 0);
            mCalDateTime.set(Calendar.MILLISECOND, 0);

            drawDate();
            doDateChanged();
        });
    }
}
