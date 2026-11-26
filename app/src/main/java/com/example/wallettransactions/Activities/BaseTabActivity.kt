package com.example.wallettransactions.Activities

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.wallettransactions.Activities.Fragments.ChooseDatePeriodFragment
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.OperationTemplatesDb
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.EquivalentCurrencies
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.DataItems.Operations.EquivalentResult
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.example.wallettransactions.SummaryOther
import com.example.wallettransactions.WalletApplication
import java.time.LocalDateTime
import java.util.Date

/**
 * Общая часть четырёх корневых экранов нижней панели: «Операции», «Кошельки»,
 * «Отчёты», «Ещё».
 *
 * Раньше каждый экран сам подсвечивал таб, сам заводил период, сам считал карточку
 * остатков и сам обрабатывал фильтр — четыре почти одинаковые копии, которые к тому
 * же не знали друг о друге, поэтому при переходе на другой таб период и фильтр
 * сбрасывались. Здесь это в одном месте:
 *
 *  * период, фильтр и валюта эквивалента живут в [PeriodFilterState] —
 *  один объект на все табы, переживает и переход, и перезапуск приложения;
 *  * вёрстка шапки — `view_tab_title.xml` + `view_tab_actions.xml` +
 *  `view_period_summary.xml`, экраны их только подключают;
 *  * цифры карточки («на начало», «на конец», доход, расход, итог) считает
 *  [refreshSummaryCard] — одна реализация, поэтому на всех табах они
 *  совпадают;
 *  * переходы между табами, кнопка «+», фильтр и выбор валюты — обработчики
 *  `android:onClick` из общих layout'ов.
 *
 * Экран наследника: `setContentView(...)` → свои `findViewById` →
 * [initTabScreen] → [reloadSharedAndTabData].
 * Перезагрузку своего списка он делает в [reloadTabData].
 */
abstract class BaseTabActivity : AppCompatActivity() {

    @JvmField
    protected var fragmentDataPeriod: ChooseDatePeriodFragment? = null

    /**
     * false, пока [initTabScreen] не вызван. Экран-наследник может
     * работать и без общей шапки — [AccountActivity] в режиме пикера вообще не
     * ставит layout, — и тогда обновлять нечего.
     */
    private var tabScreenInitialized = false

    /** Ревизия состояния, которую экран уже отрисовал — см. [onResume]. */
    private var renderedRevision = -1

    /** Пока true, слушатель периода молчит: даты ставит сам экран, а не пользователь. */
    private var applyingSharedState = false

    // ── Общее состояние ───────────────────────────────────────────────────────

    protected fun state(): PeriodFilterState = PeriodFilterState.get(this)

    protected val dateFrom: Date
        get() = state().getDateFrom()!!

    protected val dateTo: Date
        get() = state().getDateTo()!!

    protected val dataFilter: DataFilter
        get() = state().getDataFilter()

    protected val currencyEq: Currency
        get() = state().getCurrencyEq(this)

    /** Одно соединение с базой на приложение — закрывать его экраны не должны. */
    protected val db: SQLiteDatabase
        get() = Singleton.instance.getReadableDatabase(this)

    // ── Инициализация ─────────────────────────────────────────────────────────

    /**
     * @param activeTabId один из R.id.navOps / navWallets / navReport / navMore
     * @param title       текст для `view_tab_title`; null — если у экрана свой заголовок
     */
    protected fun initTabScreen(activeTabId: Int, title: String?) {
        BottomNav.setActiveTab(this, activeTabId)

        val screenTitle = findViewById<TextView>(R.id.screenTitle)
        if (screenTitle != null && title != null) screenTitle.text = title

        updateCurrencyButton()
        initOtherSection()

        val period = supportFragmentManager.findFragmentById(R.id.fragmentDataPeriod) as ChooseDatePeriodFragment?
        fragmentDataPeriod = period

        if (period != null) {
            // Сначала даты, потом слушатель: setDateFrom/To сами дёргают onDateChanged,
            // и с уже подключённым слушателем экран грузился бы дважды.
            applySharedPeriod()

            period.setOnDatePeriodChangedListener { _, from, to ->
                if (applyingSharedState) return@setOnDatePeriodChangedListener
                state().setPeriod(this, from, to)
                reloadSharedAndTabData()
            }
        }

        // Первую отрисовку наследник делает сам (например, «Операции» — только после
        // диалога аварийного восстановления), поэтому ревизию считаем актуальной.
        renderedRevision = state().getRevision()
        tabScreenInitialized = true
    }

