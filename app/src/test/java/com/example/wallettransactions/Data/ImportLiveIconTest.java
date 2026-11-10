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
 * android-wallet#50 (openspec fix-icon-reset-substitutes), задача 3.2: импорт счетов ставит
 * только живые иконки. Раньше не найденная иконка давала {@code icon_id = 1}, а у валюты
 * {@code LEFT JOIN icon} без {@code deleted_at} — 22 счёта владельца на удалённой
 * {@code Groceries} ({@code _id = 1}).
 */
@RunWith(RobolectricTestRunner.class)
public class ImportLiveIconTest {

    /** Те же таблицы, что ResetDataActivity.ALL_TABLES. */
    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;
    private long deletedGroceries;
    private long unknown;
    private long coffee;

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
        // Лёгкие иконки вместо PNG сида — см. HardWipeCacheInvalidationTest про CursorWindow.
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");
        deletedGroceries = icon("Groceries", 1_000L);
        unknown = icon("unknown", null);
        coffee = icon("Coffee", null);
        assertEquals("как у владельца: удалённая иконка под _id = 1", 1, deletedGroceries);
    }

    /**
     * Сценарий спеки «Иконки нет среди живых». Новая валюта из импорта здесь не проверяется:
     * её INSERT без {@code symbol} падает на NOT NULL независимо от иконки (не эта задача).
     */
    @Test
    public void deletedIcon_importedAsUnknown() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":["
                + "{\"name\":\"T wallet\",\"icon\":\"Groceries\"},"
                + "{\"name\":\"T missing\",\"icon\":\"Нет такой\"}]}");

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(unknown, iconIdOf("account", "T wallet"));
        assertEquals(unknown, iconIdOf("account", "T missing"));
    }

    @Test
    public void liveIcon_importedAsIs() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"T coffee\",\"icon\":\"Coffee\"}]}");

        assertEquals(coffee, iconIdOf("account", "T coffee"));
    }

    private WalletImportData.ImportResult importJson(String json) throws Exception {
        return WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private long icon(String name, Long deletedAt) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name);
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        if (deletedAt != null) cv.put(WalletContract.IconEntry.COLUMN_DELETED_AT, deletedAt);
        return db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    private long iconIdOf(String table, String name) {
        return scalar("SELECT icon_id FROM " + table + " WHERE name = '" + name + "' AND deleted_at IS NULL");
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
}
