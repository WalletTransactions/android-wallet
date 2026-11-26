package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Accounts.AccountList
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList
import com.example.wallettransactions.DataItems.IconList
import com.example.wallettransactions.DateUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.TreeMap

/**
 * openspec debts, раздел 1: таблицы {@code counterparty}/{@code account_debt}/
 * {@code counterparty_contact} и шаг v91, признак долга в модели, CRUD контрагентов и
 * займов, колонки {@code acc_from_is_debt}/{@code acc_to_is_debt} во вьюхах.
 */
@RunWith(RobolectricTestRunner::class)
class DebtsDbTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0
    private var rub = 0
    private var usd = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        db.execSQL("DELETE FROM operation_link")
        db.execSQL("DELETE FROM operation")
        db.execSQL("DELETE FROM recurring_operation")
        db.execSQL("DELETE FROM account_asset")
        db.execSQL("DELETE FROM account_debt")
        db.execSQL("DELETE FROM counterparty")
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        db.execSQL("DELETE FROM currency_conversion")
        db.execSQL("DELETE FROM currency_pair_rate")
        db.execSQL("DELETE FROM currency_pair")
        db.execSQL("DELETE FROM currency")
        db.execSQL("DELETE FROM icon")
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        insert("icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        rub = currency("Рубль", "RUB", "₽")
        usd = currency("Доллар", "USD", "$")
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 1.1 схема и шаг v91 ─────────────────────────────────────────────────

    @Test
    fun freshInstall_hasDebtTables_withoutRereadFlag() {
        assertTrue(WalletDbHelper.tableExists(db, "counterparty"))
        assertTrue(WalletDbHelper.tableExists(db, "account_debt"))
        assertTrue(WalletDbHelper.tableExists(db, "counterparty_contact"))
        assertTrue(exists("trigger", "trg_counterparty_dirty"))
        assertTrue(exists("trigger", "trg_account_debt_dirty"))
        assertFalse("контакт локальный — без dirty", exists("trigger", "trg_counterparty_contact_dirty"))
        assertTrue(exists("index", "ix_counterparty_uuid"))
        assertTrue(exists("index", "ix_account_debt_uuid"))
        assertTrue(exists("index", "uq_counterparty_name"))
        assertFalse(AppSettings.isLedgerRereadPending(context))
    }

    @Test
    fun upgradeFrom90_givesSameSchemaAsFreshInstall_andRaisesRereadFlag() {
        val fresh = debtSchema()

        db.execSQL("DROP TABLE account_debt")
        db.execSQL("DROP TABLE counterparty")
        db.execSQL("DROP TABLE counterparty_contact")
        db.execSQL("DROP VIEW v_operation_eq")
        db.execSQL("DROP VIEW v_operation")
        db.execSQL("CREATE VIEW v_operation AS SELECT 1 x")

        helper.onUpgrade(db, 90, 91)

        assertEquals(fresh, debtSchema())
        assertTrue(AppSettings.isLedgerRereadPending(context))
        val c = db.rawQuery("SELECT acc_from_is_debt, acc_to_is_debt FROM v_operation_eq LIMIT 0", null)
        c.close()
    }

    @Test
    fun counterpartyNameIndex_ignoresCaseAndYo() {
        insert("counterparty", "name", "Вася", "uuid", "cp-1")
        try {
            insert("counterparty", "name", "вася", "uuid", "cp-2")
            fail("вася = Вася")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
        insert("counterparty", "name", "Алёна", "uuid", "cp-3")
        try {
            insert("counterparty", "name", "Алена", "uuid", "cp-4")
            fail("Алена = Алёна")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
        // Мягко удалённый не держит имя.
        db.execSQL("UPDATE counterparty SET deleted_at = 1 WHERE uuid = 'cp-1'")
        insert("counterparty", "name", "вася", "uuid", "cp-5")
    }

    @Test
    fun dirtyTrigger_raisesOnUpdate() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        db.execSQL("UPDATE counterparty SET dirty = 0")
        WalletDbCRUD.UpdateCounterpartyComment(db, cp, "сосед")
        assertEquals(1, intQuery("SELECT dirty FROM counterparty WHERE _id = " + cp))
    }

    // ── 1.2 модель ──────────────────────────────────────────────────────────

    @Test
    fun loadAccounts_readsLiveDebtOnly() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val cpUuid = WalletDbCRUD.counterpartyUuid(db, cp)
        val loan = wallet("Заём", usd)
        val closed = wallet("Старый заём", usd)
        val plain = wallet("Карта", rub)
        assertTrue(WalletDbCRUD.SetAccountDebt(db, loan, cpUuid, "на ремонт", "2026-12-31"))
        assertTrue(WalletDbCRUD.SetAccountDebt(db, closed, cpUuid, "", null))
        WalletDbCRUD.ClearAccountDebt(db, closed)

        val accounts = loadAccounts()
        val debt = (accounts.getAccountById(loan) as AccountOperatingItem).debt
        assertNotNull(debt)
        assertEquals(cpUuid, debt!!.counterpartyUuid)
        assertEquals("на ремонт", debt.comment)
        assertEquals("2026-12-31", debt.dueDate)
        assertNull("мягко удалённая строка — не долг", (accounts.getAccountById(closed) as AccountOperatingItem).debt)
        assertFalse((accounts.getAccountById(plain) as AccountOperatingItem).isDebt)
    }

    @Test
    fun loadCounterparties_includesWithoutDebts_skipsDeleted() {
        WalletDbCRUD.CreateCounterparty(db, "Петя", "")
        val vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "друг")
        val gone = WalletDbCRUD.CreateCounterparty(db, "Удалённый", "")
        WalletDbCRUD.ArchiveCounterparty(db, vasya, true)
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.deleted, WalletDbCRUD.DeleteCounterparty(db, gone))

        val list = WalletDbLoader.LoadCounterparties(db)
        assertEquals(2, list.size)
        assertEquals("Вася", list[0].name)
        assertTrue(list[0].isArchived)
        assertEquals("друг", list[0].comment)
        assertEquals("Петя", list[1].name)
    }

    // ── 1.3 CRUD ────────────────────────────────────────────────────────────

    @Test
    fun createCounterparty_duplicateName_returnsMinusOne() {
        val vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        assertTrue(vasya > 0)
        assertEquals(-1, WalletDbCRUD.CreateCounterparty(db, "вася", ""))
        assertEquals(vasya, WalletDbCRUD.GetCounterpartyIdByName(db, "вася"))

        val alyona = WalletDbCRUD.CreateCounterparty(db, "Алёна", "")
        assertEquals(-1, WalletDbCRUD.CreateCounterparty(db, " Алена ", ""))
        assertEquals(alyona, WalletDbCRUD.GetCounterpartyIdByName(db, " Алена "))
        assertEquals(-1, WalletDbCRUD.CreateCounterparty(db, "   ", ""))
        assertEquals(2, intQuery("SELECT count(*) FROM counterparty"))
    }

    @Test
    fun createCounterparty_trimsName() {
        val id = WalletDbCRUD.CreateCounterparty(db, "  Петя  ", "")
        assertEquals("Петя", stringQuery("SELECT name FROM counterparty WHERE _id = " + id))
    }

    @Test
    fun renameCounterparty_refusesTakenName_allowsCaseChange() {
        val vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val petya = WalletDbCRUD.CreateCounterparty(db, "Петя", "")
        assertFalse(WalletDbCRUD.RenameCounterparty(db, petya, "ВАСЯ"))
        assertTrue(WalletDbCRUD.RenameCounterparty(db, vasya, "ВАСЯ"))
        assertEquals("ВАСЯ", stringQuery("SELECT name FROM counterparty WHERE _id = " + vasya))
    }

    @Test
    fun deleteCounterparty_withLiveDebt_refused() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null)
        assertTrue(loan > 0)
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.hasDebts, WalletDbCRUD.DeleteCounterparty(db, cp))
        assertEquals(0, intQuery("SELECT count(*) FROM counterparty WHERE deleted_at IS NOT NULL"))

        // Займ снят — удалить можно, привязка к контакту уходит.
        WalletDbCRUD.ClearAccountDebt(db, loan)
        insert("counterparty_contact", "counterparty_uuid", WalletDbCRUD.counterpartyUuid(db, cp)!!, "lookup_key", "k")
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.deleted, WalletDbCRUD.DeleteCounterparty(db, cp))
        assertEquals(0, intQuery("SELECT count(*) FROM counterparty_contact"))
    }

    @Test
    fun createDebtWallet_newLoan_namedByCounterpartyAndIso() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "на ремонт", "2026-12-31")

        assertEquals("Вася · USD", stringQuery("SELECT name FROM account WHERE _id = " + loan))
        assertEquals(usd, intQuery("SELECT currency_id FROM account_operating WHERE _id = " + loan))
        assertEquals(
            WalletDbCRUD.counterpartyUuid(db, cp),
            stringQuery("SELECT counterparty_uuid FROM account_debt WHERE _id = " + loan)
        )
        assertEquals(
            "uuid займа = uuid счёта",
            stringQuery("SELECT uuid FROM account WHERE _id = " + loan),
            stringQuery("SELECT uuid FROM account_debt WHERE _id = " + loan)
        )
        assertEquals(1, intQuery("SELECT dirty FROM account_debt WHERE _id = " + loan))
    }

    @Test
    fun createDebtWallet_secondLoanSameCurrency_getsSuffix() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val first = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null)
        val second = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "второй", "2027-01-15")
        val third = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null)

        assertTrue(first > 0 && second > 0 && third > 0)
        assertEquals("Вася · USD · 2", stringQuery("SELECT name FROM account WHERE _id = " + second))
        assertEquals("Вася · USD · 3", stringQuery("SELECT name FROM account WHERE _id = " + third))
        assertEquals("2027-01-15", stringQuery("SELECT due_date FROM account_debt WHERE _id = " + second))
        assertEquals("", stringQuery("SELECT comment FROM account_debt WHERE _id = " + first))
    }

    @Test
    fun setAccountDebt_refusesCategoryAndAsset_andViceVersa() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val cpUuid = WalletDbCRUD.counterpartyUuid(db, cp)
        val cat = insert(
            "account", "name", "Еда", "comment", "", "icon_id", iconId,
            "system_kind", 0, "kind", 0, "uuid", "cat-1"
        ).toInt()
        assertFalse("категория", WalletDbCRUD.SetAccountDebt(db, cat, cpUuid, "", null))

        val flat = wallet("Квартира", rub)
        assertTrue(WalletDbCRUD.SetAccountAsset(db, flat, 1, true))
        assertFalse("актив", WalletDbCRUD.SetAccountDebt(db, flat, cpUuid, "", null))

        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null)
        assertFalse("долговой не делается активом", WalletDbCRUD.SetAccountAsset(db, loan, 1, true))

        val plain = wallet("Карта", rub)
        assertFalse("неизвестный контрагент", WalletDbCRUD.SetAccountDebt(db, plain, "nope", "", null))
        assertEquals(1, intQuery("SELECT count(*) FROM account_debt"))
    }

    @Test
    fun setAccountDebt_again_revivesSameRow() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val cpUuid = WalletDbCRUD.counterpartyUuid(db, cp)
        val w = wallet("Заём", usd)
        WalletDbCRUD.SetAccountDebt(db, w, cpUuid, "", null)
        val uuid = stringQuery("SELECT uuid FROM account_debt WHERE _id = " + w)
        WalletDbCRUD.ClearAccountDebt(db, w)
        assertTrue(WalletDbCRUD.SetAccountDebt(db, w, cpUuid, "снова", null))
        assertEquals(1, intQuery("SELECT count(*) FROM account_debt"))
        assertEquals(uuid, stringQuery("SELECT uuid FROM account_debt WHERE _id = " + w))
        assertEquals(0, intQuery("SELECT count(*) FROM account_debt WHERE deleted_at IS NOT NULL"))
    }

    @Test
    fun deleteAccount_softDeletesDebtRow() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null)
        assertEquals(WalletDbCRUD.DeleteAccountResult.deleted, WalletDbCRUD.DeleteAccount(db, loan))
        assertEquals(1, intQuery("SELECT count(*) FROM account_debt WHERE deleted_at IS NOT NULL"))
        assertEquals(0, WalletDbCRUD.CountLiveDebts(db, WalletDbCRUD.counterpartyUuid(db, cp)!!))
    }

    // ── 1.4 признак долга во вьюхах ─────────────────────────────────────────

    @Test
    fun views_markDebtSide_incomeExpenseUnchanged() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val card = wallet("Карта", usd)
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null)
        val food = insert(
            "account", "name", "Еда", "comment", "", "icon_id", iconId,
            "system_kind", 0, "kind", 0, "uuid", "cat-1"
        ).toInt()
        val give = WalletDbCRUD.InsertOperation(db, "2026-01-10", card, loan, 100.0, "")
        val back = WalletDbCRUD.InsertOperation(db, "2026-01-20", loan, card, 40.0, "")
        val spend = WalletDbCRUD.InsertOperation(db, "2026-01-21", card, food, 5.0, "")

        assertEquals(0, intQuery("SELECT acc_from_is_debt FROM v_operation WHERE _id = " + give))
        assertEquals(1, intQuery("SELECT acc_to_is_debt FROM v_operation WHERE _id = " + give))
        assertEquals(1, intQuery("SELECT acc_from_is_debt FROM v_operation WHERE _id = " + back))
        assertEquals(0, intQuery("SELECT acc_to_is_debt FROM v_operation WHERE _id = " + back))
        assertEquals(0, intQuery("SELECT acc_from_is_debt + acc_to_is_debt FROM v_operation WHERE _id = " + spend))
        assertEquals(
            1, intQuery(
                "SELECT acc_to_is_debt FROM v_operation_eq WHERE _id = " + give
                    + " AND to_currency_id = " + usd
            )
        )

        // Доход/расход периода: перевод на долговой — не расход, трата — расход.
        assertEquals(
            5.0, doubleQuery(
                "SELECT coalesce(sum(amount), 0) FROM v_operation"
                    + " WHERE acc_from_is_operating = 1 AND acc_to_is_operating = 0"
            ), 1e-9
        )
        assertEquals(
            0.0, doubleQuery(
                "SELECT coalesce(sum(amount), 0) FROM v_operation"
                    + " WHERE acc_from_is_operating = 0 AND acc_to_is_operating = 1"
            ), 1e-9
        )
    }

    // ── 3.1 итоги долгов ────────────────────────────────────────────────────

    @Test
    fun giveLoan_liquidDrops_owedToMeGrows_totalWithDebtsUnchanged() {
        val card = wallet("Карта", rub)
        val salary = category("Зарплата")
        op("2026-01-01 10:00", salary, card, 1000.0)
        val before = DateUtils.StrToDate("2026-01-05 00:00")
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null)
        op("2026-01-10 10:00", card, loan, 100.0)
        val after = DateUtils.StrToDate("2026-01-20 00:00")
        val eq = currencies().getCurrencyById(rub)!!

        assertEquals(1000.0, WalletDbLoader.GenerateReportGetBalanceToDate(db, before, eq, null).totalAmountEq!!, 1e-9)
        assertEquals(
            "ликвидный итог без займа", 900.0,
            WalletDbLoader.GenerateReportGetBalanceToDate(db, after, eq, null).totalAmountEq!!, 1e-9
        )
        val d0 = WalletDbLoader.GenerateDebtsToDate(db, before, eq)
        val d1 = WalletDbLoader.GenerateDebtsToDate(db, after, eq)
        assertTrue(d1.hasDebts)
        assertEquals(0.0, d0.owedToMe.totalAmountEq!!, 1e-9)
        assertEquals(100.0, d1.owedToMe.totalAmountEq!!, 1e-9)
        assertEquals(0.0, d1.iOwe.totalAmountEq!!, 1e-9)
        assertEquals(d0.totalWithDebts.totalAmountEq!!, d1.totalWithDebts.totalAmountEq!!, 1e-9)
        assertEquals(1000.0, d1.totalWithDebts.totalAmountEq!!, 1e-9)
        // «Всего с капиталом» займа тоже не содержит — у него своя строка.
        assertEquals(900.0, WalletDbLoader.GenerateCapitalToDate(db, after, eq).netWorth.totalAmountEq!!, 1e-9)
    }

    @Test
    fun iOwe_isPositiveSumOfNegativeLoans() {
        val card = wallet("Карта", rub)
        val cpA = WalletDbCRUD.CreateCounterparty(db, "Банк", "")
        val cpB = WalletDbCRUD.CreateCounterparty(db, "Петя", "")
        val took = WalletDbCRUD.CreateDebtWallet(db, cpA, rub, "", null)
        val gave = WalletDbCRUD.CreateDebtWallet(db, cpB, rub, "", null)
        op("2026-01-10 10:00", took, card, 300.0) // взял в долг
        op("2026-01-11 10:00", card, gave, 50.0) // дал в долг
        val eq = currencies().getCurrencyById(rub)!!

        val d = WalletDbLoader.GenerateDebtsToDate(db, DateUtils.StrToDate("2026-02-01 00:00"), eq)
        assertEquals(50.0, d.owedToMe.totalAmountEq!!, 1e-9)
        assertEquals(300.0, d.iOwe.totalAmountEq!!, 1e-9)
        assertEquals("ликвидные 250 + 50 − 300", 0.0, d.totalWithDebts.totalAmountEq!!, 1e-9)
    }

    @Test
    fun noRate_loanLeftOut_totalsIncomplete() {
        val eur = currency("Евро", "EUR", "€")
        val card = wallet("Карта", eur)
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, eur, "", null)
        op("2026-01-10 10:00", card, loan, 100.0) // EUR → RUB курса нет
        val eq = currencies().getCurrencyById(rub)!!

        val d = WalletDbLoader.GenerateDebtsToDate(db, DateUtils.StrToDate("2026-02-01 00:00"), eq)
        assertTrue(d.owedToMe.isIncomplete)
        assertEquals(0.0, d.owedToMe.totalAmountEq!!, 1e-9)
        assertFalse(d.iOwe.isIncomplete)
        assertTrue(d.totalWithDebts.isIncomplete)
    }

    @Test
    fun noDebts_hasDebtsFalse() {
        val card = wallet("Карта", rub)
        op("2026-01-10 10:00", category("Зарплата"), card, 100.0)
        val d = WalletDbLoader.GenerateDebtsToDate(
            db,
            DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub)!!
        )
        assertFalse(d.hasDebts)
        assertFalse(WalletDbLoader.HasDebtWallets(db))
    }

    @Test
    fun balanceRows_markDebt() {
        val card = wallet("Карта", rub)
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null)
        op("2026-01-10 10:00", card, loan, 100.0)
        val d = DateUtils.StrToDate("2026-02-01 00:00")
        for (row in WalletDbLoader.LoadBalancesPerWallet(
            db, d, d,
            currencies().getCurrencyById(rub)!!
        )) {
            if (row.accountId == loan) {
                assertTrue(row.isDebt())
                assertFalse(row.isLiquid())
                assertEquals(WalletDbCRUD.counterpartyUuid(db, cp), row.debtCounterpartyUuid)
            } else {
                assertTrue(row.isLiquid())
            }
        }
    }

    // ── 3.2 сверка периода ──────────────────────────────────────────────────

    @Test
    fun reconciliation_incomeMinusExpensePlusDebtFlow_equalsLiquidChange() {
        val card = wallet("Карта", rub)
        val salary = category("Зарплата")
        val food = category("Продукты")
        val writeOff = category("Списание долгов")
        val cpA = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val cpB = WalletDbCRUD.CreateCounterparty(db, "Банк", "")
        val gave = WalletDbCRUD.CreateDebtWallet(db, cpA, rub, "", null)
        val took = WalletDbCRUD.CreateDebtWallet(db, cpB, rub, "", null)
        op("2026-04-01 10:00", salary, card, 10_000.0) // до периода
        op("2026-05-02 10:00", salary, card, 500.0) // доход
        op("2026-05-03 10:00", card, food, 200.0) // расход
        op("2026-05-04 10:00", card, gave, 100.0) // дал в долг: −100
        op("2026-05-05 10:00", gave, card, 30.0) // вернули часть: +30
        op("2026-05-06 10:00", took, card, 1_000.0) // взял в долг: +1000
        op("2026-05-07 10:00", gave, writeOff, 70.0) // простил остаток: расход 70, «Итог» не изменился
        op("2026-05-08 10:00", writeOff, took, 1_000.0) // меня простили: доход 1000

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
        val flow = WalletDbLoader.GenerateDebtFlowForPeriod(db, from, to, eq)

        assertEquals((500 - 200 - 100 + 30 + 1_000).toDouble(), total, 0.001)
        assertEquals(1_500.0, income, 0.001)
        assertEquals(270.0, expense, 0.001)
        assertEquals((-100 + 30 + 1_000 + 70 - 1_000).toDouble(), flow.totalAmountEq!!, 0.001)
        assertFalse(flow.isIncomplete)
        assertEquals(total, income - expense + flow.totalAmountEq!!, 0.001)
        // Займы не трогают движение капитала.
        assertEquals(0.0, WalletDbLoader.GenerateCapitalFlowForPeriod(db, from, to, eq).totalAmountEq!!, 0.001)
    }

    @Test
    fun reconciliation_specScenario_income500_expense200_lent100() {
        val card = wallet("Карта", rub)
        val salary = category("Зарплата")
        val food = category("Продукты")
        val loan = WalletDbCRUD.CreateDebtWallet(db, WalletDbCRUD.CreateCounterparty(db, "Вася", ""), rub, "", null)
        op("2026-05-02 10:00", salary, card, 500.0)
        op("2026-05-03 10:00", card, food, 200.0)
        op("2026-05-04 10:00", card, loan, 100.0)
        val eq = currencies().getCurrencyById(rub)!!
        val from = DateUtils.StrToDate("2026-05-01 00:00")
        val to = DateUtils.StrToDate("2026-06-01 00:00")

        val total = WalletDbLoader.GenerateReportGetBalanceToDate(db, to, eq, null).totalAmountEq!! -
            WalletDbLoader.GenerateReportGetBalanceToDate(db, from, eq, null).totalAmountEq!!
        assertEquals(200.0, total, 0.001)
        assertEquals(-100.0, WalletDbLoader.GenerateDebtFlowForPeriod(db, from, to, eq).totalAmountEq!!, 0.001)
    }

    // ── 5.4 прощение ────────────────────────────────────────────────────────

    @Test
    fun forgiveDebtToMe_expenseOnWriteOffCategory_balanceZero() {
        val card = wallet("Карта", usd)
        val loan = WalletDbCRUD.CreateDebtWallet(db, WalletDbCRUD.CreateCounterparty(db, "Вася", ""), usd, "", null)
        op("2026-05-01 10:00", card, loan, 100.0)
        val cat = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов")

        assertEquals(
            WalletDbCRUD.WriteOffResult.written,
            WalletDbCRUD.ForgiveDebt(db, loan, cat, "2026-05-10", 100.0, 2, "Долг прощён")
        )

        assertEquals(0.0, WalletDbCRUD.RoundedWalletBalance(db, loan, 2), 1e-9)
        assertEquals(loan, intQuery("SELECT acc_fr_id FROM operation WHERE acc_to_id = " + cat))
        assertEquals("2026-05-10 23:59", stringQuery("SELECT date FROM operation WHERE acc_to_id = " + cat))
        assertEquals(
            "обычная категория, не системная", 0,
            intQuery("SELECT system_kind FROM account WHERE _id = " + cat)
        )
        assertEquals("заводится один раз", cat, WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов"))
        // Расход периода: прощение — настоящий расход.
        assertEquals(
            100.0, doubleQuery(
                "SELECT coalesce(sum(amount), 0) FROM v_operation"
                    + " WHERE acc_from_is_operating = 1 AND acc_to_is_operating = 0"
            ), 1e-9
        )
    }

    @Test
    fun forgivenByThem_incomeFromWriteOffCategory() {
        val card = wallet("Карта", usd)
        val loan = WalletDbCRUD.CreateDebtWallet(db, WalletDbCRUD.CreateCounterparty(db, "Банк", ""), usd, "", null)
        op("2026-05-01 10:00", loan, card, 100.0) // взял в долг
        val cat = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов")

        assertEquals(
            WalletDbCRUD.WriteOffResult.written,
            WalletDbCRUD.ForgiveDebt(db, loan, cat, "2026-05-10", 100.0, 2, "")
        )

        assertEquals(0.0, WalletDbCRUD.RoundedWalletBalance(db, loan, 2), 1e-9)
        assertEquals(cat, intQuery("SELECT acc_fr_id FROM operation WHERE acc_to_id = " + loan))
        assertEquals(
            WalletDbCRUD.WriteOffResult.zero,
            WalletDbCRUD.ForgiveDebt(db, loan, cat, "2026-05-11", 1.0, 2, "")
        )
    }

    @Test
    fun forgiveDateTime_today_isCurrentTime_otherDay_isEndOfDay() {
        val c = java.util.Calendar.getInstance()
        c.set(2026, java.util.Calendar.NOVEMBER, 13, 18, 41, 7)
        val now = c.time
        // Сегодня — «сейчас», чтобы карточка периода (до текущего момента) видела прощение сразу.
        assertEquals("2026-11-13 18:41", WalletDbCRUD.ForgiveDateTime("2026-11-13", now))
        // Прошлый и будущий дни — конец дня, как раньше.
        assertEquals("2026-11-12 23:59", WalletDbCRUD.ForgiveDateTime("2026-11-12", now))
        assertEquals("2026-11-14 23:59", WalletDbCRUD.ForgiveDateTime("2026-11-14", now))
    }

    // ── 6.1 сброс ───────────────────────────────────────────────────────────

    @Test
    fun resetOperationsAndAccounts_dropsLoanFlags_keepsCounterparties() {
        val card = wallet("Карта", rub)
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null)
        op("2026-01-10 10:00", card, loan, 100.0)

        WalletFillData.ResetOperationsAndAccounts(db, context, false)

        assertEquals(0, intQuery("SELECT count(*) FROM account_debt WHERE deleted_at IS NULL"))
        assertEquals(1, intQuery("SELECT dirty FROM account_debt WHERE _id = " + loan))
        assertEquals("контрагент остаётся", 1, intQuery("SELECT count(*) FROM counterparty WHERE deleted_at IS NULL"))
        assertFalse(WalletDbLoader.HasDebtWallets(db))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun category(name: String): Int {
        return insert(
            "account", "name", name, "comment", "", "icon_id", iconId,
            "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString()
        ).toInt()
    }

    private fun op(date: String, from: Int, to: Int, amount: Double): Long {
        return WalletDbCRUD.InsertOperation(db, date, from, to, amount, "")
    }

    private fun currencies(): CurrencyList {
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val list = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, list, icons)
        return list
    }

    /** sql объектов долгов из sqlite_master — для сравнения onCreate и апгрейда. */
    private fun debtSchema(): Map<String, String> {
        val out: MutableMap<String, String> = TreeMap()
        val c = db.rawQuery(
            "SELECT type || ':' || name, sql FROM sqlite_master"
                + " WHERE tbl_name IN ('counterparty', 'account_debt', 'counterparty_contact')"
                + " AND sql IS NOT NULL", null
        )
        try {
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1).replace(Regex("\\s+"), " ")
        } finally {
            c.close()
        }
        assertFalse(out.isEmpty())
        return out
    }

    private fun currency(name: String, iso: String, symbol: String): Int {
        return insert("currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol).toInt()
    }

    private fun wallet(name: String, currencyId: Int): Int {
        return WalletDbCRUD.InsertOperatingAccount(db, name, "", iconId.toInt(), currencyId).toInt()
    }

    private fun loadAccounts(): AccountList {
        val icons = IconList()
        WalletDbLoader.LoadIcons(db, icons)
        val currencies = CurrencyList()
        WalletDbLoader.LoadCurrencyList(db, currencies, icons)
        val accounts = AccountList()
        WalletDbLoader.LoadAccounts(db, accounts, currencies, icons, null)
        return accounts
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

    // ── карточка контрагента (openspec counterparty-card) ─────────────────────

    @Test
    fun counterpartyIcon_savedAsUuid_andClearedToNull() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        assertNull(loadCounterparty(cp).iconUuid)

        assertTrue(WalletDbCRUD.SetCounterpartyIcon(db, cp, iconId.toInt()))
        val uuid = loadCounterparty(cp).iconUuid
        assertNotNull("форма отдаёт _id, в базе — uuid иконки", uuid)
        assertEquals(iconId.toInt(), WalletDbCRUD.IconIdByUuid(db, uuid))

        assertTrue(WalletDbCRUD.SetCounterpartyIcon(db, cp, 0))
        assertNull("«без иконки» — NULL", loadCounterparty(cp).iconUuid)
        assertEquals(-1, WalletDbCRUD.IconIdByUuid(db, null))
    }

    @Test
    fun counterpartyIcon_unknownIconOrCounterparty() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        assertTrue(WalletDbCRUD.SetCounterpartyIcon(db, cp, 99999))
        assertNull("неизвестная иконка — как «без иконки»", loadCounterparty(cp).iconUuid)
        assertFalse(WalletDbCRUD.SetCounterpartyIcon(db, 99999, iconId.toInt()))
    }

    @Test
    fun counterpartyContact_rebindAndUnbind_keepName() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val uuid = WalletDbCRUD.CounterpartyUuid(db, cp)!!
        assertNull(WalletDbCRUD.GetCounterpartyContact(db, uuid))

        WalletDbCRUD.SetCounterpartyContact(db, uuid, "key-1")
        assertEquals("key-1", WalletDbCRUD.GetCounterpartyContact(db, uuid))
        WalletDbCRUD.SetCounterpartyContact(db, uuid, "key-2")
        assertEquals("перепривязка заменяет прежний контакт", "key-2", WalletDbCRUD.GetCounterpartyContact(db, uuid))

        WalletDbCRUD.ClearCounterpartyContact(db, uuid)
        assertNull(WalletDbCRUD.GetCounterpartyContact(db, uuid))
        assertEquals("имя контрагента не меняется", "Вася", loadCounterparty(cp).name)
    }

    private fun loadCounterparty(id: Int): CounterpartyItem {
        for (c in WalletDbLoader.LoadCounterparties(db)) if (c.id == id) return c
        throw AssertionError("нет контрагента " + id)
    }
}
