package com.example.wallettransactions.Activities

import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Data.DebtDirection
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * «Дать в долг» / «Взять в долг» / «Вернуть» из выбора статьи (openspec debts, design D6):
 * контрагент → заём (существующий кошелёк или «Новый заём») → кошелёк займа и направление
 * перевода. Сумму, дату и комментарий вводят на обычной форме операции — она получает
 * готовую пару «кошелёк ↔ заём» и тип «Перевод», так что разновалютный заём идёт тем же
 * путём через «Transfer», что и любой перевод.
 *
 * Новые контрагент и заём пишутся в базу сразу, при выборе: форма операции берёт счёт
 * из кэша `Singleton`. Пустой заём, если операцию так и не сохранили, остаётся
 * нулевым займом без архива — он виден на экране «Долги» с пометкой «закрыт» (авто-архив
 * ставится только при переходе остатка «≠ 0 → 0», так что пустой заём не архивируется).
 */
internal class DebtPickFlow private constructor(
    private val activity: Activity,
    private val action: DebtDirection.Action,
    /** Кошелёк пользователя из формы — валюта «Нового займа» по умолчанию; может быть null. */
    private val userWallet: AccountOperatingItem?,
    private val result: Result
) {

    fun interface Result {
        /** @param direction [DebtDirection.WALLET_TO_LOAN] или [DebtDirection.LOAN_TO_WALLET] */
        fun onLoanPicked(loanAccountId: Int, direction: Int)
    }

    // ── шаг 1: контрагент ────────────────────────────────────────────────────

    private fun pickCounterparty() {
        val db = WalletDbHelper(activity).readableDatabase
        val list = ArrayList<CounterpartyItem>()
        for (c in WalletDbLoader.LoadCounterparties(db)) {
            if (c.isArchived) continue // спека «Архивный контрагент»: не в выборе при вводе
            if (action == DebtDirection.Action.RETURN && openLoans(db, c.uuid).isEmpty()) continue
            list.add(c)
        }
        val canCreate = action != DebtDirection.Action.RETURN
        if (list.isEmpty() && !canCreate) {
            Toast.makeText(activity, R.string.debt_return_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val pinned = ArrayList<String>()
        if (canCreate) pinned.add(activity.getString(R.string.debt_new_counterparty))
        val names = ArrayList<String>()
        for (c in list) names.add(c.name)
        // Поиск по имени (спека debts «Поиск по имени контрагента»); «Новый контрагент» поиск не скрывает.
        SearchableListDialog.show(activity, activity.getString(titleRes()), pinned, names) { index ->
            if (index < 0) askNewCounterparty()
            else pickLoan(list[index])
        }
    }

    private fun askNewCounterparty() {
        val input = EditText(activity)
        input.setHint(R.string.debt_counterparty_name_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val box = FrameLayout(activity)
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(activity)
            .setTitle(R.string.debt_new_counterparty_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = WalletDbCRUD.NormalizeCounterpartyName(input.text.toString()) ?: ""
                if (name.isEmpty()) return@setPositiveButton
                val db = WalletDbHelper(activity).writableDatabase
                var id = WalletDbCRUD.GetCounterpartyIdByName(db, name)
                if (id > 0) {
                    // Спека «Дубль имени»: второго не заводим — берём существующего.
                    Toast.makeText(
                        activity, activity.getString(R.string.debt_counterparty_exists, name),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    id = WalletDbCRUD.CreateCounterparty(db, name, "")
                }
                for (c in WalletDbLoader.LoadCounterparties(db)) {
                    if (c.id == id) {
                        pickLoan(c)
                        return@setPositiveButton
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── шаг 2: заём ──────────────────────────────────────────────────────────

    private fun pickLoan(counterparty: CounterpartyItem) {
        val db = WalletDbHelper(activity).readableDatabase
        val loans = if (action == DebtDirection.Action.RETURN) openLoans(db, counterparty.uuid)
        else liveLoans(counterparty.uuid)
        val canCreate = action != DebtDirection.Action.RETURN
        val newCurrency = newLoanCurrency(loans)
        val offset = if (canCreate && newCurrency != null) 1 else 0

        val items = arrayOfNulls<String>(loans.size + offset)
        if (offset == 1) items[0] = activity.getString(R.string.debt_new_loan, newCurrency!!.iso)
        for (i in loans.indices) {
            val loan = loans[i]
            items[i + offset] = activity.getString(
                R.string.debt_loan_row, loan.displayName,
                money(WalletDbLoader.LoadAccountBalance(db, loan.id), loan.currency)
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(counterparty.name)
            .setItems(items) { _, which ->
                if (offset == 1 && which == 0) createLoan(counterparty, newCurrency!!)
                else finish(loans[which - offset])
            }
            .show()
    }

    private fun createLoan(counterparty: CounterpartyItem, currency: Currency) {
        val db = WalletDbHelper(activity).writableDatabase
        val loanId: Int
        db.beginTransaction()
        try {
            loanId = WalletDbCRUD.CreateDebtWallet(db, counterparty.id, currency.id, "", null)
            if (loanId > 0) db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (loanId <= 0) {
            Toast.makeText(activity, R.string.debt_loan_failed, Toast.LENGTH_LONG).show()
            return
        }
        reloadAccounts(db)
        val loan = Singleton.instance.getAccounts(activity).getAccountById(loanId)
        if (loan is AccountOperatingItem) finish(loan)
    }

    private fun finish(loan: AccountOperatingItem) {
        val db = WalletDbHelper(activity).readableDatabase
        val direction = DebtDirection.of(action, WalletDbLoader.LoadAccountBalance(db, loan.id))
        if (direction == DebtDirection.NONE) {
            Toast.makeText(activity, R.string.debt_return_nothing, Toast.LENGTH_LONG).show()
            return
        }
        result.onLoanPicked(loan.id, direction)
    }

    // ── данные ───────────────────────────────────────────────────────────────

    /** Живые неархивные займы контрагента из кэша счетов. */
    private fun liveLoans(counterpartyUuid: String): List<AccountOperatingItem> {
        val out = ArrayList<AccountOperatingItem>()
        for (a in Singleton.instance.getAccounts(activity).allAccounts) {
            if (a !is AccountOperatingItem) continue
            if (a.isDebt && counterpartyUuid == a.debt!!.counterpartyUuid
                && a.isArchived != true
            ) out.add(a)
        }
        return out
    }

    /** Займы с ненулевым остатком — для «Вернуть». */
    private fun openLoans(db: SQLiteDatabase, counterpartyUuid: String): List<AccountOperatingItem> {
        val out = ArrayList<AccountOperatingItem>()
        for (w in liveLoans(counterpartyUuid)) {
            val b = WalletDbLoader.LoadAccountBalance(db, w.id)
            if (Math.abs(b) >= Math.pow(10.0, -w.currency!!.precision.toDouble()) / 2) out.add(w)
        }
        return out
    }

    /** Валюта «Нового займа»: кошелька в форме, иначе эквивалента (design D6). */
    private fun newLoanCurrency(loans: List<AccountOperatingItem>): Currency? {
        if (userWallet != null && userWallet.currency != null) return userWallet.currency
        val eq = PeriodFilterState.get(activity).getCurrencyEq(activity)
        if (eq != null) return eq
        return if (loans.isEmpty()) null else loans[0].currency
    }

    private fun reloadAccounts(db: SQLiteDatabase) {
        WalletDbLoader.LoadAccounts(
            db, Singleton.instance.getAccounts(activity),
            Singleton.instance.getCurrencyList(activity), Singleton.instance.getIconList(activity), null
        )
        PeriodFilterState.get(activity).markDataChanged()
    }

    private fun titleRes(): Int = when (action) {
        DebtDirection.Action.GIVE -> R.string.debt_action_give
        DebtDirection.Action.TAKE -> R.string.debt_action_take
        else -> R.string.debt_action_return
    }

    companion object {
        @JvmStatic
        fun start(activity: Activity, action: DebtDirection.Action, userWallet: AccountOperatingItem?, result: Result) {
            DebtPickFlow(activity, action, userWallet, result).pickCounterparty()
        }

        @JvmStatic
        fun money(amount: Double, c: Currency?): String {
            val precision = c?.precision ?: 2
            return AdapterUtils.getFormat(precision).format(amount) + (if (c != null) " " + c.symbol else "")
        }
    }
}
