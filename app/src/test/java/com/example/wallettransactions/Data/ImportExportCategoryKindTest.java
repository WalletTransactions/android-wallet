package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * openspec category-kind-income-expense, задача 5.2 (спека category-kind, «Вид в JSON-обмене»):
 * {@code category_kind} у счетов JSON-экспорта и необязательное чтение при импорте.
 */
@RunWith(RobolectricTestRunner.class)
public class ImportExportCategoryKindTest {

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_debt", "counterparty_contact", "counterparty",
            "account_operating", "account_asset", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };
    private static final String USD = "\"currency\":{\"iso\":\"USD\"}";

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        wipe();
    }

    // ── импорт ──────────────────────────────────────────────────────────────

    @Test
    public void import_categoryKind_isStored() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":["
                + "{\"name\":\"Зарплата\",\"category_kind\":1},"
                + "{\"name\":\"Продукты\",\"category_kind\":0},"
                + "{\"name\":\"Подарки\",\"category_kind\":2}]}");

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(1, kindOf("Зарплата"));
        assertEquals(0, kindOf("Продукты"));
        assertEquals(2, kindOf("Подарки"));
    }

    @Test
    public void import_oldFileWithoutField_isBoth_andDoesNotFail() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":[{\"name\":\"Кафе\"},{\"name\":\"Такси\"}]}");

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(2, kindOf("Кафе"));
        assertEquals(2, kindOf("Такси"));
    }

    @Test
    public void import_valueOutOfRange_isBoth() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Мусор\",\"category_kind\":9},{\"name\":\"Минус\",\"category_kind\":-3}]}");

        assertEquals(2, kindOf("Мусор"));
        assertEquals(2, kindOf("Минус"));
    }

    @Test
    public void import_existingCategory_withoutField_keepsItsKind() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Зарплата\",\"category_kind\":1}]}");

        importJson("{\"accounts\":[{\"name\":\"Зарплата\",\"comment\":\"новый комментарий\"}]}");

        assertEquals(1, kindOf("Зарплата"));
    }

    @Test
    public void import_existingCategory_withField_updatesKind() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Подработка\"}]}");

        importJson("{\"accounts\":[{\"name\":\"Подработка\",\"category_kind\":1}]}");

        assertEquals(1, kindOf("Подработка"));
    }

    @Test
    public void import_walletAndSystemCategories_ignoreKind() throws Exception {
        importJson("{\"accounts\":["
                + "{\"name\":\"Кошелёк\"," + USD + ",\"category_kind\":0},"
                + "{\"name\":\"Transfer\",\"category_kind\":1},"
                + "{\"name\":\"Переоценка\",\"system\":\"revaluation\",\"category_kind\":0}]}");

        assertEquals("кошелёк", 2, kindOf("Кошелёк"));
        assertEquals("системная Transfer", 2, kindOf("Transfer"));
        assertEquals("переоценка", 2, kindOf("Переоценка"));
    }

    // ── экспорт ─────────────────────────────────────────────────────────────

    @Test
    public void export_writesKindForRegularCategoriesOnly() throws Exception {
        importJson("{\"accounts\":["
                + "{\"name\":\"Зарплата\",\"category_kind\":1},"
                + "{\"name\":\"Продукты\",\"category_kind\":0},"
                + "{\"name\":\"Подарки\"},"
                + "{\"name\":\"Кошелёк\"," + USD + "},"
                + "{\"name\":\"Переоценка\",\"system\":\"revaluation\"}]}");

        JSONArray accs = WalletExportData.BuildExportJson(db).getJSONArray("accounts");

        assertEquals(1, find(accs, "Зарплата").getInt("category_kind"));
        assertEquals(0, find(accs, "Продукты").getInt("category_kind"));
        assertEquals(2, find(accs, "Подарки").getInt("category_kind"));
        assertFalse("у кошелька ключа нет", find(accs, "Кошелёк").has("category_kind"));
        assertFalse("у переоценки ключа нет", find(accs, "Переоценка").has("category_kind"));
    }

    @Test
    public void export_formatDescription_mentionsTheField() throws Exception {
        JSONObject exported = WalletExportData.BuildExportJson(db);
        assertTrue(exported.toString().contains("category_kind"));
    }

    // ── круг ────────────────────────────────────────────────────────────────

    @Test
    public void roundTrip_exportThenImportOnEmpty_keepsKinds() throws Exception {
        importJson("{\"accounts\":["
                + "{\"name\":\"Зарплата\",\"category_kind\":1},"
                + "{\"name\":\"Продукты\",\"category_kind\":0},"
                + "{\"name\":\"Подарки\",\"category_kind\":2},"
                + "{\"name\":\"Кошелёк\"," + USD + "}]}");
        JSONObject exported = WalletExportData.BuildExportJson(db);

        wipe();
        WalletImportData.ImportResult r = importJson(exported.toString());

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(1, kindOf("Зарплата"));
        assertEquals(0, kindOf("Продукты"));
        assertEquals(2, kindOf("Подарки"));
        assertEquals(2, kindOf("Кошелёк"));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private WalletImportData.ImportResult importJson(String json) throws Exception {
        return WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void wipe() {
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown");
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    private int kindOf(String accountName) {
        Cursor c = db.rawQuery("SELECT category_kind FROM account WHERE name = ? AND deleted_at IS NULL",
                new String[]{accountName});
        try {
            assertTrue("нет счёта " + accountName, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private static JSONObject find(JSONArray accounts, String name) throws Exception {
        for (int i = 0; i < accounts.length(); i++) {
            if (name.equals(accounts.getJSONObject(i).getString("name"))) return accounts.getJSONObject(i);
        }
        throw new AssertionError("в экспорте нет счёта " + name);
    }

    private static void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
    }
}
