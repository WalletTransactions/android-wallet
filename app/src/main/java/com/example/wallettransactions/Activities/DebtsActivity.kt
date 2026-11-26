package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.CalendarPicker
import com.example.wallettransactions.Chips
import com.example.wallettransactions.Data.DebtsList
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Экран «Долги» (openspec debts, спека «Экран „Долги“», design D10): итоги «Мне должны» /
 * «Я должен» в валюте эквивалента с признаком неполноты, контрагенты по модулю остатка,
 * под каждым — займы с остатком в своей валюте, сроком и пометкой «просрочен». Займы с
 * нулевым остатком без архива видны с пометкой «закрыт», архивные — за переключателем
 * «Показывать архивные»; поиск по контрагенту и чипы направления. Тап по займу — карточка
 * кошелька, где меняются контрагент, срок и комментарий.
 *
 * Остатки — на сегодня по всем проводкам (`LoadBalancesPerWallet` с завтрашней
 * датой), а не на конец выбранного периода: долг — состояние «сейчас».
 */
class DebtsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var chipShowArchived: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipOwedToMe: TextView
    private lateinit var chipIOwe: TextView

    // Состояние фильтров живёт на экране и сбрасывается при каждом его создании
    // (openspec wallets-filter-and-debt-autoarchive, D4).
    private var direction = DebtsList.Direction.ALL
    private var query = ""
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debts)
        list = findViewById(R.id.debtsList)
        chipShowArchived = findViewById(R.id.debtsShowArchived)
        chipShowArchived.setOnClickListener {
            showArchived = !showArchived
            paintChips()
            render()
        }
        val search = findViewById<EditText>(R.id.debtsSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString()
                render()
            }
        })
        chipAll = findViewById(R.id.debtsChipAll)
        chipOwedToMe = findViewById(R.id.debtsChipOwedToMe)
        chipIOwe = findViewById(R.id.debtsChipIOwe)
        chipAll.setOnClickListener { setDirection(DebtsList.Direction.ALL) }
        chipOwedToMe.setOnClickListener { setDirection(DebtsList.Direction.OWED_TO_ME) }
        chipIOwe.setOnClickListener { setDirection(DebtsList.Direction.I_OWE) }
        paintChips()
    }

    private fun setDirection(d: DebtsList.Direction) {
        direction = d
        paintChips()
        render()
    }

    private fun paintChips() {
        Chips.paint(this, chipAll, direction == DebtsList.Direction.ALL)
        Chips.paint(this, chipOwedToMe, direction == DebtsList.Direction.OWED_TO_ME)
        Chips.paint(this, chipIOwe, direction == DebtsList.Direction.I_OWE)
        Chips.paint(this, chipShowArchived, showArchived)
    }

    override fun onResume() {
        super.onResume()
        // Вернулись с формы операции («Вернуть»), карточки займа или после синка —
        // перечитать всегда: форма операции ревизию данных не поднимает, а запросы
        // экрана лёгкие (остатки займов и список контрагентов).
        render()
    }

    fun btnUp(view: View) {
        finish()
    }

    fun onOpenCounterparties(view: View) {
        startActivity(Intent(this, CounterpartiesActivity::class.java))
    }

    private fun render() {
        val db = Singleton.instance.getReadableDatabase(this)
        val eq = PeriodFilterState.get(this).getCurrencyEq(this)
        val asOf = tomorrow()

        // Итоги — тем же расчётом, что строки карточки периода.
        val owed = findViewById<TextView>(R.id.debtsOwedToMe)
        val owe = findViewById<TextView>(R.id.debtsIOwe)
        if (eq != null) {
            val totals = WalletDbLoader.GenerateDebtsToDate(db, asOf, eq)
            // Ноль — без знака и цвета: «−0,00 €» у «Я должен» читалось бы как долг.
            AdapterUtils.setAmountText(
                owed, totals.owedToMe!!, eq,
                if (totals.owedToMe!!.totalAmountEq!! > 0) AdapterUtils.Operation.deposit
                else AdapterUtils.Operation.transfer
            )
            // «Я должен» — положительной суммой: минус перед ней читался бы как двойное отрицание.
            AdapterUtils.setAmountText(owe, totals.iOwe!!, eq, AdapterUtils.Operation.transfer)
            if (totals.iOwe!!.totalAmountEq!! > 0) owe.setTextColor(ContextCompat.getColor(this, R.color.colorexpanse))
        }

        val wallets = HashMap<Int, AccountOperatingItem>()
        val loans = loadLoans(this, db, eq, asOf, wallets)
        val names = counterpartyNames(db)

        val groups = DebtsList.group(loans, names, showArchived, direction, query)
        list.removeAllViews()
        val empty = findViewById<TextView>(R.id.debtsEmpty)
        // Займы есть, но фильтры всё скрыли — «Ничего не найдено»; займов нет вообще — подсказка.
        empty.setText(if (loans.isEmpty()) R.string.debts_empty else R.string.debts_nothing_found)
        empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        for (g in groups) {
            list.addView(groupHeader(g, eq))
            for (loan in g.loans) list.addView(loanRow(loan, wallets[loan.accountId], today))
        }
    }

    private fun groupHeader(g: DebtsList.Group, eq: Currency?): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(16), 0, dp(4))
        val name = text(g.name, 16, R.color.colorTextPrimary, true)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val total = text("", 15, R.color.colorTextPrimary, true)
        AdapterUtils.setAmountText(total, g.totalEq, eq, g.approx, g.incomplete)
        row.addView(total)
        return row
    }

    private fun loanRow(loan: DebtsList.Loan, wallet: AccountOperatingItem?, today: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(12), dp(8), 0, dp(8))
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        row.setBackgroundResource(ripple.resourceId)

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        val name = text(loan.name, 14, R.color.colorTextPrimary, false)
        line.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val amount = text("", 14, R.color.colorTextPrimary, true)
        AdapterUtils.setAmountText(amount, loan.balance, wallet?.currency, false)
        line.addView(amount)
        row.addView(line)

        var note: String? = if (loan.isClosed()) getString(R.string.debts_closed)
        else if (loan.isOverdue(today)) getString(R.string.debts_overdue, loan.dueDate)
        else if (loan.dueDate != null) getString(R.string.debts_due, loan.dueDate) else null
        if (loan.comment.isNotEmpty()) note = if (note == null) loan.comment else note + " · " + loan.comment
        if (note != null) {
            val sub = text(
                note, 12, if (loan.isOverdue(today)) R.color.colorexpanse else R.color.colorTextSecondary,
                loan.isOverdue(today)
            )
            row.addView(sub)
        }

        row.setOnClickListener { showLoanActions(loan, wallet) }
        return row
    }

    // ── действия с займом (спека «Карточка займа», «Прощение долга») ────────────

    /** Заём, который прощают, пока открыт диалог; null — диалога нет. */
    private var forgiveLoan: DebtsList.Loan? = null
    private var forgiveWallet: AccountOperatingItem? = null
    private var forgiveCategoryId = 0
    private var forgiveDay: String? = null
    private var forgiveDialog: AlertDialog? = null
    private var forgiveAmount: EditText? = null
    private var forgiveCategoryView: TextView? = null
    private var forgiveDateView: TextView? = null

    private fun showLoanActions(loan: DebtsList.Loan, wallet: AccountOperatingItem?) {
        val items = ArrayList<String>()
        val actions = ArrayList<Runnable>()
        if (!loan.isClosed() && wallet != null) {
            items.add(getString(R.string.debt_action_return))
            actions.add(Runnable { startReturn(loan) })
            items.add(getString(R.string.debt_forgive))
            actions.add(Runnable { showForgive(loan, wallet) })
        }
        items.add(getString(R.string.debt_action_card))
        actions.add(Runnable {
            val edit = Intent(this, AccountItemActivity::class.java)
            edit.putExtra(AccountItemActivity.ARG_ACCOUNT_ID, loan.accountId)
            startActivity(edit)
        })
        // Единственный пункт (закрытый заём — «Вернуть» и «Простить» недоступны): меню из одной
        // строки ни к чему, сразу открываем карточку.
        if (actions.size == 1) {
            actions[0].run()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(loan.name)
            .setItems(items.toTypedArray()) { _, which -> actions[which].run() }
            .show()
    }

    /**
     * «Вернуть» (спека «Направление перевода по действию»): форма операции — перевод, заём на
     * нужной стороне по знаку остатка, сумма — весь остаток; кошелёк выбирает пользователь.
     */
    private fun startReturn(loan: DebtsList.Loan) {
        val op = Intent(this, TransactionActivity::class.java)
        op.putExtra(TransactionActivity.cKind, "transfer") // not UI: kind key
        // Дата обязательна: форма разбирает её без проверки на null (как у «+» на табах).
        op.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(Date()))
        if (loan.balance > 0) op.putExtra(TransactionActivity.cFrom, loan.accountId)
        else op.putExtra(TransactionActivity.cTo, loan.accountId)
        op.putExtra(TransactionActivity.cAmount, Math.abs(loan.balance))
        startActivity(op)
    }

    private fun showForgive(loan: DebtsList.Loan, wallet: AccountOperatingItem) {
        forgiveLoan = loan
        forgiveWallet = wallet
        forgiveDay = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val wdb = Singleton.instance.getWritableDatabase(this)
        forgiveCategoryId = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(
            wdb, getString(R.string.debt_writeoff_category)
        )
        reloadAccounts(wdb)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val c = wallet.currency
        val amountLabel = text(
            getString(R.string.debt_forgive_amount, c?.symbol ?: ""),
            12, R.color.colorTextSecondary, false
        )
        box.addView(amountLabel)
        val amountEdit = EditText(this)
        forgiveAmount = amountEdit
        amountEdit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        amountEdit.setText(AdapterUtils.plainNumber(Math.abs(loan.balance)))
        box.addView(amountEdit)
        val categoryView = text("", 15, R.color.colorPrimary, true)
        forgiveCategoryView = categoryView
        categoryView.setPadding(0, dp(12), 0, dp(12))
        categoryView.setOnClickListener {
            val pick = AccountPickerActivity.buildIntent(
                this, getString(R.string.debt_forgive),
                AccountPickerActivity.cSelectedTypeChargeAccounts, forgiveCategoryId
            )
            pick.putExtra(AccountPickerActivity.cHideSystem, true)
            startActivityForResult(pick, REQUEST_FORGIVE_CATEGORY)
        }
        box.addView(categoryView)
        val dateView = text("", 15, R.color.colorPrimary, true)
        forgiveDateView = dateView
        dateView.setPadding(0, dp(4), 0, dp(12))
        dateView.setOnClickListener {
            CalendarPicker.show(this, null as Date?) { y, m, d ->
                forgiveDay = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)
                paintForgive()
            }
        }
        box.addView(dateView)
        paintForgive()

        val cp = loan.name
        forgiveDialog = AlertDialog.Builder(this)
            .setTitle(
                if (loan.balance > 0) getString(R.string.debt_forgive_title_me, cp)
                else getString(R.string.debt_forgive_title_them, cp)
            )
            .setView(box)
            .setPositiveButton(R.string.debt_forgive) { _, _ -> writeForgive() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { forgiveDialog = null }
            .show()
    }

    private fun paintForgive() {
        val cat = Singleton.instance.getAccounts(this).getAccountById(forgiveCategoryId)
        forgiveCategoryView!!.text = getString(R.string.debt_forgive_category, cat?.displayName ?: "—")
        forgiveDateView!!.text = getString(R.string.debt_forgive_date, forgiveDay)
    }

    private fun writeForgive() {
        val amount = try {
            forgiveAmount!!.text.toString().replace(',', '.').trim().toDouble()
        } catch (e: NumberFormatException) {
            0.0
        }
        val wdb = Singleton.instance.getWritableDatabase(this)
        val c = forgiveWallet!!.currency
        val r = WalletDbCRUD.ForgiveDebt(
            wdb, forgiveLoan!!.accountId,
            forgiveCategoryId, forgiveDay!!, amount, c?.precision ?: 2,
            getString(R.string.debt_forgive_comment)
        )
        val ok = r == WalletDbCRUD.WriteOffResult.written
        Toast.makeText(
            this, if (ok) R.string.debt_forgive_done else R.string.debt_forgive_failed,
            Toast.LENGTH_SHORT
        ).show()
        if (ok) {
            PeriodFilterState.get(this).markDataChanged()
            render()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FORGIVE_CATEGORY || resultCode != RESULT_OK || data == null) return
        val id = data.getIntExtra(AccountPickerActivity.cAccountId, 0)
        if (id > 0) forgiveCategoryId = id
        if (forgiveDialog != null) paintForgive()
    }

    private fun reloadAccounts(db: SQLiteDatabase) {
        WalletDbLoader.LoadAccounts(
            db, Singleton.instance.getAccounts(this),
            Singleton.instance.getCurrencyList(this), Singleton.instance.getIconList(this), null
        )
    }

    private fun text(s: String?, sp: Int, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        t.setTextColor(ContextCompat.getColor(this, color))
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FORGIVE_CATEGORY = 410

        /**
         * Все займы (долговые кошельки) с остатками на дату `asOf` — для экрана «Долги» и карточки
         * контрагента (openspec counterparty-card): одни и те же цифры там и там.
         *
         * @param wallets если не null — сюда кладутся кошельки займов по `_id`
         */
        @JvmStatic
        internal fun loadLoans(
            ctx: Context, db: SQLiteDatabase, eq: Currency?,
            asOf: Date, wallets: MutableMap<Int, AccountOperatingItem>?
        ): List<DebtsList.Loan> {
            val balances = HashMap<Int, WalletDbLoader.WalletBalanceRow>()
            if (eq != null) {
                for (r in WalletDbLoader.LoadBalancesPerWallet(db, asOf, asOf, eq)) {
                    balances[r.accountId] = r
                }
            }
            val loans = ArrayList<DebtsList.Loan>()
            for (a in Singleton.instance.getAccounts(ctx).allAccounts) {
                if (a !is AccountOperatingItem || !a.isDebt) continue
                wallets?.put(a.id, a)
                val row = balances[a.id]
                val c = a.currency
                loans.add(
                    DebtsList.Loan(
                        a.id, a.displayName, a.debt!!.counterpartyUuid,
                        row?.balance ?: 0.0,
                        if (row == null) 0.0 else if (row.hasEq) row.balanceEq else null,
                        c?.symbol ?: "", c?.precision ?: 2,
                        a.debt!!.dueDate, a.debt!!.comment,
                        c != null && eq != null && c.id != eq.id,
                        a.isArchived == true
                    )
                )
            }
            return loans
        }

        /** uuid контрагента → имя. */
        @JvmStatic
        internal fun counterpartyNames(db: SQLiteDatabase): Map<String, String> {
            val names = HashMap<String, String>()
            for (c in WalletDbLoader.LoadCounterparties(db)) names[c.uuid] = c.name
            return names
        }

        /** Завтра 00:00 — «остатки на сегодня включительно», как на экране «Долги». */
        @JvmStatic
        internal fun asOfTomorrow(): Date = tomorrow()

        private fun tomorrow(): Date {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_MONTH, 1)
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            return c.time
        }
    }
}
