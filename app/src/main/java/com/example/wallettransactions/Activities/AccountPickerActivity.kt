package com.example.wallettransactions.Activities

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.AccountNames
import com.example.wallettransactions.Data.DebtDirection
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CatalogFilter
import com.example.wallettransactions.DataItems.Accounts.CategoryKindFilter
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.PeriodFilterState
import com.example.wallettransactions.PickerSort
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * Экран со списком счетов. Работает в двух режимах, различает их [cCatalogMode]:
 *
 *  * **выбор счёта для операции** — тап по строке возвращает её id и закрывает
 *  экран. Пришёл на смену списку-диалогу внутри [AccountActivity], где он
 *  мешался с дашбордом «Кошельки»;
 *
 *  * **справочник** (пункт «Кошельки и категории» на табе «Ещё») — тот же
 *  список, но тап открывает счёт на редактирование ([AccountItemActivity]),
 *  в шапке есть «+», и видны архивные счета: снять архив больше негде.
 *
 * Строка списка — строка кошелька из отчёта «Кошельки» без остатка на начало
 * периода и без изменения за период: при вводе операции важно только, сколько на
 * счёте сейчас. Остаток берётся на конец общего периода — ровно то же число, что
 * показывает таб «Кошельки», чтобы экраны не расходились.
 *
 * Тип счёта задаёт [cAccountSelectedType]:
 *
 *  * [cSelectedTypeALL] — и категории, и кошельки, сверху переключатель;
 *  * [cSelectedTypeChargeAccounts] — только категории трат,
 *  * [cSelectedTypeOperationAccounts] — только кошельки.
 *
 * В двух последних случаях переключатель скрыт: слот операции уже знает свой тип,
 * и подменять его выбором из другого списка нельзя.
 *
 * Порядок строк ([PickerSort]) у кошельков и категорий свой: на входе — из
 * «Настроек», кнопка в шапке переключает его по кругу только на этот раз, в
 * настройки не сохраняя. «По частоте» учитывает счёт из
 * второго слота операции ([cPairAccountId]), если это кошелёк: сверху то, что
 * чаще всего шло именно с ним, ниже — по общей частоте.
 */
class AccountPickerActivity : AppCompatActivity() {

    private val mWalletRows = ArrayList<Row>()
    private val mCategoryRows = ArrayList<Row>()

    private val mVisibleRows = ArrayList<Row>()

    /** `_id` → место среди приоритетных ([cPriorityIds]). */
    private val mPriorityRank = HashMap<Int, Int>()
    private var mAdapter: AccountPickerAdapter? = null

    private lateinit var mKindCategories: TextView
    private lateinit var mKindWallets: TextView
    private lateinit var mBtnSort: TextView
    private lateinit var mSearchInput: EditText
    private lateinit var mEmptyState: View

    private var mShowWallets = false

    private var mCatalogMode = false

    /**
     * Фильтры справочника (openspec catalog-filters-panel): тип кошелька, виды категорий,
     * «Показывать архивные». Живут, пока открыт экран; при каждом открытии — значения по умолчанию.
     */
    private val mFilter = CatalogFilter()

    /**
     * Состояние панели «Фильтры» на каждой вкладке свое. Исходное берётся из настроек
     * ([AppSettings.isCatalogPanelOpen]); кнопка «Фильтры» меняет только эти поля и в настройки
     * не пишет.
     */
    private var mWalletsPanelOpen = false
    private var mCategoriesPanelOpen = false
    private lateinit var mFiltersButton: TextView
    private var mCurrentAccountId = 0

    /** Только кошелёк: с категорией в паре частота ничего не говорит о выборе кошелька. */
    private var mPairWalletId = 0

    /** Порядок списков: на входе — из «Настроек», кнопка в шапке меняет только здесь. */
    private lateinit var mWalletSort: PickerSort
    private lateinit var mCategorySort: PickerSort

