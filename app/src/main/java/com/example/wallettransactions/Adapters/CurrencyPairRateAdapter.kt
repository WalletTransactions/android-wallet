package com.example.wallettransactions.Adapters

import android.content.Context
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.R
import java.util.Date
import java.util.Locale

class CurrencyPairRateAdapter(
    private val ctx: Context,
    private val currencyPairRate: List<Pair<Date, Double>>,
    private val precision: Int
) : ArrayAdapter<Pair<Date, Double>>(ctx, R.layout.currencypair_rate_list_content, currencyPairRate) {

    private var currencyPairId = 0
    private var listener: OnRateClickListener? = null

    private val dateFormat = "%1\$td %1\$tb %1\$tY"

    fun interface OnRateClickListener {
        fun onRateClick(rateDate: Date, rateValue: Double)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val listItem: View
        val holder: Holder

        if (convertView == null) {
            listItem = LayoutInflater.from(ctx).inflate(R.layout.currencypair_rate_list_content, parent, false)

            holder = Holder(
                listItem.findViewById(R.id.textDate),
                listItem.findViewById(R.id.textRate),
                listItem.findViewById(R.id.textDelta)
            )
            listItem.tag = holder
        } else {
            listItem = convertView
            holder = convertView.tag as Holder
        }

        val rate = getItem(position)!!

        holder.TextRate.text = AdapterUtils.getFormat(precision).format(rate.second)
        holder.TvDate.text = String.format(dateFormat, rate.first)

        // Список отсортирован от новых к старым, поэтому предыдущий курс — следующий по списку.
        val previous: Double? = if (position + 1 < count) getItem(position + 1)!!.second else null
        holder.TextDelta.text = formatDelta(previous, rate.second)
        holder.TextDelta.setTextColor(
            ContextCompat.getColor(ctx, deltaColorRes(previous, rate.second))
        )

        val l = listener
        if (l != null) {
            listItem.setOnClickListener { l.onRateClick(rate.first, rate.second) }
        }

        return listItem
    }

    fun setOnRateClickListener(listener: OnRateClickListener?) {
        this.listener = listener
    }

    fun setCurrencyPairId(id: Int) {
        this.currencyPairId = id
    }

    fun getPrecision(): Int = precision

    internal class Holder(
        @JvmField var TvDate: TextView,
        @JvmField var TextRate: TextView,
        @JvmField var TextDelta: TextView
    )

    companion object {
        /**
         * Изменение курса в процентах. Пустая строка, если сравнивать не с чем —
         * у самой старой записи предыдущего курса нет.
         */
        @JvmStatic
        fun formatDelta(previous: Double?, current: Double): String {
            if (previous == null || previous == 0.0) return ""
            return String.format(Locale.ROOT, "%+.2f%%", (current - previous) / previous * 100)
        }

        @JvmStatic
        fun deltaColorRes(previous: Double?, current: Double): Int {
            if (previous == null || previous == 0.0 || current == previous) return R.color.colortransfer
            return if (current > previous) R.color.colorprofit else R.color.colorexpanse
        }
    }
}
