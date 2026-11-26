package com.example.wallettransactions.Activities.Fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.R
import java.util.Calendar
import java.util.Date

class ChooseDateFragment : Fragment() {

    private lateinit var mEditDate: TextView
    private lateinit var mCalDateTime: Calendar
    private lateinit var btnDate: ImageView
    private val cCalendarFormat = "%1\$td %1\$tb '%1\$ty"

    fun setWhiteFonts() {
        mEditDate.setTextColor(ContextCompat.getColor(requireContext(), R.color.graylight))
    }

    fun interface OnDateChangedListener {
        fun onDateChanged(parent: ChooseDateFragment, date: Date)
    }

    private var mOnDateChangedListener: OnDateChangedListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_choose_date, container, false)
        mEditDate = rootView.findViewById(R.id.editDate)
        mEditDate.isFocusable = false
        mEditDate.setOnClickListener { btnDateClick(it) }

        btnDate = rootView.findViewById(R.id.btnDate)
        btnDate.setOnClickListener { btnDateClick(it) }

        mCalDateTime = Calendar.getInstance()
        drawDate()
        return rootView
    }

    fun setOnDateChangedListener(l: OnDateChangedListener?) {
        mOnDateChangedListener = l
    }

    fun doDateChanged() {
        mOnDateChangedListener?.onDateChanged(this, mCalDateTime.time)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    private fun drawDate() {
        mEditDate.text = String.format(cCalendarFormat, mCalDateTime.time)
    }

    fun setDate(dt: Date) {
        mCalDateTime.time = dt
        drawDate()
        doDateChanged()
    }

    fun changeDate(field: Int, value: Int) {
        mCalDateTime.add(field, value)
        drawDate()
        doDateChanged()
    }

    fun getDate(): Date = mCalDateTime.time

    fun dateToSetStartOfDay() {
        mCalDateTime.set(Calendar.HOUR_OF_DAY, 0)
        mCalDateTime.set(Calendar.MINUTE, 0)
        mCalDateTime.set(Calendar.SECOND, 0)
        mCalDateTime.set(Calendar.MILLISECOND, 0)
        drawDate()
    }

    fun dateToSetEndOfDay() {
        mCalDateTime.set(Calendar.HOUR_OF_DAY, 0)
        mCalDateTime.set(Calendar.MINUTE, 0)
        mCalDateTime.set(Calendar.SECOND, 0)
        mCalDateTime.set(Calendar.MILLISECOND, 0)
        mCalDateTime.add(Calendar.DATE, 1)
        mCalDateTime.add(Calendar.SECOND, -1)
        drawDate()
    }

    fun btnDateClick(view: View) {
        CalendarPicker.show(requireContext(), mCalDateTime) { year, month, day ->
            mCalDateTime.set(Calendar.YEAR, year)
            mCalDateTime.set(Calendar.MONTH, month)
            mCalDateTime.set(Calendar.DAY_OF_MONTH, day)
            // Граница периода — начало суток. SECOND тоже обнуляем: Calendar.getInstance()
            // приносит текущие секунды, и «с 1 июля» начиналось на полминуты позже полуночи.
            mCalDateTime.set(Calendar.HOUR_OF_DAY, 0)
            mCalDateTime.set(Calendar.MINUTE, 0)
            mCalDateTime.set(Calendar.SECOND, 0)
            mCalDateTime.set(Calendar.MILLISECOND, 0)

            drawDate()
            doDateChanged()
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): ChooseDateFragment = ChooseDateFragment()
    }
}
