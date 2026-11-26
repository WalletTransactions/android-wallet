package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.DataFilter
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DateUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * openspec capital-assets: таблица {@code account_asset} и шаг v89, признак актива в модели,
 * системная «Переоценка», запись переоценки, исключение её из дохода/расхода, итоги
 * «ликвидные / капитал / всего с капиталом» и перенос операций категории в актив.
 */
@RunWith(RobolectricTestRunner::class)
class CapitalAssetsDbTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0
    private var rub = 0
    private var usd = 0
    private var eur = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        // Сид оставляем только справочником иконок — свои валюты, счета и курсы ниже.
        db.execSQL("DELETE FROM operation_link")
        db.execSQL("DELETE FROM operation")
        db.execSQL("DELETE FROM recurring_operation")
        db.execSQL("DELETE FROM account_asset")
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        db.execSQL("DELETE FROM currency_conversion")
        db.execSQL("DELETE FROM currency_pair_rate")
        db.execSQL("DELETE FROM currency_pair")
        db.execSQL("DELETE FROM currency")
        // Картинки сида не влезают в CursorWindow Robolectric при LoadIcons — свои, маленькие.
        db.execSQL("DELETE FROM icon")
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        insert("icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        rub = currency("Рубль", "RUB", "₽")
        usd = currency("Доллар", "USD", "$")
        eur = currency("Евро", "EUR", "€")
        val pair = insert("currency_pair", "symbol", "USDRUB", "base_currency_id", usd, "quote_currency_id", rub)
        insert("currency_pair_rate", "currency_pair_id", pair, "date", "2025-01-01", "rate", 90.0)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 1.1 схема и шаг v89 ─────────────────────────────────────────────────

    @Test
    fun freshInstall_hasAccountAssetTable_withoutRereadFlag() {
        assertTrue(WalletDbHelper.tableExists(db, "account_asset"))
        assertTrue(exists("trigger", "trg_account_asset_dirty"))
        assertTrue(exists("index", "ix_account_asset_uuid"))
        assertFalse(AppSettings.isLedgerRereadPending(context))
    }

    @Test
    fun upgradeFrom88_createsTableTriggerViewColumn_andRaisesRereadFlag() {
        db.execSQL("DROP TABLE account_asset")
        db.execSQL("DROP VIEW v_operation_eq")
        db.execSQL("DROP VIEW v_operation")
        db.execSQL("CREATE VIEW v_operation AS SELECT 1 x")

        helper.onUpgrade(db, 88, 89)

        assertTrue(WalletDbHelper.tableExists(db, "account_asset"))
        assertTrue(exists("trigger", "trg_account_asset_dirty"))
        assertTrue(exists("index", "ix_account_asset_uuid"))
        assertTrue(AppSettings.isLedgerRereadPending(context))
        val c = db.rawQuery("SELECT is_revaluation FROM v_operation_eq LIMIT 0", null)
        c.close()

        // Триггер dirty работает: UPDATE без dirty поднимает его.
        val wallet = wallet("Квартира", rub)
        assertTrue(WalletDbCRUD.SetAccountAsset(db, wallet, 1, true))
        db.execSQL("UPDATE account_asset SET dirty = 0")
        db.execSQL("UPDATE account_asset SET comment = 'x'")
        assertEquals(1, intQuery("SELECT dirty FROM account_asset WHERE _id = " + wallet))
    }

    // ── 1.2 модель ──────────────────────────────────────────────────────────

    @Test
    fun systemKindFromValue3_isRevaluation() {
        assertEquals(AccountItem.SystemKind.REVALUATION, AccountItem.SystemKind.fromValue(3))
        val reval = AccountItem(
            1, "Переоценка", "", null, false,
            AccountItem.SystemKind.REVALUATION, AccountItem.Kind.CATEGORY
        )
        assertTrue(reval.isSystemRevaluation)
        assertTrue("нельзя удалить и переименовать", reval.isSystem)
    }

    @Test
    fun loadAccounts_readsLiveAssetOnly() {
        val flat = wallet("Квартира", rub)
        val car = wallet("Машина", rub)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        WalletDbCRUD.SetAccountAsset(db, car, 2, false)
        WalletDbCRUD.ClearAccountAsset(db, car)

        val accounts = loadAccounts()
        val f = accounts.getAccountById(flat) as AccountOperatingItem
        assertNotNull(f.asset)
        assertEquals(1, f.asset!!.type)
        assertTrue(f.asset!!.includeInNetWorth)
        assertNull(
            "мягко удалённый признак — ликвидный кошелёк",
            (accounts.getAccountById(car) as AccountOperatingItem).asset
        )
    }

    // ── 1.3 пометка, снятие, повторная пометка ──────────────────────────────

    @Test
    fun setClearSet_keepsSingleRowWithAccountUuid_dirty() {
        val flat = wallet("Квартира", rub)
        val accountUuid = stringQuery("SELECT uuid FROM account WHERE _id = " + flat)

        assertTrue(WalletDbCRUD.SetAccountAsset(db, flat, 1, true))
        assertEquals(accountUuid, stringQuery("SELECT uuid FROM account_asset WHERE _id = " + flat))
        assertEquals(1, intQuery("SELECT dirty FROM account_asset WHERE _id = " + flat))

        db.execSQL("UPDATE account_asset SET dirty = 0")
        WalletDbCRUD.ClearAccountAsset(db, flat)
        assertEquals(1, intQuery("SELECT count(*) FROM account_asset WHERE deleted_at IS NOT NULL"))
        assertEquals(1, intQuery("SELECT dirty FROM account_asset WHERE _id = " + flat))

        db.execSQL("UPDATE account_asset SET dirty = 0")
        assertTrue(WalletDbCRUD.SetAccountAsset(db, flat, 3, false))
        assertEquals(1, intQuery("SELECT count(*) FROM account_asset"))
        assertEquals(0, intQuery("SELECT count(*) FROM account_asset WHERE deleted_at IS NOT NULL"))
        assertEquals(3, intQuery("SELECT asset_type FROM account_asset"))
        assertEquals(0, intQuery("SELECT include_in_net_worth FROM account_asset"))
        assertEquals(1, intQuery("SELECT dirty FROM account_asset"))
    }

    @Test
    fun setAccountAsset_refusesCategory() {
        val category = category("Продукты", 0)
        assertFalse(WalletDbCRUD.SetAccountAsset(db, category, 1, true))
        assertEquals(0, intQuery("SELECT count(*) FROM account_asset"))
    }

    // ── 1.4 is_revaluation ──────────────────────────────────────────────────

    @Test
    fun isRevaluation_eitherSide_ofSystemKind3() {
        val flat = wallet("Квартира", rub)
        val card = wallet("Карта", rub)
        val reval = category("Переоценка", 3)
        val food = category("Продукты", 0)
        val up = op("2026-03-01 23:59", reval, flat, 100.0)
        val down = op("2026-03-02 23:59", flat, reval, 50.0)
        val plain = op("2026-03-03 10:00", card, food, 10.0)

        assertEquals(1, intQuery("SELECT is_revaluation FROM v_operation_eq WHERE _id = " + up + " LIMIT 1"))
        assertEquals(1, intQuery("SELECT is_revaluation FROM v_operation_eq WHERE _id = " + down + " LIMIT 1"))
        assertEquals(0, intQuery("SELECT is_revaluation FROM v_operation_eq WHERE _id = " + plain + " LIMIT 1"))
    }

    // ── 3.1 системная категория ─────────────────────────────────────────────

    @Test
    fun revaluationCategory_createdOnce_withSystemKind3() {
        val id = WalletDbCRUD.GetOrCreateRevaluationCategory(db)
        assertTrue(id > 0)
        assertEquals("Переоценка", stringQuery("SELECT name FROM account WHERE _id = " + id))
        assertEquals(3, intQuery("SELECT system_kind FROM account WHERE _id = " + id))
        assertEquals(id, WalletDbCRUD.GetOrCreateRevaluationCategory(db))
    }

    @Test
    fun revaluationCategory_takenName_usesFallback_ordinaryUntouched() {
        val ordinary = category("Переоценка", 0)
        val id = WalletDbCRUD.GetOrCreateRevaluationCategory(db)
        assertNotEquals(ordinary, id)
        assertEquals("Переоценка активов", stringQuery("SELECT name FROM account WHERE _id = " + id))
        assertEquals(0, intQuery("SELECT system_kind FROM account WHERE _id = " + ordinary))
        assertEquals(id, WalletDbCRUD.GetOrCreateRevaluationCategory(db))
    }

    // ── 3.2 запись переоценки ───────────────────────────────────────────────

    @Test
    fun writeRevaluation_upDownBackdatedUnchanged() {
        val card = wallet("Карта", rub)
        val flat = wallet("Квартира", rub)
        val salary = category("Зарплата", 0)
        op("2026-01-01 10:00", salary, card, 20_000_000.0)
        op("2026-02-01 10:00", card, flat, 10_000_000.0)

        assertEquals(
            WalletDbCRUD.RevaluationResult.written,
            WalletDbCRUD.WriteRevaluation(db, flat, "2026-06-30", 12_000_000.0, 2, "Оценка: 12 000 000")
        )
        val reval = intQuery("SELECT _id FROM account WHERE system_kind = 3")
        assertEquals(
            1, intQuery(
                "SELECT count(*) FROM operation WHERE acc_fr_id = " + reval
                    + " AND acc_to_id = " + flat + " AND amount = 2000000 AND date = '2026-06-30 23:59'"
            )
        )
        assertEquals(12_000_000.0, WalletDbLoader.LoadAccountBalanceAt(db, flat, "2026-06-30 23:59"), 0.001)

        // Задним числом: Δ от баланса на 31 марта (10 000 000), а не от текущего.
        assertEquals(
            WalletDbCRUD.RevaluationResult.written,
            WalletDbCRUD.WriteRevaluation(db, flat, "2026-03-31", 9_700_000.0, 2, "")
        )
        assertEquals(
            1, intQuery(
                "SELECT count(*) FROM operation WHERE acc_fr_id = " + flat
                    + " AND acc_to_id = " + reval + " AND amount = 300000 AND date = '2026-03-31 23:59'"
            )
        )

        assertEquals(
            WalletDbCRUD.RevaluationResult.unchanged,
            WalletDbCRUD.WriteRevaluation(db, flat, "2026-03-31", 9_700_000.0, 2, "")
        )
        assertEquals(
            WalletDbCRUD.RevaluationResult.negative,
            WalletDbCRUD.WriteRevaluation(db, flat, "2026-03-31", -5.0, 2, "")
        )
        assertEquals(
            "Δ = 0 и отрицательная оценка не пишут проводок", 2,
            intQuery("SELECT count(*) FROM operation WHERE acc_fr_id = " + reval + " OR acc_to_id = " + reval)
        )
    }

    // ── 3.3 доход и расход без переоценки ───────────────────────────────────

    @Test
    fun incomeExpense_excludeRevaluation_includingManualOnes() {
        val card = wallet("Карта", rub)
        val flat = wallet("Квартира", rub)
        val salary = category("Зарплата", 0)
        val food = category("Продукты", 0)
        op("2026-05-01 10:00", salary, card, 1000.0)
        op("2026-05-02 10:00", card, food, 300.0)
        WalletDbCRUD.WriteRevaluation(db, flat, "2026-05-03", 2_000_000.0, 2, "") // вверх
        val reval = WalletDbCRUD.GetOrCreateRevaluationCategory(db)
        op("2026-05-04 10:00", card, reval, 77.0) // вручную, «вниз» с карты

        val accounts = loadAccounts()
        val currencies = currencies()
        val eq = currencies.getCurrencyById(rub)!!
        val from = DateUtils.StrToDate("2026-05-01 00:00")
        val to = DateUtils.StrToDate("2026-05-31 23:59")

        val income = WalletDbLoader.GenerateIncomeReportByAccount(db, from, to, accounts, currencies, eq, null).total
        val expense = WalletDbLoader.GenerateExpanseReportByAccount(db, from, to, accounts, currencies, eq, null).total
        assertEquals(1000.0, income.totalAmountEq!!, 0.001)
        assertEquals(300.0, expense.totalAmountEq!!, 0.001)

        val incomeFilter = DataFilter()
        incomeFilter.setOperationKind(DataFilter.OperationKind.income)
        val expenseFilter = DataFilter()
        expenseFilter.setOperationKind(DataFilter.OperationKind.expense)
        assertEquals(1, WalletDbLoader.CountOperations(db, from, to, eq, incomeFilter))
        assertEquals(1, WalletDbLoader.CountOperations(db, from, to, eq, expenseFilter))

        val revalItem = accounts.getAccountById(reval)!!
        val cardItem = accounts.getAccountById(card)!!
        assertFalse(expenseFilter.matchesKind(cardItem, revalItem))
        assertFalse(incomeFilter.matchesKind(revalItem, accounts.getAccountById(flat)!!))
    }

    // ── 4.1 балансы и капитал ───────────────────────────────────────────────

    @Test
    fun purchase_liquidDrops_capitalAndNetWorthKeepTotal() {
        val card = wallet("Карта", rub)
        val flat = wallet("Квартира", rub)
        val salary = category("Зарплата", 0)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        op("2025-12-01 10:00", salary, card, 10_000_000.0)
        op("2026-02-10 10:00", card, flat, 10_000_000.0)
        val eq = currencies().getCurrencyById(rub)!!
        val start = DateUtils.StrToDate("2026-02-01 00:00")
        val end = DateUtils.StrToDate("2026-03-01 00:00")

        val liquidStart = WalletDbLoader.GenerateReportGetBalanceToDate(db, start, eq, null)
        val liquidEnd = WalletDbLoader.GenerateReportGetBalanceToDate(db, end, eq, null)
        val capital = WalletDbLoader.GenerateCapitalToDate(db, end, eq)

        assertEquals(10_000_000.0, liquidStart.totalAmountEq!! - liquidEnd.totalAmountEq!!, 0.001)
        assertTrue(capital.hasAssets)
        assertEquals(10_000_000.0, capital.capital.totalAmountEq!!, 0.001)
        assertEquals(liquidStart.totalAmountEq!!, capital.netWorth.totalAmountEq!!, 0.001)
    }

    @Test
    fun assetWithoutRate_marksCapitalIncomplete_notConverted1to1() {
        val card = wallet("Карта", rub)
        val house = wallet("Дом", eur)
        val salary = category("Зарплата", 0)
        WalletDbCRUD.SetAccountAsset(db, house, 1, true)
        op("2026-01-01 10:00", salary, card, 500.0)
        op("2026-01-02 10:00", salary, house, 100_000.0)
        val capital = WalletDbLoader.GenerateCapitalToDate(
            db,
            DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub)!!
        )

        assertTrue(capital.capital.isIncomplete)
        assertEquals(0.0, capital.capital.totalAmountEq!!, 0.001)
        assertTrue(capital.netWorth.isIncomplete)
        assertEquals(500.0, capital.netWorth.totalAmountEq!!, 0.001)
    }

    @Test
    fun assetOutOfNetWorth_inCapitalOnly() {
        val card = wallet("Карта", rub)
        val car = wallet("Машина", rub)
        val salary = category("Зарплата", 0)
        WalletDbCRUD.SetAccountAsset(db, car, 2, false)
        op("2026-01-01 10:00", salary, card, 500.0)
        op("2026-01-02 10:00", salary, car, 1_500_000.0)
        val capital = WalletDbLoader.GenerateCapitalToDate(
            db,
            DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub)!!
        )

        assertEquals(1_500_000.0, capital.capital.totalAmountEq!!, 0.001)
        assertEquals(500.0, capital.netWorth.totalAmountEq!!, 0.001)
        assertEquals(
            500.0, WalletDbLoader.GenerateReportGetBalanceToDate(
                db,
                DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub)!!, null
            )
                .totalAmountEq!!, 0.001
        )
    }

    // ── .github-private#1: сверка «Итога» с «Доход − Расход» ───────────────

    @Test
    fun capitalFlow_closesGapBetweenTotalAndIncomeMinusExpense() {
        val card = wallet("Карта", rub)
        val flat = wallet("Квартира", rub)
        val deposit = wallet("Вклад", rub)
        val salary = category("Зарплата", 0)
        val food = category("Продукты", 0)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        WalletDbCRUD.SetAccountAsset(db, deposit, 3, true)
        op("2026-04-01 10:00", salary, card, 1_000_000.0) // до периода
        op("2026-05-02 10:00", salary, card, 200.0) // доход, ликвидный
        op("2026-05-03 10:00", card, food, 50.0) // расход, ликвидный
        op("2026-05-04 10:00", card, flat, 400_000.0) // покупка актива: −400 000
        op("2026-05-05 10:00", salary, deposit, 30.0) // доход прямо на актив: −30
        op("2026-05-06 10:00", flat, card, 1_000.0) // возврат из актива: +1 000
        op("2026-05-07 10:00", flat, deposit, 5.0) // актив ↔ актив: 0
        WalletDbCRUD.WriteRevaluation(db, flat, "2026-05-08", 2_000_000.0, 2, "") // переоценка: 0

        val accounts = loadAccounts()
        val currencies = currencies()
        val eq = currencies.getCurrencyById(rub)!!
        val from = DateUtils.StrToDate("2026-05-01 00:00")
        val to = DateUtils.StrToDate("2026-06-01 00:00")

        val total = WalletDbLoader.GenerateReportGetBalanceToDate(db, to, eq, null).totalAmountEq!! -
            WalletDbLoader.GenerateReportGetBalanceToDate(db, from, eq, null).totalAmountEq!!
        val income = WalletDbLoader.GenerateIncomeReportByAccount(db, from, to, accounts, currencies, eq, null)
            .total.totalAmountEq!!
        val expense = WalletDbLoader.GenerateExpanseReportByAccount(db, from, to, accounts, currencies, eq, null)
            .total.totalAmountEq!!
        val flow = WalletDbLoader.GenerateCapitalFlowForPeriod(db, from, to, eq)

        assertEquals((-400_000 - 30 + 1_000).toDouble(), flow.totalAmountEq!!, 0.001)
        assertFalse(flow.isIncomplete)
        // Без валютных расхождений остатка нет: Итог = Доход − Расход + движение.
        assertEquals(total, income - expense + flow.totalAmountEq!!, 0.001)
    }

    @Test
    fun capitalFlow_withoutRate_isIncomplete_andSkipsOperation() {
        val card = wallet("Карта", rub)
        val house = wallet("Дом", eur)
        WalletDbCRUD.SetAccountAsset(db, house, 1, true)
        val salary = category("Зарплата", 0)
        op("2026-05-02 10:00", salary, house, 100.0) // EUR → RUB курса нет
        val flow = WalletDbLoader.GenerateCapitalFlowForPeriod(
            db,
            DateUtils.StrToDate("2026-05-01 00:00"), DateUtils.StrToDate("2026-06-01 00:00"),
            currencies().getCurrencyById(rub)!!
        )

        assertTrue(flow.isIncomplete)
        assertEquals(0.0, flow.totalAmountEq!!, 0.001)
    }

    // ── 6.1 перенос категории в актив ───────────────────────────────────────

    @Test
    fun move_sameCurrency_expenseAndRefund_becomeTransfers_categoryArchived() {
        val card = wallet("Карта", rub)
        val flat = wallet("Квартира Ленина", rub)
        val category = category("Квартира", 0)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        val pay = op("2026-01-10 10:00", card, category, 1000.0)
        val refund = op("2026-01-11 10:00", category, card, 200.0)
        recurring(card, category)

        val r = WalletDbCRUD.MoveCategoryToAsset(db, category, flat)

        assertEquals(2, r.moved)
        assertEquals(0, r.skippedNoRate)
        assertTrue(r.archived)
        assertEquals(flat, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + pay))
        assertEquals(flat, intQuery("SELECT acc_fr_id FROM operation WHERE _id = " + refund))
        assertEquals(1000.0, doubleQuery("SELECT amount FROM operation WHERE _id = " + pay), 0.001)
        assertEquals(1, intQuery("SELECT is_archived FROM account WHERE _id = " + category))
        assertEquals(flat, intQuery("SELECT acc_to_id FROM recurring_operation"))
    }

    @Test
    fun move_otherCurrency_twoLinkedLegsViaTransfer_atRateOnDate() {
        val dollars = wallet("Доллары", usd)
        val flat = wallet("Квартира Ленина", rub)
        val category = category("Квартира", 0)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        val pay = op("2025-03-10 10:00", dollars, category, 1000.0)

        val r = WalletDbCRUD.MoveCategoryToAsset(db, category, flat)

        assertEquals(1, r.moved)
        val transfer = intQuery("SELECT _id FROM account WHERE system_kind = 1")
        assertEquals(transfer, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + pay))
        assertEquals(1000.0, doubleQuery("SELECT amount FROM operation WHERE _id = " + pay), 0.001)
        val leg = intQuery("SELECT link_id FROM operation_link WHERE main_id = " + pay + " AND type = 0").toLong()
        assertEquals(transfer, intQuery("SELECT acc_fr_id FROM operation WHERE _id = " + leg))
        assertEquals(flat, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + leg))
        assertEquals(90_000.0, doubleQuery("SELECT amount FROM operation WHERE _id = " + leg), 0.001)
    }

    @Test
    fun move_noRate_skipsThatOne_categoryStaysActive() {
        val card = wallet("Карта", rub)
        val euros = wallet("Евро", eur)
        val flat = wallet("Квартира Ленина", rub)
        val category = category("Квартира", 0)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        for (i in 1..4) op("2026-01-0" + i + " 10:00", card, category, 100.0)
        val noRate = op("2026-01-05 10:00", euros, category, 50.0)

        val r = WalletDbCRUD.MoveCategoryToAsset(db, category, flat)

        assertEquals(4, r.moved)
        assertEquals(1, r.skippedNoRate)
        assertFalse(r.archived)
        assertEquals(category, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + noRate))
        assertEquals(0, intQuery("SELECT is_archived FROM account WHERE _id = " + category))
    }

    @Test
    fun move_failureMidway_rollsBackEverything() {
        val dollars = wallet("Доллары", usd)
        val card = wallet("Карта", rub)
        val flat = wallet("Квартира Ленина", rub)
        val category = category("Квартира", 0)
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        val same = op("2025-03-09 10:00", card, category, 500.0)
        val cross = op("2025-03-10 10:00", dollars, category, 1000.0)
        db.execSQL(
            "CREATE TRIGGER fail_leg BEFORE INSERT ON operation WHEN NEW.amount = 90000"
                + " BEGIN SELECT RAISE(ABORT, 'boom'); END"
        )

        try {
            WalletDbCRUD.MoveCategoryToAsset(db, category, flat)
            fail("ожидалась ошибка вставки ноги")
        } catch (expected: IllegalStateException) {
            // ok
        }
        assertEquals(category, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + same))
        assertEquals(category, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + cross))
        assertEquals(0, intQuery("SELECT count(*) FROM operation_link"))
        assertEquals(0, intQuery("SELECT is_archived FROM account WHERE _id = " + category))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun currency(name: String, iso: String, symbol: String): Int {
        return insert("currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol).toInt()
    }

    private fun wallet(name: String, currencyId: Int): Int {
        val id = WalletDbCRUD.InsertOperatingAccount(db, name, "", iconId.toInt(), currencyId)
        return id.toInt()
    }

    private fun category(name: String, systemKind: Int): Int {
        return insert(
            "account", "name", name, "comment", "", "icon_id", iconId,
            "system_kind", systemKind, "kind", 0, "uuid", java.util.UUID.randomUUID().toString()
        ).toInt()
    }

    private fun op(date: String, from: Int, to: Int, amount: Double): Long {
        return WalletDbCRUD.InsertOperation(db, date, from, to, amount, "")
    }

    private fun recurring(from: Int, to: Int) {
        insert(
            "recurring_operation", "acc_fr_id", from, "acc_to_id", to, "amount", 5,
            "frequency", 1, "start_date", "2026-01-01"
        )
    }

    private fun loadAccounts(): AccountList {
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val accounts = AccountList()
        WalletDbLoader.LoadAccounts(db, accounts, currencies(), icons, null)
        return accounts
    }

    private fun currencies(): CurrencyList {
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val list = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, list, icons)
        return list
    }

    private fun exists(type: String, name: String): Boolean {
        val c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name))
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    private fun intQuery(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun doubleQuery(sql: String): Double {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getDouble(0)
        } finally {
            c.close()
        }
    }

    private fun stringQuery(sql: String): String {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            val value = kv[i + 1]
            when (value) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is Double -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
