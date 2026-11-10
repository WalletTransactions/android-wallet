package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * android-wallet#54 (и #29): импорт счёта с валютой, которой нет в базе, падал
 * {@code NOT NULL constraint failed: currency.symbol} — формат не несёт символа, а INSERT не
 * ставил колонку. Раньше все валюты файла были в прежнем сиде, а в новом наборе их 15.
 * Валюта «уже есть» — только среди живых.
 */
@RunWith(RobolectricTestRunner.class)
public class ImportNewCurrencyTest {

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_operating", "account_asset", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;
    private long unknownIcon;

    @Before
    public void setUp() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown");
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        unknownIcon = db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    @Test
    public void json_newCurrency_nativeSymbol_andWalletCreated() throws Exception {
        WalletImportData.ImportResult r = WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(("{\"accounts\":["
                        + "{\"name\":\"T usd\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}]}")
                        .getBytes(StandardCharsets.UTF_8)));

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals("$", stringOf("SELECT symbol FROM currency WHERE iso_code = 'USD'"));
        assertEquals(2, scalar("SELECT precision FROM currency WHERE iso_code = 'USD'"));
        assertEquals("кошелёк в новой валюте", 1, scalar(
                "SELECT count(*) FROM account_operating ao JOIN account a ON a._id = ao._id"
                        + " JOIN currency c ON c._id = ao.currency_id"
                        + " WHERE a.name = 'T usd' AND c.iso_code = 'USD'"));
    }

    @Test
    public void csv_newCurrency_nativeSymbol_andWalletCreated() throws Exception {
        int n = WalletImportData.FillAccountDataFromFile(context,
                new ByteArrayInputStream(("AccountName,AccountComment,AccountIconName,"
                        + "CurrencyName,CurrencyIso,CurrencyIconName\n"
                        + "T eur,c,unknown,Euro,EUR,unknown\n").getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, n);
        assertEquals("€", stringOf("SELECT symbol FROM currency WHERE iso_code = 'EUR'"));
        assertEquals(1, scalar("SELECT count(*) FROM account_operating WHERE currency_id = "
                + "(SELECT _id FROM currency WHERE iso_code = 'EUR')"));
    }

    @Test
    public void existingLiveCurrency_notDuplicated() throws Exception {
        String json = "{\"accounts\":[{\"name\":\"T one\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}},"
                + "{\"name\":\"T two\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}]}";
        WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, scalar("SELECT count(*) FROM currency WHERE iso_code = 'USD' AND deleted_at IS NULL"));
        assertEquals(2, scalar("SELECT count(*) FROM account_operating"));
    }

    /** Родной символ и точность по ISO; неизвестный код — сам код, точность 2. */
    @Test
    public void symbolAndPrecision_nativeWithIsoFallback() {
        assertEquals("₽", WalletImportData.currencySymbolFor("RUB"));
        assertEquals("₴", WalletImportData.currencySymbolFor("uah"));
        assertEquals("£", WalletImportData.currencySymbolFor("GBP"));
        assertEquals("ZZZ", WalletImportData.currencySymbolFor("ZZZ"));
        assertEquals("", WalletImportData.currencySymbolFor(null));
        assertEquals(0, WalletImportData.currencyPrecisionFor("JPY"));
        assertEquals(2, WalletImportData.currencyPrecisionFor("EUR"));
        assertEquals(2, WalletImportData.currencyPrecisionFor("ZZZ"));
    }

    /** Удалённая валюта с тем же кодом не блокирует вставку и не подхватывается кошельком. */
    @Test
    public void deletedCurrencySameIso_newOneCreated_walletUsesLive() throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, "Old dollar");
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "USD");
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "$");
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, unknownIcon);
        cv.put(WalletContract.CurrencyEntry.COLUMN_UUID, "dead-usd");
        cv.put(WalletContract.CurrencyEntry.COLUMN_DELETED_AT, 1_000L);
        long deletedId = db.insertOrThrow(WalletContract.CurrencyEntry.TABLE_NAME, null, cv);

        WalletImportData.ImportResult r = WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(("{\"accounts\":["
                        + "{\"name\":\"T usd\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}]}")
                        .getBytes(StandardCharsets.UTF_8)));

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        long liveId = scalar("SELECT _id FROM currency WHERE iso_code = 'USD' AND deleted_at IS NULL");
        assertTrue("новая живая валюта, не удалённая", liveId != deletedId);
        assertEquals("кошелёк на живой валюте", liveId, scalar(
                "SELECT ao.currency_id FROM account_operating ao JOIN account a ON a._id = ao._id"
                        + " WHERE a.name = 'T usd'"));
    }

    private long scalar(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private String stringOf(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }
}
