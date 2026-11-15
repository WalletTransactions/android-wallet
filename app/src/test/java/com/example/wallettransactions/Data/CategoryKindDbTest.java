package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.IconList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * openspec category-kind-income-expense, группа 1: колонка {@code account.category_kind}
 * (шаг v92), модель {@link AccountItem.CategoryKind}, загрузка и запись вида.
 */
@RunWith(RobolectricTestRunner.class)
public class CategoryKindDbTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;
    private int rub;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        AppSettings.setLedgerRereadPending(context, false);
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        // Сид оставляем только справочником иконок — свои счета и валюта ниже.
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
        rub = (int) insert("currency", "name", "Рубль", "iso_code", "RUB", "icon_id", iconId, "symbol", "₽");
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 1.1 схема и шаг v92 ─────────────────────────────────────────────────

    @Test
    public void freshInstall_hasColumnWithDefaultBoth() {
        assertTrue(WalletDbHelper.columnExists(db, "account", "category_kind"));
        long id = insert("account", "name", "Прочее", "comment", "", "icon_id", iconId,
                "uuid", java.util.UUID.randomUUID().toString());
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + id));
    }

    @Test
    public void upgradeFrom91_addsColumnAsBoth_andDoesNotTouchRowsOrDirty() {
        int groceries = category("Продукты");
        int salary = category("Зарплата");
        // «Синхронизирована» (dirty = 0) и «ждёт отправки» (dirty = 1) до апгрейда.
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = " + groceries);
        db.execSQL("UPDATE account SET dirty = 1 WHERE _id = " + salary);
        // Имитация схемы v91: колонки category_kind ещё нет. DROP COLUMN в SQLite Robolectric
        // не поддерживается (нужен 3.35+), поэтому колонку переименовываем — для миграции это
        // то же самое: columnExists(category_kind) == false, и она идёт по ветке ADD COLUMN.
        db.execSQL("ALTER TABLE account RENAME COLUMN category_kind TO category_kind_v91_stub");
        assertTrue(!WalletDbHelper.columnExists(db, "account", "category_kind"));

        helper.onUpgrade(db, 91, 92);

        assertTrue(WalletDbHelper.columnExists(db, "account", "category_kind"));
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + groceries));
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE _id = " + salary));
        // Изменение схемы — не правка пользователя: dirty прежний, на сервер ничего не уходит.
        assertEquals(0, intQuery("SELECT dirty FROM account WHERE _id = " + groceries));
        assertEquals(1, intQuery("SELECT dirty FROM account WHERE _id = " + salary));
    }

    @Test
    public void upgradeFrom91_whenColumnAlreadyExists_isNoOp() {
        // Апгрейд из очень старой базы: колонку уже завёл accountTableBody() при перестройке.
        category("Продукты");
        helper.onUpgrade(db, 91, 92);
        helper.onUpgrade(db, 91, 92);
        assertTrue(WalletDbHelper.columnExists(db, "account", "category_kind"));
        assertEquals(1, intQuery("SELECT count(*) FROM account"));
    }

    // ── 1.2 модель ──────────────────────────────────────────────────────────

    @Test
    public void categoryKindFromValue_knownValuesAndSafeDefault() {
        assertEquals(AccountItem.CategoryKind.EXPENSE, AccountItem.CategoryKind.fromValue(0));
        assertEquals(AccountItem.CategoryKind.INCOME, AccountItem.CategoryKind.fromValue(1));
        assertEquals(AccountItem.CategoryKind.BOTH, AccountItem.CategoryKind.fromValue(2));
        assertEquals(AccountItem.CategoryKind.BOTH, AccountItem.CategoryKind.fromValue(-1));
        assertEquals(AccountItem.CategoryKind.BOTH, AccountItem.CategoryKind.fromValue(3));
        assertEquals(0, AccountItem.CategoryKind.EXPENSE.getValue());
        assertEquals(1, AccountItem.CategoryKind.INCOME.getValue());
        assertEquals(2, AccountItem.CategoryKind.BOTH.getValue());
    }

    @Test
    public void loadAccounts_readsKind_andOutOfRangeBecomesBoth() {
        int expense = category("Продукты");
        int income = category("Зарплата");
        int broken = category("Мусор");
        db.execSQL("UPDATE account SET category_kind = 0 WHERE _id = " + expense);
        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + income);
        db.execSQL("UPDATE account SET category_kind = 7 WHERE _id = " + broken);

        AccountList accounts = loadAccounts();

        assertEquals(AccountItem.CategoryKind.EXPENSE, accounts.getAccountById(expense).getCategoryKind());
        assertEquals(AccountItem.CategoryKind.INCOME, accounts.getAccountById(income).getCategoryKind());
        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(broken).getCategoryKind());
    }

    @Test
    public void loadAccounts_walletAndSystemCategoryStayBoth() {
        int wallet = (int) WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", (int) iconId, rub);
        int transfer = WalletDbCRUD.GetOrCreateSystemCategory(db,
                AccountItem.SystemKind.TRANSFER.getValue(), AccountItem.SYSTEM_TRANSFER_NAME, "unknown");

        AccountList accounts = loadAccounts();

        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(wallet).getCategoryKind());
        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(transfer).getCategoryKind());
    }

    @Test
    public void loadAccounts_secondLoadUpdatesCachedKind() {
        int id = category("Подарки");
        AccountList accounts = loadAccounts();
        assertEquals(AccountItem.CategoryKind.BOTH, accounts.getAccountById(id).getCategoryKind());

        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + id);
        IconList icons = new IconList();
        WalletDbLoader.LoadIcons(db, icons);
        WalletDbLoader.LoadAccounts(db, accounts, currencies(), icons, null);

        assertEquals(AccountItem.CategoryKind.INCOME, accounts.getAccountById(id).getCategoryKind());
    }

    // ── 1.3 запись ──────────────────────────────────────────────────────────

    @Test
    public void insertAccount_withoutKind_isBoth() {
        WalletDbCRUD.InsertAccount(db, "Прочее", "", (int) iconId, false);
        assertEquals(2, intQuery("SELECT category_kind FROM account WHERE name = 'Прочее'"));
    }

    @Test
    public void insertAccount_withKind_storesItAndMarksDirty() {
        WalletDbCRUD.InsertAccount(db, "Премия", "", (int) iconId, false, AccountItem.CategoryKind.INCOME);
        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE name = 'Премия'"));
        assertEquals(1, intQuery("SELECT dirty FROM account WHERE name = 'Премия'"));
        assertEquals(0, intQuery("SELECT kind FROM account WHERE name = 'Премия'"));
        assertEquals(0, intQuery("SELECT system_kind FROM account WHERE name = 'Премия'"));
    }

    @Test
    public void updateAccount_withKind_changesKindAndMarksDirty() {
        int id = category("Подарки");
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = " + id);

        WalletDbCRUD.UpdateAccount(db, id, "Подарки", "", (int) iconId, false, AccountItem.CategoryKind.EXPENSE);

        assertEquals(0, intQuery("SELECT category_kind FROM account WHERE _id = " + id));
        assertEquals(1, intQuery("SELECT dirty FROM account WHERE _id = " + id));
    }

    @Test
    public void updateAccount_withoutKind_keepsKind() {
        int id = category("Зарплата");
        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + id);

        WalletDbCRUD.UpdateAccount(db, id, "Зарплата 2", "", (int) iconId, false);

        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE _id = " + id));
    }

    @Test
    public void updateAccount_withNullKind_keepsKind() {
        int id = category("Зарплата");
        db.execSQL("UPDATE account SET category_kind = 1 WHERE _id = " + id);

        WalletDbCRUD.UpdateAccount(db, id, "Зарплата", "", (int) iconId, true, null);

        assertEquals(1, intQuery("SELECT category_kind FROM account WHERE _id = " + id));
        assertNotNull(loadAccounts().getAccountById(id));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private int category(String name) {
        return (int) insert("account", "name", name, "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString());
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

    private int intQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            c.moveToFirst();
            return c.getInt(0);
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