    private fun applySharedPeriod() {
        applyingSharedState = true
        try {
            fragmentDataPeriod!!.setDateFrom(dateFrom)
            fragmentDataPeriod!!.setDateTo(dateTo)
        } finally {
            applyingSharedState = false
        }
    }

    /**
     * Период, фильтр или валюту могли сменить на другом табе (или в «Настройках») —
     * тогда экран, поднятый через FLAG_ACTIVITY_REORDER_TO_FRONT, показывал бы старое.
     */
    override fun onResume() {
        super.onResume()
        if (!tabScreenInitialized) return

        state().syncCurrencyEqFromSettings(this)
        applyRecurringOperations()
        reloadIfStale()
        state().addDataChangedListener(onDataChanged)
    }

    override fun onPause() {
        state().removeDataChangedListener(onDataChanged)
        super.onPause()
    }

    /** Данные сменились, пока экран открыт (фоновый синк) — см. PeriodFilterState.markDataChanged. */
    private val onDataChanged = Runnable { reloadIfStale() }

    private fun reloadIfStale() {
        if (state().getRevision() == renderedRevision) return

        updateCurrencyButton()
        if (fragmentDataPeriod != null) applySharedPeriod()
        reloadSharedAndTabData()
    }

    /**
     * Заводит операции, которые по расписаниям шаблонов операций уже наступили. В фоне
     * их к сроку заводит [com.example.wallettransactions.sync.RecurringWorker], но
     * Doze может его придержать — открытый таб не ждёт. Создали что-то — поднимаем
     * ревизию, и проверка ниже перечитает экран.
     *
     * После аварийного завершения не трогаем базу, пока пользователь не выбрал действие в
     * диалоге восстановления ([MainActivity]).
     */
    private fun applyRecurringOperations() {
        if (WalletApplication.hadCrash(this)) return
        val created = OperationTemplatesDb.generateDue(
            Singleton.instance.getWritableDatabase(this), LocalDateTime.now()
        )
        if (created > 0) state().markDataChanged()
    }

    // ── Перерисовка ───────────────────────────────────────────────────────────

    /**
     * Обновить общую шапку и данные экрана. Публичный (в Java был protected, то есть виден
     * и пакету): его зовут тесты из того же пакета, а у Kotlin protected пакету не виден.
     */
    fun reloadSharedAndTabData() {
        refreshSummaryCard()
        refreshFilterBanner()
        reloadTabData()
        renderedRevision = state().getRevision()
    }

    /** Перезагрузка того, что специфично для экрана: лента операций, кошельки, категории. */
    protected abstract fun reloadTabData()

    /**
     * Цифры карточки состояния денег за текущий период. Одна реализация на все табы —
     * до этого «Операции» и «Кошельки» считали их своими копиями этого кода.
     */
    protected fun refreshSummaryCard() {
        val openingBalance = findViewById<TextView>(R.id.openingBalance)
            ?: return // экран без карточки («Ещё»)

        val db = db
        val currencyEq = currencyEq

        val opBalance = WalletDbLoader.GenerateReportGetBalanceToDate(
            db, dateFrom, currencyEq, dataFilter
        )
        val clBalance = WalletDbLoader.GenerateReportGetBalanceToDate(
            db, dateTo, currencyEq, dataFilter
        )

        AdapterUtils.setAmountText(openingBalance, opBalance, currencyEq, AdapterUtils.Operation.transfer)
        AdapterUtils.setAmountText(
            findViewById<TextView>(R.id.endingBalance), clBalance, currencyEq,
            AdapterUtils.Operation.transfer
        )
        AdapterUtils.setAmountText(
            findViewById<TextView>(R.id.totalProfitBalance),
            clBalance.totalAmountEq!! - opBalance.totalAmountEq!!,
            currencyEq,
            clBalance.approxEq || opBalance.approxEq,
            clBalance.isIncomplete || opBalance.isIncomplete
        )

        // Доход и расход — те же запросы, что строит отчёт за период. Суммы приходят
        // положительными: доход или расход задаёт направление операции, а не знак, —
        // поэтому операция указана явно, иначе расход отрисовался бы зелёным плюсом.
        // Фильтр сюда не идёт (null): карточка — итог по всем деньгам, а плитки «Доход» и
        // «Расход» сами ставят фильтр по виду — с ним вторая плитка обнулялась бы.
        val reportIncome = WalletDbLoader.GenerateIncomeReportByAccount(
            db,
            dateFrom, dateTo, Singleton.instance.getAccounts(this),
            Singleton.instance.getCurrencyList(this), currencyEq, null
        )
        val reportExpanse = WalletDbLoader.GenerateExpanseReportByAccount(
            db,
            dateFrom, dateTo, Singleton.instance.getAccounts(this),
            Singleton.instance.getCurrencyList(this), currencyEq, null
        )

        AdapterUtils.setAmountText(
            findViewById<TextView>(R.id.incomeSum), reportIncome.total,
            currencyEq, AdapterUtils.Operation.deposit
        )
        AdapterUtils.setAmountText(
            findViewById<TextView>(R.id.expenseSum), reportExpanse.total,
            currencyEq, AdapterUtils.Operation.expanse
        )

        refreshOtherSection(
            db, currencyEq, opBalance, clBalance,
            reportIncome.total, reportExpanse.total
        )
    }

