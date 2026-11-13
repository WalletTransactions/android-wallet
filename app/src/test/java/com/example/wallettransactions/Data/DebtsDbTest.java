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
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.IconList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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

    // ── helpers ─────────────────────────────────────────────────────────────

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
