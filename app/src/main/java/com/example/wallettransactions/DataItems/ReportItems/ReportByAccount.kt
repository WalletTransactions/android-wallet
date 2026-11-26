package com.example.wallettransactions.DataItems.ReportItems

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.Operations.EquivalentResult

import java.util.ArrayList
import java.util.HashMap

class ReportByAccount {
    inner class ReportByAccountItemCurrency(
        @JvmField var amount: Double?,
        @JvmField var currency: Currency?,
        @JvmField var amountEq: Double?
    )

    @JvmField
    var ReportAccounts: HashMap<AccountItem, ArrayList<ReportByAccountItemCurrency>> = HashMap()



    val total: EquivalentResult
        get() = computeTotal()

    private fun computeTotal(): EquivalentResult {
        var lastCur: Currency? = null
        var isAprox = false
        var total: Double?

        if (ReportAccounts.size == 0)
            total = null
        else
            total = 0.0

        for (arr in ReportAccounts.values)
            for (itm in arr) {
                if (lastCur == null)
                    lastCur = itm.currency

                isAprox = isAprox || (lastCur !== itm.currency)

                if ((total != null) && (itm.amountEq != null))
                    total += itm.amountEq!!
                else
                    total = null
            }

        return EquivalentResult(total, isAprox)
    }

    fun AddSubOper(account: AccountItem, currency: Currency?, amount: Double?, amountEq: Double?): ReportByAccountItemCurrency {
        var curlist = ReportAccounts[account]
        if (curlist == null) {
            curlist = ArrayList<ReportByAccountItemCurrency>()
            ReportAccounts[account] = curlist
        }
        val res = ReportByAccountItemCurrency(amount, currency, amountEq)

        curlist.add(res)
        return res
    }
}