    /** Читается из базы при первой сортировке по частоте; null — ещё не читали. */
    private var mUsage: Map<Int, WalletDbLoader.AccountUsage>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_picker)

        mKindCategories = findViewById(R.id.kindCategories)
        mKindWallets = findViewById(R.id.kindWallets)
        mBtnSort = findViewById(R.id.btnSort)
        mSearchInput = findViewById(R.id.searchInput)
        mEmptyState = findViewById(R.id.emptyState)

        val title = intent.getStringExtra(cTitle)
        if (title != null) findViewById<TextView>(R.id.pickerTitle).text = title

        mCatalogMode = intent.getBooleanExtra(cCatalogMode, false)
        mCurrentAccountId = intent.getIntExtra(cAccountId, 0)
        val selectedType = intent.getIntExtra(cAccountSelectedType, cSelectedTypeALL)

        val priority = intent.getIntArrayExtra(cPriorityIds)
        if (priority != null) for (i in priority.indices) mPriorityRank.putIfAbsent(priority[i], i)

        val pairId = intent.getIntExtra(cPairAccountId, 0)
        val pair = if (pairId > 0) Singleton.instance.getAccounts(this).getAccountById(pairId) else null
        mPairWalletId = if (pair != null && pair.isOperatingAccount) pairId else 0

        mWalletSort = AppSettings.getPickerSort(this, true)
        mCategorySort = AppSettings.getPickerSort(this, false)

        findViewById<View>(R.id.btnAddAccountRow).visibility = if (mCatalogMode) View.VISIBLE else View.GONE
        // Панель «Фильтры» (чипы типов и видов, «Показывать архивные») — только в справочнике; всё
        // сброшено при каждом открытии. Выбор счёта при вводе операции архивные по-прежнему скрывает
        // и панели не имеет (спека account-screens).
        mFiltersButton = findViewById(R.id.btnFilters)
        mFiltersButton.visibility = if (mCatalogMode) View.VISIBLE else View.GONE
        mWalletsPanelOpen = mCatalogMode && AppSettings.isCatalogPanelOpen(this, true)
        mCategoriesPanelOpen = mCatalogMode && AppSettings.isCatalogPanelOpen(this, false)
        val debtActions = !mCatalogMode && selectedType == cSelectedTypeChargeAccounts
                && intent.getBooleanExtra(cDebtActions, false)
        findViewById<View>(R.id.rowDebtActions).visibility = if (debtActions) View.VISIBLE else View.GONE

        mRenderedRevision = PeriodFilterState.get(this).getRevision()
        buildRows()

        // Тип задан слотом операции — переключать его нельзя, поэтому и показывать нечего.
        val typeIsFixed = selectedType != cSelectedTypeALL
        findViewById<View>(R.id.rowKindFilter).visibility = if (typeIsFixed) View.GONE else View.VISIBLE

        if (typeIsFixed) {
            mShowWallets = selectedType == cSelectedTypeOperationAccounts
        } else {
            // Открываемся на той вкладке, где лежит уже выбранный счёт.
            val current = if (mCurrentAccountId > 0)
                Singleton.instance.getAccounts(this).getAccountById(mCurrentAccountId) else null
            mShowWallets = current == null || current.isOperatingAccount
        }

        val adapter = AccountPickerAdapter(this, mVisibleRows, currencyEq(), mCurrentAccountId)
        mAdapter = adapter

        val list = findViewById<ListView>(R.id.listAccounts)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val accountId = mVisibleRows[position].account.id

            if (mCatalogMode) {
                val edit = Intent(this, AccountItemActivity::class.java)
                edit.putExtra(AccountItemActivity.ARG_ACCOUNT_ID, accountId)
                startActivityForResult(edit, REQUEST_EDIT_ACCOUNT)
                return@setOnItemClickListener
            }

            val result = Intent()
            result.putExtra(cAccountId, accountId)
            setResult(RESULT_OK, result)
            finish()
        }

        mSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })

        paintKindChips()
        applyFilters()
        AppSettings.applyPickerKeyboard(this, mSearchInput)
    }

    fun btnUp(view: View) {
        finish()
    }

    /** «Дать в долг» / «Взять в долг» / «Вернуть» — см. [DebtPickFlow]. */
    fun onDebtActionClick(view: View) {
        val id = view.id
        val action = if (id == R.id.debtActionGive) DebtDirection.Action.GIVE
        else if (id == R.id.debtActionTake) DebtDirection.Action.TAKE
        else DebtDirection.Action.RETURN
        val pairId = intent.getIntExtra(cPairAccountId, 0)
        val pair = if (pairId > 0) Singleton.instance.getAccounts(this).getAccountById(pairId) else null
        DebtPickFlow.start(
            this, action,
            pair as? AccountOperatingItem
        ) { loanId, direction ->
            val result = Intent()
            result.putExtra(cAccountId, loanId)
            result.putExtra(cDebtDirection, direction)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    /** Тот же выбор, что на табе «Кошельки»: справочник — второе место, где счета заводят. */
    fun onAddAccount(view: View) {
        AlertDialog.Builder(this)
            .setTitle(R.string.picker_add_title)
            .setItems(arrayOf(getString(R.string.picker_add_wallet), getString(R.string.picker_add_category))) { _, which ->
                if (which == 0) {
                    startActivityForResult(Intent(this, AddWalletActivity::class.java), REQUEST_ADD_WALLET)
                } else {
                    startActivityForResult(Intent(this, AddCategoryActivity::class.java), REQUEST_ADD_CATEGORY)
                }
            }
            .show()
    }

    /** Ревизия данных, под которую собран список, — см. [reloadIfStale]. */
    private var mRenderedRevision = 0

    /**
     * Данные сменились, пока экран был открыт: после архивации со списанием остатка
     * стартует фоновый синк, и его итог (а с ним и правка/удаление счёта, сделанные сразу
     * после архивации) долетает уже после onActivityResult — без этого строка удалённой
     * категории оставалась на экране.
     */
    private val mOnDataChanged = Runnable { reloadIfStale() }

    override fun onResume() {
        super.onResume()
        reloadIfStale()
        PeriodFilterState.get(this).addDataChangedListener(mOnDataChanged)
    }

    override fun onPause() {
        PeriodFilterState.get(this).removeDataChangedListener(mOnDataChanged)
        super.onPause()
    }

    private fun reloadIfStale() {
        val revision = PeriodFilterState.get(this).getRevision()
        if (mAdapter == null || revision == mRenderedRevision) return
        refreshRows()
    }

    /** Перечитывает справочник из базы и перестраивает видимый список. */
    private fun refreshRows() {
        mRenderedRevision = PeriodFilterState.get(this).getRevision()
        Singleton.instance.reloadAccounts(this)
        buildRows()
        applyFilters()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Счёт могли переименовать, заархивировать или удалить — список пересобираем.
        when (requestCode) {
            REQUEST_EDIT_ACCOUNT, REQUEST_ADD_WALLET, REQUEST_ADD_CATEGORY ->
                if (resultCode == RESULT_OK) refreshRows()
        }
    }

    fun onKindFilterClick(view: View) {
        val pickedWallets = view.id == R.id.kindWallets
        if (pickedWallets == mShowWallets) return

        mShowWallets = pickedWallets
        paintKindChips()
        applyFilters()
    }

    fun onWalletTypeClick(view: View) {
        val id = view.id
        val picked = if (id == R.id.walletTypeRegular) CatalogFilter.WalletType.REGULAR
        else if (id == R.id.walletTypeAssets) CatalogFilter.WalletType.ASSET
        else if (id == R.id.walletTypeDebts) CatalogFilter.WalletType.DEBT else CatalogFilter.WalletType.ALL
        if (picked == mFilter.walletType) return
        mFilter.walletType = picked
        paintKindChips()
        applyFilters()
    }

    /** Кнопка «Фильтры»: раскрывает или сворачивает панель текущей вкладки, настройку не трогает. */
    fun onToggleFiltersPanel(view: View) {
        if (mShowWallets) mWalletsPanelOpen = !mWalletsPanelOpen
        else mCategoriesPanelOpen = !mCategoriesPanelOpen
        paintKindChips()
    }

    /** Чип «Показывать архивные»: общий для обеих вкладок; архив отсекается при сборке списков. */
    fun onShowArchivedClick(view: View) {
        mFilter.showArchived = !mFilter.showArchived
        paintKindChips()
        buildRows()
        applyFilters()
    }

    /** Чип вида на «Категориях»: мультивыбор, тап добавляет вид или убирает его. */
    fun onCategoryKindClick(view: View) {
        val kind = when (view.id) {
            R.id.catalogKindExpense -> AccountItem.CategoryKind.EXPENSE
            R.id.catalogKindIncome -> AccountItem.CategoryKind.INCOME
            else -> AccountItem.CategoryKind.BOTH
        }
        mFilter.toggleKind(kind)
        paintKindChips()
        applyFilters()
    }

    private fun matchesWalletType(account: AccountItem): Boolean {
        if (!mCatalogMode) return true
        val asset = isAsset(account)
        val debt = account is AccountOperatingItem && account.isDebt
        return mFilter.matchesWallet(asset, debt)
    }

    /** Отбор по виду — только в справочнике; слот операции фильтрует [CategoryKindFilter] в buildRows. */
    private fun matchesCategoryKind(account: AccountItem): Boolean =
        !mCatalogMode || mFilter.matchesCategory(account.categoryKind, account.isSystem)

    private fun paintKindChips() {
        paintChip(mKindCategories, !mShowWallets)
        paintChip(mKindWallets, mShowWallets)

        // Панель — только в справочнике; у каждой вкладки свой флаг, ряды чипов зависят от вкладки.
        val panelOpen = mCatalogMode && (if (mShowWallets) mWalletsPanelOpen else mCategoriesPanelOpen)
        findViewById<View>(R.id.catalogFilterPanel).visibility = if (panelOpen) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowWalletTypeFilter).visibility = if (mShowWallets) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowCategoryKindFilter).visibility = if (mShowWallets) View.GONE else View.VISIBLE
        paintChip(findViewById(R.id.walletTypeAll), mFilter.walletType == CatalogFilter.WalletType.ALL)
        paintChip(findViewById(R.id.walletTypeRegular), mFilter.walletType == CatalogFilter.WalletType.REGULAR)
        paintChip(findViewById(R.id.walletTypeAssets), mFilter.walletType == CatalogFilter.WalletType.ASSET)
        paintChip(findViewById(R.id.walletTypeDebts), mFilter.walletType == CatalogFilter.WalletType.DEBT)
        paintChip(findViewById(R.id.catalogKindExpense), mFilter.hasKind(AccountItem.CategoryKind.EXPENSE))
        paintChip(findViewById(R.id.catalogKindIncome), mFilter.hasKind(AccountItem.CategoryKind.INCOME))
        paintChip(findViewById(R.id.catalogKindBoth), mFilter.hasKind(AccountItem.CategoryKind.BOTH))
        paintChip(findViewById(R.id.pickerShowArchived), mFilter.showArchived)
        paintFiltersButton(panelOpen)
        // У каждого списка свой порядок — кнопка показывает режим видимого.
        paintSortButton()
    }

    /** «Фильтры ▾/▴» и, если есть нестандартные условия текущей вкладки, их число. */
    private fun paintFiltersButton(open: Boolean) {
        if (!mCatalogMode) return
        val count = mFilter.activeCount(mShowWallets)
        val label = if (open) {
            if (count > 0) R.string.picker_filters_open_count else R.string.picker_filters_open
        } else {
            if (count > 0) R.string.picker_filters_closed_count else R.string.picker_filters_closed
        }
        mFiltersButton.text = getString(label, count)
    }

    /** Тот же способ подсветки чипа, что на экране операции. */
    private fun paintChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.colorSurface else R.color.colorTextSecondary
            )
        )
    }

    private fun applyFilters() {
        // Именно подстрока, а не AccountItem.isLike: тот принимает строку как регулярное
        // выражение, и одна скобка, набранная в поиске, уронила бы экран. Правило общее для
        // всех поисков по имени: без регистра, «ё» = «е» (AccountNames.matchesSearch).
        val query = mSearchInput.text.toString()

        mVisibleRows.clear()
        for (row in (if (mShowWallets) mWalletRows else mCategoryRows)) {
            if (AccountNames.matchesSearch(row.account.displayName, query)
                && (if (mShowWallets) matchesWalletType(row.account) else matchesCategoryKind(row.account))
            )
                mVisibleRows.add(row)
        }

        mAdapter!!.notifyDataSetChanged()
        mEmptyState.visibility = if (mVisibleRows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun currencyEq(): Currency = PeriodFilterState.get(this).getCurrencyEq(this)

    /**
     * Кошельки с остатком на конец периода + категории трат. Остатки приходят только
     * по счетам, где были операции, поэтому список строится от самих счетов — иначе
     * только что заведённый кошелёк выбрать было бы нельзя.
     */
    private fun buildRows() {
        val accounts = Singleton.instance.getAccounts(this)
        val currencyEq: Currency? = currencyEq()
        val hideSystem = intent.getBooleanExtra(cHideSystem, false)
        val hideDebts = !mCatalogMode && intent.getBooleanExtra(cHideDebts, false)
        // Вид операции — только при выборе статьи; в справочнике (mCatalogMode) фильтра нет.
        val operationKind = if (mCatalogMode) null
        else CategoryKindFilter.operationKindFromExtra(
            intent.getIntExtra(cCategoryKind, CategoryKindFilter.NO_FILTER)
        )

        mWalletRows.clear()
        mCategoryRows.clear()
        mUsage = null

        val balances = HashMap<Int, WalletDbLoader.WalletBalanceRow>()
        if (currencyEq != null) {
            val db = Singleton.instance.getReadableDatabase(this)
            val dateTo = PeriodFilterState.get(this).getDateTo()!!
            for (r in WalletDbLoader.LoadBalancesPerWallet(db, dateTo, dateTo, currencyEq)) {
                balances[r.accountId] = r
            }
        }

        for (account in accounts.allAccounts) {
            // Архивный счёт на то и архивный, чтобы не попадаться при вводе операции. В справочнике
            // он нужен, чтобы снять архив, — но за переключателем «Показывать архивные», по
            // умолчанию выключенным. Флаг — только для показа: в расчётах и отчётах счёт остаётся
            // (AccountItem.getIsArchived).
            if ((!mCatalogMode || !mFilter.showArchived) && account.isArchived == true) continue
            // «Переоценку» при вводе операции не предлагаем (capital-assets): её проводки
            // пишет диалог переоценки актива. В справочнике она видна.
            if (!mCatalogMode && account.isSystemRevaluation) continue
            if (hideSystem && account.isSystem) continue
            // Займы (debts) — при вводе новой операции не предлагаются; уже выбранный
            // (правка операции) остаётся.
            if (hideDebts && account is AccountOperatingItem
                && account.isDebt
                && account.id != mCurrentAccountId
            ) continue

            if (account.isOperatingAccount) {
                mWalletRows.add(Row(account, balances[account.id]))
            } else {
                // Вид категории (category-kind): уже выбранная и приоритетные (ИИ, импорт выписки)
                // остаются в списке, даже если их вид не подходит слоту.
                val keep = account.id == mCurrentAccountId
                        || mPriorityRank.containsKey(account.id)
                if (!CategoryKindFilter.isVisible(account.categoryKind, operationKind, keep)) continue
                mCategoryRows.add(Row(account, null))
            }
        }

        sortRows()
    }

    // ── Сортировка ────────────────────────────────────────────────────────────

    /**
     * Тап по кнопке в шапке — следующий режим видимого списка по кругу. Только на этот
     * раз: в настройки не пишется, следующий экран выбора снова откроется с режимом
     * из «Настроек».
     */
    fun onPickSort(view: View) {
        val modes = if (mShowWallets) PickerSort.WALLET_MODES else PickerSort.CATEGORY_MODES
        val next = modes[(PickerSort.indexOf(modes, currentSort()) + 1) % modes.size]
        if (mShowWallets) mWalletSort = next else mCategorySort = next

        sortRows()
        paintSortButton()
        applyFilters()
    }

    private fun currentSort(): PickerSort = if (mShowWallets) mWalletSort else mCategorySort

    private fun paintSortButton() {
        mBtnSort.text = "⇅ " + currentSort().getTitle(this)
    }

    private fun sortRows() {
        // capital-assets: при вводе операции активы — после всех ликвидных кошельков при
        // любой сортировке (стабильное разбиение поверх выбранного порядка). В справочнике
        // активы стоят вместе с кошельками, только с подписью типа.
        var walletOrder = withPriority(comparator(mWalletSort))
        if (!mCatalogMode) {
            walletOrder = compareBy<Row> { if (isAsset(it.account)) 1 else 0 }.then(walletOrder)
        }
        mWalletRows.sortWith(walletOrder)
        mCategoryRows.sortWith(withPriority(comparator(mCategorySort)))
    }

    /** Приоритетные счета — первыми в своём порядке, остальные — по `rest`. */
    private fun withPriority(rest: Comparator<Row>): Comparator<Row> {
        if (mPriorityRank.isEmpty()) return rest
        return compareBy<Row> { mPriorityRank.getOrDefault(it.account.id, Int.MAX_VALUE) }.then(rest)
    }

    private fun comparator(sort: PickerSort): Comparator<Row> {
        val byName = Comparator<Row> { a, b -> a.account.displayName.compareTo(b.account.displayName, ignoreCase = true) }

        return when (sort) {
            PickerSort.BALANCE ->
                // Как в отчёте: крупные остатки сверху, счета без курса к валюте
                // эквивалента опускаются под те, что можно сравнить.
                compareByDescending<Row> { it.hasEq }
                    .thenByDescending { if (it.hasEq) it.balanceEq else it.balance }
                    .then(byName)

            PickerSort.USAGE -> {
                // Сначала то, что шло с кошельком из второго слота, потом общая частота.
                // При равном счёте выше тот, что использовали позже; ни разу не
                // использованные — в конце по алфавиту.
                val usage = usage()
                val none = WalletDbLoader.AccountUsage()
                val laterFirst: Comparator<String?> = Comparator.nullsLast(Comparator.reverseOrder<String>())
                val of = { r: Row -> usage.getOrDefault(r.account.id, none) }
                compareBy<Row> { -of(it).pairCount }
                    .thenComparator { a, b -> laterFirst.compare(of(a).pairLastDate, of(b).pairLastDate) }
                    .thenBy { -of(it).count }
                    .thenComparator { a, b -> laterFirst.compare(of(a).lastDate, of(b).lastDate) }
                    .then(byName)
            }

            else -> byName
        }
    }

    private fun usage(): Map<Int, WalletDbLoader.AccountUsage> {
        var u = mUsage
        if (u == null) {
            u = WalletDbLoader.LoadAccountUsage(
                Singleton.instance.getReadableDatabase(this), mPairWalletId
            )
            mUsage = u
        }
        return u!!
    }

    /** Счёт для строки списка: у кошелька есть остаток, у категории — нет. */
    private class Row(val account: AccountItem, balanceRow: WalletDbLoader.WalletBalanceRow?) {
        val currencySymbol: String
        val balance: Double
        val balanceEq: Double
        val hasBalance: Boolean
        val hasEq: Boolean
        val approx: Boolean

        init {
            val wallet = account as? AccountOperatingItem

            // Кошелёк без операций в LoadBalancesPerWallet не попадает — у него ровно ноль.
            hasBalance = wallet != null
            currencySymbol = if (wallet != null && wallet.currency != null) wallet.currency.symbol else ""
            balance = balanceRow?.balance ?: 0.0
            balanceEq = if (balanceRow != null && balanceRow.hasEq) balanceRow.balanceEq else 0.0
            hasEq = balanceRow == null || balanceRow.hasEq
            approx = balanceRow != null && balanceRow.approx
        }
    }

    // ── Адаптер (account_picker_item_layout.xml) ──────────────────────────────

    private class AccountPickerAdapter(
        ctx: Context, rows: List<Row>,
        private val mCurrencyEq: Currency?,
        private val mCurrentAccountId: Int
    ) : ArrayAdapter<Row>(ctx, R.layout.account_picker_item_layout, rows) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.account_picker_item_layout, parent, false)

            val row = getItem(position)!!

            val imgIcon = view.findViewById<ImageView>(R.id.pickerIcon)
            val imgFlag = view.findViewById<ImageView>(R.id.pickerFlag)
            val tvName = view.findViewById<TextView>(R.id.pickerName)
            val tvBalance = view.findViewById<TextView>(R.id.pickerBalance)
            val tvBalanceEq = view.findViewById<TextView>(R.id.pickerBalanceEq)

            if (row.account.icon != null) {
                imgIcon.setImageBitmap(row.account.icon!!.bitmap)
            } else {
                imgIcon.setImageResource(android.R.drawable.ic_menu_myplaces)
            }

            val wallet = row.account as? AccountOperatingItem
            if (wallet != null && wallet.currency != null && wallet.currency.icon != null) {
                imgFlag.setImageBitmap(wallet.currency!!.icon!!.bitmap)
                imgFlag.visibility = View.VISIBLE
            } else {
                imgFlag.visibility = View.GONE
            }

            val archived = row.account.isArchived == true
            var name = row.account.displayName
            if (wallet != null && wallet.isAsset) {
                name = view.context.getString(
                    R.string.picker_asset_name, name,
                    assetTypeTitle(view.context, wallet.asset!!.type)
                )
            }
            tvName.text = if (archived) view.context.getString(R.string.picker_archived_name, name) else name

            var nameColor = R.color.colorTextPrimary
            if (archived) nameColor = R.color.colorTextHint
            else if (row.account.id == mCurrentAccountId) nameColor = R.color.colorPrimary
            tvName.setTextColor(ContextCompat.getColor(context, nameColor))

            if (!row.hasBalance) {
                tvBalance.visibility = View.GONE
                tvBalanceEq.visibility = View.GONE
                return view
            }

            tvBalance.visibility = View.VISIBLE
            tvBalance.text = AdapterUtils.getFormat(2).format(row.balance) + " " + row.currencySymbol

            // Эквивалент показываем только когда он что-то добавляет: у кошелька в валюте
            // эквивалента это была бы та же сумма второй раз.
            if (mCurrencyEq == null || !row.approx) {
                tvBalanceEq.visibility = View.GONE
            } else {
                tvBalanceEq.visibility = View.VISIBLE
                tvBalanceEq.text = if (row.hasEq)
                    "≈ " + AdapterUtils.getFormat(mCurrencyEq.precision).format(row.balanceEq) +
                            " " + mCurrencyEq.symbol
                else
                    "~"
            }

            return view
        }
    }

    companion object {
        const val cSelectedTypeALL = 0
        const val cSelectedTypeOperationAccounts = 1
        const val cSelectedTypeChargeAccounts = 2

        const val cAccountSelectedType = "AccountPicker.SelectedType"

        /** На входе — выбранный сейчас счёт (0, если не выбран), на выходе — выбранный. */
        const val cAccountId = "AccountPicker.AccountId"
        const val cTitle = "AccountPicker.Title"
        const val cCatalogMode = "AccountPicker.CatalogMode"

        /** Счёт из второго слота операции (0 — нет): по нему считается частота «в паре». */
        const val cPairAccountId = "AccountPicker.PairAccountId"

        /**
         * Счета, которые идут первыми в указанном порядке при любой сортировке
         * (int[] `_id`): кандидаты resolver с экрана «ИИ», подсказка категории
         * импорта выписки. Остальные — как обычно.
         */
        const val cPriorityIds = "AccountPicker.PriorityIds"

        /**
         * Не показывать системные счета («Перевод», «Комиссия», «Переоценка»): списание остатка
         * при архивации кошелька идёт только на обычную категорию (archive-wallet-writeoff, D8).
         */
        const val cHideSystem = "AccountPicker.HideSystem"

        /**
         * Над статьями — «Дать в долг», «Взять в долг», «Вернуть» (openspec debts, design D6).
         * Результат тогда — кошелёк займа в [cAccountId] и [cDebtDirection].
         */
        const val cDebtActions = "AccountPicker.DebtActions"

        /** Не предлагать займы среди кошельков — ввод новой операции (спека, «Долговой кошелёк в обычном пикере»). */
        const val cHideDebts = "AccountPicker.HideDebts"

        /** На выходе: `DebtDirection.WALLET_TO_LOAN`/`LOAN_TO_WALLET`; нет — обычный выбор. */
        const val cDebtDirection = "AccountPicker.DebtDirection"

        /**
         * Вид операции для «Статьи расхода» (`0`) и «Статьи дохода» (`1`): в списке только
         * категории этого вида и вида «Оба» (openspec category-kind-income-expense). Нет extra —
         * фильтра нет и видны все категории (справочник, перенос между категориями, прощение долга).
         * Выбранная категория и приоритетные кандидаты остаются в списке при любом виде.
         */
        const val cCategoryKind = "AccountPicker.CategoryKind"

        private const val REQUEST_EDIT_ACCOUNT = 300
        private const val REQUEST_ADD_WALLET = 301
        private const val REQUEST_ADD_CATEGORY = 302

        @JvmStatic
        @JvmOverloads
        fun buildIntent(
            ctx: Context, title: String?, selectedType: Int, currentAccountId: Int,
            pairAccount: AccountItem? = null, priorityIds: IntArray? = null
        ): Intent {
            val intent = Intent(ctx, AccountPickerActivity::class.java)
            if (priorityIds != null && priorityIds.isNotEmpty()) intent.putExtra(cPriorityIds, priorityIds)
            intent.putExtra(cTitle, title)
            intent.putExtra(cAccountSelectedType, selectedType)
            intent.putExtra(cAccountId, currentAccountId)
            intent.putExtra(cPairAccountId, if (pairAccount == null) 0 else pairAccount.id)
            return intent
        }

        /** Справочник «Кошельки и категории»: оба списка, переключатель, правка по тапу. */
        @JvmStatic
        fun buildCatalogIntent(ctx: Context): Intent {
            val intent = Intent(ctx, AccountPickerActivity::class.java)
            intent.putExtra(cTitle, ctx.getString(R.string.picker_catalog_title))
            intent.putExtra(cAccountSelectedType, cSelectedTypeALL)
            intent.putExtra(cCatalogMode, true)
            return intent
        }

        private fun isAsset(account: AccountItem): Boolean {
            return account is AccountOperatingItem && account.isAsset
        }

        /** Подпись типа актива: «недвижимость», «авто», «прочее». */
        private fun assetTypeTitle(ctx: Context, type: Int): String {
            return when (type) {
                1 -> ctx.getString(R.string.asset_type_real_estate_short)
                2 -> ctx.getString(R.string.asset_type_vehicle_short)
                else -> ctx.getString(R.string.asset_type_other_short)
            }
        }
    }
}
