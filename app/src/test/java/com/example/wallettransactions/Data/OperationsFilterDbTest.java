package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.DataItems.IconList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Date;

/**
 * openspec similar-search / operations-filter: «И» между группой категорий и группой
 * кошельков, «ИЛИ» внутри группы — на реальной базе, и в ленте, и в отчёте «Расход».
 */
@RunWith(RobolectricTestRunner.class)
public class OperationsFilterDbTest {

    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;
    private int rub;
    private int card;
    private int cash;
    private int food;
    private int cafe;

    private Date from;
    private Date to;
    private Currency eq;
    private AccountList accounts;
    private CurrencyList currencies;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        AppSettings.setLedgerRereadPending(context, false);
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
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
        db.execSQL("DELETE FROM icon");
        iconId = insert("icon", "name", "test_icon", "image", new byte[]{0}, "icon_type", 1);
        insert("icon", "name", "unknown", "image", new byte[]{0}, "icon_type", 1);
        rub = (int) insert("currency", "name", "Рубль", "iso_code", "RUB", "icon_id", iconId, "symbol", "₽");

        card = (int) WalletDbCRUD.InsertOperatingAccount(db, "Карта", "", (int) iconId, rub);
        cash = (int) WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", (int) iconId, rub);
        food = category("Продукты");
        cafe = category("Кафе");

        WalletDbCRUD.InsertOperation(db, "2026-05-02 10:00", card, food, 100, "");
        WalletDbCRUD.InsertOperation(db, "2026-05-03 10:00", cash, food, 200, "");
        WalletDbCRUD.InsertOperation(db, "2026-05-04 10:00", card, cafe, 50, "");

        IconList icons = new IconList();
        WalletDbLoader.LoadIcons(db, icons);
        currencies = new CurrencyList();
        WalletDbLoader.LoadCurrencyList(db, currencies, icons);
        accounts = new AccountList();
        WalletDbLoader.LoadAccounts(db, accounts, currencies, icons, null);

        from = DateUtils.StrToDate("2026-05-01 00:00");
        to = DateUtils.StrToDate("2026-05-31 23:59");
        eq = currencies.getCurrencyById(rub);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private DataFilter filter(int... ids) {
        DataFilter filter = new DataFilter();
        for (int id : ids) filter.addAccount(accounts.getAccountById(id));
        return filter;
    }

    private int count(DataFilter filter) {
        return WalletDbLoader.CountOperations(db, from, to, eq, filter);
    }

    private double expense(DataFilter filter) {
        return WalletDbLoader.GenerateExpanseReportByAccount(db, from, to, accounts, currencies, eq, filter)
                .getTotal().getTotalAmountEq();
    }

    @Test
    public void categoryAndWallet_giveIntersection() {
        DataFilter f = filter(food, card);
        assertEquals(1, count(f));
        assertEquals(100, expense(f), 0.001);
    }

    @Test
    public void onlyCategory_keepsOldBehavior() {
        DataFilter f = filter(food);
        assertEquals(2, count(f));
        assertEquals(300, expense(f), 0.001);
    }

    @Test
    public void onlyWallet_keepsOldBehavior() {
        DataFilter f = filter(card);
        assertEquals(2, count(f));
        assertEquals(150, expense(f), 0.001);
    }

    @Test
    public void twoWalletsInGroup_areOr() {
        DataFilter f = filter(food, card, cash);
        assertEquals(2, count(f));
        assertEquals(300, expense(f), 0.001);
    }

    @Test
    public void twoCategoriesInGroup_areOr() {
        DataFilter f = filter(food, cafe, card);
        assertEquals(2, count(f));
        assertEquals(150, expense(f), 0.001);
    }

    @Test
    public void emptyFilter_countsAll() {
        assertEquals(3, count(new DataFilter()));
    }

    private int category(String name) {
        return (int) insert("account", "name", name, "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString());
    }

    private long insert(String table, Object... kv) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            if (value instanceof Integer) cv.put(key, (Integer) value);
            else if (value instanceof Long) cv.put(key, (Long) value);
            else if (value instanceof byte[]) cv.put(key, (byte[]) value);
            else cv.put(key, (String) value);
        }
        return db.insertOrThrow(table, null, cv);
    }
}