    // ── «Прочее» ──────────────────────────────────────────────────────────────

    /**
     * Раскрыты ли детали «Прочего» на этом экране (openspec summary-other-section, D2).
     * Начальное значение — из настройки при открытии экрана, дальше его меняет только
     * нажатие на строку: настройку оно не трогает, а экраны («Операции», «Кошельки»,
     * «Отчёты») — разные Activity, поэтому у каждого свой флаг.
     */
    private var otherExpanded = false

    private fun initOtherSection() {
        otherExpanded = AppSettings.isSummaryOtherExpanded(this)
        val row = findViewById<View>(R.id.otherRow)
            ?: return // экран без карточки («Ещё»)
        row.setOnClickListener {
            otherExpanded = !otherExpanded
            applyOtherExpanded()
        }
    }

    /** Поворот экрана не должен сворачивать то, что пользователь раскрыл вручную. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OTHER_EXPANDED, otherExpanded)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.containsKey(STATE_OTHER_EXPANDED)) {
            otherExpanded = savedInstanceState.getBoolean(STATE_OTHER_EXPANDED)
            applyOtherExpanded()
        }
    }

    /** Блок деталей и стрелка по [otherExpanded]; цифры тут не пересчитываются. */
    private fun applyOtherExpanded() {
        val details = findViewById<View>(R.id.otherDetails)
        val row = findViewById<View>(R.id.otherRow)
        if (details == null || row == null) return
        details.visibility = if (otherExpanded && row.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        val chevron = findViewById<View>(R.id.otherChevron)
        if (chevron != null) chevron.rotation = if (otherExpanded) -90f else 90f
    }

    /**
     * Строка «Прочее» и её детали вместо прежних восьми строк карточки: расчёт и условия
     * видимости — в [SummaryOther], здесь только отрисовка. Сумма — остаток
     * `Итог − (Доход − Расход)`, поэтому сходится всегда, даже при неполных курсах.
     */
    private fun refreshOtherSection(
        db: SQLiteDatabase, currencyEq: Currency,
        opBalance: EquivalentResult, clBalance: EquivalentResult,
        income: EquivalentResult, expense: EquivalentResult
    ) {
        val row = findViewById<View>(R.id.otherRow) ?: return

        // Помеченный актив без операций тоже даёт строки (с нулём).
        val s = SummaryOther.compute(
            opBalance, clBalance, income, expense,
            WalletDbCRUD.HasAssets(db),
            WalletDbLoader.GenerateCapitalToDate(db, dateTo, currencyEq),
            WalletDbLoader.GenerateCapitalFlowForPeriod(db, dateFrom, dateTo, currencyEq),
            WalletDbLoader.GenerateDebtsToDate(db, dateTo, currencyEq),
            WalletDbLoader.GenerateDebtFlowForPeriod(db, dateFrom, dateTo, currencyEq)
        )

        row.visibility = if (s.showOther) View.VISIBLE else View.GONE
        applyOtherExpanded()

        // Дочерние строки обновляем и при скрытой «Прочем»: иначе у них остаётся видимость
        // прошлого периода и после повторного показа строки нужно было бы верить рефрешу.
        if (s.showOther) setOtherAmount(R.id.otherSum, s.other, currencyEq, true)
        showOtherRow(
            R.id.otherCapitalFlowRow, R.id.otherCapitalFlowSum, s.showCapitalFlow,
            s.capitalFlow, currencyEq, true
        )
        showOtherRow(
            R.id.otherCapitalRow, R.id.otherCapitalSum, s.showCapital,
            s.capital, currencyEq, false
        )
        showOtherRow(
            R.id.otherDebtFlowRow, R.id.otherDebtFlowSum, s.showDebtFlow,
            s.debtFlow, currencyEq, true
        )
        showOtherRow(R.id.otherOwedRow, R.id.otherOwedSum, s.showDebts, s.owedToMe, currencyEq, false)
        showOtherRow(R.id.otherOweRow, R.id.otherOweSum, s.showDebts, s.iOwe, currencyEq, false)
        showOtherRow(R.id.otherFxRow, R.id.otherFxSum, s.showFxDiff, s.fxDiff, currencyEq, true)
        showOtherRow(R.id.otherTotalRow, R.id.otherTotalSum, s.showTotal, s.total, currencyEq, false)
    }

    private fun showOtherRow(
        rowId: Int, sumId: Int, visible: Boolean, amount: SummaryOther.Amount,
        currencyEq: Currency, coloredBySign: Boolean
    ) {
        val row = findViewById<View>(rowId)
        row.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) setOtherAmount(sumId, amount, currencyEq, coloredBySign)
    }

