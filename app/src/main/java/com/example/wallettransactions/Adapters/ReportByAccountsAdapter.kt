package com.example.wallettransactions.Adapters

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DataItems.ReportItems.ReportByAccount
import com.example.wallettransactions.R
import kotlin.math.abs

/**
 * Строки отчёта по категориям (report_byaccount_item_layout.xml).
 *
 * Сортировка по убыванию суммы и полоса доли — часть макета: без порядка
 * полосы не читаются. Ширина полосы берётся от самой большой категории
 * (у верхней строки полоса всегда полная), а процент — от общей суммы отчёта.
 */
class ReportByAccountsAdapter(
    private val ctx: Context,
    private val report: ReportByAccount,
    private val fIconList: IconList?,
    private val mCurrencyEq: Currency?,
    private val barColorRes: Int
) : ArrayAdapter<AccountItem>(ctx, R.layout.report_byaccount_item_layout, sortedAccounts(report)) {

    /** Сумма в эквиваленте по категории; null — эквивалент посчитать не удалось. */
    private val sums: MutableMap<AccountItem, Double?> = HashMap()

    /** Была ли конвертация — определяет знак ≈ перед суммой. */
    private val converted: MutableMap<AccountItem, Boolean> = HashMap()

    private var maxAbs = 0.0
    private var totalAbs = 0.0

    init {
        for (i in 0 until count) {
            val account = getItem(i)!!
            val t = totalsFor(report, account, mCurrencyEq)
            sums[account] = t.sumEq
            converted[account] = t.converted

            if (t.sumEq != null) {
                val abs = abs(t.sumEq!!)
                totalAbs += abs
                if (abs > maxAbs) maxAbs = abs
            }
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val listItem: View
        val holder: Holder

        if (convertView == null) {
            listItem = LayoutInflater.from(ctx).inflate(R.layout.report_byaccount_item_layout, parent, false)

            holder = Holder(
                listItem.findViewById(R.id.textName),
                listItem.findViewById(R.id.textBalance),
                listItem.findViewById(R.id.textShare),
                listItem.findViewById(R.id.imageView),
                listItem.findViewById(R.id.ListViewFc),
                listItem.findViewById(R.id.barFill),
                listItem.findViewById(R.id.barRest)
            )
            listItem.tag = holder
        } else {
            listItem = convertView
            holder = convertView.tag as Holder
        }

        val chargeItem = getItem(position)!!

        val curList = report.ReportAccounts[chargeItem]!!

        holder.ListViewItem.removeAllViews()
        for (cur in curList) {
            if (cur.currency == mCurrencyEq && (curList.size == 1))
                break

            val listSubItems = LayoutInflater.from(ctx).inflate(R.layout.accountoperation_value_currency_item_layout, null)

            val imgSb: ImageView = listSubItems.findViewById(R.id.imageAccountView)
            val txSb: TextView = listSubItems.findViewById(R.id.textAmount)
            val curSb: TextView = listSubItems.findViewById(R.id.textCurr)

            imgSb.setImageBitmap(cur.currency!!.icon!!.bitmap)
            txSb.text = cur.amount.toString()
            curSb.text = cur.currency!!.symbol
            holder.ListViewItem.addView(listSubItems)
        }

        val sumEq = sums[chargeItem]
        val wasConverted = converted[chargeItem] == true

        // ~ means the figure could not be produced, ≈ that it was converted
        val aproxSign = if (sumEq == null) "~" else if (wasConverted) "≈" else ""

        if (sumEq == null) {
            holder.TextBalance.text = "$aproxSign?"
            holder.TextBalance.setTextColor(ContextCompat.getColor(ctx, R.color.colortransfer))
        } else {
            holder.TextBalance.text = aproxSign + AdapterUtils
                .getFormat(mCurrencyEq?.precision ?: 2)
                .format(sumEq)

            val operating = chargeItem.isOperatingAccount
            if ((operating && sumEq > 0) || (!operating && sumEq < 0)) {
                holder.TextBalance.setTextColor(ContextCompat.getColor(ctx, R.color.colorprofit))
            } else if ((operating && sumEq < 0) || (!operating && sumEq > 0)) {
                holder.TextBalance.setTextColor(ContextCompat.getColor(ctx, R.color.colorexpanse))
            } else
                holder.TextBalance.setTextColor(ContextCompat.getColor(ctx, R.color.colortransfer))
        }

        bindShare(holder, sumEq)

        holder.TvName.text = chargeItem.displayName
        holder.Img.setImageBitmap(chargeItem.icon!!.bitmap)

        return listItem
    }

    private fun bindShare(holder: Holder, sumEq: Double?) {
        val abs = if (sumEq == null) 0.0 else abs(sumEq)

        val share = if (maxAbs > 0) abs / maxAbs else 0.0
        setWeight(holder.BarFill, share.toFloat())
        setWeight(holder.BarRest, (1.0 - share).toFloat())
        holder.BarFill.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, barColorRes))

        if (sumEq == null || totalAbs <= 0) {
            holder.TextShare.text = ""
        } else {
            holder.TextShare.text = Math.round(abs / totalAbs * 100).toString() + "%"
        }
    }

    private class Totals {
        var sumEq: Double? = null
        var converted = false
    }

    internal class Holder(
        @JvmField var TvName: TextView,
        @JvmField var TextBalance: TextView,
        @JvmField var TextShare: TextView,
        @JvmField var Img: ImageView,
        @JvmField var ListViewItem: LinearLayout,
        @JvmField var BarFill: View,
        @JvmField var BarRest: View
    )

    companion object {
        /** Категории по убыванию суммы — порядок HashMap для диаграммы не годится. */
        private fun sortedAccounts(report: ReportByAccount): ArrayList<AccountItem> {
            val accounts = ArrayList(report.ReportAccounts.keys)

            val weight: MutableMap<AccountItem, Double> = HashMap()
            for (account in accounts) {
                val t = totalsFor(report, account, null)
                weight[account] = if (t.sumEq == null) -1.0 else abs(t.sumEq!!)
            }

            accounts.sortWith { a, b -> weight[b]!!.compareTo(weight[a]!!) }
            return accounts
        }

        private fun totalsFor(report: ReportByAccount, account: AccountItem, currencyEq: Currency?): Totals {
            val result = Totals()
            val curList = report.ReportAccounts[account] ?: return result

            var sum = 0.0
            for (cur in curList) {
                if (currencyEq != null && cur.currency != currencyEq) result.converted = true

                if (cur.amountEq == null) return result // sumEq остаётся null
                sum += cur.amountEq!!
            }
            result.sumEq = sum
            return result
        }

        private fun setWeight(view: View, weight: Float) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.weight = weight
            view.layoutParams = params
        }
    }
}
