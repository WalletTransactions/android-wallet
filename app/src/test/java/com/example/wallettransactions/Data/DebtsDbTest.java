package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;
import com.example.wallettransactions.DataItems.IconList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * openspec debts, раздел 1: таблицы {@code counterparty}/{@code account_debt}/
 * {@code counterparty_contact} и шаг v91, признак долга в модели, CRUD контрагентов и
 * займов, колонки {@code acc_from_is_debt}/{@code acc_to_is_debt} во вьюхах.
 */
@RunWith(RobolectricTestRunner.class)
public class DebtsDbTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;
    private int rub;
    private int usd;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        AppSettings.setLedgerRereadPending(context, false);
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        db.execSQL("DELETE FROM operation_link");
        db.execSQL("DELETE FROM operation");
        db.execSQL("DELETE FROM recurring_operation");
        db.execSQL("DELETE FROM account_asset");
        db.execSQL("DELETE FROM account_debt");
        db.execSQL("DELETE FROM counterparty");
        db.execSQL("DELETE FROM account_operating");
        db.execSQL("DELETE FROM account");
        db.execSQL("DELETE FROM currency_conversion");
        db.execSQL("DELETE FROM currency_pair_rate");
        db.execSQL("DELETE FROM currency_pair");
        db.execSQL("DELETE FROM currency");
        db.execSQL("DELETE FROM icon");
        iconId = insert("icon", "name", "test_icon", "image", new byte[]{0}, "icon_type", 1);
        insert("icon", "name", "unknown", "image", new byte[]{0}, "icon_type", 1);
        rub = currency("Рубль", "RUB", "₽");
        usd = currency("Доллар", "USD", "$");
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 1.1 схема и шаг v91 ─────────────────────────────────────────────────

    @Test
    public void freshInstall_hasDebtTables_withoutRereadFlag() {
        assertTrue(WalletDbHelper.tableExists(db, "counterparty"));
        assertTrue(WalletDbHelper.tableExists(db, "account_debt"));
        assertTrue(WalletDbHelper.tableExists(db, "counterparty_contact"));
        assertTrue(exists("trigger", "trg_counterparty_dirty"));
        assertTrue(exists("trigger", "trg_account_debt_dirty"));
        assertFalse("контакт локальный — без dirty", exists("trigger", "trg_counterparty_contact_dirty"));
        assertTrue(exists("index", "ix_counterparty_uuid"));
        assertTrue(exists("index", "ix_account_debt_uuid"));
        assertTrue(exists("index", "uq_counterparty_name"));
        assertFalse(AppSettings.isLedgerRereadPending(context));
    }

    @Test
    public void upgradeFrom90_givesSameSchemaAsFreshInstall_andRaisesRereadFlag() {
        Map<String, String> fresh = debtSchema();

        db.execSQL("DROP TABLE account_debt");
        db.execSQL("DROP TABLE counterparty");
        db.execSQL("DROP TABLE counterparty_contact");
        db.execSQL("DROP VIEW v_operation_eq");
        db.execSQL("DROP VIEW v_operation");
        db.execSQL("CREATE VIEW v_operation AS SELECT 1 x");

        helper.onUpgrade(db, 90, 91);

        assertEquals(fresh, debtSchema());
        assertTrue(AppSettings.isLedgerRereadPending(context));
        Cursor c = db.rawQuery("SELECT acc_from_is_debt, acc_to_is_debt FROM v_operation_eq LIMIT 0", null);
        c.close();
    }

    @Test
    public void counterpartyNameIndex_ignoresCaseAndYo() {
        insert("counterparty", "name", "Вася", "uuid", "cp-1");
        try {
            insert("counterparty", "name", "вася", "uuid", "cp-2");
            fail("вася = Вася");
        } catch (SQLiteConstraintException expected) {
            // ok
        }
        insert("counterparty", "name", "Алёна", "uuid", "cp-3");
        try {
            insert("counterparty", "name", "Алена", "uuid", "cp-4");
            fail("Алена = Алёна");
        } catch (SQLiteConstraintException expected) {
            // ok
        }
        // Мягко удалённый не держит имя.
        db.execSQL("UPDATE counterparty SET deleted_at = 1 WHERE uuid = 'cp-1'");
        insert("counterparty", "name", "вася", "uuid", "cp-5");
    }

    @Test
    public void dirtyTrigger_raisesOnUpdate() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        db.execSQL("UPDATE counterparty SET dirty = 0");
        WalletDbCRUD.UpdateCounterpartyComment(db, cp, "сосед");
        assertEquals(1, intQuery("SELECT dirty FROM counterparty WHERE _id = " + cp));
    }

    // ── 1.2 модель ──────────────────────────────────────────────────────────

    @Test
    public void loadAccounts_readsLiveDebtOnly() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        String cpUuid = WalletDbCRUD.counterpartyUuid(db, cp);
        int loan = wallet("Заём", usd);
        int closed = wallet("Старый заём", usd);
        int plain = wallet("Карта", rub);
        assertTrue(WalletDbCRUD.SetAccountDebt(db, loan, cpUuid, "на ремонт", "2026-12-31"));
        assertTrue(WalletDbCRUD.SetAccountDebt(db, closed, cpUuid, "", null));
        WalletDbCRUD.ClearAccountDebt(db, closed);

        AccountList accounts = loadAccounts();
        AccountOperatingItem.DebtInfo debt = ((AccountOperatingItem) accounts.getAccountById(loan)).getDebt();
        assertNotNull(debt);
        assertEquals(cpUuid, debt.counterpartyUuid);
        assertEquals("на ремонт", debt.comment);
        assertEquals("2026-12-31", debt.dueDate);
        assertNull("мягко удалённая строка — не долг", ((AccountOperatingItem) accounts.getAccountById(closed)).getDebt());
        assertFalse(((AccountOperatingItem) accounts.getAccountById(plain)).isDebt());
    }

    @Test
    public void loadCounterparties_includesWithoutDebts_skipsDeleted() {
        WalletDbCRUD.CreateCounterparty(db, "Петя", "");
        int vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "друг");
        int gone = WalletDbCRUD.CreateCounterparty(db, "Удалённый", "");
        WalletDbCRUD.ArchiveCounterparty(db, vasya, true);
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.deleted, WalletDbCRUD.DeleteCounterparty(db, gone));

        List<CounterpartyItem> list = WalletDbLoader.LoadCounterparties(db);
        assertEquals(2, list.size());
        assertEquals("Вася", list.get(0).name);
        assertTrue(list.get(0).isArchived);
        assertEquals("друг", list.get(0).comment);
        assertEquals("Петя", list.get(1).name);
    }

    // ── 1.3 CRUD ────────────────────────────────────────────────────────────

    @Test
    public void createCounterparty_duplicateName_returnsMinusOne() {
        int vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        assertTrue(vasya > 0);
        assertEquals(-1, WalletDbCRUD.CreateCounterparty(db, "вася", ""));
        assertEquals(vasya, WalletDbCRUD.GetCounterpartyIdByName(db, "вася"));

        int alyona = WalletDbCRUD.CreateCounterparty(db, "Алёна", "");
        assertEquals(-1, WalletDbCRUD.CreateCounterparty(db, " Алена ", ""));
        assertEquals(alyona, WalletDbCRUD.GetCounterpartyIdByName(db, " Алена "));
        assertEquals(-1, WalletDbCRUD.CreateCounterparty(db, "   ", ""));
        assertEquals(2, intQuery("SELECT count(*) FROM counterparty"));
    }

    @Test
    public void createCounterparty_trimsName() {
        int id = WalletDbCRUD.CreateCounterparty(db, "  Петя  ", "");
        assertEquals("Петя", stringQuery("SELECT name FROM counterparty WHERE _id = " + id));
    }

    @Test
    public void renameCounterparty_refusesTakenName_allowsCaseChange() {
        int vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int petya = WalletDbCRUD.CreateCounterparty(db, "Петя", "");
        assertFalse(WalletDbCRUD.RenameCounterparty(db, petya, "ВАСЯ"));
        assertTrue(WalletDbCRUD.RenameCounterparty(db, vasya, "ВАСЯ"));
        assertEquals("ВАСЯ", stringQuery("SELECT name FROM counterparty WHERE _id = " + vasya));
    }

    @Test
    public void deleteCounterparty_withLiveDebt_refused() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null);
        assertTrue(loan > 0);
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.hasDebts, WalletDbCRUD.DeleteCounterparty(db, cp));
        assertEquals(0, intQuery("SELECT count(*) FROM counterparty WHERE deleted_at IS NOT NULL"));

        // Займ снят — удалить можно, привязка к контакту уходит.
        WalletDbCRUD.ClearAccountDebt(db, loan);
        insert("counterparty_contact", "counterparty_uuid", WalletDbCRUD.counterpartyUuid(db, cp), "lookup_key", "k");
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.deleted, WalletDbCRUD.DeleteCounterparty(db, cp));
        assertEquals(0, intQuery("SELECT count(*) FROM counterparty_contact"));
    }

    @Test
    public void createDebtWallet_newLoan_namedByCounterpartyAndIso() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "на ремонт", "2026-12-31");

        assertEquals("Вася · USD", stringQuery("SELECT name FROM account WHERE _id = " + loan));
        assertEquals(usd, intQuery("SELECT currency_id FROM account_operating WHERE _id = " + loan));
        assertEquals(WalletDbCRUD.counterpartyUuid(db, cp),
                stringQuery("SELECT counterparty_uuid FROM account_debt WHERE _id = " + loan));
        assertEquals("uuid займа = uuid счёта",
                stringQuery("SELECT uuid FROM account WHERE _id = " + loan),
                stringQuery("SELECT uuid FROM account_debt WHERE _id = " + loan));
        assertEquals(1, intQuery("SELECT dirty FROM account_debt WHERE _id = " + loan));
    }

    @Test
    public void createDebtWallet_secondLoanSameCurrency_getsSuffix() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int first = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null);
        int second = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "второй", "2027-01-15");
        int third = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null);

        assertTrue(first > 0 && second > 0 && third > 0);
        assertEquals("Вася · USD · 2", stringQuery("SELECT name FROM account WHERE _id = " + second));
        assertEquals("Вася · USD · 3", stringQuery("SELECT name FROM account WHERE _id = " + third));
        assertEquals("2027-01-15", stringQuery("SELECT due_date FROM account_debt WHERE _id = " + second));
        assertEquals("", stringQuery("SELECT comment FROM account_debt WHERE _id = " + first));
    }

    @Test
    public void setAccountDebt_refusesCategoryAndAsset_andViceVersa() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        String cpUuid = WalletDbCRUD.counterpartyUuid(db, cp);
        int cat = (int) insert("account", "name", "Еда", "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", "cat-1");
        assertFalse("категория", WalletDbCRUD.SetAccountDebt(db, cat, cpUuid, "", null));

        int flat = wallet("Квартира", rub);
        assertTrue(WalletDbCRUD.SetAccountAsset(db, flat, 1, true));
        assertFalse("актив", WalletDbCRUD.SetAccountDebt(db, flat, cpUuid, "", null));

        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null);
        assertFalse("долговой не делается активом", WalletDbCRUD.SetAccountAsset(db, loan, 1, true));

        int plain = wallet("Карта", rub);
        assertFalse("неизвестный контрагент", WalletDbCRUD.SetAccountDebt(db, plain, "nope", "", null));
        assertEquals(1, intQuery("SELECT count(*) FROM account_debt"));
    }

    @Test
    public void setAccountDebt_again_revivesSameRow() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        String cpUuid = WalletDbCRUD.counterpartyUuid(db, cp);
        int w = wallet("Заём", usd);
        WalletDbCRUD.SetAccountDebt(db, w, cpUuid, "", null);
        String uuid = stringQuery("SELECT uuid FROM account_debt WHERE _id = " + w);
        WalletDbCRUD.ClearAccountDebt(db, w);
        assertTrue(WalletDbCRUD.SetAccountDebt(db, w, cpUuid, "снова", null));
        assertEquals(1, intQuery("SELECT count(*) FROM account_debt"));
        assertEquals(uuid, stringQuery("SELECT uuid FROM account_debt WHERE _id = " + w));
        assertEquals(0, intQuery("SELECT count(*) FROM account_debt WHERE deleted_at IS NOT NULL"));
    }

    @Test
    public void deleteAccount_softDeletesDebtRow() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null);
        assertEquals(WalletDbCRUD.DeleteAccountResult.deleted, WalletDbCRUD.DeleteAccount(db, loan));
        assertEquals(1, intQuery("SELECT count(*) FROM account_debt WHERE deleted_at IS NOT NULL"));
        assertEquals(0, WalletDbCRUD.CountLiveDebts(db, WalletDbCRUD.counterpartyUuid(db, cp)));
    }

    // ── 1.4 признак долга во вьюхах ─────────────────────────────────────────

    @Test
    public void views_markDebtSide_incomeExpenseUnchanged() {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int card = wallet("Карта", usd);
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, usd, "", null);
        int food = (int) insert("account", "name", "Еда", "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", "cat-1");
        long give = WalletDbCRUD.InsertOperation(db, "2026-01-10", card, loan, 100, "");
        long back = WalletDbCRUD.InsertOperation(db, "2026-01-20", loan, card, 40, "");
        long spend = WalletDbCRUD.InsertOperation(db, "2026-01-21", card, food, 5, "");

        assertEquals(0, intQuery("SELECT acc_from_is_debt FROM v_operation WHERE _id = " + give));
        assertEquals(1, intQuery("SELECT acc_to_is_debt FROM v_operation WHERE _id = " + give));
        assertEquals(1, intQuery("SELECT acc_from_is_debt FROM v_operation WHERE _id = " + back));
        assertEquals(0, intQuery("SELECT acc_to_is_debt FROM v_operation WHERE _id = " + back));
        assertEquals(0, intQuery("SELECT acc_from_is_debt + acc_to_is_debt FROM v_operation WHERE _id = " + spend));
        assertEquals(1, intQuery("SELECT acc_to_is_debt FROM v_operation_eq WHERE _id = " + give
                + " AND to_currency_id = " + usd));

        // Доход/расход периода: перевод на долговой — не расход, трата — расход.
        assertEquals(5.0, doubleQuery("SELECT coalesce(sum(amount), 0) FROM v_operation"
                + " WHERE acc_from_is_operating = 1 AND acc_to_is_operating = 0"), 1e-9);
        assertEquals(0.0, doubleQuery("SELECT coalesce(sum(amount), 0) FROM v_operation"
                + " WHERE acc_from_is_operating = 0 AND acc_to_is_operating = 1"), 1e-9);
    }

    // ── 3.1 итоги долгов ────────────────────────────────────────────────────

    @Test
    public void giveLoan_liquidDrops_owedToMeGrows_totalWithDebtsUnchanged() {
        int card = wallet("Карта", rub);
        int salary = category("Зарплата");
        op("2026-01-01 10:00", salary, card, 1000);
        Date before = DateUtils.StrToDate("2026-01-05 00:00");
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null);
        op("2026-01-10 10:00", card, loan, 100);
        Date after = DateUtils.StrToDate("2026-01-20 00:00");
        Currency eq = currencies().getCurrencyById(rub);

        assertEquals(1000, WalletDbLoader.GenerateReportGetBalanceToDate(db, before, eq, null).getTotalAmountEq(), 1e-9);
        assertEquals("ликвидный итог без займа", 900,
                WalletDbLoader.GenerateReportGetBalanceToDate(db, after, eq, null).getTotalAmountEq(), 1e-9);
        WalletDbLoader.DebtsResult d0 = WalletDbLoader.GenerateDebtsToDate(db, before, eq);
        WalletDbLoader.DebtsResult d1 = WalletDbLoader.GenerateDebtsToDate(db, after, eq);
        assertTrue(d1.hasDebts);
        assertEquals(0, d0.owedToMe.getTotalAmountEq(), 1e-9);
        assertEquals(100, d1.owedToMe.getTotalAmountEq(), 1e-9);
        assertEquals(0, d1.iOwe.getTotalAmountEq(), 1e-9);
        assertEquals(d0.totalWithDebts.getTotalAmountEq(), d1.totalWithDebts.getTotalAmountEq(), 1e-9);
        assertEquals(1000, d1.totalWithDebts.getTotalAmountEq(), 1e-9);
        // «Всего с капиталом» займа тоже не содержит — у него своя строка.
        assertEquals(900, WalletDbLoader.GenerateCapitalToDate(db, after, eq).netWorth.getTotalAmountEq(), 1e-9);
    }

    @Test
    public void iOwe_isPositiveSumOfNegativeLoans() {
        int card = wallet("Карта", rub);
        int cpA = WalletDbCRUD.CreateCounterparty(db, "Банк", "");
        int cpB = WalletDbCRUD.CreateCounterparty(db, "Петя", "");
        int took = WalletDbCRUD.CreateDebtWallet(db, cpA, rub, "", null);
        int gave = WalletDbCRUD.CreateDebtWallet(db, cpB, rub, "", null);
        op("2026-01-10 10:00", took, card, 300);   // взял в долг
        op("2026-01-11 10:00", card, gave, 50);    // дал в долг
        Currency eq = currencies().getCurrencyById(rub);

        WalletDbLoader.DebtsResult d = WalletDbLoader.GenerateDebtsToDate(db, DateUtils.StrToDate("2026-02-01 00:00"), eq);
        assertEquals(50, d.owedToMe.getTotalAmountEq(), 1e-9);
        assertEquals(300, d.iOwe.getTotalAmountEq(), 1e-9);
        assertEquals("ликвидные 250 + 50 − 300", 0, d.totalWithDebts.getTotalAmountEq(), 1e-9);
    }

    @Test
    public void noRate_loanLeftOut_totalsIncomplete() {
        int eur = currency("Евро", "EUR", "€");
        int card = wallet("Карта", eur);
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, eur, "", null);
        op("2026-01-10 10:00", card, loan, 100);   // EUR → RUB курса нет
        Currency eq = currencies().getCurrencyById(rub);

        WalletDbLoader.DebtsResult d = WalletDbLoader.GenerateDebtsToDate(db, DateUtils.StrToDate("2026-02-01 00:00"), eq);
        assertTrue(d.owedToMe.isIncomplete());
        assertEquals(0, d.owedToMe.getTotalAmountEq(), 1e-9);
        assertFalse(d.iOwe.isIncomplete());
        assertTrue(d.totalWithDebts.isIncomplete());
    }

    @Test
    public void noDebts_hasDebtsFalse() {
        int card = wallet("Карта", rub);
        op("2026-01-10 10:00", category("Зарплата"), card, 100);
        WalletDbLoader.DebtsResult d = WalletDbLoader.GenerateDebtsToDate(db,
                DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub));
        assertFalse(d.hasDebts);
        assertFalse(WalletDbLoader.HasDebtWallets(db));
    }

    @Test
    public void balanceRows_markDebt() {
        int card = wallet("Карта", rub);
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null);
        op("2026-01-10 10:00", card, loan, 100);
        Date d = DateUtils.StrToDate("2026-02-01 00:00");
        for (WalletDbLoader.WalletBalanceRow row : WalletDbLoader.LoadBalancesPerWallet(db, d, d,
                currencies().getCurrencyById(rub))) {
            if (row.accountId == loan) {
                assertTrue(row.isDebt());
                assertFalse(row.isLiquid());
                assertEquals(WalletDbCRUD.counterpartyUuid(db, cp), row.debtCounterpartyUuid);
            } else {
                assertTrue(row.isLiquid());
            }
        }
    }

    // ── 3.2 сверка периода ──────────────────────────────────────────────────

    @Test
    public void reconciliation_incomeMinusExpensePlusDebtFlow_equalsLiquidChange() {
        int card = wallet("Карта", rub);
        int salary = category("Зарплата");
        int food = category("Продукты");
        int writeOff = category("Списание долгов");
        int cpA = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int cpB = WalletDbCRUD.CreateCounterparty(db, "Банк", "");
        int gave = WalletDbCRUD.CreateDebtWallet(db, cpA, rub, "", null);
        int took = WalletDbCRUD.CreateDebtWallet(db, cpB, rub, "", null);
        op("2026-04-01 10:00", salary, card, 10_000);   // до периода
        op("2026-05-02 10:00", salary, card, 500);      // доход
        op("2026-05-03 10:00", card, food, 200);        // расход
        op("2026-05-04 10:00", card, gave, 100);        // дал в долг: −100
        op("2026-05-05 10:00", gave, card, 30);         // вернули часть: +30
        op("2026-05-06 10:00", took, card, 1_000);      // взял в долг: +1000
        op("2026-05-07 10:00", gave, writeOff, 70);     // простил остаток: расход 70, «Итог» не изменился
        op("2026-05-08 10:00", writeOff, took, 1_000);  // меня простили: доход 1000

        AccountList accounts = loadAccounts();
        CurrencyList currencies = currencies();
        Currency eq = currencies.getCurrencyById(rub);
        Date from = DateUtils.StrToDate("2026-05-01 00:00");
        Date to = DateUtils.StrToDate("2026-06-01 00:00");

        double total = WalletDbLoader.GenerateReportGetBalanceToDate(db, to, eq, null).getTotalAmountEq()
                - WalletDbLoader.GenerateReportGetBalanceToDate(db, from, eq, null).getTotalAmountEq();
        double income = WalletDbLoader.GenerateIncomeReportByAccount(db, from, to, accounts, currencies, eq, null)
                .getTotal().getTotalAmountEq();
        double expense = WalletDbLoader.GenerateExpanseReportByAccount(db, from, to, accounts, currencies, eq, null)
                .getTotal().getTotalAmountEq();
        EquivalentResult flow = WalletDbLoader.GenerateDebtFlowForPeriod(db, from, to, eq);

        assertEquals(500 - 200 - 100 + 30 + 1_000, total, 0.001);
        assertEquals(1_500, income, 0.001);
        assertEquals(270, expense, 0.001);
        assertEquals(-100 + 30 + 1_000 + 70 - 1_000, flow.getTotalAmountEq(), 0.001);
        assertFalse(flow.isIncomplete());
        assertEquals(total, income - expense + flow.getTotalAmountEq(), 0.001);
        // Займы не трогают движение капитала.
        assertEquals(0, WalletDbLoader.GenerateCapitalFlowForPeriod(db, from, to, eq).getTotalAmountEq(), 0.001);
    }

    @Test
    public void reconciliation_specScenario_income500_expense200_lent100() {
        int card = wallet("Карта", rub);
        int salary = category("Зарплата");
        int food = category("Продукты");
        int loan = WalletDbCRUD.CreateDebtWallet(db, WalletDbCRUD.CreateCounterparty(db, "Вася", ""), rub, "", null);
        op("2026-05-02 10:00", salary, card, 500);
        op("2026-05-03 10:00", card, food, 200);
        op("2026-05-04 10:00", card, loan, 100);
        Currency eq = currencies().getCurrencyById(rub);
        Date from = DateUtils.StrToDate("2026-05-01 00:00");
        Date to = DateUtils.StrToDate("2026-06-01 00:00");

        double total = WalletDbLoader.GenerateReportGetBalanceToDate(db, to, eq, null).getTotalAmountEq()
                - WalletDbLoader.GenerateReportGetBalanceToDate(db, from, eq, null).getTotalAmountEq();
        assertEquals(200, total, 0.001);
        assertEquals(-100, WalletDbLoader.GenerateDebtFlowForPeriod(db, from, to, eq).getTotalAmountEq(), 0.001);
    }

    // ── 5.4 прощение ────────────────────────────────────────────────────────

    @Test
    public void forgiveDebtToMe_expenseOnWriteOffCategory_balanceZero() {
        int card = wallet("Карта", usd);
        int loan = WalletDbCRUD.CreateDebtWallet(db, WalletDbCRUD.CreateCounterparty(db, "Вася", ""), usd, "", null);
        op("2026-05-01 10:00", card, loan, 100);
        int cat = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов");

        assertEquals(WalletDbCRUD.WriteOffResult.written,
                WalletDbCRUD.ForgiveDebt(db, loan, cat, "2026-05-10", 100, 2, "Долг прощён"));

        assertEquals(0, WalletDbCRUD.RoundedWalletBalance(db, loan, 2), 1e-9);
        assertEquals(loan, intQuery("SELECT acc_fr_id FROM operation WHERE acc_to_id = " + cat));
        assertEquals("2026-05-10 23:59", stringQuery("SELECT date FROM operation WHERE acc_to_id = " + cat));
        assertEquals("обычная категория, не системная", 0,
                intQuery("SELECT system_kind FROM account WHERE _id = " + cat));
        assertEquals("заводится один раз", cat, WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов"));
        // Расход периода: прощение — настоящий расход.
        assertEquals(100.0, doubleQuery("SELECT coalesce(sum(amount), 0) FROM v_operation"
                + " WHERE acc_from_is_operating = 1 AND acc_to_is_operating = 0"), 1e-9);
    }

    @Test
    public void forgivenByThem_incomeFromWriteOffCategory() {
        int card = wallet("Карта", usd);
        int loan = WalletDbCRUD.CreateDebtWallet(db, WalletDbCRUD.CreateCounterparty(db, "Банк", ""), usd, "", null);
        op("2026-05-01 10:00", loan, card, 100);   // взял в долг
        int cat = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов");

        assertEquals(WalletDbCRUD.WriteOffResult.written,
                WalletDbCRUD.ForgiveDebt(db, loan, cat, "2026-05-10", 100, 2, ""));

        assertEquals(0, WalletDbCRUD.RoundedWalletBalance(db, loan, 2), 1e-9);
        assertEquals(cat, intQuery("SELECT acc_fr_id FROM operation WHERE acc_to_id = " + loan));
        assertEquals(WalletDbCRUD.WriteOffResult.zero,
                WalletDbCRUD.ForgiveDebt(db, loan, cat, "2026-05-11", 1, 2, ""));
    }

    @Test
    public void forgiveDateTime_today_isCurrentTime_otherDay_isEndOfDay() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.NOVEMBER, 13, 18, 41, 7);
        java.util.Date now = c.getTime();
        // Сегодня — «сейчас», чтобы карточка периода (до текущего момента) видела прощение сразу.
        assertEquals("2026-11-13 18:41", WalletDbCRUD.ForgiveDateTime("2026-11-13", now));
        // Прошлый и будущий дни — конец дня, как раньше.
        assertEquals("2026-11-12 23:59", WalletDbCRUD.ForgiveDateTime("2026-11-12", now));
        assertEquals("2026-11-14 23:59", WalletDbCRUD.ForgiveDateTime("2026-11-14", now));
    }

    // ── 6.1 сброс ───────────────────────────────────────────────────────────

    @Test
    public void resetOperationsAndAccounts_dropsLoanFlags_keepsCounterparties() {
        int card = wallet("Карта", rub);
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, rub, "", null);
        op("2026-01-10 10:00", card, loan, 100);

        WalletFillData.ResetOperationsAndAccounts(db, context, false);

        assertEquals(0, intQuery("SELECT count(*) FROM account_debt WHERE deleted_at IS NULL"));
        assertEquals(1, intQuery("SELECT dirty FROM account_debt WHERE _id = " + loan));
        assertEquals("контрагент остаётся", 1, intQuery("SELECT count(*) FROM counterparty WHERE deleted_at IS NULL"));
        assertFalse(WalletDbLoader.HasDebtWallets(db));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private int category(String name) {
        return (int) insert("account", "name", name, "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString());
    }

    private long op(String date, int from, int to, double amount) {
        return WalletDbCRUD.InsertOperation(db, date, from, to, amount, "");
    }

    private CurrencyList currencies() {
        IconList icons = new IconList();
        WalletDbLoader.LoadIcons(db, icons);
        CurrencyList list = new CurrencyList();
        WalletDbLoader.LoadCurrencyList(db, list, icons);
        return list;
    }

    /** sql объектов долгов из sqlite_master — для сравнения onCreate и апгрейда. */
    private Map<String, String> debtSchema() {
        Map<String, String> out = new TreeMap<>();
        Cursor c = db.rawQuery("SELECT type || ':' || name, sql FROM sqlite_master"
                + " WHERE tbl_name IN ('counterparty', 'account_debt', 'counterparty_contact')"
                + " AND sql IS NOT NULL", null);
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getString(1).replaceAll("\\s+", " "));
        } finally {
            c.close();
        }
        assertFalse(out.isEmpty());
        return out;
    }

    private int currency(String name, String iso, String symbol) {
        return (int) insert("currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol);
    }

    private int wallet(String name, int currencyId) {
        return (int) WalletDbCRUD.InsertOperatingAccount(db, name, "", (int) iconId, currencyId);
    }

    private AccountList loadAccounts() {
        IconList icons = new IconList();
        WalletDbLoader.LoadIcons(db, icons);
        CurrencyList currencies = new CurrencyList();
        WalletDbLoader.LoadCurrencyList(db, currencies, icons);
        AccountList accounts = new AccountList();
        WalletDbLoader.LoadAccounts(db, accounts, currencies, icons, null);
        return accounts;
    }

    private boolean exists(String type, String name) {
        Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?", new String[]{type, name});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private int intQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private double doubleQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getDouble(0);
        } finally {
            c.close();
        }
    }

    private String stringQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    private long insert(String table, Object... kv) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            if (value instanceof Integer) cv.put(key, (Integer) value);
            else if (value instanceof Long) cv.put(key, (Long) value);
            else if (value instanceof Double) cv.put(key, (Double) value);
            else if (value instanceof byte[]) cv.put(key, (byte[]) value);
            else cv.put(key, (String) value);
        }
        return db.insertOrThrow(table, null, cv);
    }
}
