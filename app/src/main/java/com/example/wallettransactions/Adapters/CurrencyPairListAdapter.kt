package com.example.wallettransactions.Adapters

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair
import com.example.wallettransactions.R
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * Row adapter for the "Курсы валют" pair list (`pair_item_layout.xml`) — flags, symbol,
 * plus the pair's latest known rate/date and the % change against the previous known rate.
 */
class CurrencyPairListAdapter(
    private val ctx: Context,
    items: List<CurrencyPair>,
    private val db: SQLiteDatabase?
) : ArrayAdapter<CurrencyPair>(ctx, R.layout.pair_item_layout, items) {

    private val dateFormat = "%1\$td %1\$tb %1\$tY"

    /** Swaps in a new (e.g. filtered) list and redraws. */
    fun setItems(items: List<CurrencyPair>) {
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var view = convertView
        val holder: Holder

        if (view == null) {
            view = LayoutInflater.from(ctx).inflate(R.layout.pair_item_layout, parent, false)
            holder = Holder(
                view.findViewById(R.id.flagA),
                view.findViewById(R.id.flagB),
                view.findViewById(R.id.pairName),
                view.findViewById(R.id.pairDate),
                view.findViewById(R.id.pairRate),
                view.findViewById(R.id.pairDelta)
            )
            view.tag = holder
        } else {
            holder = view.tag as Holder
        }

        val cp = getItem(position) ?: return view!!

        holder.flagA.setImageBitmap(cp.baseCurrency!!.icon!!.bitmap)
        holder.flagB.setImageBitmap(cp.quoteCurrency!!.icon!!.bitmap)
        holder.pairName.text = cp.symbol

        // Full history, sorted DESC by date (see WalletDbLoader.LoadCurrencyPairRateList) — the
        // first two rows are the latest rate and the one right before it.
        val rates = WalletDbLoader.LoadCurrencyPairRateList(db!!, cp.symbol)

        if (rates.isEmpty()) {
            holder.pairDate.setText(R.string.pair_no_rate)
            holder.pairRate.text = "—"
            holder.pairDelta.text = ""
        } else {
            val latest = rates[0]
            holder.pairDate.text = String.format(dateFormat, latest.first)
            holder.pairRate.text = AdapterUtils.getFormat(cp.precision).format(latest.second)

            if (rates.size > 1 && rates[1].second != 0.0) {
                val prev: Double = rates[1].second
                val pct = (latest.second - prev) / prev * 100.0
                val pctFmt = DecimalFormat("0.00")
                val sign = if (pct >= 0) "+" else "-"
                holder.pairDelta.text = sign + pctFmt.format(abs(pct)) + "%"
                holder.pairDelta.setTextColor(
                    ctx.resources.getColor(
                        if (pct >= 0) R.color.colorprofit else R.color.colorexpanse
                    )
                )
            } else {
                holder.pairDelta.text = ""
            }
        }

        return view!!
    }

    private class Holder(
        val flagA: ImageView, val flagB: ImageView,
        val pairName: TextView, val pairDate: TextView, val pairRate: TextView, val pairDelta: TextView
    )
}
