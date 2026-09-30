package com.example.wallettransactions.Activities.Fragments;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.R;

import java.util.Calendar;
import java.util.Date;

public class ChooseDatePeriodFragment extends Fragment {

    public static final String ARG_PARAM_DATE_FROM = "paramDateFrom";
    public static final String ARG_PARAM_DATE_TO = "paramDateTo";


    private ChooseDateFragment fragmentFrom;
    private ChooseDateFragment fragmentTo;


    public Date getDateFrom()
    {
        return fragmentFrom.getDate();
    }

    public Date getDateTo()
    {
        return fragmentTo.getDate();
    }

    public void setWhiteFonts(){
        fragmentFrom.setWhiteFonts();
        fragmentTo.setWhiteFonts();

    }

    public void setDateFrom(Date date)
    {
        fragmentFrom.setDate(date);
        fragmentFrom.dateToSetStartOfDay();
    }


    public void changeDateFrom(int field, int value) {
        fragmentFrom.changeDate( field, value);
        fragmentFrom.dateToSetStartOfDay();
    }

    public void changeDateTo(int field, int value) {
        fragmentTo.changeDate( field, value);
        fragmentTo.dateToSetEndOfDay();
    }

    public void setDateTo(Date date)
    {
        fragmentTo.setDate(date);
        fragmentTo.dateToSetEndOfDay();
    }

    public interface OnDatePeriodChangedListener {
        void onDateChanged(ChooseDatePeriodFragment parent, Date dateFrom, Date dateTo);
    }

    private OnDatePeriodChangedListener mOnDatePeriodChangedListener;

    public void setOnDatePeriodChangedListener(@Nullable OnDatePeriodChangedListener l) {
        mOnDatePeriodChangedListener = l;
    }

    public void doDatePeriodChanged() {
        if (mOnDatePeriodChangedListener != null)
            mOnDatePeriodChangedListener.onDateChanged(this, getDateFrom(), getDateTo());
    }

    public static ChooseDateFragment newInstance() {
        return new ChooseDateFragment();
    }

    public ChooseDatePeriodFragment() {
        // Required empty public constructor
    }

    public static ChooseDatePeriodFragment newInstance(Date dateFrom, Date dateTo) {
        ChooseDatePeriodFragment fragment = new ChooseDatePeriodFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM_DATE_FROM, DateUtils.DateToStr(dateFrom));
        args.putString(ARG_PARAM_DATE_TO, DateUtils.DateToStr(dateTo));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            setDateFrom(DateUtils.StrToDate(getArguments().getString(ARG_PARAM_DATE_FROM)));
            setDateTo(DateUtils.StrToDate(getArguments().getString(ARG_PARAM_DATE_TO)));
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_choose_date_period, container, false);

        FragmentManager fm = this.getChildFragmentManager();

        fragmentFrom  = (ChooseDateFragment)fm.findFragmentById(R.id.fragmentDataFrom);
        fragmentFrom.setOnDateChangedListener(new ChooseDateFragment.OnDateChangedListener() {
            @Override
            public void onDateChanged(ChooseDateFragment parent, Date date) {
                if (date.after(getDateTo())){
                    setDateTo(date);
                }
                fragmentFrom.dateToSetStartOfDay();
                doDatePeriodChanged();
            }
        });

        fragmentTo  = (ChooseDateFragment)fm.findFragmentById(R.id.fragmentDataTo);
        fragmentTo.setOnDateChangedListener(new ChooseDateFragment.OnDateChangedListener() {
            @Override
            public void onDateChanged(ChooseDateFragment parent, Date date) {
                if (getDateFrom().after(date)){
                    setDateFrom(date);
                }

                fragmentTo.dateToSetEndOfDay();
                doDatePeriodChanged();
            }
        });

        ImageButton btnPrev = rootView.findViewById(R.id.btnPrev);
        ImageButton btnNext = rootView.findViewById(R.id.btnNext);

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fragmentFrom.changeDate(Calendar.MONTH, +1);
                fragmentTo.changeDate(Calendar.MONTH, +1);
            }
        });

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fragmentFrom.changeDate(Calendar.MONTH, -1);
                fragmentTo.changeDate(Calendar.MONTH, -1);
            }
        });
        return rootView;
    }
}