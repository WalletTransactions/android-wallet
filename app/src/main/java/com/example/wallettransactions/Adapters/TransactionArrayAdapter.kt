package com.example.wallettransactions.Adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Data.DebtLabels
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.Operations.EquivalentResult
import com.example.wallettransactions.DataItems.Operations.OperationItem
import com.example.wallettransactions.DataItems.Operations.OperationList
import com.example.wallettransactions.R
import java.util.Calendar
import java.util.Date
import java.util.Hashtable
import kotlin.math.abs

class TransactionArrayAdapter(
    private val ctx: Context,
    private val tranList: OperationList,
    private var currencyEq: Currency?,
    groupByTime: GroupByTime
) : ArrayAdapter<OperationItem>(ctx, R.layout.transaction_item_layout, tranList) {

    enum class GroupByTime { day, week, month }

    private lateinit var dateComparer: DateComparer

    private val groupedItems = GroupedItems()

    private abstract class DateComparer {
        private val cal1: Calendar = Calendar.getInstance()
        private val cal2: Calendar = Calendar.getInstance()

        protected fun equalsByType(dt1: Date, dt2: Date, compareBy: IntArray): Boolean {
            cal1.time = dt1
            cal2.time = dt2

            var res = true

            for (itm in compareBy) {
                res = res && cal1.get(itm) == cal2.get(itm)
            }
            return res
        }

        abstract fun isTheSameInterval(dt1: Date, dt2: Date): Boolean
        abstract fun getDateName(dt: Date): String
    }

    private class DateComparerDay : DateComparer() {
        override fun isTheSameInterval(dt1: Date, dt2: Date): Boolean {
            return equalsByType(dt1, dt2, intArrayOf(Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH))
        }

        override fun getDateName(dt: Date): String = dayOrWeekName(dt)
    }

    private class DateComparerMonth : DateComparer() {
        override fun getDateName(dt: Date): String {
            var dateFormat = "%1\$tb"
            val diffDays = ((Date().time - dt.time) / (24 * 60 * 60 * 1000)).toInt()

            if (diffDays > 365)
                dateFormat = " %1\$tY $dateFormat"

            return String.format(dateFormat, dt.time)
        }

        override fun isTheSameInterval(dt1: Date, dt2: Date): Boolean {
            return equalsByType(dt1, dt2, intArrayOf(Calendar.YEAR, Calendar.MONTH))
        }
    }

    private class DateComparerWeek : DateComparer() {
        override fun isTheSameInterval(dt1: Date, dt2: Date): Boolean {
            return equalsByType(dt1, dt2, intArrayOf(Calendar.YEAR, Calendar.WEEK_OF_YEAR))
        }

        override fun getDateName(dt: Date): String = dayOrWeekName(dt)
    }

    private inner class GroupedItems {
        private val groupedItems = Hashtable<OperationItem, EquivalentResult>()

        fun addGroupedItem(operationItem: OperationItem): EquivalentResult {
            // Движение всей операции, а не одной проводки: связанные ноги и комиссия
            // своей строки в ленте не имеют, но в итог дня входить обязаны.
            val res = EquivalentResult(
                operationItem.movementAmountEq,
                operationItem.isApproxEq(currencyEq)
            )
            groupedItems[operationItem] = res
            return res
        }

        fun clear() {
            groupedItems.clear()
        }

        fun getGroupedResult(operationItem: OperationItem): EquivalentResult? {
            return groupedItems[operationItem]
        }
    }

    fun setCurrencyEq(currencyEq: Currency?) {
        this.currencyEq = currencyEq
    }

    init {
        setGroupByTime(groupByTime)
        //   values.onListChanged.add(this);
    }

    fun setGroupByTime(groupByTime: GroupByTime) {
        dateComparer = if (groupByTime == GroupByTime.month)
            DateComparerMonth()
        else if (groupByTime == GroupByTime.week)
            DateComparerWeek()
        else
            DateComparerDay()
        prepareGroupedItems()
        super.notifyDataSetChanged()
    }

    override fun notifyDataSetChanged() {
        prepareGroupedItems()
        super.notifyDataSetInvalidated()
    }

    private fun prepareGroupedItems() {
        groupedItems.clear()
        var operationGroupItem: OperationItem? = null

        var currentGroupedResult: EquivalentResult? = null

        for (item in tranList) {
            if (operationGroupItem == null || !dateComparer.isTheSameInterval(item.Date, operationGroupItem.Date)) {
                currentGroupedResult = groupedItems.addGroupedItem(item)
                operationGroupItem = item
            } else {
                currentGroupedResult!!.addAmount(item.movementAmountEq, item.isApproxEq(currencyEq))
            }
        }
    }

    fun setGroupByMonth() {
        setGroupByTime(GroupByTime.month)
    }

    fun setGroupByDay() {
        setGroupByTime(GroupByTime.day)
    }

    fun setGroupByWewek() {
        setGroupByTime(GroupByTime.week)
    }

    private class Holder(
        var tvAccountToName: TextView,
        var tvAmount: TextView,
        var tvComment: TextView,
        var imgFrom: ImageView,
        var imgAcc: ImageView,
        var imgAccTo: ImageView,
        var layoutDate: LinearLayout,
        var tvDate: TextView,
        var tvAmountEq: TextView,
        /** Суммы связанных проводок: зачисление во второй валюте и комиссия. */
        var tvAmountTo: TextView,
        var tvCommission: TextView
    )

    /**
     * Суммы связанных проводок в той же карточке: зачисление в валюте получателя —
     * зелёным (у валютного перевода оно отличается от списания), ниже — комиссия.
     * Обе строки прячутся, когда связанных проводок нет: строки списка переиспользуются.
     */
    private fun bindLinkedAmounts(holder: Holder, tran: OperationItem) {
        val leg = tran.LinkedLeg
        if (leg != null) {
            holder.tvAmountTo.text = formatAmount(leg.Amount, leg.currency)
            holder.tvAmountTo.visibility = View.VISIBLE
        } else {
            holder.tvAmountTo.visibility = View.GONE
        }

        val fee = tran.LinkedCommission
        if (fee != null) {
            holder.tvCommission.text = context.getString(
                R.string.operation_commission_amount,
                formatAmount(fee.Amount, fee.currency)
            )
            holder.tvCommission.visibility = View.VISIBLE
        } else {
            holder.tvCommission.visibility = View.GONE
        }
    }

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int {
        val tran = tranList[position]
        if (tran.isRevaluation) return TYPE_REVALUATION
        return if (isTransfer(tran)) TYPE_TRANSFER else TYPE_DEFAULT
    }

    private class RevaluationHolder(v: View) {
        val layoutDate: LinearLayout = v.findViewById(R.id.LayoutDate)
        val tvDate: TextView = v.findViewById(R.id.textDate)
        val tvAmountEq: TextView = v.findViewById(R.id.textAmountEq)
        val imgAsset: ImageView = v.findViewById(R.id.revaluationAssetIcon)
        val tvAsset: TextView = v.findViewById(R.id.revaluationAsset)
        val tvLabel: TextView = v.findViewById(R.id.revaluationLabel)
        val tvAmount: TextView = v.findViewById(R.id.revaluationAmount)
    }

    /**
     * Переоценка: название актива, подпись «Переоценка» (с комментарием, если есть), сумма
     * со знаком «+» вверх и «−» вниз цветом перевода — это не доход и не расход.
     */
    private fun getRevaluationView(tran: OperationItem, convertView: View?, parent: ViewGroup?): View {
        val listItem: View
        val holder: RevaluationHolder
        if (convertView == null) {
            listItem = LayoutInflater.from(ctx).inflate(R.layout.transaction_item_revaluation, parent, false)
            holder = RevaluationHolder(listItem)
            listItem.tag = holder
        } else {
            listItem = convertView
            holder = listItem.tag as RevaluationHolder
        }

        bindDayHeader(holder.layoutDate, holder.tvDate, holder.tvAmountEq, tran)

        val up = tran.isRevaluationUp
        val asset = if (up) tran.AccTo else tran.AccFrom
        holder.imgAsset.setImageBitmap(asset.icon!!.bitmap)
        holder.tvAsset.text = asset.displayName
        val hasComment = tran.Comment != null && tran.Comment!!.trim().isNotEmpty()
        holder.tvLabel.text = if (hasComment) ctx.getString(R.string.row_revaluation_comment, tran.Comment)
        else ctx.getString(R.string.row_revaluation)
        holder.tvAmount.text = revaluationAmountText(tran.Amount, tran.currency, up)
        return listItem
    }

    /** Шапка периода: видна только у первой операции группы, у остальных скрыта. */
    private fun bindDayHeader(
        layoutDate: LinearLayout, tvDate: TextView, tvAmountEq: TextView,
        tran: OperationItem
    ) {
        val groupedResult = groupedItems.getGroupedResult(tran)

        if (groupedResult == null) {
            layoutDate.visibility = View.GONE
            return
        }

        layoutDate.visibility = View.VISIBLE
        tvDate.text = dateComparer.getDateName(tran.Date)

        // null — не пересчиталось вообще ничего: числа нет, показываем «?».
        // Частично пересчитанный итог печатает setAmountText с пометкой «~».
        if (groupedResult.totalAmountEq == null) {
            tvAmountEq.setTextColor(ContextCompat.getColor(ctx, R.color.colortransfer))
            tvAmountEq.text = if (groupedResult.isIncomplete) "~?" else "?"
        } else {
            AdapterUtils.setAmountText(
                tvAmountEq, groupedResult.totalAmountEq, currencyEq,
                groupedResult.approxEq, groupedResult.isIncomplete
            )
        }
    }

    private class TransferHolder(v: View) {
        val layoutDate: LinearLayout = v.findViewById(R.id.LayoutDate)
        val tvDate: TextView = v.findViewById(R.id.textDate)
        val tvAmountEq: TextView = v.findViewById(R.id.textAmountEq)
        val imgFrom: ImageView = v.findViewById(R.id.transferFromIcon)
        val imgTo: ImageView = v.findViewById(R.id.transferToIcon)
        val tvRoute: TextView = v.findViewById(R.id.transferRoute)
        val tvComment: TextView = v.findViewById(R.id.transferComment)
        val tvFee: TextView = v.findViewById(R.id.transferFee)
        val tvOut: TextView = v.findViewById(R.id.transferOut)
        val tvIn: TextView = v.findViewById(R.id.transferIn)
    }

    /** «Дал в долг · Вася» или null, если перевод не с займом. */
    private fun debtLabelText(tran: OperationItem): String? {
        val debtLabel = tran.DebtLabel ?: return null
        val res = when (debtLabel) {
            DebtLabels.Label.GAVE -> R.string.debt_label_gave
            DebtLabels.Label.TOOK -> R.string.debt_label_took
            DebtLabels.Label.REPAID -> R.string.debt_label_repaid
            else -> R.string.debt_label_returned
        }
        val label = ctx.getString(res)
        return if (tran.DebtCounterparty == null) label
        else ctx.getString(R.string.debt_label_with, label, tran.DebtCounterparty)
    }

    private fun getTransferView(tran: OperationItem, convertView: View?, parent: ViewGroup?): View {
        val listItem: View
        val holder: TransferHolder

        if (convertView == null) {
            listItem = LayoutInflater.from(ctx)
                .inflate(R.layout.transaction_item_transfer, parent, false)
            holder = TransferHolder(listItem)
            listItem.tag = holder
        } else {
            listItem = convertView
            holder = listItem.tag as TransferHolder
        }

        bindDayHeader(holder.layoutDate, holder.tvDate, holder.tvAmountEq, tran)

        val accTo = tran.destinationAccount

        holder.imgFrom.setImageBitmap(tran.AccFrom.icon!!.bitmap)
        holder.imgTo.setImageBitmap(accTo.icon!!.bitmap)
        holder.tvRoute.text = tran.AccFrom.displayName + " → " + accTo.displayName

        // Пустой комментарий занимал бы целую строку высоты ни за чем.
        val hasComment = tran.Comment != null && tran.Comment!!.trim().isNotEmpty()
        // Заём (debts, D6): «Дал в долг · Вася» перед комментарием — подпись считается при
        // загрузке ленты (WalletDbLoader.AttachDebtLabels).
        val debt = debtLabelText(tran)
        val commentLine: String? = if (debt == null) tran.Comment
        else if (hasComment) debt + " — " + tran.Comment!!.trim() else debt
        holder.tvComment.text = commentLine
        holder.tvComment.visibility =
            if (commentLine != null && commentLine.trim().isNotEmpty()) View.VISIBLE else View.GONE

        // Заём: «дал в долг» и «вернул долг» — деньги ушли (красный, минус); «взял в долг» и
        // «мне вернули» — пришли (зелёный). Перевод между кошельками в одной валюте деньги
        // не тратит и не приносит — нейтральный цвет без знака; с конвертацией (LinkedLeg)
        // списание остаётся красным, а зачисление ниже — зелёным.
        if (tran.DebtLabel != null) {
            val moneyOut = tran.DebtLabel == DebtLabels.Label.GAVE
                    || tran.DebtLabel == DebtLabels.Label.REPAID
            if (moneyOut) {
                AdapterUtils.setAmountExpanseText(holder.tvOut, tran.Amount, tran.currency)
            } else {
                AdapterUtils.setAmountDepositText(holder.tvOut, tran.Amount, tran.currency)
            }
        } else if (tran.LinkedLeg == null) {
            AdapterUtils.setAmountTransferText(holder.tvOut, tran.Amount, tran.currency)
        } else {
            AdapterUtils.setAmountExpanseText(holder.tvOut, tran.Amount, tran.currency)
        }

        // Зачисление показываем только когда оно отличается от списания, то есть у
        // перевода между разными валютами: у одновалютного это то же число второй раз.
        val leg = tran.LinkedLeg
        if (leg != null) {
            AdapterUtils.setAmountDepositText(holder.tvIn, leg.Amount, leg.currency)
            holder.tvIn.visibility = View.VISIBLE
        } else {
            holder.tvIn.visibility = View.GONE
        }

        val fee = tran.LinkedCommission
        if (fee != null) {
            holder.tvFee.text = context.getString(
                R.string.operation_commission_amount,
                formatAmount(fee.Amount, fee.currency)
            )
            holder.tvFee.visibility = View.VISIBLE
        } else {
            holder.tvFee.visibility = View.GONE
        }

        return listItem
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tran = tranList[position]

        if (tran.isRevaluation) return getRevaluationView(tran, convertView, parent)
        if (isTransfer(tran)) return getTransferView(tran, convertView, parent)

        val listItem: View
        val holder: Holder

        if (convertView == null) {
            listItem = LayoutInflater.from(ctx).inflate(R.layout.transaction_item_layout, parent, false)

            holder = Holder(
                listItem.findViewById(R.id.accountToName),
                listItem.findViewById(R.id.amount),
                listItem.findViewById(R.id.comment),
                listItem.findViewById(R.id.imageFromView),
                listItem.findViewById(R.id.imageAccountView),
                listItem.findViewById(R.id.imageToView),
                listItem.findViewById(R.id.LayoutDate),
                listItem.findViewById(R.id.textDate),
                listItem.findViewById(R.id.textAmountEq),
                listItem.findViewById(R.id.amountTo),
                listItem.findViewById(R.id.commission)
            )
            listItem.tag = holder
        } else {
            listItem = convertView
            holder = convertView.tag as Holder
        }

        bindDayHeader(holder.layoutDate, holder.tvDate, holder.tvAmountEq, tran)

        holder.tvComment.text = tran.Comment
        holder.imgAccTo.visibility = View.GONE

        bindLinkedAmounts(holder, tran)

        // Переводы сюда не доходят — у них своя строка (getTransferView).
        if (tran.isOperatingAccountFrom) {
            AdapterUtils.setAmountExpanseText(holder.tvAmount, tran.Amount, tran.currency)

            holder.tvAccountToName.text = tran.AccTo.displayName
            holder.imgAcc.setImageBitmap(tran.AccTo.icon!!.bitmap)
            holder.imgFrom.setImageBitmap(tran.AccFrom.icon!!.bitmap)
        } else //if (tran.AccFrom instanceof AccountOperatingItem)
        {
            AdapterUtils.setAmountDepositText(holder.tvAmount, tran.Amount, tran.currency)

            holder.tvAccountToName.text = tran.AccFrom.displayName
            holder.imgAcc.setImageBitmap(tran.AccFrom.icon!!.bitmap)
            holder.imgFrom.setImageBitmap(tran.AccTo.icon!!.bitmap)
        }

        return listItem
    }

    companion object {
        // ── Два вида строк: перевод со счёта на счёт и всё остальное ──────────────

        private const val TYPE_DEFAULT = 0
        private const val TYPE_TRANSFER = 1

        /** Переоценка капитального актива (capital-assets) — своя строка, см. getRevaluationView. */
        private const val TYPE_REVALUATION = 2

        /** Название дня/недели в шапке группы; у дневной и недельной группировки оно общее. */
        private fun dayOrWeekName(dt: Date): String {
            var dateFormat = "%1\$tB"
            val diffDays = ((Date().time - dt.time) / (24 * 60 * 60 * 1000)).toInt()

            dateFormat = if (diffDays == 1)
                "Yesterday"
            else if (diffDays == 0)
                "Today"
            else if (diffDays < 7)
                "%1\$ta, %1\$td $dateFormat"
            else
                "%1\$td $dateFormat"

            if (diffDays > 365)
                dateFormat = "$dateFormat %1\$tY"

            return String.format(dateFormat, dt.time)
        }

        private fun formatAmount(amount: Double, currency: Currency?): String {
            val precision = currency?.precision ?: 2
            val symbol = currency?.symbol ?: ""
            return AdapterUtils.getFormat(precision).format(amount) + " " + symbol
        }

        /**
         * Перевод со счёта на счёт: либо обе стороны проводки — кошельки, либо это валютный
         * перевод, который в базе лежит двумя проводками через категорию-мост.
         *
         * Старые импортированные валютные переводы связей не имеют, поэтому под условие не
         * попадают и рисуются обычной строкой — как две отдельные операции, чем они в базе
         * и являются.
         */
        private fun isTransfer(tran: OperationItem): Boolean {
            return tran.isCrossCurrencyTransfer
                    || (tran.isOperatingAccountFrom && tran.isOperatingAccountTo)
        }

        /** «+2 000 000.00₽» / «−150 000.00₽» — знак по направлению переоценки. */
        @JvmStatic
        fun revaluationAmountText(amount: Double, currency: Currency?, up: Boolean): String {
            val precision = currency?.precision ?: 2
            val symbol = currency?.symbol ?: ""
            return (if (up) "+" else "−") + AdapterUtils.getFormat(precision).format(abs(amount)) + symbol
        }
    }
}
