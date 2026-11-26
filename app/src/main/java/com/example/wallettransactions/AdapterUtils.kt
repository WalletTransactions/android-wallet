package com.example.wallettransactions

import android.icu.text.DecimalFormat
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.Operations.EquivalentResult
import java.math.BigDecimal
import kotlin.math.abs

object AdapterUtils {
    private val formatCache: MutableMap<Int, DecimalFormat> = HashMap()

    /**
     * Число для поля ввода и короткой подписи: без экспоненты и лишних нулей
     * (10000000.0 → «10000000», 100.50 → «100.5»). `String.valueOf(double)` с 10^7
     * печатает «1.0E7» — такую строку ни поле суммы, ни пользователь не поймут.
     */
    @JvmStatic
    fun plainNumber(value: Double): String {
        if (value == 0.0) return "0"
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    @JvmStatic
    fun getFormat(precision: Int): DecimalFormat {
        return formatCache.computeIfAbsent(precision) { p ->
            val pattern = StringBuilder("#,##0")
            if (p > 0) {
                pattern.append(".")
                for (i in 0 until p) pattern.append("0")
            }
            val format = DecimalFormat(pattern.toString())
            // Разделитель тысяч — пробел в любой локали (в английской по умолчанию запятая).
            // Неразрывный, чтобы «1 290,00» не переносилось посреди суммы.
            val symbols = format.decimalFormatSymbols
            symbols.groupingSeparator = '\u00A0'
            format.decimalFormatSymbols = symbols
            format
        }
    }

    @JvmStatic
    fun formatAmount(amount: Double, currency: Currency?): String =
        getFormat(currency?.precision ?: 2).format(amount)

    @JvmStatic
    fun setListViewHeightBasedOnChildren(listView: ListView) {
        val listAdapter = listView.adapter ?: return // pre-condition

        var totalHeight = listView.paddingTop + listView.paddingBottom

        for (i in 0 until listAdapter.count) {
            val listItem = listAdapter.getView(i, null, listView)
            if (listItem is ViewGroup) {
                listItem.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            listItem.measure(0, 0)
            totalHeight += listItem.measuredHeight
        }

        val params = listView.layoutParams
        params.height = totalHeight + listView.dividerHeight * (listAdapter.count - 1)
        listView.layoutParams = params
    }

    @JvmStatic
    fun setListViewHeightBasedOnChildren2(listView: ListView) {
        val listAdapter = listView.adapter ?: return // pre-condition

        var totalHeight = 0
        val desiredWidth = View.MeasureSpec.makeMeasureSpec(listView.width, View.MeasureSpec.AT_MOST)
        for (i in 0 until listAdapter.count) {
            val listItem = listAdapter.getView(i, null, listView)
            listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
            totalHeight += listItem.measuredHeight
        }

        val params = listView.layoutParams
        params.height = totalHeight + listView.dividerHeight * (listAdapter.count - 1)
        listView.layoutParams = params
        listView.requestLayout()
    }

    enum class Operation { expanse, deposit, transfer }

    @JvmStatic
    fun setAmountText(textView: TextView, eqAmount: EquivalentResult, currency: Currency?) {
        setAmountText(
            textView, eqAmount.totalAmountEq, currency,
            deriveOperation(eqAmount.totalAmountEq),
            eqAmount.approxEq, eqAmount.isIncomplete
        )
    }

    @JvmStatic
    fun setAmountText(
        textView: TextView, eqAmount: EquivalentResult, currency: Currency?,
        operation: Operation
    ) {
        setAmountText(
            textView, eqAmount.totalAmountEq, currency, operation,
            eqAmount.approxEq, eqAmount.isIncomplete
        )
    }

    private fun deriveOperation(amount: Double?): Operation {
        if (amount == null) return Operation.transfer
        return if (amount > 0) Operation.deposit else if (amount < 0) Operation.expanse else Operation.transfer
    }

    @JvmStatic
    fun setAmountExpanseText(textView: TextView, amount: Double?, currency: Currency?) {
        setAmountText(textView, amount, currency, Operation.expanse, false)
    }

    @JvmStatic
    fun setAmountDepositText(textView: TextView, amount: Double?, currency: Currency?) {
        setAmountText(textView, amount, currency, Operation.deposit, false)
    }

    @JvmStatic
    fun setAmountTransferText(textView: TextView, amount: Double?, currency: Currency?) {
        setAmountText(textView, amount, currency, Operation.transfer, false)
    }

    @JvmStatic
    fun setAmountText(textView: TextView, amount: Double?, currency: Currency?, isAprox: Boolean) {
        setAmountText(textView, amount, currency, deriveOperation(amount), isAprox, false)
    }

    /** Colours by the sign of `amount`, so keep this for figures that can go negative. */
    @JvmStatic
    fun setAmountText(
        textView: TextView, amount: Double?, currency: Currency?,
        isAprox: Boolean, isIncomplete: Boolean
    ) {
        setAmountText(textView, amount, currency, deriveOperation(amount), isAprox, isIncomplete)
    }

    @JvmStatic
    fun setAmountText(
        textView: TextView, amount: Double?, currency: Currency?,
        operation: Operation, isAprox: Boolean
    ) {
        setAmountText(textView, amount, currency, operation, isAprox, false)
    }

    /**
     * @param isAprox      a conversion was applied — marked with `≈`
     * @param isIncomplete something could not be converted at all — marked with `~`,
     *                     which wins over `≈` because the figure is not just rounded
     *                     but missing a part
     */
    @JvmStatic
    fun setAmountText(
        textView: TextView, amount: Double?, currency: Currency?,
        operation: Operation, isAprox: Boolean, isIncomplete: Boolean
    ) {
        val fmt = getFormat(currency?.precision ?: 2)
        val amText: String = if (amount == null)
            "?"
        else if (operation == Operation.expanse)
            fmt.format(-1 * abs(amount))
        else
            fmt.format(amount)

        val aproxSign = if (isIncomplete) "~" else if (isAprox) "≈" else ""
        // Валюты может не быть: кошелёк без строки account_operating (см.
        // AccountList.getOperationAccounts) — сумму показываем без символа.
        val symbol = currency?.symbol ?: ""
        val colorRes = when (operation) {
            Operation.expanse -> R.color.colorexpanse
            Operation.deposit -> R.color.colorprofit
            Operation.transfer -> R.color.colortransfer
        }
        textView.setTextColor(ContextCompat.getColor(textView.context, colorRes))
        textView.text = aproxSign + amText + symbol
    }
}