    /**
     * Потоки и разница — со знаком и цветом по знаку, остатки (капитал, долги, «Всего…») —
     * нейтральным цветом, как «На начало» и «На конец».
     */
    private fun setOtherAmount(
        sumId: Int, amount: SummaryOther.Amount, currencyEq: Currency,
        coloredBySign: Boolean
    ) {
        val sum = findViewById<TextView>(sumId)
        if (coloredBySign) {
            AdapterUtils.setAmountText(sum, amount.value, currencyEq, amount.approx, amount.incomplete)
        } else {
            AdapterUtils.setAmountText(
                sum, amount.value, currencyEq, AdapterUtils.Operation.transfer,
                amount.approx, amount.incomplete
            )
        }
    }

    protected fun refreshFilterBanner() {
        val filterBanner = findViewById<View>(R.id.filterBanner) ?: return

        // Вид операции идёт первым: его ставят плиткой карточки, а не экраном «Фильтр»,
        // поэтому без строки-резюме он остался бы включённым незаметно для пользователя.
        val parts = ArrayList<String>()
        val kindTitle = operationKindTitle(state().getOperationKind())
        if (kindTitle != null) parts.add(kindTitle)
        parts.addAll(state().getAccountNames(this))

        if (state().getSearchText().isNotEmpty()) parts.add("«" + state().getSearchText() + "»")

        val amountRange = rangeSummary(state().getAmountMin(), state().getAmountMax())
        if (amountRange != null) parts.add(amountRange)

        val eqRange = rangeSummary(state().getEqMin(), state().getEqMax())
        if (eqRange != null) {
            val currencyEq: Currency? = currencyEq
            parts.add(eqRange + (if (currencyEq != null) " " + currencyEq.iso else ""))
        }

        if (parts.isEmpty()) {
            filterBanner.visibility = View.GONE
            return
        }

        filterBanner.visibility = View.VISIBLE
        findViewById<TextView>(R.id.filterSummary).text = getString(R.string.filter_banner, join(parts))
    }

    /** null — фильтра по виду нет, показывать в резюме нечего. */
    private fun operationKindTitle(kind: DataFilter.OperationKind): String? {
        return when (kind) {
            DataFilter.OperationKind.income -> getString(R.string.filter_only_income)
            DataFilter.OperationKind.expense -> getString(R.string.filter_only_expense)
            DataFilter.OperationKind.transfer -> getString(R.string.filter_only_transfers)
            else -> null
        }
    }

