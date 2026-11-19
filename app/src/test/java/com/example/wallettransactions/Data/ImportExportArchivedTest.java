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
 * Поле {@code archived} у счетов JSON-обмена: необязательное чтение при импорте (нет поля —
 * архивность не трогаем) и запись в экспорт только для архивных.
 */
@RunWith(RobolectricTestRunner.class)
public class ImportExportArchivedTest {

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

    @Test
    public void import_archivedTrue_marksNewWallet() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":["
                + "{\"name\":\"Старый\"," + USD + ",\"archived\":true},"
                + "{\"name\":\"Живой\"," + USD + ",\"archived\":false},"
                + "{\"name\":\"Без поля\"," + USD + "}]}");

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(1, archivedOf("Старый"));
        assertEquals(0, archivedOf("Живой"));
        assertEquals(0, archivedOf("Без поля"));
    }

    @Test
    public void import_existingWallet_withField_changesArchive() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + "}]}");

        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + ",\"archived\":true}]}");
        assertEquals(1, archivedOf("Счёт"));

        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + ",\"archived\":false}]}");
        assertEquals(0, archivedOf("Счёт"));
    }

    @Test
    public void import_existingArchivedWallet_withoutField_staysArchived() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + ",\"archived\":true}]}");

        importJson("{\"accounts\":[{\"name\":\"Счёт\",\"comment\":\"новый комментарий\"," + USD + "}]}");

        assertEquals(1, archivedOf("Счёт"));
    }

    @Test
    public void export_writesArchivedOnlyForArchived() throws Exception {
        importJson("{\"accounts\":["
                + "{\"name\":\"Старый\"," + USD + ",\"archived\":true},"
                + "{\"name\":\"Живой\"," + USD + "}]}");

        JSONArray accs = WalletExportData.BuildExportJson(db).getJSONArray("accounts");

        assertTrue(find(accs, "Старый").getBoolean("archived"));
        assertFalse("у неархивного ключа нет", find(accs, "Живой").has("archived"));
    }

    @Test
    public void roundTrip_exportThenImportOnEmpty_keepsArchive() throws Exception {
        importJson("{\"accounts\":["
                + "{\"name\":\"Старый\"," + USD + ",\"archived\":true},"
                + "{\"name\":\"Живой\"," + USD + "}]}");
        JSONObject exported = WalletExportData.BuildExportJson(db);

        wipe();
        WalletImportData.ImportResult r = importJson(exported.toString());

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(1, archivedOf("Старый"));
        assertEquals(0, archivedOf("Живой"));
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

    private int archivedOf(String accountName) {
        Cursor c = db.rawQuery("SELECT is_archived FROM account WHERE name = ? AND deleted_at IS NULL",
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
