package com.example.wallettransactions.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.example.wallettransactions.AdapterUtils
import com.example.wallettransactions.Chips
import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.Data.WalletSections
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton
import com.google.android.material.appbar.AppBarLayout
import java.util.EnumSet

/**
 * Дашборд «Кошельки» — таб нижней панели: общая шапка всех корневых экранов
 * ([BaseTabActivity] — период, фильтр, карточка остатков) плюс список
 * остатков по кошелькам (`activity_account.xml`).
 *
 * Выбор счёта для операции жил здесь же вторым режимом и открывался списком-диалогом;
 * теперь это отдельный экран — [AccountPickerActivity]. Кнопки «+» в шапке нет:
 * счета заводятся только в справочнике «Кошельки и категории» (тот же
 * AccountPickerActivity, режим справочника; openspec currency-ui-tweaks, design D10).
 */
class AccountActivity : BaseTabActivity() {

    // Чипы секций и «Показывать архивные» живут на экране и сбрасываются при каждом его
    // создании; общий фильтр шапки они не затрагивают (openspec wallets-filter-and-debt-autoarchive, D4).
    private val selectedSections: MutableSet<WalletSections.Section> =
        EnumSet.noneOf(WalletSections.Section::class.java)
    private var showArchived = false
    private lateinit var chipLiquid: TextView
    private lateinit var chipCapital: TextView
    private lateinit var chipDebts: TextView
    private lateinit var chipsRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_account)
        // Вьюхи чипов — до initTabScreen: первая перезагрузка списка их уже красит.
        chipsRow = findViewById(R.id.walletsChipsRow)
        chipLiquid = findViewById(R.id.walletsChipLiquid)
        chipCapital = findViewById(R.id.walletsChipCapital)
        chipDebts = findViewById(R.id.walletsChipDebts)
        chipLiquid.setOnClickListener { toggleSection(WalletSections.Section.LIQUID) }
        chipCapital.setOnClickListener { toggleSection(WalletSections.Section.CAPITAL) }
        chipDebts.setOnClickListener { toggleSection(WalletSections.Section.DEBTS) }
        initTabScreen(R.id.navWallets, getString(R.string.tab_wallets))
        reloadSharedAndTabData()
    }

    private fun toggleSection(s: WalletSections.Section) {
        if (!selectedSections.remove(s)) selectedSections.add(s)
        reloadTabData()
    }

    private fun paintChips(available: Set<WalletSections.Section>) {
        chipLiquid.visibility = if (available.contains(WalletSections.Section.LIQUID)) View.VISIBLE else View.GONE
        chipCapital.visibility = if (available.contains(WalletSections.Section.CAPITAL)) View.VISIBLE else View.GONE
        chipDebts.visibility = if (available.contains(WalletSections.Section.DEBTS)) View.VISIBLE else View.GONE
        // Чипы выбирают среди секций; с одной-единственной выбирать нечего.
        chipsRow.visibility = if (available.size > 1) View.VISIBLE else View.GONE
        Chips.paint(this, chipLiquid, selectedSections.contains(WalletSections.Section.LIQUID))
        Chips.paint(this, chipCapital, selectedSections.contains(WalletSections.Section.CAPITAL))
        Chips.paint(this, chipDebts, selectedSections.contains(WalletSections.Section.DEBTS))
    }

    override fun reloadTabData() {
        val accounts = Singleton.instance.getAccounts(this)
        val walletRows = buildWalletRows()

        val listWallets = findViewById<ListView>(R.id.listWallets)
        listWallets.adapter = WalletBalanceAdapter(this, walletRows, currencyEq, accounts)
        // Сводка и чипы лежат в AppBarLayout и уезжают вверх вместе со списком. Пустой список
        // прокрутить нельзя — свёрнутую шапку (с датами периода) было бы не вернуть.
        if (walletRows.isEmpty()) {
            findViewById<AppBarLayout>(R.id.summaryBar).setExpanded(true, false)
        }

        // Строка кошелька — свёрнутый итог, за которым стоят операции: тап разворачивает
        // его в ленту «Операций» по этому кошельку. Так же ведёт себя строка категории в
        // «Отчётах» (ReportActivity.treeViewClick) — фильтр общий, поэтому он же виден в
        // шапке и снимается «Сбросить». Правка кошелька живёт в справочнике «Ещё».
        listWallets.setOnItemClickListener { _, _, position, _ ->
            val row = walletRows[position]
            if (row.isHeader()) return@setOnItemClickListener
            state().setAccountIds(this, listOf(row.accountId))

            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    /**
     * One row per wallet with open/close balance (native + equivalent) and the net change
     * over the period — per wallet_balance_item_layout.xml, fattest wallet first.
     *
     * A wallet untouched during the period is listed too: the report is about where the
     * money stands, and a full wallet nobody touched this month still holds its money —
     * hiding it would leave the balances not adding up to the card above. Archived wallets
     * are hidden from the list by default (toggle «Показывать архивные»), but the archive flag
     * is display-only: they stay in section sums and the card above
     * (`AccountItem.getIsArchived()`).
     */
    private fun buildWalletRows(): List<WalletRow> {
        val db = db
        val currencyEq = currencyEq

        val openRows = WalletDbLoader.LoadBalancesPerWallet(
            db, dateFrom, dateFrom, currencyEq
        )
        val closeRows = WalletDbLoader.LoadBalancesPerWallet(
            db, dateTo, dateTo, currencyEq
        )

        val openByAccount = HashMap<Int, WalletDbLoader.WalletBalanceRow>()
        for (r in openRows) openByAccount[r.accountId] = r

        val result = ArrayList<WalletRow>()
        for (close in closeRows) {
            val open = openByAccount[close.accountId]

            // Missing from the "open" snapshot means no activity existed before dateFrom at
            // all — a real, exactly-zero balance, not a missing-rate gap.
            val openBal = if (open != null) open.balance else 0.0
            val openEq = if (open != null && open.hasEq) open.balanceEq else 0.0
            val openHasEq = open == null || open.hasEq

            val netEqComputable = close.hasEq && openHasEq
            val netEq = if (netEqComputable) close.balanceEq - openEq else 0.0
            val netNative = close.balance - openBal

            val row = WalletRow(
                close.accountId, close.name, close.currencySymbol,
                openBal, openEq, openHasEq, open != null && open.approx,
                close.balance, close.balanceEq, close.hasEq, close.approx,
                netEq, netEqComputable, netNative
            )
            row.isAsset = close.isAsset() == true
            row.isDebt = close.isDebt() == true
            val item = Singleton.instance.getAccounts(this).getAccountById(close.accountId)
            row.archived = item != null && item.isArchived == true
            // Нулевой заём (погашен) в секцию «Долги» не входит — он виден на экране «Долги»
            // (спека, «Таб „Кошельки“»).
            if (row.isDebt && Math.abs(close.balance) < 0.005) continue
            result.add(row)
        }

        // Fattest wallet at the end of the period first. Wallets whose currency has no rate to
        // currencyEq at all are not comparable with the rest, so they sink below every wallet
        // that is, and are ranked among themselves by their own currency.
        applyFilter(result)

        result.sortWith(
            compareByDescending<WalletRow> { it.closeHasEq }
                .thenByDescending { if (it.closeHasEq) it.closeEq else it.closeBal }
        )

        // Чипы секций и скрытие архивных (openspec wallets-filter-and-debt-autoarchive): архив —
        // только для показа, суммы секций считаются по всем строкам, включая скрытые.
        val layout = WalletSections.layout(
            result,
            { r: WalletRow ->
                if (r.isDebt) WalletSections.Section.DEBTS
                else if (r.isAsset) WalletSections.Section.CAPITAL else WalletSections.Section.LIQUID
            },
            { r: WalletRow -> r.archived }, selectedSections, showArchived
        )
        paintChips(layout.available)

        val onlyLiquid = layout.available.isEmpty()
                || (layout.available.size == 1 && layout.available.contains(WalletSections.Section.LIQUID))
        if (onlyLiquid) return ArrayList(layout.shown[WalletSections.Section.LIQUID]!!)
        // capital-assets: «Ликвидные», затем «Капитал», затем «Долги» — каждая секция со своей
        // суммой на конец периода; сортировка «самый толстый первым» — внутри секции.
        val sectioned = ArrayList<WalletRow>()
        for (s in layout.visible) {
            val title = if (s == WalletSections.Section.LIQUID) R.string.wallets_section_liquid
            else if (s == WalletSections.Section.CAPITAL) R.string.wallets_section_capital else R.string.debts_title
            sectioned.add(WalletRow.header(getString(title), layout.all[s]!!))
            sectioned.addAll(layout.shown[s]!!)
        }
        return sectioned
    }

    /**
     * Общий фильтр шапки отбирает строки, но не меняет цифры в них: остаток — это остаток,
     * «сумма операций с комментарием „кофе“ с начала времён» остатком не была бы.
     *
     *  * в фильтре выбраны кошельки — остаются только они;
     *  * задано что-то ещё (категории, вид операции, текст, суммы) — остаются кошельки,
     *  у которых за период есть хоть одна операция, прошедшая фильтр, т.е. те, чьи
     *  операции видны в ленте «Операций» с тем же фильтром.
     */
    private fun applyFilter(rows: MutableList<WalletRow>) {
        if (!state().hasFilter()) return

        val accounts = Singleton.instance.getAccounts(this)
        val selectedWallets = HashSet<Int>()
        var categorySelected = false
        for (id in state().getAccountIds()) {
            val item = accounts.getAccountById(id) ?: continue
            if (item.isOperatingAccount) selectedWallets.add(id)
            else categorySelected = true
        }

        if (selectedWallets.isNotEmpty()) rows.removeAll { !selectedWallets.contains(it.accountId) }

        val operationCriteria = categorySelected
                || state().getOperationKind() != DataFilter.OperationKind.all
                || state().getSearchText().isNotEmpty()
                || state().getAmountMin() != null || state().getAmountMax() != null
                || state().getEqMin() != null || state().getEqMax() != null
        if (!operationCriteria) return

        val withOperations = WalletDbLoader.LoadWalletIdsWithOperations(
            db, dateFrom, dateTo, currencyEq, dataFilter
        )
        rows.removeAll { !withOperations.contains(it.accountId) }
    }

    /** One combined open+close+net row for the wallets list (see buildWalletRows). */
    private class WalletRow(
        val accountId: Int,
        val name: String?,
        val currencySymbol: String?,
        val openBal: Double, val openEq: Double,
        val openHasEq: Boolean, val openApprox: Boolean,
        val closeBal: Double, val closeEq: Double,
        val closeHasEq: Boolean, val closeApprox: Boolean,
        val netEq: Double,
        val netEqComputable: Boolean,
        val netNative: Double
    ) {
        /** Капитальный актив (capital-assets) — строка секции «Капитал». */
        var isAsset = false

        /** Заём (debts) — строка секции «Долги». */
        var isDebt = false

        /** Флаг архива кошелька: скрывает строку из списка, но не из сумм. */
        var archived = false

        /** Не null — это заголовок секции, а не кошелёк: closeEq/closeHasEq — сумма секции. */
        var headerTitle: String? = null

        fun isHeader(): Boolean = headerTitle != null

        companion object {
            /** Заголовок секции: сумма closeEq строк с курсом; строка без курса — сумма неполная. */
            fun header(title: String, rows: List<WalletRow>): WalletRow {
                var sum = 0.0
                var complete = true
                var approx = false
                for (r in rows) {
                    if (r.closeHasEq) sum += r.closeEq else complete = false
                    approx = approx or r.closeApprox
                }
                val h = WalletRow(0, title, "", 0.0, 0.0, true, false, 0.0, sum, complete, approx, 0.0, false, 0.0)
                h.headerTitle = title
                return h
            }
        }
    }

    // ── Wallet balance row adapter (wallet_balance_item_layout.xml — open/close/net) ────────

    private class WalletBalanceAdapter(
        ctx: Context, rows: List<WalletRow>,
        private val mCurrencyEq: Currency,
        private val mAccounts: AccountList
    ) : ArrayAdapter<WalletRow>(ctx, R.layout.wallet_balance_item_layout, rows) {

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (getItem(position)!!.isHeader()) 1 else 0

        override fun isEnabled(position: Int): Boolean = !getItem(position)!!.isHeader()

        private fun getHeaderView(row: WalletRow, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.wallet_section_header, parent, false)
            view.findViewById<TextView>(R.id.sectionTitle).text = row.headerTitle
            val prefix = if (!row.closeHasEq) "~" else if (row.closeApprox) "≈ " else ""
            view.findViewById<TextView>(R.id.sectionSum).text = (prefix
                    + AdapterUtils.getFormat(mCurrencyEq.precision).format(row.closeEq) + " " + mCurrencyEq.symbol)
            return view
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)!!
            if (row.isHeader()) return getHeaderView(row, convertView, parent)

            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.wallet_balance_item_layout, parent, false)

            val imgIcon = view.findViewById<ImageView>(R.id.walletIcon)
            val imgFlag = view.findViewById<ImageView>(R.id.walletFlag)
            val tvName = view.findViewById<TextView>(R.id.walletName)
            val tvNet = view.findViewById<TextView>(R.id.walletNet)
            val tvOpenNative = view.findViewById<TextView>(R.id.walletOpenNative)
            val tvOpenEq = view.findViewById<TextView>(R.id.walletOpenEq)
            val tvCloseNative = view.findViewById<TextView>(R.id.walletCloseNative)
            val tvCloseEq = view.findViewById<TextView>(R.id.walletCloseEq)

            val acc: AccountItem? = mAccounts.getAccountById(row.accountId)
            if (acc != null && acc.icon != null) {
                imgIcon.setImageBitmap(acc.icon!!.bitmap)
            } else {
                imgIcon.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
            if (acc is AccountOperatingItem && acc.currency != null) {
                imgFlag.setImageBitmap(acc.currency.icon!!.bitmap)
                imgFlag.visibility = View.VISIBLE
            } else {
                imgFlag.visibility = View.GONE
            }

            tvName.text = row.name

            if (row.netEqComputable) {
                setNetText(tvNet, row.netEq, mCurrencyEq.precision, mCurrencyEq.symbol, "")
            } else {
                // No rate to currencyEq exists for this wallet's currency at all — fall back
                // to its own currency so the change is still shown, just not comparable.
                setNetText(tvNet, row.netNative, 2, row.currencySymbol, "~")
            }

            tvOpenNative.text = AdapterUtils.getFormat(2).format(row.openBal) + " " + row.currencySymbol
            tvOpenEq.text = if (row.openHasEq)
                (if (row.openApprox) "≈ " else "") + AdapterUtils.getFormat(mCurrencyEq.precision).format(row.openEq) + " " + mCurrencyEq.symbol
            else
                "~"

            // Остаток на конец — главная цифра строки, поэтому стоит рядом с названием
            // кошелька и без подписи. Эквивалент идёт следом мелким, но только когда
            // кошелёк не в валюте эквивалента: иначе это была бы та же цифра дважды.
            tvCloseNative.text = AdapterUtils.getFormat(2).format(row.closeBal) + " " + row.currencySymbol
            if (!row.closeApprox) {
                tvCloseEq.visibility = View.GONE
            } else {
                tvCloseEq.visibility = View.VISIBLE
                tvCloseEq.text = if (row.closeHasEq)
                    "≈ " + AdapterUtils.getFormat(mCurrencyEq.precision).format(row.closeEq) + " " + mCurrencyEq.symbol
                else
                    "~"
            }

            return view
        }

        /**
         * Приход за период. Ноль — обычное дело: кошелёк могли просто не трогать, — и он
         * не «плюс», поэтому идёт без знака и нейтральным цветом.
         */
        private fun setNetText(view: TextView, net: Double, precision: Int, symbol: String?, prefix: String) {
            val isZero = Math.round(net * 100) == 0L
            val sign = if (isZero) "" else if (net > 0) "+" else "-"

            view.text = (prefix + sign + AdapterUtils.getFormat(precision).format(Math.abs(net))
                    + " " + symbol)
            view.setTextColor(
                context.resources.getColor(
                    if (isZero) R.color.colortransfer else if (net > 0) R.color.colorprofit else R.color.colorexpanse
                )
            )
        }
    }
}