    /** null — обе границы не заданы, показывать в резюме нечего. */
    private fun rangeSummary(min: Double?, max: Double?): String? {
        if (min == null && max == null) return null
        if (min != null && max != null) return formatCompact(min) + "–" + formatCompact(max)
        if (min != null) return getString(R.string.filter_range_from, formatCompact(min))
        return getString(R.string.filter_range_to, formatCompact(max!!))
    }

    protected fun updateCurrencyButton() {
        val btnCurrencyEq = findViewById<TextView>(R.id.btnCurrencyEq) ?: return

        val currencyEq: Currency? = currencyEq
        btnCurrencyEq.text = if (currencyEq != null) currencyEq.iso else "—"
    }

    // ── Обработчики общих layout'ов ───────────────────────────────────────────

    fun btnChangeFilter(view: View) {
        val intent = Intent(this, FilterActivity::class.java)
        intent.putExtra(MainActivity.cFilterAccountList, state().getAccountIdsCsv())
        startActivityForResult(intent, requestCodeSharedFilter)
    }

    fun onClearFilter(view: View) {
        state().clearFilter(this)
        reloadSharedAndTabData()
    }

    /** Валюта эквивалента: выбор общий для всех табов, поэтому уходит в PeriodFilterState. */
    fun onPickCurrencyEq(view: View) {
        val popup = PopupMenu(this, view)
        var currencyEq: Currency? = currencyEq
        // Заглушка пустого справочника — не валюта, в списке ей не место.
        if (PeriodFilterState.isPlaceholder(currencyEq)) currencyEq = null

        // Все валюты с флагом «Показывать в эквиваленте» + текущая — как в «Настройках»;
        // кошелёк в валюте не нужен (equivalent-picker-by-flag).
        val allCurrencies = ArrayList(Singleton.instance.getCurrencyList(this))
        var idx = 0
        for (currency in EquivalentCurrencies.filter(allCurrencies, currencyEq)) {
            val menuItem = popup.menu.add(
                R.id.checkgroup, currency.id, idx,
                currency.iso + " (" + currency.symbol + ")"
            )
            menuItem.isCheckable = true
            if (currencyEq != null && currencyEq.id == currency.id) menuItem.isChecked = true
            ++idx
        }
        popup.menu.setGroupCheckable(R.id.checkgroup, true, true)

        popup.setOnMenuItemClickListener { item ->
            state().setCurrencyEq(
                this,
                Singleton.instance.getCurrencyList(this).getCurrencyById(item.itemId)
            )
            updateCurrencyButton()
            reloadSharedAndTabData()
            true
        }

        popup.show()
    }

