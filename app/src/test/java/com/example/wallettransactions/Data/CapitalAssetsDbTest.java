package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Date;

/**
 * openspec capital-assets: таблица {@code account_asset} и шаг v89, признак актива в модели,
 * системная «Переоценка», запись переоценки, исключение её из дохода/расхода, итоги
 * «ликвидные / капитал / всего с капиталом» и перенос операций категории в актив.
 */
@RunWith(RobolectricTestRunner.class)
public class CapitalAssetsDbTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;
    private int rub;
    private int usd;
    private int eur;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        AppSettings.setLedgerRereadPending(context, false);
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        // Сид оставляем только справочником иконок — свои валюты, счета и курсы ниже.
        db.execSQL("DELETE FROM operation_link");
        db.execSQL("DELETE FROM operation");
        db.execSQL("DELETE FROM recurring_operation");
        db.execSQL("DELETE FROM account_asset");
        db.execSQL("DELETE FROM account_operating");
        db.execSQL("DELETE FROM account");
        db.execSQL("DELETE FROM currency_conversion");
        db.execSQL("DELETE FROM currency_pair_rate");
        db.execSQL("DELETE FROM currency_pair");
        db.execSQL("DELETE FROM currency");
        // Картинки сида не влезают в CursorWindow Robolectric при LoadIcons — свои, маленькие.
        db.execSQL("DELETE FROM icon");
        iconId = insert("icon", "name", "test_icon", "image", new byte[]{0}, "icon_type", 1);
        insert("icon", "name", "unknown", "image", new byte[]{0}, "icon_type", 1);
        rub = currency("Рубль", "RUB", "₽");
        usd = currency("Доллар", "USD", "$");
        eur = currency("Евро", "EUR", "€");
        long pair = insert("currency_pair", "symbol", "USDRUB", "base_currency_id", usd, "quote_currency_id", rub);
        insert("currency_pair_rate", "currency_pair_id", pair, "date", "2025-01-01", "rate", 90.0);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 1.1 схема и шаг v89 ─────────────────────────────────────────────────

    @Test
    public void freshInstall_hasAccountAssetTable_withoutRereadFlag() {
        assertTrue(WalletDbHelper.tableExists(db, "account_asset"));
        assertTrue(exists("trigger", "trg_account_asset_dirty"));
        assertTrue(exists("index", "ix_account_asset_uuid"));
        assertFalse(AppSettings.isLedgerRereadPending(context));
    }

    @Test
    public void upgradeFrom88_createsTableTriggerViewColumn_andRaisesRereadFlag() {
        db.execSQL("DROP TABLE account_asset");
        db.execSQL("DROP VIEW v_operation_eq");
        db.execSQL("DROP VIEW v_operation");
        db.execSQL("CREATE VIEW v_operation AS SELECT 1 x");

        helper.onUpgrade(db, 88, 89);

        assertTrue(WalletDbHelper.tableExists(db, "account_asset"));
        assertTrue(exists("trigger", "trg_account_asset_dirty"));
        assertTrue(exists("index", "ix_account_asset_uuid"));
        assertTrue(AppSettings.isLedgerRereadPending(context));
        Cursor c = db.rawQuery("SELECT is_revaluation FROM v_operation_eq LIMIT 0", null);
        c.close();

        // Триггер dirty работает: UPDATE без dirty поднимает его.
        int wallet = wallet("Квартира", rub);
        assertTrue(WalletDbCRUD.SetAccountAsset(db, wallet, 1, true));
        db.execSQL("UPDATE account_asset SET dirty = 0");
        db.execSQL("UPDATE account_asset SET comment = 'x'");
        assertEquals(1, intQuery("SELECT dirty FROM account_asset WHERE _id = " + wallet));
    }

    // ── 1.2 модель ──────────────────────────────────────────────────────────

    @Test
    public void systemKindFromValue3_isRevaluation() {
        assertEquals(AccountItem.SystemKind.REVALUATION, AccountItem.SystemKind.fromValue(3));
        AccountItem reval = new AccountItem(1, "Переоценка", "", null, false,
                AccountItem.SystemKind.REVALUATION, AccountItem.Kind.CATEGORY);
        assertTrue(reval.isSystemRevaluation());
        assertTrue("нельзя удалить и переименовать", reval.isSystem());
    }

    @Test
    public void loadAccounts_readsLiveAssetOnly() {
        int flat = wallet("Квартира", rub);
        int car = wallet("Машина", rub);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        WalletDbCRUD.SetAccountAsset(db, car, 2, false);
        WalletDbCRUD.ClearAccountAsset(db, car);

        AccountList accounts = loadAccounts();
        AccountOperatingItem f = (AccountOperatingItem) accounts.getAccountById(flat);
        assertNotNull(f.getAsset());
        assertEquals(1, f.getAsset().type);
        assertTrue(f.getAsset().includeInNetWorth);
        assertNull("мягко удалённый признак — ликвидный кошелёк",
                ((AccountOperatingItem) accounts.getAccountById(car)).getAsset());
    }

    // ── 1.3 пометка, снятие, повторная пометка ──────────────────────────────

    @Test
    public void setClearSet_keepsSingleRowWithAccountUuid_dirty() {
        int flat = wallet("Квартира", rub);
        String accountUuid = stringQuery("SELECT uuid FROM account WHERE _id = " + flat);

        assertTrue(WalletDbCRUD.SetAccountAsset(db, flat, 1, true));
        assertEquals(accountUuid, stringQuery("SELECT uuid FROM account_asset WHERE _id = " + flat));
        assertEquals(1, intQuery("SELECT dirty FROM account_asset WHERE _id = " + flat));

        db.execSQL("UPDATE account_asset SET dirty = 0");
        WalletDbCRUD.ClearAccountAsset(db, flat);
        assertEquals(1, intQuery("SELECT count(*) FROM account_asset WHERE deleted_at IS NOT NULL"));
        assertEquals(1, intQuery("SELECT dirty FROM account_asset WHERE _id = " + flat));

        db.execSQL("UPDATE account_asset SET dirty = 0");
        assertTrue(WalletDbCRUD.SetAccountAsset(db, flat, 3, false));
        assertEquals(1, intQuery("SELECT count(*) FROM account_asset"));
        assertEquals(0, intQuery("SELECT count(*) FROM account_asset WHERE deleted_at IS NOT NULL"));
        assertEquals(3, intQuery("SELECT asset_type FROM account_asset"));
        assertEquals(0, intQuery("SELECT include_in_net_worth FROM account_asset"));
        assertEquals(1, intQuery("SELECT dirty FROM account_asset"));
    }

    @Test
    public void setAccountAsset_refusesCategory() {
        int category = category("Продукты", 0);
        assertFalse(WalletDbCRUD.SetAccountAsset(db, category, 1, true));
        assertEquals(0, intQuery("SELECT count(*) FROM account_asset"));
    }

    // ── 1.4 is_revaluation ──────────────────────────────────────────────────

    @Test
    public void isRevaluation_eitherSide_ofSystemKind3() {
        int flat = wallet("Квартира", rub);
        int card = wallet("Карта", rub);
        int reval = category("Переоценка", 3);
        int food = category("Продукты", 0);
        long up = op("2026-03-01 23:59", reval, flat, 100);
        long down = op("2026-03-02 23:59", flat, reval, 50);
        long plain = op("2026-03-03 10:00", card, food, 10);

        assertEquals(1, intQuery("SELECT is_revaluation FROM v_operation_eq WHERE _id = " + up + " LIMIT 1"));
        assertEquals(1, intQuery("SELECT is_revaluation FROM v_operation_eq WHERE _id = " + down + " LIMIT 1"));
        assertEquals(0, intQuery("SELECT is_revaluation FROM v_operation_eq WHERE _id = " + plain + " LIMIT 1"));
    }

    // ── 3.1 системная категория ─────────────────────────────────────────────

    @Test
    public void revaluationCategory_createdOnce_withSystemKind3() {
        int id = WalletDbCRUD.GetOrCreateRevaluationCategory(db);
        assertTrue(id > 0);
        assertEquals("Переоценка", stringQuery("SELECT name FROM account WHERE _id = " + id));
        assertEquals(3, intQuery("SELECT system_kind FROM account WHERE _id = " + id));
        assertEquals(id, WalletDbCRUD.GetOrCreateRevaluationCategory(db));
    }

    @Test
    public void revaluationCategory_takenName_usesFallback_ordinaryUntouched() {
        int ordinary = category("Переоценка", 0);
        int id = WalletDbCRUD.GetOrCreateRevaluationCategory(db);
        assertNotEquals(ordinary, id);
        assertEquals("Переоценка активов", stringQuery("SELECT name FROM account WHERE _id = " + id));
        assertEquals(0, intQuery("SELECT system_kind FROM account WHERE _id = " + ordinary));
        assertEquals(id, WalletDbCRUD.GetOrCreateRevaluationCategory(db));
    }

    // ── 3.2 запись переоценки ───────────────────────────────────────────────

    @Test
    public void writeRevaluation_upDownBackdatedUnchanged() {
        int card = wallet("Карта", rub);
        int flat = wallet("Квартира", rub);
        int salary = category("Зарплата", 0);
        op("2026-01-01 10:00", salary, card, 20_000_000);
        op("2026-02-01 10:00", card, flat, 10_000_000);

        assertEquals(WalletDbCRUD.RevaluationResult.written,
                WalletDbCRUD.WriteRevaluation(db, flat, "2026-06-30", 12_000_000, 2, "Оценка: 12 000 000"));
        int reval = intQuery("SELECT _id FROM account WHERE system_kind = 3");
        assertEquals(1, intQuery("SELECT count(*) FROM operation WHERE acc_fr_id = " + reval
                + " AND acc_to_id = " + flat + " AND amount = 2000000 AND date = '2026-06-30 23:59'"));
        assertEquals(12_000_000, WalletDbLoader.LoadAccountBalanceAt(db, flat, "2026-06-30 23:59"), 0.001);

        // Задним числом: Δ от баланса на 31 марта (10 000 000), а не от текущего.
        assertEquals(WalletDbCRUD.RevaluationResult.written,
                WalletDbCRUD.WriteRevaluation(db, flat, "2026-03-31", 9_700_000, 2, ""));
        assertEquals(1, intQuery("SELECT count(*) FROM operation WHERE acc_fr_id = " + flat
                + " AND acc_to_id = " + reval + " AND amount = 300000 AND date = '2026-03-31 23:59'"));

        assertEquals(WalletDbCRUD.RevaluationResult.unchanged,
                WalletDbCRUD.WriteRevaluation(db, flat, "2026-03-31", 9_700_000, 2, ""));
        assertEquals(WalletDbCRUD.RevaluationResult.negative,
                WalletDbCRUD.WriteRevaluation(db, flat, "2026-03-31", -5, 2, ""));
        assertEquals("Δ = 0 и отрицательная оценка не пишут проводок", 2,
                intQuery("SELECT count(*) FROM operation WHERE acc_fr_id = " + reval + " OR acc_to_id = " + reval));
    }

    // ── 3.3 доход и расход без переоценки ───────────────────────────────────

    @Test
    public void incomeExpense_excludeRevaluation_includingManualOnes() {
        int card = wallet("Карта", rub);
        int flat = wallet("Квартира", rub);
        int salary = category("Зарплата", 0);
        int food = category("Продукты", 0);
        op("2026-05-01 10:00", salary, card, 1000);
        op("2026-05-02 10:00", card, food, 300);
        WalletDbCRUD.WriteRevaluation(db, flat, "2026-05-03", 2_000_000, 2, "");      // вверх
        int reval = WalletDbCRUD.GetOrCreateRevaluationCategory(db);
        op("2026-05-04 10:00", card, reval, 77);                                       // вручную, «вниз» с карты

        AccountList accounts = loadAccounts();
        CurrencyList currencies = currencies();
        Currency eq = currencies.getCurrencyById(rub);
        Date from = DateUtils.StrToDate("2026-05-01 00:00");
        Date to = DateUtils.StrToDate("2026-05-31 23:59");

        EquivalentResult income = WalletDbLoader.GenerateIncomeReportByAccount(db, from, to, accounts, currencies, eq, null).getTotal();
        EquivalentResult expense = WalletDbLoader.GenerateExpanseReportByAccount(db, from, to, accounts, currencies, eq, null).getTotal();
        assertEquals(1000, income.getTotalAmountEq(), 0.001);
        assertEquals(300, expense.getTotalAmountEq(), 0.001);

        DataFilter incomeFilter = new DataFilter();
        incomeFilter.setOperationKind(DataFilter.OperationKind.income);
        DataFilter expenseFilter = new DataFilter();
        expenseFilter.setOperationKind(DataFilter.OperationKind.expense);
        assertEquals(1, WalletDbLoader.CountOperations(db, from, to, eq, incomeFilter));
        assertEquals(1, WalletDbLoader.CountOperations(db, from, to, eq, expenseFilter));

        AccountItem revalItem = accounts.getAccountById(reval);
        AccountItem cardItem = accounts.getAccountById(card);
        assertFalse(expenseFilter.matchesKind(cardItem, revalItem));
        assertFalse(incomeFilter.matchesKind(revalItem, accounts.getAccountById(flat)));
    }

    // ── 4.1 балансы и капитал ───────────────────────────────────────────────

    @Test
    public void purchase_liquidDrops_capitalAndNetWorthKeepTotal() {
        int card = wallet("Карта", rub);
        int flat = wallet("Квартира", rub);
        int salary = category("Зарплата", 0);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        op("2025-12-01 10:00", salary, card, 10_000_000);
        op("2026-02-10 10:00", card, flat, 10_000_000);
        Currency eq = currencies().getCurrencyById(rub);
        Date start = DateUtils.StrToDate("2026-02-01 00:00");
        Date end = DateUtils.StrToDate("2026-03-01 00:00");

        EquivalentResult liquidStart = WalletDbLoader.GenerateReportGetBalanceToDate(db, start, eq, null);
        EquivalentResult liquidEnd = WalletDbLoader.GenerateReportGetBalanceToDate(db, end, eq, null);
        WalletDbLoader.CapitalResult capital = WalletDbLoader.GenerateCapitalToDate(db, end, eq);

        assertEquals(10_000_000, liquidStart.getTotalAmountEq() - liquidEnd.getTotalAmountEq(), 0.001);
        assertTrue(capital.hasAssets);
        assertEquals(10_000_000, capital.capital.getTotalAmountEq(), 0.001);
        assertEquals(liquidStart.getTotalAmountEq(), capital.netWorth.getTotalAmountEq(), 0.001);
    }

    @Test
    public void assetWithoutRate_marksCapitalIncomplete_notConverted1to1() {
        int card = wallet("Карта", rub);
        int house = wallet("Дом", eur);
        int salary = category("Зарплата", 0);
        WalletDbCRUD.SetAccountAsset(db, house, 1, true);
        op("2026-01-01 10:00", salary, card, 500);
        op("2026-01-02 10:00", salary, house, 100_000);
        WalletDbLoader.CapitalResult capital = WalletDbLoader.GenerateCapitalToDate(db,
                DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub));

        assertTrue(capital.capital.isIncomplete());
        assertEquals(0, capital.capital.getTotalAmountEq(), 0.001);
        assertTrue(capital.netWorth.isIncomplete());
        assertEquals(500, capital.netWorth.getTotalAmountEq(), 0.001);
    }

    @Test
    public void assetOutOfNetWorth_inCapitalOnly() {
        int card = wallet("Карта", rub);
        int car = wallet("Машина", rub);
        int salary = category("Зарплата", 0);
        WalletDbCRUD.SetAccountAsset(db, car, 2, false);
        op("2026-01-01 10:00", salary, card, 500);
        op("2026-01-02 10:00", salary, car, 1_500_000);
        WalletDbLoader.CapitalResult capital = WalletDbLoader.GenerateCapitalToDate(db,
                DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub));

        assertEquals(1_500_000, capital.capital.getTotalAmountEq(), 0.001);
        assertEquals(500, capital.netWorth.getTotalAmountEq(), 0.001);
        assertEquals(500, WalletDbLoader.GenerateReportGetBalanceToDate(db,
                DateUtils.StrToDate("2026-02-01 00:00"), currencies().getCurrencyById(rub), null)
                .getTotalAmountEq(), 0.001);
    }

    // ── .github-private#1: сверка «Итога» с «Доход − Расход» ───────────────

    @Test
    public void capitalFlow_closesGapBetweenTotalAndIncomeMinusExpense() {
        int card = wallet("Карта", rub);
        int flat = wallet("Квартира", rub);
        int deposit = wallet("Вклад", rub);
        int salary = category("Зарплата", 0);
        int food = category("Продукты", 0);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        WalletDbCRUD.SetAccountAsset(db, deposit, 3, true);
        op("2026-04-01 10:00", salary, card, 1_000_000);   // до периода
        op("2026-05-02 10:00", salary, card, 200);          // доход, ликвидный
        op("2026-05-03 10:00", card, food, 50);             // расход, ликвидный
        op("2026-05-04 10:00", card, flat, 400_000);        // покупка актива: −400 000
        op("2026-05-05 10:00", salary, deposit, 30);        // доход прямо на актив: −30
        op("2026-05-06 10:00", flat, card, 1_000);          // возврат из актива: +1 000
        op("2026-05-07 10:00", flat, deposit, 5);           // актив ↔ актив: 0
        WalletDbCRUD.WriteRevaluation(db, flat, "2026-05-08", 2_000_000, 2, "");   // переоценка: 0

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
        EquivalentResult flow = WalletDbLoader.GenerateCapitalFlowForPeriod(db, from, to, eq);

        assertEquals(-400_000 - 30 + 1_000, flow.getTotalAmountEq(), 0.001);
        assertFalse(flow.isIncomplete());
        // Без валютных расхождений остатка нет: Итог = Доход − Расход + движение.
        assertEquals(total, income - expense + flow.getTotalAmountEq(), 0.001);
    }

    @Test
    public void capitalFlow_withoutRate_isIncomplete_andSkipsOperation() {
        int card = wallet("Карта", rub);
        int house = wallet("Дом", eur);
        WalletDbCRUD.SetAccountAsset(db, house, 1, true);
        int salary = category("Зарплата", 0);
        op("2026-05-02 10:00", salary, house, 100);   // EUR → RUB курса нет
        EquivalentResult flow = WalletDbLoader.GenerateCapitalFlowForPeriod(db,
                DateUtils.StrToDate("2026-05-01 00:00"), DateUtils.StrToDate("2026-06-01 00:00"),
                currencies().getCurrencyById(rub));

        assertTrue(flow.isIncomplete());
        assertEquals(0, flow.getTotalAmountEq(), 0.001);
    }

    // ── 6.1 перенос категории в актив ───────────────────────────────────────

    @Test
    public void move_sameCurrency_expenseAndRefund_becomeTransfers_categoryArchived() {
        int card = wallet("Карта", rub);
        int flat = wallet("Квартира Ленина", rub);
        int category = category("Квартира", 0);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        long pay = op("2026-01-10 10:00", card, category, 1000);
        long refund = op("2026-01-11 10:00", category, card, 200);
        recurring(card, category);

        WalletDbCRUD.MoveResult r = WalletDbCRUD.MoveCategoryToAsset(db, category, flat);

        assertEquals(2, r.moved);
        assertEquals(0, r.skippedNoRate);
        assertTrue(r.archived);
        assertEquals(flat, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + pay));
        assertEquals(flat, intQuery("SELECT acc_fr_id FROM operation WHERE _id = " + refund));
        assertEquals(1000, doubleQuery("SELECT amount FROM operation WHERE _id = " + pay), 0.001);
        assertEquals(1, intQuery("SELECT is_archived FROM account WHERE _id = " + category));
        assertEquals(flat, intQuery("SELECT acc_to_id FROM recurring_operation"));
    }

    @Test
    public void move_otherCurrency_twoLinkedLegsViaTransfer_atRateOnDate() {
        int dollars = wallet("Доллары", usd);
        int flat = wallet("Квартира Ленина", rub);
        int category = category("Квартира", 0);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        long pay = op("2025-03-10 10:00", dollars, category, 1000);

        WalletDbCRUD.MoveResult r = WalletDbCRUD.MoveCategoryToAsset(db, category, flat);

        assertEquals(1, r.moved);
        int transfer = intQuery("SELECT _id FROM account WHERE system_kind = 1");
        assertEquals(transfer, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + pay));
        assertEquals(1000, doubleQuery("SELECT amount FROM operation WHERE _id = " + pay), 0.001);
        long leg = intQuery("SELECT link_id FROM operation_link WHERE main_id = " + pay + " AND type = 0");
        assertEquals(transfer, intQuery("SELECT acc_fr_id FROM operation WHERE _id = " + leg));
        assertEquals(flat, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + leg));
        assertEquals(90_000, doubleQuery("SELECT amount FROM operation WHERE _id = " + leg), 0.001);
    }

    @Test
    public void move_noRate_skipsThatOne_categoryStaysActive() {
        int card = wallet("Карта", rub);
        int euros = wallet("Евро", eur);
        int flat = wallet("Квартира Ленина", rub);
        int category = category("Квартира", 0);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        for (int i = 1; i <= 4; i++) op("2026-01-0" + i + " 10:00", card, category, 100);
        long noRate = op("2026-01-05 10:00", euros, category, 50);

        WalletDbCRUD.MoveResult r = WalletDbCRUD.MoveCategoryToAsset(db, category, flat);

        assertEquals(4, r.moved);
        assertEquals(1, r.skippedNoRate);
        assertFalse(r.archived);
        assertEquals(category, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + noRate));
        assertEquals(0, intQuery("SELECT is_archived FROM account WHERE _id = " + category));
    }

    @Test
    public void move_failureMidway_rollsBackEverything() {
        int dollars = wallet("Доллары", usd);
        int card = wallet("Карта", rub);
        int flat = wallet("Квартира Ленина", rub);
        int category = category("Квартира", 0);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        long same = op("2025-03-09 10:00", card, category, 500);
        long cross = op("2025-03-10 10:00", dollars, category, 1000);
        db.execSQL("CREATE TRIGGER fail_leg BEFORE INSERT ON operation WHEN NEW.amount = 90000"
                + " BEGIN SELECT RAISE(ABORT, 'boom'); END");

        try {
            WalletDbCRUD.MoveCategoryToAsset(db, category, flat);
            fail("ожидалась ошибка вставки ноги");
        } catch (IllegalStateException expected) {
            // ok
        }
        assertEquals(category, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + same));
        assertEquals(category, intQuery("SELECT acc_to_id FROM operation WHERE _id = " + cross));
        assertEquals(0, intQuery("SELECT count(*) FROM operation_link"));
        assertEquals(0, intQuery("SELECT is_archived FROM account WHERE _id = " + category));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private int currency(String name, String iso, String symbol) {
        return (int) insert("currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol);
    }

    private int wallet(String name, int currencyId) {
        long id = WalletDbCRUD.InsertOperatingAccount(db, name, "", (int) iconId, currencyId);
        return (int) id;
    }

    private int category(String name, int systemKind) {
        return (int) insert("account", "name", name, "comment", "", "icon_id", iconId,
                "system_kind", systemKind, "kind", 0, "uuid", java.util.UUID.randomUUID().toString());
    }

    private long op(String date, int from, int to, double amount) {
        return WalletDbCRUD.InsertOperation(db, date, from, to, amount, "");
    }

    private void recurring(int from, int to) {
        insert("recurring_operation", "acc_fr_id", from, "acc_to_id", to, "amount", 5,
                "frequency", 1, "start_date", "2026-01-01");
    }

    private AccountList loadAccounts() {
        IconList icons = new IconList();
        WalletDbLoader.LoadIcons(db, icons);
        AccountList accounts = new AccountList();
        WalletDbLoader.LoadAccounts(db, accounts, currencies(), icons, null);
        return accounts;
    }

    private CurrencyList currencies() {
        IconList icons = new IconList();
        WalletDbLoader.LoadIcons(db, icons);
        CurrencyList list = new CurrencyList();
        WalletDbLoader.LoadCurrencyList(db, list, icons);
        return list;
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
