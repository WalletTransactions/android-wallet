package com.example.wallettransactions.Activities.Fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.R
import java.util.Calendar
import java.util.Date

class ChooseDatePeriodFragment : Fragment() {

    private lateinit var fragmentFrom: ChooseDateFragment
    private lateinit var fragmentTo: ChooseDateFragment

    fun getDateFrom(): Date = fragmentFrom.getDate()

    fun getDateTo(): Date = fragmentTo.getDate()

    fun setWhiteFonts() {
        fragmentFrom.setWhiteFonts()
        fragmentTo.setWhiteFonts()
    }

    fun setDateFrom(date: Date) {
        fragmentFrom.setDate(date)
        fragmentFrom.dateToSetStartOfDay()
    }

    fun changeDateFrom(field: Int, value: Int) {
        fragmentFrom.changeDate(field, value)
        fragmentFrom.dateToSetStartOfDay()
    }

    fun changeDateTo(field: Int, value: Int) {
        fragmentTo.changeDate(field, value)
        fragmentTo.dateToSetEndOfDay()
    }

    fun setDateTo(date: Date) {
        fragmentTo.setDate(date)
        fragmentTo.dateToSetEndOfDay()
    }

    fun interface OnDatePeriodChangedListener {
        fun onDateChanged(parent: ChooseDatePeriodFragment, dateFrom: Date, dateTo: Date)
    }

    private var mOnDatePeriodChangedListener: OnDatePeriodChangedListener? = null

    fun setOnDatePeriodChangedListener(l: OnDatePeriodChangedListener?) {
        mOnDatePeriodChangedListener = l
    }

    fun doDatePeriodChanged() {
        mOnDatePeriodChangedListener?.onDateChanged(this, getDateFrom(), getDateTo())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        if (args != null) {
            setDateFrom(DateUtils.StrToDate(args.getString(ARG_PARAM_DATE_FROM)!!))
            setDateTo(DateUtils.StrToDate(args.getString(ARG_PARAM_DATE_TO)!!))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        val rootView = inflater.inflate(R.layout.fragment_choose_date_period, container, false)

        val fm = childFragmentManager

        fragmentFrom = fm.findFragmentById(R.id.fragmentDataFrom) as ChooseDateFragment
        fragmentFrom.setOnDateChangedListener { _, date ->
            if (date.after(getDateTo())) {
                setDateTo(date)
            }
            fragmentFrom.dateToSetStartOfDay()
            doDatePeriodChanged()
        }

        fragmentTo = fm.findFragmentById(R.id.fragmentDataTo) as ChooseDateFragment
        fragmentTo.setOnDateChangedListener { _, date ->
            if (getDateFrom().after(date)) {
                setDateFrom(date)
            }

            fragmentTo.dateToSetEndOfDay()
            doDatePeriodChanged()
        }

        val btnPrev: ImageButton = rootView.findViewById(R.id.btnPrev)
        val btnNext: ImageButton = rootView.findViewById(R.id.btnNext)

        btnNext.setOnClickListener {
            fragmentFrom.changeDate(Calendar.MONTH, +1)
            fragmentTo.changeDate(Calendar.MONTH, +1)
        }

        btnPrev.setOnClickListener {
            fragmentFrom.changeDate(Calendar.MONTH, -1)
            fragmentTo.changeDate(Calendar.MONTH, -1)
        }
        return rootView
    }

    companion object {
        const val ARG_PARAM_DATE_FROM = "paramDateFrom"
        const val ARG_PARAM_DATE_TO = "paramDateTo"

        @JvmStatic
        fun newInstance(): ChooseDateFragment = ChooseDateFragment()

        @JvmStatic
        fun newInstance(dateFrom: Date, dateTo: Date): ChooseDatePeriodFragment {
            val fragment = ChooseDatePeriodFragment()
            val args = Bundle()
            args.putString(ARG_PARAM_DATE_FROM, DateUtils.DateToStr(dateFrom))
            args.putString(ARG_PARAM_DATE_TO, DateUtils.DateToStr(dateTo))
            fragment.arguments = args
            return fragment
        }
    }
}