    fun btnOperationAdd(view: View) {
        val intent = Intent(this, TransactionActivity::class.java)
        intent.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(Date()))
        intent.putExtra(TransactionActivity.cComment, "")
        intent.putExtra(TransactionActivity.cAmount, 0.0)
        intent.putExtra(TransactionActivity.cIsEdit, false)
        putFilterPrefill(intent)
        startActivityForResult(intent, requestCodeSharedOperationAdd)
    }

    /**
     * Лента отфильтрована по счетам — новая операция почти наверняка про них же: подставляем
     * первый выбранный кошелёк и первую категорию фильтра. Тип берём из фильтра по виду
     * операции; без него — расход, как и при пустом окне. У перевода кошелёк фильтра
     * встаёт в «Откуда», второй выбранный кошелёк (если есть) — в «Куда».
     * Кошелька в фильтре нет — подставляем последний использованный.
     */
    private fun putFilterPrefill(intent: Intent) {
        var kind = state().getOperationKind()
        if (kind == DataFilter.OperationKind.all) kind = DataFilter.OperationKind.expense

        val accounts = Singleton.instance.getAccounts(this)
        val wallets = ArrayList<AccountItem>()
        var category: AccountItem? = null

        // Порядок id в фильтре — порядок выбора, поэтому «первый» здесь и есть первый выбранный.
        for (id in state().getAccountIds()) {
            val item = accounts.getAccountById(id)
            if (item == null || item.isArchived || item.isSystem) continue

            if (item.isOperatingAccount) wallets.add(item)
            else if (category == null) category = item
        }

        val wallet = if (wallets.isEmpty()) lastWallet(accounts) else wallets[0]
        val from: AccountItem?
        val to: AccountItem?
        when (kind) {
            DataFilter.OperationKind.income -> {
                from = category
                to = wallet
            }
            DataFilter.OperationKind.transfer -> {
                from = wallet
                to = if (wallets.size > 1) wallets[1] else null
            }
            else -> {
                from = wallet
                to = category
            }
        }

        intent.putExtra(TransactionActivity.cKind, kind.name)
        if (from != null) intent.putExtra(TransactionActivity.cFrom, from.id.toInt())
        if (to != null) intent.putExtra(TransactionActivity.cTo, to.id.toInt())
    }

    /** Кошелёк последней сохранённой операции, если его с тех пор не удалили и не убрали в архив. */
    private fun lastWallet(accounts: AccountList): AccountItem? {
        val id = AppSettings.getLastWalletId(this)
        val item = if (id > 0) accounts.getAccountById(id) else null
        if (item == null || !item.isOperatingAccount || item.isArchived) return null
        // Заём (debts) кошельком новой операции не подставляем: он не «мой кошелёк».
        if (item is AccountOperatingItem && item.isDebt) return null
        return item
    }

    // ── Плитки «Доход» и «Расход» карточки (android:onClick в view_period_summary.xml) ──

    fun onFilterIncome(view: View) {
        openOperationsFilteredByKind(DataFilter.OperationKind.income)
    }

    fun onFilterExpense(view: View) {
        openOperationsFilteredByKind(DataFilter.OperationKind.expense)
    }

    /**
     * Цифра в карточке — свёрнутый итог, за ним всегда стоят конкретные операции: тап
     * разворачивает его в ленту «Операций», где остаются только операции этого вида.
     * Фильтр общий, поэтому он виден в строке-резюме шапки и снимается тем же «Сбросить».
     */
    private fun openOperationsFilteredByKind(kind: DataFilter.OperationKind) {
        state().setOperationKind(this, kind)

        // Уже на «Операциях» — переходить некуда, но ленту надо перечитать: onResume,
        // который обычно ловит смену фильтра, здесь не сработает.
        if (javaClass == MainActivity::class.java) reloadSharedAndTabData()
        else openTab(MainActivity::class.java)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            requestCodeSharedFilter -> if (resultCode == RESULT_OK) {
                state().setAccountIdsCsv(
                    this,
                    data?.getStringExtra(MainActivity.cFilterAccountList)
                )
                // Период на «Фильтре» тоже могли сменить. reloadSharedAndTabData
                // запоминает ревизию, поэтому onResume сюда уже не вернётся и
                // пилюлю периода надо обновить прямо здесь.
                if (fragmentDataPeriod != null) applySharedPeriod()
                reloadSharedAndTabData()
            }

            requestCodeSharedOperationAdd -> if (resultCode == RESULT_OK) reloadSharedAndTabData()
        }
    }

    // ── Нижняя панель (android:onClick в view_bottom_nav.xml) ──────────────────

    fun navOperations(view: View) {
        openTab(MainActivity::class.java)
    }

    fun navWallets(view: View) {
        openTab(AccountActivity::class.java)
    }

    fun navReport(view: View) {
        openTab(ReportActivity::class.java)
    }

    fun navMore(view: View) {
        openTab(MoreActivity::class.java)
    }

    /**
     * REORDER_TO_FRONT: уже открытый таб поднимается, а не создаётся заново, поэтому
     * стек не растёт от прыжков по панели, а его список не грузится с нуля. Актуальность
     * периода и фильтра при этом проверяет [onResume].
     */
    private fun openTab(target: Class<*>) {
        if (javaClass == target) return

        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(0, 0)
    }

    companion object {
        /** Коды запросов общей шапки; наследники нумеруют свои ниже 900. */
        protected const val requestCodeSharedFilter = 900
        protected const val requestCodeSharedOperationAdd = 901

        private const val STATE_OTHER_EXPANDED = "summaryOtherExpanded"

        /** Без лишних нулей после запятой: 100.0 → «100», 100.5 → «100.5». */
        private fun formatCompact(value: Double): String {
            return AdapterUtils.plainNumber(value)
        }

        private fun join(parts: List<String>): String {
            val joined = StringBuilder()
            for (part in parts) {
                if (joined.isNotEmpty()) joined.append(", ")
                joined.append(part)
            }
            return joined.toString()
        }
    }
}
