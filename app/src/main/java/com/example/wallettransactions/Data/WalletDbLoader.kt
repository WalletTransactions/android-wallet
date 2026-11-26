package com.example.wallettransactions.Data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import android.graphics.BitmapFactory
import android.util.Log
import android.util.Pair
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.DataItems.Icon
import com.example.wallettransactions.DataItems.Operations.EquivalentResult
import com.example.wallettransactions.DataItems.ReportItems.ReportByAccount
import com.example.wallettransactions.DataItems.Operations.OperationItem
import com.example.wallettransactions.DataItems.Operations.OperationList
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DateUtils
import com.example.wallettransactions.Singleton

import java.util.Date

object WalletDbLoader {

    @JvmStatic
    fun LoadIcons(db: SQLiteDatabase, iconList: IconList): Int {
        // deleted_at IS NULL — с v72 ничто в этом коде пока не выставляет его
        // локально (удаление иконки — жёсткий DELETE, см. IconEditDialog.deleteIcon),
        // но входящая с сервера pull-строка (когда синк появится) может прийти уже
        // помеченной удалённой — фильтр на будущее не мешает уже сейчас.
        val sql_select = "SELECT " + WalletContract.IconEntry._ID +
                ", "+ WalletContract.IconEntry.COLUMN_NAME +
          //      +", "+ WalletContract.IconsEntry.COLUMN_RESOURCE
                ", "+ WalletContract.IconEntry.COLUMN_IMAGE +
                ", "+ WalletContract.IconEntry.COLUMN_ICON_TYPE +
                ", "+ WalletContract.IconEntry.COLUMN_ORIGIN +
                " FROM "+ WalletContract.IconEntry.TABLE_NAME +
                " WHERE " + WalletContract.IconEntry.COLUMN_DELETED_AT + " IS NULL"

        val res = 0
        val cursor = db.rawQuery(sql_select, null)
        try {
            val idColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry._ID)
            val nameColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_NAME)
         //   int resourceColumnIndex = cursor.getColumnIndex(WalletContract.IconsEntry.COLUMN_RESOURCE);
            val imageColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_IMAGE)
            val typeColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_ICON_TYPE)
            val originColumnIndex = cursor.getColumnIndex(WalletContract.IconEntry.COLUMN_ORIGIN)

            while (cursor.moveToNext()) {
                val currentID = cursor.getInt(idColumnIndex)
                val currentName = cursor.getString(nameColumnIndex)

             //   Integer currentResource = cursor.getInt(resourceColumnIndex);
                val currentImage: ByteArray = cursor.getBlob(imageColumnIndex)
                val currentType = Icon.IconType.fromValue(cursor.getInt(typeColumnIndex))

                val fromServer = cursor.getInt(originColumnIndex) == WalletContract.IconEntry.ORIGIN_SERVER

                iconList.AddIcon(currentID, currentName,
                        BitmapFactory.decodeByteArray(currentImage, 0, currentImage.size),
                        currentType, fromServer)
            }
            return res
        } finally {
            cursor.close()
        }
    }

    const val getBalancesToDate = "select vao._id, vao.name,  c._id currency_id, c.iso_code currency_iso_code,\n" +
            "opt.amount_to, \n" +
            "coalesce(opf.amount_fr,0) amount_fr,\n" +
            "round(coalesce(opt.amount_to,0) - coalesce(opf.amount_fr,0),2) as balance,\n" +
            "round(vccr.rate * (coalesce(opt.amount_to,0) - coalesce(opf.amount_fr,0) ),2) as balance_eq,\n" +
            "vccr.to_currency_id\n" +
            "from v_account_operating vao \n" +
            "join currency c \n" +
            "  on vao.currency_id = c._id  \n" +
            "left join (\n" +
            "select o.acc_fr_id, round(sum(amount),2) amount_fr\n" +
            "from operation o\n" +
            "WHERE date < ? \n" +
            "group by o.acc_fr_id\n" +
            ") opf\n" +
            "on vao._id = opf.acc_fr_id \n" +
            "left join (\n" +
            "  select o.acc_to_id, round(sum(amount),2) amount_to\n" +
            "from operation o\n" +
            "   WHERE date < ? \n" +
            "   group by o.acc_to_id\n" +
            ") opt\n" +
            "on vao._id = opt.acc_to_id \n" +
            "join (\n" +
            "select from_currency_id , to_currency_id , max(date) max_date \n" +
            "from v_currency_conversion_rate vccr\n" +
            "where date <=? \n" +
            "group by from_currency_id , to_currency_id \n" +
            ") mx\n" +
            "on mx.from_currency_id =c._id and mx.to_currency_id = ? \n" +
            "join v_currency_conversion_rate vccr\n" +
            "on vccr.from_currency_id =mx.from_currency_id and vccr.to_currency_id = mx.to_currency_id and vccr.date = mx.max_date\n" +
            "where (opt.acc_to_id is not null or opf.acc_fr_id is not null)"

    class WalletBalanceRow {
        @JvmField val accountId: Int
        @JvmField val name: String
        @JvmField val balance: Double
        @JvmField val currencySymbol: String
        @JvmField val balanceEq: Double
        /** False when no rate to the target currency exists — balanceEq is then meaningless. */
        @JvmField val hasEq: Boolean
        /** True whenever a conversion was applied, i.e. the wallet is not already in the target currency. */
        @JvmField val approx: Boolean
        /**
         * Тип капитального актива (capital-assets) из живой строки `account_asset`;
         * `null` — обычный (ликвидный) кошелёк.
         */
        @JvmField val assetType: Int?
        /** Входит ли актив во «Всего с капиталом»; у ликвидного кошелька всегда true. */
        @JvmField val includeInNetWorth: Boolean
        /**
         * uuid контрагента займа (debts) из живой строки `account_debt`; `null` —
         * не долговой кошелёк.
         */
        @JvmField val debtCounterpartyUuid: String?

        constructor(accountId: Int, name: String, balance: Double, currencySymbol: String, balanceEq: Double, hasEq: Boolean, approx: Boolean) : this(accountId, name, balance, currencySymbol, balanceEq, hasEq, approx, null, true) {
        }

        constructor(accountId: Int, name: String, balance: Double, currencySymbol: String, balanceEq: Double, hasEq: Boolean, approx: Boolean, assetType: Int?, includeInNetWorth: Boolean) : this(accountId, name, balance, currencySymbol, balanceEq, hasEq, approx, assetType,
                    includeInNetWorth, null) {
        }

        constructor(accountId: Int, name: String, balance: Double, currencySymbol: String, balanceEq: Double, hasEq: Boolean, approx: Boolean, assetType: Int?, includeInNetWorth: Boolean, debtCounterpartyUuid: String?) {
            this.accountId      = accountId
            this.name           = name
            this.balance        = balance
            this.currencySymbol = currencySymbol
            this.balanceEq      = balanceEq
            this.hasEq          = hasEq
            this.approx         = approx
            this.assetType      = assetType
            this.includeInNetWorth = includeInNetWorth
            this.debtCounterpartyUuid = debtCounterpartyUuid
        }

        fun isAsset(): Boolean {
            return assetType != null
        }

        fun isDebt(): Boolean {
            return debtCounterpartyUuid != null
        }

        /** Ликвидный — ни актив, ни заём: только такие входят в «Итог» карточки (debts, D9). */
        fun isLiquid(): Boolean {
            return !isAsset() && !isDebt()
        }
    }

    /**
     * Per-wallet balances at a given date, sorted by balanceEq descending.
     *
     * Archived wallets are in, deliberately: archiving is a UI flag (see
     * [com.example.wallettransactions.DataItems.Accounts.AccountItem.getIsArchived()]),
     * so their money is still money and their history still counts. Filtering them out here
     * would silently change the totals for periods already closed.
     */
    @JvmStatic
    fun LoadBalancesPerWallet(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, currencyEq: Currency): List<WalletBalanceRow> {
        val sql = "SELECT vao._id, vao.name, c._id currency_id, c.symbol, c.precision, " +
                "round(coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0), c.precision) balance, " +
                // No coalesce on the rate: a missing rate must leave balance_eq NULL rather
                // than silently converting 1:1.
                "round(vccr.rate * (coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0)), 2) balance_eq, " +
                // capital-assets: признак актива — тем же запросом, итоги разбиваются по нему.
                "aa.asset_type, coalesce(aa.include_in_net_worth, 1) include_in_net_worth, " +
                // debts (design D9): признак займа — так же, итоги делятся и по нему.
                "ad.counterparty_uuid debt_counterparty_uuid " +
                "FROM v_account_operating vao " +
                "JOIN currency c ON vao.currency_id = c._id " +
                "LEFT JOIN account_asset aa ON aa._id = vao._id AND aa.deleted_at IS NULL " +
                "LEFT JOIN account_debt ad ON ad._id = vao._id AND ad.deleted_at IS NULL " +
                // deleted_at IS NULL — с v72 WalletDbCRUD.DeleteOperation стал мягким;
                // без фильтра баланс продолжал бы учитывать удалённые проводки.
                "LEFT JOIN (SELECT acc_to_id, round(sum(amount), 2) amount_to FROM operation " +
                "  WHERE date < ? AND deleted_at IS NULL GROUP BY acc_to_id) opt ON vao._id = opt.acc_to_id " +
                "LEFT JOIN (SELECT acc_fr_id, round(sum(amount), 2) amount_fr FROM operation " +
                "  WHERE date < ? AND deleted_at IS NULL GROUP BY acc_fr_id) opf ON vao._id = opf.acc_fr_id " +
                "LEFT JOIN (SELECT from_currency_id, to_currency_id, max(date) max_date " +
                "  FROM v_currency_conversion_rate WHERE date <= ? " +
                "  GROUP BY from_currency_id, to_currency_id) mx " +
                "  ON mx.from_currency_id = c._id AND mx.to_currency_id = ? " +
                "LEFT JOIN v_currency_conversion_rate vccr " +
                "  ON vccr.from_currency_id = mx.from_currency_id " +
                "  AND vccr.to_currency_id = mx.to_currency_id AND vccr.date = mx.max_date " +
                "WHERE (opt.acc_to_id IS NOT NULL OR opf.acc_fr_id IS NOT NULL) " +
                "ORDER BY balance_eq DESC"

        val dateFromStr = DateUtils.DateToStr(dateFrom)
        val dateToStr = DateUtils.DateToStr(dateTo)
        val params: Array<String> = arrayOf<String>(dateFromStr, dateFromStr, dateToStr, currencyEq.id.toString())

        android.util.Log.d("WalletDbLoader", "LoadBalancesPerWallet SQL: " + sql)
        android.util.Log.d("WalletDbLoader", "Params: dateFromStr=" + dateFromStr + ", dateToStr=" + dateToStr + ", currencyEqId=" + currencyEq.id)

        val cursor = db.rawQuery(sql, params)

        val result = ArrayList<WalletBalanceRow>()
        try {
            val colId = cursor.getColumnIndex("_id")
            val colName = cursor.getColumnIndex("name")
            val colBalance = cursor.getColumnIndex("balance")
            val colSymbol = cursor.getColumnIndex("symbol")
            val colEq = cursor.getColumnIndex("balance_eq")
            val colCurrencyId = cursor.getColumnIndex("currency_id")
            val colAssetType = cursor.getColumnIndex("asset_type")
            val colInclude = cursor.getColumnIndex("include_in_net_worth")
            val colDebt = cursor.getColumnIndex("debt_counterparty_uuid")

            while (cursor.moveToNext()) {
                val hasEq = !cursor.isNull(colEq)
                val approx = cursor.getInt(colCurrencyId) != currencyEq.id

                result.add(WalletBalanceRow(
                        cursor.getInt(colId),
                        cursor.getString(colName),
                        cursor.getDouble(colBalance),
                        cursor.getString(colSymbol), if (hasEq) cursor.getDouble(colEq) else 0.0,
                        hasEq,
                        approx, if (cursor.isNull(colAssetType)) null else cursor.getInt(colAssetType),
                        cursor.getInt(colInclude) != 0, if (cursor.isNull(colDebt)) null else cursor.getString(colDebt)))
            }
        } finally {
            cursor.close()
        }
        return result
    }

    /** Сколько живых операций у счёта и когда была последняя — всего и в паре с одним счётом. */
    class AccountUsage {
        @JvmField var count: Int = 0
        /** Дата в формате базы ([DateUtils.DateToStr]) — строки сравниваются как даты. */
        @JvmField var lastDate: String? = null
        @JvmField var pairCount: Int = 0
        @JvmField var pairLastDate: String? = null
    }

    /**
     * Частота использования счетов для сортировки экрана выбора. Считается на лету по
     * `operation`, а не отдельным счётчиком: так она сама учитывает операции,
     * пришедшие синком с других устройств, импорт, правку и удаление.
     *
     * @param pairAccountId счёт из второго слота операции (0 — нет): для каждого счёта
     *        дополнительно считаются операции именно с ним. Валютный перевод лежит двумя
     *        ногами через мост «Transfer» (связка `operation_link.type = 0`), поэтому
     *        пара «кошелёк → кошелёк» собирается и по ногам, иначе такие переводы с
     *        кошельком-партнёром не встречались бы никогда.
     */
    @JvmStatic
    fun LoadAccountUsage(db: SQLiteDatabase, pairAccountId: Int): Map<Int, AccountUsage> {
        val result = HashMap<Int, AccountUsage>()

        val total = "SELECT acc, count(*) cnt, max(date) last_date FROM (" +
                "  SELECT acc_fr_id acc, date FROM operation WHERE deleted_at IS NULL" +
                "  UNION ALL" +
                "  SELECT acc_to_id acc, date FROM operation WHERE deleted_at IS NULL" +
                ") GROUP BY acc"
        var cursor = db.rawQuery(total, null)
        try {
            while (cursor.moveToNext()) {
                val usage = AccountUsage()
                usage.count = cursor.getInt(1)
                usage.lastDate = cursor.getString(2)
                result.put(cursor.getInt(0), usage)
            }
        } finally {
            cursor.close()
        }

        if (pairAccountId <= 0) return result

        val legs = " FROM operation_link k" +
                "  JOIN operation m ON m._id = k.main_id" +
                "  JOIN operation l ON l._id = k.link_id" +
                "  WHERE k.type = " + OperationItem.LinkType.LEG.value +
                "  AND k.deleted_at IS NULL AND m.deleted_at IS NULL AND l.deleted_at IS NULL"
        val pair = "SELECT acc, count(*) cnt, max(date) last_date FROM (" +
                "  SELECT acc_to_id acc, date FROM operation WHERE deleted_at IS NULL AND acc_fr_id = ?" +
                "  UNION ALL" +
                "  SELECT acc_fr_id acc, date FROM operation WHERE deleted_at IS NULL AND acc_to_id = ?" +
                "  UNION ALL" +
                "  SELECT l.acc_to_id acc, m.date date" + legs + " AND m.acc_fr_id = ?" +
                "  UNION ALL" +
                "  SELECT m.acc_fr_id acc, m.date date" + legs + " AND l.acc_to_id = ?" +
                ") GROUP BY acc"
        val id = pairAccountId.toString()
        cursor = db.rawQuery(pair, arrayOf<String>(id, id, id, id))
        try {
            while (cursor.moveToNext()) {
                val usage = result.get(cursor.getInt(0))
                if (usage == null) continue   // в общем счёте есть всё, что есть в паре
                usage.pairCount = cursor.getInt(1)
                usage.pairLastDate = cursor.getString(2)
            }
        } finally {
            cursor.close()
        }
        return result
    }

    @JvmStatic
    fun GenerateReportGetBalanceToDate(db: SQLiteDatabase, date: Date, currencyEqId: Currency, dataFilter: DataFilter?): EquivalentResult {
        // Same per-wallet query the «Кошельки» list is built from, so the card total and the
        // rows below it can never disagree.
        val rows = LoadBalancesPerWallet(db, date, date, currencyEqId)

        var totalEq: Double = 0.0
        var hasAprox = false
        var incomplete = false
        for (row in rows) {
            // capital-assets: итог карточки — только ликвидные кошельки, капитал —
            // GenerateCapitalToDate из тех же строк; займы (debts) — GenerateDebtsToDate.
            if (!row.isLiquid()) continue
            if (!row.hasEq) {
                // No rate at all: converting 1:1 would inflate the total, so the wallet is
                // left out and the sum is reported as incomplete rather than merely rounded.
                incomplete = true
                continue
            }
            totalEq += row.balanceEq
            if (row.approx) hasAprox = true
        }

        return EquivalentResult(totalEq, hasAprox, incomplete)
    }

    /** Капитал и «всего с капиталом» на дату — см. [GenerateCapitalToDate]. */
    class CapitalResult {
        /** Есть ли хоть один актив с операциями до даты; нет — строк «Капитал» не показывают. */
        @JvmField val hasAssets: Boolean
        @JvmField val capital: EquivalentResult
        @JvmField val netWorth: EquivalentResult

        constructor(hasAssets: Boolean, capital: EquivalentResult, netWorth: EquivalentResult) {
            this.hasAssets = hasAssets
            this.capital = capital
            this.netWorth = netWorth
        }
    }

    /**
     * Капитал (все активы) и «Всего с капиталом» (ликвидные + активы с включённым
     * «Учитывать во „Всего с капиталом“») на дату, в валюте эквивалента (openspec
     * capital-assets, design п. 6). Строится из того же [LoadBalancesPerWallet], что и
     * [GenerateReportGetBalanceToDate], с той же неполнотой: строка без курса не
     * пересчитывается 1:1, а помечает сумму неполной.
     */
    @JvmStatic
    fun GenerateCapitalToDate(db: SQLiteDatabase, date: Date, currencyEq: Currency): CapitalResult {
        val rows = LoadBalancesPerWallet(db, date, date, currencyEq)
        var capital: Double = 0.0
        var netWorth: Double = 0.0
        var capApprox = false
        var capIncomplete = false
        var nwApprox = false
        var nwIncomplete = false
        var hasAssets = false
        for (row in rows) {
            // Займы (debts) не входят ни в капитал, ни во «Всего с капиталом» — у них своя
            // строка «Всего с долгами» (GenerateDebtsToDate).
            if (row.isDebt()) continue
            val inNetWorth = !row.isAsset() || row.includeInNetWorth
            if (row.isAsset()) {
                hasAssets = true
                if (row.hasEq) {
                    capital += row.balanceEq
                    if (row.approx) capApprox = true
                } else {
                    capIncomplete = true
                }
            }
            if (inNetWorth) {
                if (row.hasEq) {
                    netWorth += row.balanceEq
                    if (row.approx) nwApprox = true
                } else {
                    nwIncomplete = true
                }
            }
        }
        return CapitalResult(hasAssets,
                EquivalentResult(capital, capApprox, capIncomplete),
                EquivalentResult(netWorth, nwApprox, nwIncomplete))
    }

    /** Итоги долгов на дату — см. [GenerateDebtsToDate]. */
    class DebtsResult {
        /** Есть ли в леджере хоть один живой заём; нет — строк долгов не показывают. */
        @JvmField val hasDebts: Boolean
        /** «Мне должны» — сумма положительных остатков займов. */
        @JvmField val owedToMe: EquivalentResult
        /** «Я должен» — сумма модулей отрицательных остатков, положительное число. */
        @JvmField val iOwe: EquivalentResult
        /** «Всего с долгами» = ликвидные + «Мне должны» − «Я должен». */
        @JvmField val totalWithDebts: EquivalentResult

        constructor(hasDebts: Boolean, owedToMe: EquivalentResult, iOwe: EquivalentResult, totalWithDebts: EquivalentResult) {
            this.hasDebts = hasDebts
            this.owedToMe = owedToMe
            this.iOwe = iOwe
            this.totalWithDebts = totalWithDebts
        }
    }

    /**
     * «Мне должны», «Я должен» и «Всего с долгами» на дату в валюте эквивалента (openspec
     * debts, design D9) из того же [LoadBalancesPerWallet], что и ликвидный итог, с
     * той же неполнотой: заём без курса не пересчитывается 1:1, а помечает сумму неполной.
     * Знак — по остатку в валюте займа, а не в эквиваленте: без курса направление
     * всё равно известно.
     */
    @JvmStatic
    fun GenerateDebtsToDate(db: SQLiteDatabase, date: Date, currencyEq: Currency): DebtsResult {
        val rows = LoadBalancesPerWallet(db, date, date, currencyEq)
        var owed: Double = 0.0
        var owe: Double = 0.0
        var liquid: Double = 0.0
        var owedApprox = false
        var owedIncomplete = false
        var oweApprox = false
        var oweIncomplete = false
        var liqApprox = false
        var liqIncomplete = false
        for (row in rows) {
            if (row.isLiquid()) {
                if (row.hasEq) {
                    liquid += row.balanceEq
                    if (row.approx) liqApprox = true
                } else {
                    liqIncomplete = true
                }
                continue
            }
            if (!row.isDebt() || row.balance == 0.0) continue
            if (row.balance > 0) {
                if (row.hasEq) {
                    owed += row.balanceEq
                    if (row.approx) owedApprox = true
                } else {
                    owedIncomplete = true
                }
            } else {
                if (row.hasEq) {
                    owe -= row.balanceEq
                    if (row.approx) oweApprox = true
                } else {
                    oweIncomplete = true
                }
            }
        }
        return DebtsResult(HasDebtWallets(db),
                EquivalentResult(owed, owedApprox, owedIncomplete),
                EquivalentResult(owe, oweApprox, oweIncomplete),
                EquivalentResult(liquid + owed - owe, liqApprox || owedApprox || oweApprox,
                        liqIncomplete || owedIncomplete || oweIncomplete))
    }

    /** Есть ли хоть один живой заём (живая `account_debt` у живого счёта). */
    @JvmStatic
    fun HasDebtWallets(db: SQLiteDatabase): Boolean {
        val c = db.rawQuery("SELECT 1 FROM " + WalletContract.AccountDebtEntry.TABLE_NAME + " ad" +
                " JOIN " + WalletContract.AccountEntry.TABLE_NAME + " a ON a._id = ad._id AND a.deleted_at IS NULL" +
                " WHERE ad." + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " IS NULL LIMIT 1", null)
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    /**
     * «В долг / из долга» за период в валюте эквивалента (openspec debts, design D9) —
     * слагаемое сверки «На конец − На начало = Доход − Расход + В долг / из долга + …».
     * «Итог» — только ликвидные, а займ для дохода и расхода — обычный кошелёк, поэтому
     * считаются проводки, где ровно одна сторона — заём, а другая не актив:
     * <ul>
     *   <li>ликвидный → заём (выдача, возврат своего долга) — «−»;</li>
     *   <li>заём → ликвидный (взял в долг, мне вернули) — «+»;</li>
     *   <li>заём ↔ категория (прощение) — с тем же знаком: прощение уже в доходе или расходе,
     *   а «Итог» не изменился, и эта строка их гасит.</li>
     * </ul>
     * Заём ↔ заём и заём ↔ актив в «Итог» не попадают. Операция без курса на дату в сумму
     * не входит, результат помечается неполным.
     */
    @JvmStatic
    fun GenerateDebtFlowForPeriod(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, currencyEq: Currency): EquivalentResult {
        val sql = "SELECT round(sum(CASE WHEN o.acc_to_is_debt = 1 THEN -o.amount_eq ELSE o.amount_eq END), 2) net, " +
                "sum(CASE WHEN o.amount_eq IS NULL THEN 1 ELSE 0 END) missing, " +
                "sum(CASE WHEN o.currency_id <> o.to_currency_id THEN 1 ELSE 0 END) converted " +
                "FROM v_operation_eq o " +
                "LEFT JOIN account_asset fa ON fa._id = o.acc_fr_id AND fa.deleted_at IS NULL " +
                "LEFT JOIN account_asset ta ON ta._id = o.acc_to_id AND ta.deleted_at IS NULL " +
                "WHERE o.date BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" + DateUtils.DateToStr(dateTo) + "' " +
                "AND o.to_currency_id = " + currencyEq.id + " " +
                "AND o.acc_from_is_debt <> o.acc_to_is_debt " +
                "AND fa._id IS NULL AND ta._id IS NULL"
        val c = db.rawQuery(sql, null)
        try {
            if (!c.moveToFirst()) return EquivalentResult(0.0, false, false)
            val net: Double = if (c.isNull(0)) 0.0 else c.getDouble(0)
            return EquivalentResult(net, c.getInt(2) > 0, c.getInt(1) > 0)
        } finally {
            c.close()
        }
    }

    /**
     * Движение денег между ликвидными кошельками и активами за период, в валюте эквивалента
     * (android-wallet issue .github-private#1): слагаемое, которого не хватает в равенстве
     * «Итог = Доход − Расход + движение + курсовая разница». «Итог» — разница балансов только
     * ликвидных кошельков, а «Доход»/«Расход» считают актив обычным кошельком, поэтому:
     * <ul>
     *   <li>перевод ликвидный → актив (и доход на актив) уменьшает «Итог» без расхода — со знаком «−»;</li>
     *   <li>перевод актив → ликвидный (и расход с актива) увеличивает «Итог» без дохода — «+»;</li>
     *   <li>актив ↔ актив и переоценка в «Итог» не попадают — их нет в сумме.</li>
     * </ul>
     * Операция без курса на дату в сумму не входит, результат помечается неполным.
     */
    @JvmStatic
    fun GenerateCapitalFlowForPeriod(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, currencyEq: Currency): EquivalentResult {
        val sql = "SELECT round(sum(CASE WHEN ta._id IS NOT NULL THEN -o.amount_eq ELSE o.amount_eq END), 2) net, " +
                "sum(CASE WHEN o.amount_eq IS NULL THEN 1 ELSE 0 END) missing, " +
                "sum(CASE WHEN o.currency_id <> o.to_currency_id THEN 1 ELSE 0 END) converted " +
                "FROM v_operation_eq o " +
                "LEFT JOIN account_asset fa ON fa._id = o.acc_fr_id AND fa.deleted_at IS NULL " +
                "LEFT JOIN account_asset ta ON ta._id = o.acc_to_id AND ta.deleted_at IS NULL " +
                "WHERE o.date BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" + DateUtils.DateToStr(dateTo) + "' " +
                "AND o.to_currency_id = " + currencyEq.id + " " +
                "AND o.is_revaluation = 0 " +
                "AND ((fa._id IS NULL) <> (ta._id IS NULL))"
        val c = db.rawQuery(sql, null)
        try {
            if (!c.moveToFirst()) return EquivalentResult(0.0, false, false)
            val net: Double = if (c.isNull(0)) 0.0 else c.getDouble(0)
            return EquivalentResult(net, c.getInt(2) > 0, c.getInt(1) > 0)
        } finally {
            c.close()
        }
    }

    /**
     * Баланс счёта в его валюте по всем живым проводкам с датой не позже `dateInclusive`
     * (строка формата базы, сравнение строк — как у остальных запросов). Для диалога
     * переоценки: Δ = новая оценка − этот баланс на конец выбранного дня.
     */
    @JvmStatic
    fun LoadAccountBalanceAt(db: SQLiteDatabase, accountId: Int, dateInclusive: String): Double {
        val id = accountId.toString()
        val c = db.rawQuery(
                "SELECT coalesce(sum(CASE WHEN " + WalletContract.OperationEntry.COLUMN_TO + " = ? THEN " +
                        WalletContract.OperationEntry.COLUMN_AMOUNT + " ELSE -" + WalletContract.OperationEntry.COLUMN_AMOUNT +
                        " END), 0) FROM " + WalletContract.OperationEntry.TABLE_NAME +
                        " WHERE (" + WalletContract.OperationEntry.COLUMN_FROM + " = ? OR " +
                        WalletContract.OperationEntry.COLUMN_TO + " = ?)" +
                        " AND " + WalletContract.OperationEntry.COLUMN_DATE + " <= ?" +
                        " AND " + WalletContract.OperationEntry.COLUMN_DELETED_AT + " IS NULL",
                arrayOf<String>(id, id, id, dateInclusive))
        try {
            return if (c.moveToFirst()) c.getDouble(0) else 0.0
        } finally {
            c.close()
        }
    }

    /**
     * Итоговый баланс счёта в его валюте по всем живым проводкам, без границы по дате
     * (archive-wallet-writeoff, D2: после списания кошелёк должен быть в нуле, даже если
     * после выбранной даты есть операции).
     */
    @JvmStatic
    fun LoadAccountBalance(db: SQLiteDatabase, accountId: Int): Double {
        // Дата в базе — "yyyy-MM-dd HH:mm", строка "9999-…" больше любой из них.
        return LoadAccountBalanceAt(db, accountId, "9999-12-31 23:59:59")
    }


    /**
     * Net движение через мост «Transfer» за период, в валюте эквивалента: credit-нога
     * (Transfer → кошелёк) минус debit-нога (кошелёк → Transfer) одного и того же
     * валютного перевода. Обе ноги гасят друг друга — остаётся курсовая разница
     * (обычно она и есть весь «net»), см. п.3 docs/TODO_transfers.md.
     *
     * Привязка через `operation_link.type = 0` (нога, см. `OperationItem.LinkType`),
     * а не по совпадению счёта с системной категорией «Transfer» — иначе сюда попал бы
     * и разовый ручной расход/доход, который пользователь мог завести прямо на эту
     * категорию (пикер счетов её не прячет).
     *
     * @return null, если валютных переводов за период не было (сумма пуста).
     */
    private fun GetTransferNetEq(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, currencyEq: Currency, dataFilter: DataFilter?): Double? {
        val sql = "select round(sum(\n" +
                "  case when ol.link_id = o._id then o.amount_eq else -o.amount_eq end\n" +
                "),2) net\n" +
                "from v_operation_eq o\n" +
                "join operation_link ol on (ol.main_id = o._id or ol.link_id = o._id)\n" +
                "  and ol.type = 0 and ol.deleted_at is null\n" +
                "where "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)+"'\n"+
                "and (nullIf(o.to_currency_id,"+currencyEq.id+") is null)\n" +
                // Каждую ногу фильтр проверяет отдельно: при фильтре по кошельку в net
                // попадёт только его нога — так же, как в ленте «Операций».
                reportFilterSql(dataFilter)

        val c = db.rawQuery(sql, null)
        try {
            return if ((c.moveToFirst() && !c.isNull(0))) c.getDouble(0) else null
        } finally {
            c.close()
        }
    }

    /**
     * Условие общего фильтра шапки для отчётов по `v_operation_eq`; `null` —
     * без фильтра (карточка состояния денег считает итоги по всем операциям).
     */
    private fun reportFilterSql(dataFilter: DataFilter?): String {
        return if (dataFilter == null) "" else dataFilter.getSQLFilter(
                arrayOf<String>(WalletContract.OperationEntry.COLUMN_FROM, WalletContract.OperationEntry.COLUMN_TO))
    }

    @JvmStatic
    fun GenerateIncomeReportByAccount(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, accounts: AccountList, currencyList: CurrencyList, currencyEq: Currency, dataFilter: DataFilter?): ReportByAccount {
        val res = ReportByAccount()
        var sql_select = "select o.acc_fr_id, o.currency_id, round(sum(amount),2) amount,  round(sum(amount_eq),2) amount_eq\n" +
                "from v_operation_eq o\n" +
                "WHERE "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)+"'\n"+
                "and (nullIf(to_currency_id,"+currencyEq.id+") is null) and acc_to_is_operating = 1 and acc_from_is_operating =0\n" +
                // capital-assets: переоценка (одна сторона — «Переоценка») — не доход.
                "and is_revaluation = 0\n" +
                reportFilterSql(dataFilter) +
                // Credit-нога валютного перевода (Transfer → кошелёк) исключена: она не
                // самостоятельный доход, а половина перевода, см. GetTransferNetEq выше и
                // п.3 docs/TODO_transfers.md.
                "and not exists (select 1 from operation_link ol where ol.link_id = o._id and ol.type = 0 and ol.deleted_at is null)\n" +
                "group by o.acc_fr_id, o.currency_id" 

        // Net положительный — перевод в этой валюте эквивалента обошёлся «в плюс»
        // (или просто отражает курсовую разницу в пользу получателя); показываем
        // это одной строкой на счёте «Transfer», а не гросс-суммой каждой ноги.
        val net: Double? = GetTransferNetEq(db, dateFrom, dateTo, currencyEq, dataFilter)
        if (net != null && net > 0) {
            val transfer = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER)
            if (transfer != null) {
                sql_select += "\nunion all select " + transfer.id + ", " + currencyEq.id +
                        ", " + net + ", " + net
            }
        }

        val cursor = db.rawQuery(sql_select, null)

        try {
            val accIdColumnIndex = cursor.getColumnIndex("acc_fr_id")
            val curIdColumnIndex = cursor.getColumnIndex("currency_id")
            val amountColumnIndex = cursor.getColumnIndex("amount")
            val amountRcColumnIndex = cursor.getColumnIndex("amount_eq")

            while (cursor.moveToNext()) {
                val accID = cursor.getInt(accIdColumnIndex)
                val curID = cursor.getInt(curIdColumnIndex)
                val amount: Double? = cursor.getDouble(amountColumnIndex)
                var amountRc: Double? = null
                if (!cursor.isNull(amountRcColumnIndex))
                    amountRc = cursor.getDouble(amountRcColumnIndex)

                res.AddSubOper(accounts.getAccountById(accID)!!,
                        currencyList.getCurrencyById(curID),
                        amount,
                        amountRc
                )

            }
        } finally {
            cursor.close()
        }
        return  res
    }

    @JvmStatic
    fun GenerateExpanseReportByAccount(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, accounts: AccountList, currencyList: CurrencyList, currencyEq: Currency, dataFilter: DataFilter?): ReportByAccount {
        val res = ReportByAccount()
        var sql_select = "select o.acc_to_id, o.currency_id, round(sum(amount),2) amount,  round(sum(amount_eq),2) amount_eq\n" +
                "from v_operation_eq o\n" +
                "WHERE "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo)+"'\n"+
                "AND (nullIf(to_currency_id,"+currencyEq.id+") is null) and acc_to_is_operating = 0 and acc_from_is_operating =1\n" +
                // capital-assets: переоценка вниз — не расход.
                "and is_revaluation = 0\n" +
                reportFilterSql(dataFilter) +
                // Debit-нога валютного перевода (кошелёк → Transfer) исключена: она не
                // самостоятельный расход, а половина перевода, см. GetTransferNetEq выше и
                // п.3 docs/TODO_transfers.md.
                "and not exists (select 1 from operation_link ol where ol.main_id = o._id and ol.type = 0 and ol.deleted_at is null)\n" +
                "group by o.acc_to_id,  o.currency_id"

        // Net отрицательный — перевод обошёлся «в минус» (курсовая разница/спред);
        // показываем это одной строкой на счёте «Transfer», а не гросс-суммой каждой ноги.
        val net: Double? = GetTransferNetEq(db, dateFrom, dateTo, currencyEq, dataFilter)
        if (net != null && net < 0) {
            val transfer = accounts.getAccountBySystemKind(AccountItem.SystemKind.TRANSFER)
            if (transfer != null) {
                sql_select += "\nunion all select " + transfer.id + ", " + currencyEq.id +
                        ", " + (-net) + ", " + (-net)
            }
        }
        sql_select += "\norder by  amount_eq desc"

        val cursor = db.rawQuery(sql_select, null)

        try {
            val accIdColumnIndex = cursor.getColumnIndex("acc_to_id")
            val amountColumnIndex = cursor.getColumnIndex("amount")
            val amountRcColumnIndex = cursor.getColumnIndex("amount_eq")
            val curIdColumnIndex = cursor.getColumnIndex("currency_id")

            while (cursor.moveToNext()) {
                val accID = cursor.getInt(accIdColumnIndex)
                val curID = cursor.getInt(curIdColumnIndex)
                val amount: Double? = cursor.getDouble(amountColumnIndex)
                var amountRc: Double? = null
                if (!cursor.isNull(amountRcColumnIndex))
                    amountRc = cursor.getDouble(amountRcColumnIndex)

                res.AddSubOper(accounts.getAccountById(accID)!!,
                        currencyList.getCurrencyById(curID),
                        amount,
                        amountRc
                        )

            }
        } finally {
            cursor.close()
        }
        return  res
    }

    @JvmStatic
    fun UpdateAccountsOperatingBalance(db: SQLiteDatabase, accounts: AccountList) {
        val cFromSumAcc = "fr_amount"
        val cToSumAcc = "to_amount"
        val cTotal = "cTotal"
        // deleted_at IS NULL на обеих суммах — с v72 WalletDbCRUD.DeleteOperation
        // стал мягким; без фильтра баланс продолжал бы учитывать удалённые
        // проводки. a.deleted_at/ao.deleted_at — ТОЖЕ обязаны фильтроваться
        // здесь, а не только "выше по стеку" (был реальный краш на живом
        // устройстве: LoadAccounts строит accounts БЕЗ мягко удалённых
        // счетов (см. её WHERE a.deleted_at IS NULL), а этот JOIN — без
        // фильтра вовсе, значит мог отдать _id мягко удалённого счёта,
        // которого в accounts уже нет; accounts.getAccountById(currentID)
        // возвращал null, и (AccountOperatingItem)null.CurrentBalance = ...
        // падал NullPointerException'ом прямо на старте приложения, в
        // MainActivity.onCreate → LoadAccounts → эту функцию).
        val sql_select = "SELECT a." + WalletContract.AccountEntry._ID +
                ", tf."+cFromSumAcc+", tt."+cToSumAcc+", round(ifNull(tt."+cToSumAcc+",0)-ifNull(tf."+cFromSumAcc+",0),2) "+cTotal +
                " FROM "+ WalletContract.AccountEntry.TABLE_NAME +
                " a JOIN "   + WalletContract.AccountOperatingEntry.TABLE_NAME +
                " ao on ao." + WalletContract.AccountOperatingEntry._ID +"=a."+WalletContract.AccountEntry._ID +
                " and ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " is null" +

                " left join (select tf."+ WalletContract.OperationEntry.COLUMN_FROM +
                ", round(sum(tf."+ WalletContract.OperationEntry.COLUMN_AMOUNT +
                "),2) "+cFromSumAcc +
                " from "+ WalletContract.OperationEntry.TABLE_NAME +
                " tf" + //where tf.date between FROM and TO
                " where tf." + WalletContract.OperationEntry.COLUMN_DELETED_AT + " is null" +
                " group by " + WalletContract.OperationEntry.COLUMN_FROM +
                ") tf on a."+WalletContract.AccountEntry._ID+"=tf."+ WalletContract.OperationEntry.COLUMN_FROM +

                " left join (select tt."+ WalletContract.OperationEntry.COLUMN_TO +
                ", round(sum(tt."+ WalletContract.OperationEntry.COLUMN_AMOUNT +
                "),2) "+cToSumAcc +
                " from "+ WalletContract.OperationEntry.TABLE_NAME +
                " tt" + //where tf.date between FROM and TO
                " where tt." + WalletContract.OperationEntry.COLUMN_DELETED_AT + " is null" +
                " group by " + WalletContract.OperationEntry.COLUMN_TO +
                ") tt on a."+WalletContract.AccountEntry._ID+"=tt."+ WalletContract.OperationEntry.COLUMN_TO +

                " WHERE a." + WalletContract.AccountEntry.COLUMN_DELETED_AT + " is null"
        //where isVisible = 1
        val cursor = db.rawQuery(sql_select, null)

        try {
            val idColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry._ID)
            val totalColumnIndex = cursor.getColumnIndex(cTotal)

            while (cursor.moveToNext()) {
                val currentID = cursor.getInt(idColumnIndex)
                val currentTotal: Double = cursor.getDouble(totalColumnIndex)

                ((accounts.getAccountById(currentID) as AccountOperatingItem)).CurrentBalance = currentTotal
            }
        } finally {
            cursor.close()
        }
    }


    @JvmStatic
    fun LoadCurrencyList(db: SQLiteDatabase, context: Context) {
        LoadCurrencyList(db, Singleton.instance.getCurrencyList(context),
                Singleton.instance.getIconList(context))
    }

    /** То же, что [LoadCurrencyList(SQLiteDatabase,], без синглтона — для тестов. */
    @JvmStatic
    fun LoadCurrencyList(db: SQLiteDatabase, currencyList: CurrencyList, icons: IconList) {
        // c.deleted_at IS NULL — с v72 WalletDbCRUD.DeleteCurrency стал мягким
        // (см. её javadoc); без фильтра "удалённая" валюта продолжала бы
        // появляться в списке выбора.
        val sql_select = "SELECT c." + WalletContract.CurrencyEntry._ID +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_NAME +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_ISO_NAME +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_ICON_ID +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_SYMBOL +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_PRECISION +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT +
                ", c."+ WalletContract.CurrencyEntry.COLUMN_IS_SHARED +
                " FROM "+ WalletContract.CurrencyEntry.TABLE_NAME +" c" +
                " WHERE c." + WalletContract.CurrencyEntry.COLUMN_DELETED_AT + " IS NULL"


        val cursor = db.rawQuery(sql_select, null)
        try {
            val idColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry._ID)
            val nameColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_ISO_NAME)
            val isoNameColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_NAME)
            val iconIdColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_ICON_ID)
            val symbolColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_SYMBOL)
            val precisionColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_PRECISION)
            val showEqColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_SHOW_IN_EQUIVALENT)
            val sharedColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyEntry.COLUMN_IS_SHARED)

            while (cursor.moveToNext()) {
                val currentID = cursor.getInt(idColumnIndex)
                val currentName = cursor.getString(nameColumnIndex)
                val currentIsoName = cursor.getString(isoNameColumnIndex)
                val currentIconId: Int? = cursor.getInt(iconIdColumnIndex)
                val currentSymbol = cursor.getString(symbolColumnIndex)
                val currentPrecision: Int = if (precisionColumnIndex >= 0) cursor.getInt(precisionColumnIndex) else 2

                currencyList.addCurrency(currentID, currentName, currentIsoName,
                                        icons.getIconByIdOrUnknown(currentIconId),
                                        currentSymbol, currentPrecision,
                                        cursor.getInt(showEqColumnIndex) != 0,
                                        cursor.getInt(sharedColumnIndex) != 0)
            }
        } finally {
            cursor.close()
        }
    }

    @JvmStatic
    fun LoadCurrencyPairList(db: SQLiteDatabase, currencyList: CurrencyList, currencyPairList: CurrencyPairList) {
        // deleted_at IS NULL — с v72, на cp (мягко удалённая пара) и внутри
        // подзапроса max-даты по cpr (мягко удалённый курс не должен считаться
        // "последним известным"). Сегодня ничего в коде не удаляет currency_pair,
        // но фильтр не мешает и на будущее.
        val sql_select = "SELECT cp." + WalletContract.CurrencyPairEntry._ID +
                ", cp."+ WalletContract.CurrencyPairEntry.COLUMN_SYMBOL +
                ", cp."+ WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID +
                ", cp."+ WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID +
                ", cp."+ WalletContract.CurrencyPairEntry.COLUMN_PRECISION +
                ", cp."+ WalletContract.CurrencyPairEntry.COLUMN_IS_SHARED +
                ", cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_RATE +
                ", cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_DATE +
                "\n FROM "+ WalletContract.CurrencyPairEntry.TABLE_NAME +
                " cp\n left join (select cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                ", max("+WalletContract.CurrencyPairRateEntry.COLUMN_DATE+") dt_max \n" +
                " from "+WalletContract.CurrencyPairRateEntry.TABLE_NAME +
                " cpr\n where cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " is null" +
                "\n group by cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                ") t\n" +
                " on cp."+WalletContract.CurrencyPairEntry._ID+"=t." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                " left join " + WalletContract.CurrencyPairRateEntry.TABLE_NAME +
                " cpr\n on cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE+"=t." +
                WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                " and cpr."+WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE+"=t.dt_max" +
                " WHERE cp." + WalletContract.CurrencyPairEntry.COLUMN_DELETED_AT + " IS NULL"

       /*"select cp._id, cp.base_currency_id , cp.quote_currency_id , cp.symbol , cpr.rate \n" +
                "from currency_pair cp \n" +
                "left join (\n" +
                "select cpr.currency_pair_id, max(date) dt_max\n" +
                "  from currency_pair_rate cpr \n" +
                " group by cpr.currency_pair_id\n" +
                ") t\n" +
                "on cp._id =t.currency_pair_id\n" +
                "left join currency_pair_rate cpr \n" +
                "on cpr.currency_pair_id = t.currency_pair_id and t.dt_max = cpr.date ";*/

        val cursor = db.rawQuery(sql_select, null)
        try {
            val idColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry._ID)
            val symbolColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_SYMBOL)
            val baseCurColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_BASE_CURRECNCY_ID)
            val quoteCurColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_QUOTE_CURRECNCY_ID)
            val precisionColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_PRECISION)
            val sharedColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairEntry.COLUMN_IS_SHARED)

            while (cursor.moveToNext()) {
                val currentID = cursor.getInt(idColumnIndex)
                val currentSymbol = cursor.getString(symbolColumnIndex)
                val currentBaseCur = cursor.getInt(baseCurColumnIndex)
                val currentQuoteCur = cursor.getInt(quoteCurColumnIndex)
                val currentPrecision: Int = if (precisionColumnIndex >= 0) cursor.getInt(precisionColumnIndex) else 5

                currencyPairList.AddCurrencyPair(currentID, currentSymbol,
                        currencyList.getCurrencyById(currentBaseCur),
                        currencyList.getCurrencyById(currentQuoteCur),
                        currentPrecision,
                        cursor.getInt(sharedColumnIndex) != 0)
            }
        } finally {
            cursor.close()
        }
    }

    /**
     * Курс на конкретную дату (или ближайший более ранний), напрямую по валютам —
     * без привязки к символу пары и без учёта направления (использует
     * `v_currency_conversion_rate`, где обратный курс уже посчитан). Нужен для
     * автоподстановки суммы во второй валюте при переводе между разновалютными счетами.
     * Возвращает `null`, если курса на эту дату (или раньше) ещё не завели.
     */
    /**
     * Курс и дата, на которую он реально стоит. Дата почти никогда не равна запрошенной:
     * берётся ближайший курс не позже неё, — поэтому её и надо показывать, а не дату
     * операции, иначе подпись утверждает, что курс свежий.
     */
    class RateOnDate {
        @JvmField val rate: Double
        @JvmField val date: Date

        constructor(rate: Double, date: Date) {
            this.rate = rate
            this.date = date
        }
    }

    @JvmStatic
    fun LoadRateOnDate(db: SQLiteDatabase, fromCurrencyId: Int, toCurrencyId: Int, date: Date): RateOnDate? {
        if (fromCurrencyId == toCurrencyId) return RateOnDate(1.0, date)

        val cursor = db.rawQuery(
                "SELECT rate, date FROM v_currency_conversion_rate " +
                "WHERE from_currency_id = ? AND to_currency_id = ? AND date <= ? " +
                "ORDER BY date DESC LIMIT 1",
                arrayOf<String>(fromCurrencyId.toString(), toCurrencyId.toString(), DateUtils.DateToDateStr(date)))
        try {
            if (cursor.moveToFirst())
                return RateOnDate(cursor.getDouble(0), DateUtils.StrToDate(cursor.getString(1)))
            return null
        } finally {
            cursor.close()
        }
    }

    @JvmStatic
    fun LoadLastRateDate(db: SQLiteDatabase, currencyPair: String): Date {
        // deleted_at IS NULL — с v72 мягко удалённый курс не должен считаться
        // "последним известным" (см. RateEditDialog.deleteRate).
        val sql = "SELECT MAX(cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ") max_date" +
                " FROM " + WalletContract.CurrencyPairRateEntry.TABLE_NAME + " cpr" +
                " JOIN " + WalletContract.CurrencyPairEntry.TABLE_NAME + " cp" +
                " ON cp." + WalletContract.CurrencyPairEntry._ID +
                " = cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                " WHERE cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL + " = ?" +
                " AND cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + " <= ?" +
                " AND cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL"
        val cursor = db.rawQuery(sql, arrayOf<String>(currencyPair, DateUtils.DateToStr(Date())))
        try {
            if (cursor.moveToFirst() && !cursor.isNull(0))
                return DateUtils.StrToDate(cursor.getString(0))
            return Date()
        } finally {
            cursor.close()
        }
    }

    @JvmStatic
    fun LoadCurrencyPairRateList(db: SQLiteDatabase, currencyPair: String): ArrayList<Pair<Date, Double>> {
        return LoadCurrencyPairRateList(db, currencyPair, null, null)
    }

    @JvmStatic
    fun LoadCurrencyPairRateList(db: SQLiteDatabase, currencyPair: String, dateFrom: Date?, dateTo: Date?): ArrayList<Pair<Date, Double>> {
        val res = ArrayList<Pair<Date, Double>>()

        var dateFilter = ""
        if (dateFrom != null && dateTo != null)
            // Rates are stored as plain 'yyyy-MM-dd' (no time), so comparing against a
            // 'yyyy-MM-dd HH:mm' bound would exclude the exact boundary day (e.g. selecting
            // "from 2009-07-30" must still include the 2009-07-30 rate). Compare on the date
            // part only so both ends of the range are inclusive by calendar day.
            dateFilter = " AND substr(cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DATE + ", 1, 10)" +
                    " BETWEEN '" + DateUtils.DateToDateStr(dateFrom) + "' AND '" + DateUtils.DateToDateStr(dateTo) + "'"

        // deleted_at IS NULL — с v72 мягко удалённый курс не должен появляться в
        // списке (см. RateEditDialog.deleteRate).
        val sql_select = "SELECT " + WalletContract.CurrencyPairRateEntry.COLUMN_DATE +
                ", "+ WalletContract.CurrencyPairRateEntry.COLUMN_RATE +
                " FROM "+ WalletContract.CurrencyPairRateEntry.TABLE_NAME +
                " cpr \n JOIN "+ WalletContract.CurrencyPairEntry.TABLE_NAME +
                " cp\n on cp."+ WalletContract.CurrencyPairEntry._ID +
                " = cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE +
                " and cp." + WalletContract.CurrencyPairEntry.COLUMN_SYMBOL +" = '" + currencyPair +
                "'" + dateFilter +
                " and cpr." + WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " is null" +
                " Order by "+ WalletContract.CurrencyPairRateEntry.COLUMN_DATE +" desc"

        val cursor = db.rawQuery(sql_select, null)
        try {
            val idColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry._ID)
            val dateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_DATE)
            val rateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_RATE)

            while (cursor.moveToNext()) {
                //int currentID = cursor.getInt(idColumnIndex);
                val dt = DateUtils.StrToDate(cursor.getString(dateColumnIndex))
                val rate: Double? = cursor.getDouble(rateColumnIndex)

                res.add(Pair(dt, rate))
            }
            return res
        } finally {
            cursor.close()
        }
    }

    /*  public static void LoadCurrencyConversionRates(SQLiteDatabase db, List<Currency> fromCurrency, Currency toCurrency,
                                                                     Date dateFrom, Date dateTo, CurrencyConversionList currencyConversionList)
    {
        ArrayList<Pair<Date, Double>> res = new ArrayList<Pair<Date, Double>>();


        String fromCurParam = "";

        for (Currency frCur:fromCurrency)
        {
            fromCurParam = fromCurParam + "," + String.valueOf(frCur.getId());
        }


        String sql_select = "SELECT from_currency_id, to_currency_id, date, rate  \n"
                +"FROM v_currency_conversion_rate\n" +
                "where date between '"+String.valueOf(dateFrom.getTime())
                +"' and '"+String.valueOf(dateTo.getTime())+"'\n" +
                "and from_currency_id in ("+fromCurParam+") and to_currency_id =" + String.valueOf(toCurrency.getId());


        Cursor cursor = db.rawQuery(sql_select, null);
        try {
            int fromCurrencyColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyConversionEntry.COLUMN_FROM_CURRENCY_ID);
            int toCurrencyColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyConversionEntry.COLUMN_TO_CURRENCY_ID);
            int dateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_DATE);
            int rateColumnIndex = cursor.getColumnIndex(WalletContract.CurrencyPairRateEntry.COLUMN_RATE);

            while (cursor.moveToNext()) {
            //    int fromCurrency = cursor.getInt(fromCurrencyColumnIndex);
           //     int toCurrency = cursor.getInt(toCurrencyColumnIndex);
                Date dt = new Date(cursor.getLong(dateColumnIndex));
                Double rate = cursor.getDouble(rateColumnIndex);

                currencyConversionList.AddConversionRate();
                res.add(new Pair(dt, rate));
            }
       //     return res;
        } finally {
            cursor.close();
        }
    }*/

    @JvmStatic
    fun LoadAccounts(db: SQLiteDatabase, accounts: AccountList, currencyList: CurrencyList, iconList: IconList, specificId: Int?) {

        // a.deleted_at IS NULL — с v72 WalletDbCRUD.DeleteAccount стал мягким (см.
        // её javadoc); без фильтра "удалённый" счёт продолжал бы появляться везде,
        // где используется этот список (пикеры, категории, лента). ao.deleted_at —
        // аналогично снятая "операционность" не должна больше давать валюту счёту.
        var sql_select = "SELECT a." + WalletContract.AccountEntry._ID +
                ", a."+ WalletContract.AccountEntry.COLUMN_NAME +
                ", a."+ WalletContract.AccountEntry.COLUMN_COMMENT +
                ", a."+ WalletContract.AccountEntry.COLUMN_ICON_ID +
                ", a."+ WalletContract.AccountEntry.COLUMN_IS_ARCHIVED +
                ", a."+ WalletContract.AccountEntry.COLUMN_SYSTEM_KIND +
                ", a."+ WalletContract.AccountEntry.COLUMN_KIND +
                ", a."+ WalletContract.AccountEntry.COLUMN_CATEGORY_KIND +

                ", ao."+ WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID +
                // Признак актива (capital-assets) — только живая строка и только у кошелька.
                ", aa."+ WalletContract.AccountAssetEntry.COLUMN_ASSET_TYPE +
                ", aa."+ WalletContract.AccountAssetEntry.COLUMN_INCLUDE_IN_NET_WORTH +
                // Признак долга (debts) — так же, только живая строка у кошелька.
                ", ad."+ WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID +
                ", ad."+ WalletContract.AccountDebtEntry.COLUMN_COMMENT + " debt_comment" +
                ", ad."+ WalletContract.AccountDebtEntry.COLUMN_DUE_DATE +

                " FROM "+ WalletContract.AccountEntry.TABLE_NAME +
                " a left join "+ WalletContract.AccountOperatingEntry.TABLE_NAME +
                " ao on a." +WalletContract.AccountEntry._ID + "=ao."+WalletContract.AccountOperatingEntry._ID +
                " and ao." + WalletContract.AccountOperatingEntry.COLUMN_DELETED_AT + " is null" +
                " left join "+ WalletContract.AccountAssetEntry.TABLE_NAME +
                " aa on aa." + WalletContract.AccountAssetEntry._ID + "=ao." + WalletContract.AccountOperatingEntry._ID +
                " and aa." + WalletContract.AccountAssetEntry.COLUMN_DELETED_AT + " is null" +
                " left join "+ WalletContract.AccountDebtEntry.TABLE_NAME +
                " ad on ad." + WalletContract.AccountDebtEntry._ID + "=ao." + WalletContract.AccountOperatingEntry._ID +
                " and ad." + WalletContract.AccountDebtEntry.COLUMN_DELETED_AT + " is null" +
                " left join "+ WalletContract.CurrencyEntry.TABLE_NAME +
                " c on c." +WalletContract.CurrencyEntry._ID + "=ao."+WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID+"\n" +
                " WHERE a." + WalletContract.AccountEntry.COLUMN_DELETED_AT + " IS NULL"

        if (specificId != null)
            sql_select = sql_select + " AND a."+WalletContract.AccountEntry._ID+"="+specificId.toString()

        val cursor = db.rawQuery(sql_select, null)

        try {
            val idColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry._ID)
            val nameColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_NAME)
            val commentColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_COMMENT)
            val icoIdColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_ICON_ID)
            val isArchivedColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_IS_ARCHIVED)
            val systemKindColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_SYSTEM_KIND)
            val kindColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_KIND)
            val categoryKindColumnIndex = cursor.getColumnIndex(WalletContract.AccountEntry.COLUMN_CATEGORY_KIND)

            val curIdColumnIndex = cursor.getColumnIndex(WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID)
            val assetTypeColumnIndex = cursor.getColumnIndex(WalletContract.AccountAssetEntry.COLUMN_ASSET_TYPE)
            val includeColumnIndex = cursor.getColumnIndex(WalletContract.AccountAssetEntry.COLUMN_INCLUDE_IN_NET_WORTH)
            val counterpartyColumnIndex = cursor.getColumnIndex(WalletContract.AccountDebtEntry.COLUMN_COUNTERPARTY_UUID)
            val debtCommentColumnIndex = cursor.getColumnIndex("debt_comment")
            val dueDateColumnIndex = cursor.getColumnIndex(WalletContract.AccountDebtEntry.COLUMN_DUE_DATE)


            while (cursor.moveToNext()) {
                val currentID = cursor.getInt(idColumnIndex)
                val currentName = cursor.getString(nameColumnIndex)
                val currentComment = cursor.getString(commentColumnIndex)
                val currentIconId: Int? = cursor.getInt(icoIdColumnIndex)

                val curId = cursor.getInt(curIdColumnIndex)

                val icon = iconList.getIconByIdOrUnknown(currentIconId)
                val currentIsArchived = cursor.getInt(isArchivedColumnIndex)==1
                val currentSystemKind = AccountItem.SystemKind.fromValue(cursor.getInt(systemKindColumnIndex))
                val currentKind = AccountItem.Kind.fromValue(cursor.getInt(kindColumnIndex))


                val asset: AccountOperatingItem.AssetInfo? = if (cursor.isNull(assetTypeColumnIndex)) null else AccountOperatingItem.AssetInfo(cursor.getInt(assetTypeColumnIndex),
                                cursor.getInt(includeColumnIndex) != 0)
                val debt: AccountOperatingItem.DebtInfo? = if (cursor.isNull(counterpartyColumnIndex)) null else AccountOperatingItem.DebtInfo(cursor.getString(counterpartyColumnIndex),
                                cursor.getString(debtCommentColumnIndex), cursor.getString(dueDateColumnIndex))

                val account = accounts.getAccountById(currentID)

                if (account == null) {
                    if (curId != 0) {
                        val wallet = AccountOperatingItem(currentID, currentName, currentComment, icon, currentIsArchived, currencyList.getCurrencyById(curId), currentSystemKind, currentKind)
                        wallet.setAsset(asset)
                        wallet.setDebt(debt)
                        accounts.put(currentID, wallet)
                    } else
                        accounts.put(currentID, AccountItem(currentID, currentName, currentComment, icon, currentIsArchived, currentSystemKind, currentKind))
                }
                else {
                    account.update(currentName, currentComment, icon, currentIsArchived, currentSystemKind, currentKind)
                    if (account is AccountOperatingItem) {
                        ((account as AccountOperatingItem)).setAsset(asset)
                        ((account as AccountOperatingItem)).setDebt(debt)
                    }
                }
                // Вид категории (v92): ставим после создания/обновления, чтобы не менять
                // конструкторы AccountItem. Любое значение вне 0..2 читается как «Оба».
                val loaded = accounts.getAccountById(currentID)
                if (loaded != null) {
                    loaded.setCategoryKind(AccountItem.CategoryKind.fromValue(
                            cursor.getInt(categoryKindColumnIndex)))
                }
            }
            accounts.notifyObservers()
        } finally {
            cursor.close()
        }
        WalletDbLoader.UpdateAccountsOperatingBalance(db, accounts)
    }

    /**
     * Живые контрагенты (openspec debts, design D4), по имени без учёта регистра. Архивные
     * тоже — экран «Контрагенты» показывает их отдельно, а займы архивного не теряются.
     */
    @JvmStatic
    fun LoadCounterparties(db: SQLiteDatabase): List<CounterpartyItem> {
        val out = ArrayList<CounterpartyItem>()
        val c = db.rawQuery("SELECT " + WalletContract.CounterpartyEntry._ID +
                ", " + WalletContract.CounterpartyEntry.COLUMN_UUID +
                ", " + WalletContract.CounterpartyEntry.COLUMN_NAME +
                ", " + WalletContract.CounterpartyEntry.COLUMN_COMMENT +
                ", " + WalletContract.CounterpartyEntry.COLUMN_ICON_UUID +
                ", " + WalletContract.CounterpartyEntry.COLUMN_IS_ARCHIVED +
                " FROM " + WalletContract.CounterpartyEntry.TABLE_NAME +
                " WHERE " + WalletContract.CounterpartyEntry.COLUMN_DELETED_AT + " IS NULL" +
                " ORDER BY " + AccountNames.keySql(WalletContract.CounterpartyEntry.COLUMN_NAME), null)
        try {
            while (c.moveToNext()) {
                out.add(CounterpartyItem(
                        c.getInt(0), c.getString(1), c.getString(2), c.getString(3), if (c.isNull(4)) null else c.getString(4), c.getInt(5) != 0))
            }
        } finally {
            c.close()
        }
        return out
    }

    /**
     * Сколько операций отдаст [LoadOperations] с этим же фильтром и периодом —
     * для кнопки «Показать N» на экране «Фильтр» (FilterActivity), пересчитывается на
     * лету, пока пользователь ещё не применил выбор.
     *
     * Условие "category→category" исключено тем же способом, каким LoadOperations
     * отбрасывает такие строки на Java-стороне (см. её тело) — иначе счётчик обещал бы
     * больше строк, чем реально попадёт в ленту.
     */
    @JvmStatic
    fun CountOperations(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, toCurrency: Currency, dataFilter: DataFilter): Int {
        val accountsSQL = dataFilter.getSQLFilter(arrayOf<String>(WalletContract.OperationEntry.COLUMN_FROM, WalletContract.OperationEntry.COLUMN_TO))

        val sql_select = "SELECT COUNT(*)" +
                " FROM v_operation_eq t" +
                " WHERE " + WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" + DateUtils.DateToStr(dateTo) +
                "' AND (nullIf(to_currency_id," + toCurrency.id + ") is null)\n" +
                " AND NOT (acc_from_is_operating = 0 AND acc_to_is_operating = 0)\n" +
                accountsSQL

        val cursor = db.rawQuery(sql_select, null)
        try {
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    /**
     * Кошельки, которые участвуют хотя бы в одной операции периода, прошедшей фильтр —
     * те же условия, что у [LoadOperations]. По ним «Кошельки» решают, какие строки
     * показать при включённом фильтре (см. AccountActivity.applyFilter).
     */
    @JvmStatic
    fun LoadWalletIdsWithOperations(db: SQLiteDatabase, dateFrom: Date, dateTo: Date, toCurrency: Currency, dataFilter: DataFilter): Set<Int> {
        val where = " FROM v_operation_eq" +
                " WHERE " + WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" + DateUtils.DateToStr(dateTo) +
                "' AND (nullIf(to_currency_id," + toCurrency.id + ") is null)\n" +
                reportFilterSql(dataFilter)

        val sql = "SELECT acc_fr_id" + where + " AND acc_from_is_operating = 1\n" +
                "UNION SELECT acc_to_id" + where + " AND acc_to_is_operating = 1"

        val ids = java.util.HashSet<Int>()
        val cursor = db.rawQuery(sql, null)
        try {
            while (cursor.moveToNext()) ids.add(cursor.getInt(0))
        } finally {
            cursor.close()
        }
        return ids
    }

    @JvmStatic
    fun LoadOperations(db: SQLiteDatabase, transactions: OperationList, accounts: AccountList, dateFrom: Date, dateTo: Date, toCurrency: Currency, dataFilter: DataFilter) {
        transactions.clear()

        val accountsSQL = dataFilter.getSQLFilter(arrayOf<String>(WalletContract.OperationEntry.COLUMN_FROM, WalletContract.OperationEntry.COLUMN_TO))


        val sql_select = "SELECT " +
                "t." + WalletContract.OperationEntry._ID +
                ", "+ WalletContract.OperationEntry.COLUMN_DATE +
                ", "+ WalletContract.OperationEntry.COLUMN_FROM +
                ", "+ WalletContract.OperationEntry.COLUMN_TO +
                ", "+ WalletContract.OperationEntry.COLUMN_AMOUNT +
                ", amount_eq" +
                ", t."+ WalletContract.OperationEntry.COLUMN_COMMENT +
                " FROM v_operation_eq  t" +
                " WHERE "+ WalletContract.OperationEntry.COLUMN_DATE + " BETWEEN '" + DateUtils.DateToStr(dateFrom) + "' AND '" +DateUtils.DateToStr(dateTo) +
                "' AND (nullIf(to_currency_id,"+toCurrency.id+") is null)\n" +
                accountsSQL +
                "\nORDER BY "+ WalletContract.OperationEntry.COLUMN_DATE +" DESC"

        // Делаем запрос
        val cursor = db.rawQuery(sql_select, null)                   // порядок сортировки

        try {
            // Узнаем индекс каждого столбца
            val idColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry._ID)
            val dateColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_DATE)
            val accFromColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_FROM)
            val accToColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_TO)

            val amountRCColumnIndex = cursor.getColumnIndex("amount_eq")
            val amountColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_AMOUNT)
            val commentColumnIndex = cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_COMMENT)

            var amEq: Double?? = null
            while (cursor.moveToNext()) {
                val rowId = cursor.getInt(idColumnIndex)
                try {
                    if (!cursor.isNull(amountRCColumnIndex))
                        amEq = cursor.getDouble(amountRCColumnIndex)
                    else
                        amEq = null

                    val accFrom = accounts.getAccountById(cursor.getInt(accFromColumnIndex))
                    val accTo = accounts.getAccountById(cursor.getInt(accToColumnIndex))

                    if (accFrom == null || accTo == null) {
                        Log.w("WalletDbLoader", "LoadOperations: skipping op id=" + rowId +
                                " — accFrom=" + accFrom + " accTo=" + accTo)
                        continue
                    }

                    if (!(accFrom is com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem)
                            && !(accTo is com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem)) {
                        Log.w("WalletDbLoader", "LoadOperations: skipping category→category op id=" + rowId +
                                " (" + accFrom.name + "→" + accTo.name + ")")
                        continue
                    }

                    transactions.add(OperationItem(rowId,
                            DateUtils.StrToDate(cursor.getString(dateColumnIndex)),
                            accFrom, accTo,
                            cursor.getDouble(amountColumnIndex),
                            amEq,
                            cursor.getString(commentColumnIndex)))

                } catch (e: Exception) {
                    Log.e("WalletDbLoader", "LoadOperations: error parsing op id=" + rowId +
                            " — " + e.message + ". Scheduling repair.")
                    WalletDbCRUD.repairDatabase(db)
                }
            }
        } finally {
            cursor.close()
        }
        AttachLinkedOperations(db, transactions, accounts, toCurrency)
        AttachDebtLabels(db, transactions, accounts)
        transactions.notifyObservers()
    }

    /**
     * Подписи «Дал в долг» / «Взял в долг» / «Вернул долг» / «Мне вернули» (openspec debts,
     * design D6): все живые проводки займов — по всем датам, не только периода ленты, —
     * считаются одним проходом [DebtLabels.compute]; строке ленты достаётся подпись её
     * самой или второй ноги валютного перевода (там заём — получатель моста «Transfer»).
     */
    @JvmStatic
    fun AttachDebtLabels(db: SQLiteDatabase, transactions: OperationList, accounts: AccountList) {
        if (transactions.isEmpty() || !HasDebtWallets(db)) return
        val postings = ArrayList<DebtLabels.Posting>()
        val loanOf = HashMap<Int, Int>()
        val c = db.rawQuery("SELECT o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, " +
                "adf._id IS NOT NULL, adt._id IS NOT NULL FROM operation o " +
                "LEFT JOIN account_debt adf ON adf._id = o.acc_fr_id AND adf.deleted_at IS NULL " +
                "LEFT JOIN account_debt adt ON adt._id = o.acc_to_id AND adt.deleted_at IS NULL " +
                "WHERE o.deleted_at IS NULL AND (adf._id IS NULL) <> (adt._id IS NULL)", null)
        try {
            while (c.moveToNext()) {
                val toLoan = c.getInt(6) != 0
                val loan: Int = if (toLoan) c.getInt(3) else c.getInt(2)
                val amount: Double = c.getDouble(4)
                postings.add(DebtLabels.Posting(c.getInt(0), c.getString(1), loan, if (toLoan) amount else -amount))
                loanOf.put(c.getInt(0), loan)
            }
        } finally {
            c.close()
        }
        if (postings.isEmpty()) return
        val labels = DebtLabels.compute(postings)
        val names = HashMap<String, String>()
        for (cp in LoadCounterparties(db)) names.put(cp.uuid, cp.name)

        for (i in 0 until transactions.size) {
            val op = transactions.get(i)
            var id = op.IdTran
            if (!labels.containsKey(id) && op.LinkedLeg != null) id = op.LinkedLeg!!.IdTran
            val label = labels.get(id)
            if (label == null) continue
            op.DebtLabel = label
            val loan = accounts.getAccountById(loanOf.get(id)!!)
            if (loan is AccountOperatingItem && ((loan as AccountOperatingItem)).isDebt) {
                op.DebtCounterparty = names.get(((loan as AccountOperatingItem)).debt!!.counterpartyUuid)
            }
        }
    }

    /**
     * Схлопывает связанные проводки в одну строку ленты: вторая нога валютного перевода и
     * комиссия уходят внутрь главной проводки и перестают быть отдельными операциями.
     *
     * Дочерние проводки догружаются отдельно, а не берутся из уже загруженного списка:
     * фильтр по счетам мог их отсечь (нога «Transfer → кошелёк» не проходит фильтр по
     * кошельку-источнику), а показать операцию целиком всё равно нужно.
     */
    private fun AttachLinkedOperations(db: SQLiteDatabase, transactions: OperationList, accounts: AccountList, toCurrency: Currency) {
        if (transactions.isEmpty()) return

        val ids = StringBuilder()
        for (op in transactions) {
            if (ids.length > 0) ids.append(",")
            ids.append(op.IdTran)
        }

        // Проводка, привязанная к другой, своей строки в ленте не получает.
        val childIds = HashSet<Int>()
        var cursor = db.rawQuery("SELECT " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID +
                " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME +
                " WHERE " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID + " IN (" + ids + ")" +
                // Ничто в коде сегодня не удаляет operation_link мягко (нет метода
                // DeleteOperationLink) — фильтр на будущее, не меняет поведение сейчас.
                " AND " + WalletContract.OperationLinkEntry.COLUMN_DELETED_AT + " IS NULL", null)
        try {
            while (cursor.moveToNext()) childIds.add(cursor.getInt(0))
        } finally {
            cursor.close()
        }
        if (!childIds.isEmpty()) {
            transactions.removeIf({ op -> childIds.contains(op.IdTran) })
        }
        if (transactions.isEmpty()) return

        // Связи и дочерние проводки — двумя запросами на всю ленту, а не по паре на строку
        // (AttachLinksTo): каждый запрос к v_operation_eq заново материализует
        // v_currency_conversion_rate (~15–30 мс), и за период «с 2018» (8,6 тыс. строк,
        // 274 связи) старт шёл ~5 с на главном потоке.
        val mainIds = StringBuilder()
        for (op in transactions) {
            if (mainIds.length > 0) mainIds.append(",")
            mainIds.append(op.IdTran)
        }
        val linksByMain = HashMap<Int, MutableMap<Int, OperationItem.LinkType>>()
        val linkedIds = HashSet<Int>()
        cursor = db.rawQuery("SELECT " + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID +
                ", " + WalletContract.OperationLinkEntry.COLUMN_LINK_ID +
                ", " + WalletContract.OperationLinkEntry.COLUMN_TYPE +
                " FROM " + WalletContract.OperationLinkEntry.TABLE_NAME +
                " WHERE " + WalletContract.OperationLinkEntry.COLUMN_MAIN_ID + " IN (" + mainIds + ")", null)
        try {
            while (cursor.moveToNext()) {
                linksByMain.computeIfAbsent(cursor.getInt(0), { k -> LinkedHashMap() })
                        .put(cursor.getInt(1), OperationItem.LinkType.fromValue(cursor.getInt(2)))
                linkedIds.add(cursor.getInt(1))
            }
        } finally {
            cursor.close()
        }
        val children = LoadOperationsByIds(db, linkedIds, accounts, toCurrency)

        for (op in transactions) {
            op.LinkedLeg = null
            op.LinkedCommission = null
            val links = linksByMain.get(op.IdTran)
            if (links != null) attachLinks(op, links, children)
        }
    }

    /** Роль связанной проводки — явный operation_link.type (v77), см. [OperationItem.LinkType]. */
    private fun attachLinks(op: OperationItem, links: Map<Int, OperationItem.LinkType>, children: Map<Int, OperationItem>) {
        for (e in links.entries) {
            val child = children.get(e.key)
            if (child == null) continue
            if (e.value == OperationItem.LinkType.LEG) op.LinkedLeg = child
            else op.LinkedCommission = child
        }
    }

    private const val SELECT_OPERATION_EQ = "SELECT t." + WalletContract.OperationEntry._ID +
            ", " + WalletContract.OperationEntry.COLUMN_DATE +
            ", " + WalletContract.OperationEntry.COLUMN_FROM +
            ", " + WalletContract.OperationEntry.COLUMN_TO +
            ", " + WalletContract.OperationEntry.COLUMN_AMOUNT +
            ", amount_eq" +
            ", t." + WalletContract.OperationEntry.COLUMN_COMMENT +
            " FROM v_operation_eq t"

    /** Операции по id одним запросом — как [LoadSingleOperation], но пачкой. */
    @JvmStatic
    fun LoadOperationsByIds(db: SQLiteDatabase, ids: Set<Int>, accounts: AccountList, toCurrency: Currency): Map<Int, OperationItem> {
        val result = HashMap<Int, OperationItem>()
        if (ids.isEmpty()) return result
        val input = StringBuilder()
        for (id in ids) {
            if (input.length > 0) input.append(",")
            input.append(id)
        }
        val cursor = db.rawQuery(SELECT_OPERATION_EQ +
                " WHERE t." + WalletContract.OperationEntry._ID + " IN (" + input + ")" +
                " AND (nullIf(to_currency_id, " + toCurrency.id + ") is null)", null)
        try {
            while (cursor.moveToNext()) {
                val op = readOperationEq(cursor, accounts)
                result.put(op.IdTran, op)
            }
        } finally {
            cursor.close()
        }
        return result
    }

    /** Строка [SELECT_OPERATION_EQ] → операция. */
    private fun readOperationEq(cursor: Cursor, accounts: AccountList): OperationItem {
        val amountEqIdx = cursor.getColumnIndex("amount_eq")
        val amEq: Double? = if (cursor.isNull(amountEqIdx)) null else cursor.getDouble(amountEqIdx)
        return OperationItem(
                cursor.getInt(cursor.getColumnIndex(WalletContract.OperationEntry._ID)),
                DateUtils.StrToDate(cursor.getString(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_DATE))),
                accounts.getAccountById(cursor.getInt(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_FROM)))!!,
                accounts.getAccountById(cursor.getInt(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_TO)))!!,
                cursor.getDouble(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_AMOUNT)),
                amEq,
                cursor.getString(cursor.getColumnIndex(WalletContract.OperationEntry.COLUMN_COMMENT)))
    }

    /**
     * Подвешивает связанные проводки к одной операции. Нужно и при точечном обновлении
     * ленты после правки: перезагруженная операция иначе теряет вторую сумму и комиссию.
     */
    @JvmStatic
    fun AttachLinksTo(db: SQLiteDatabase, op: OperationItem, accounts: AccountList, toCurrency: Currency) {
        op.LinkedLeg = null
        op.LinkedCommission = null

        val linkTypes = WalletDbCRUD.GetLinkedOperations(db, op.IdTran)
        if (linkTypes.isEmpty()) return
        attachLinks(op, linkTypes,
                LoadOperationsByIds(db, linkTypes.keys, accounts, toCurrency))
    }

    @JvmStatic
    fun LoadSingleOperation(db: SQLiteDatabase, tranId: Int, accounts: AccountList, toCurrency: Currency): OperationItem? {
        val cursor = db.rawQuery(SELECT_OPERATION_EQ +
                " WHERE t." + WalletContract.OperationEntry._ID + " = ?" +
                " AND (nullIf(to_currency_id, " + toCurrency.id + ") is null)",
                arrayOf<String>(tranId.toString()))
        try {
            return if (cursor.moveToFirst()) readOperationEq(cursor, accounts) else null
        } finally {
            cursor.close()
        }
    }
}
