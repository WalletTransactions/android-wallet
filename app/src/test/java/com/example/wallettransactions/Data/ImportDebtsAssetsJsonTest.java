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
 * openspec json-debts-assets-format: контрагенты, блоки {@code debt}/{@code asset} и
 * {@code "system": "revaluation"} в файловом JSON — импорт, экспорт и круг «экспорт → импорт».
 */
@RunWith(RobolectricTestRunner.class)
public class ImportDebtsAssetsJsonTest {

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_debt", "counterparty_contact", "counterparty",
            "account_operating", "account_asset", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;

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
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    private WalletImportData.ImportResult importJson(String json) throws Exception {
        return WalletImportData.FillOperationDataFromJson(context,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String USD = "\"currency\":{\"iso\":\"USD\"}";

    @Test
    public void debtWallet_newCounterpartyCreatedOnTheFly() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":[{\"name\":\"Вася · USD\"," + USD
                + ",\"debt\":{\"counterparty\":\"Вася\",\"comment\":\"за билеты\",\"due_date\":\"2026-12-31\"}}]}");

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(1, scalar("SELECT count(*) FROM counterparty WHERE name = 'Вася'"));
        assertEquals("за билеты", stringOf("SELECT comment FROM account_debt"));
        assertEquals("2026-12-31", stringOf("SELECT due_date FROM account_debt"));
        assertEquals("uuid долга = uuid счёта", 1, scalar("SELECT count(*) FROM account_debt ad"
                + " JOIN account a ON a._id = ad._id AND a.uuid = ad.uuid"
                + " JOIN counterparty cp ON cp.uuid = ad.counterparty_uuid WHERE a.name = 'Вася · USD'"));
    }

    @Test
    public void debtWallet_existingCounterpartyMatchedByNormalizedName() throws Exception {
        importJson("{\"counterparties\":[{\"name\":\"Алёна\"}]}");
        importJson("{\"accounts\":[{\"name\":\"Алена · USD\"," + USD
                + ",\"debt\":{\"counterparty\":\" алена \"}}]}");

        assertEquals("второго контрагента нет", 1, scalar("SELECT count(*) FROM counterparty"));
        assertEquals(1, scalar("SELECT count(*) FROM account_debt"));
    }

    @Test
    public void counterparties_updatedNotDuplicated_archivedFlagApplied() throws Exception {
        importJson("{\"counterparties\":[{\"name\":\"Петя\",\"comment\":\"a\"}]}");
        WalletImportData.ImportResult r = importJson(
                "{\"counterparties\":[{\"name\":\"петя\",\"comment\":\"b\",\"archived\":true}]}");

        assertEquals(1, r.imported);
        assertEquals(1, scalar("SELECT count(*) FROM counterparty"));
        assertEquals("b", stringOf("SELECT comment FROM counterparty"));
        assertEquals(1, scalar("SELECT is_archived FROM counterparty"));
    }

    @Test
    public void asset_markedWithTypeAndNetWorth() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":[{\"name\":\"Квартира\"," + USD
                + ",\"asset\":{\"type\":\"real_estate\",\"include_in_net_worth\":false,\"comment\":\"центр\"}}]}");

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());
        assertEquals(WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE, scalar("SELECT asset_type FROM account_asset"));
        assertEquals(0, scalar("SELECT include_in_net_worth FROM account_asset"));
        assertEquals("центр", stringOf("SELECT comment FROM account_asset"));
    }

    @Test
    public void debtAndAsset_together_skippedWithoutPartialAccount() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":[{\"name\":\"X\"," + USD
                + ",\"debt\":{\"counterparty\":\"Z\"},\"asset\":{\"type\":\"other\"}},"
                + "{\"name\":\"Y\"," + USD + "}]}");

        assertEquals(1, r.skipped.size());
        assertTrue(r.skipped.get(0), r.skipped.get(0).startsWith("accounts#0 — "));
        assertEquals("X не заведён", 0, scalar("SELECT count(*) FROM account WHERE name = 'X'"));
        assertEquals("остальное применено", 1, scalar("SELECT count(*) FROM account WHERE name = 'Y'"));
        assertEquals(0, scalar("SELECT count(*) FROM counterparty"));
    }

    @Test
    public void blockOnCategory_badType_badDueDate_oppositeFlag_allSkipped() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Долг · USD\"," + USD + ",\"debt\":{\"counterparty\":\"К\"}}]}");
        WalletImportData.ImportResult r = importJson("{\"accounts\":["
                + "{\"name\":\"Кат\",\"asset\":{\"type\":\"other\"}},"
                + "{\"name\":\"A1\"," + USD + ",\"asset\":{\"type\":\"boat\"}},"
                + "{\"name\":\"D1\"," + USD + ",\"debt\":{\"counterparty\":\"К\",\"due_date\":\"2026-02-30\"}},"
                + "{\"name\":\"Долг · USD\"," + USD + ",\"asset\":{\"type\":\"other\"}}]}");

        assertEquals(r.skipped.toString(), 4, r.skipped.size());
        assertEquals(0, scalar("SELECT count(*) FROM account_asset"));
        assertEquals("кошелёк-долг не стал активом", 1, scalar("SELECT count(*) FROM account_debt"));
        assertEquals(0, scalar("SELECT count(*) FROM account WHERE name IN ('A1','D1')"));
    }

    @Test
    public void revaluation_stamped_andNoSecondCategory() throws Exception {
        importJson("{\"accounts\":[{\"name\":\"Переоценка\",\"system\":\"revaluation\"}]}");
        assertEquals(3, scalar("SELECT system_kind FROM account WHERE name = 'Переоценка'"));

        importJson("{\"accounts\":[{\"name\":\"Другое имя\",\"system\":\"revaluation\"}]}");
        assertEquals("вторая системная не заведена", 0, scalar("SELECT count(*) FROM account WHERE name = 'Другое имя'"));
        assertEquals(1, scalar("SELECT count(*) FROM account WHERE system_kind = 3"));
    }

    @Test
    public void fileWithoutNewFields_importsAsBefore() throws Exception {
        WalletImportData.ImportResult r = importJson("{\"accounts\":[{\"name\":\"Cash\"," + USD + "}]}");

        assertTrue(r.skipped.isEmpty());
        assertEquals(0, scalar("SELECT count(*) FROM counterparty"));
        assertEquals(0, scalar("SELECT count(*) FROM account_debt"));
        assertEquals(0, scalar("SELECT count(*) FROM account_asset"));
    }

    @Test
    public void export_hasAllSections_noContactNoSyncFields() throws Exception {
        JSONObject empty = WalletExportData.BuildExportJson(db);
        for (String k : new String[]{"counterparties", "accounts", "currency_pairs", "rates", "operations"})
            assertTrue(k, empty.has(k));
        assertEquals(0, empty.getJSONArray("counterparties").length());

        importJson("{\"counterparties\":[{\"name\":\"Вася\"}],\"accounts\":[{\"name\":\"Вася · USD\"," + USD
                + ",\"debt\":{\"counterparty\":\"Вася\"}}]}");
        db.execSQL("INSERT INTO counterparty_contact (counterparty_uuid, lookup_key)"
                + " SELECT uuid, 'k1' FROM counterparty");
        String text = WalletExportData.BuildExportJson(db).toString();
        assertFalse(text.contains("lookup_key"));
        assertFalse(text.contains("\"uuid\""));
        assertFalse(text.contains("k1"));
    }

    /** Круг: телефон A → файл → пустой телефон B; контрагенты, долги со сроком, активы, переоценка. */
    @Test
    public void roundTrip_exportThenImportOnEmpty_restoresEverything() throws Exception {
        importJson("{\"counterparties\":[{\"name\":\"Вася\",\"comment\":\"друг\",\"archived\":true}],"
                + "\"accounts\":["
                + "{\"name\":\"Вася · USD\"," + USD + ",\"debt\":{\"counterparty\":\"Вася\",\"comment\":\"c\",\"due_date\":\"2026-12-31\"}},"
                + "{\"name\":\"Авто\"," + USD + ",\"asset\":{\"type\":\"vehicle\",\"include_in_net_worth\":true,\"comment\":\"m\"}},"
                + "{\"name\":\"Переоценка\",\"system\":\"revaluation\"}]}");
        JSONObject exported = WalletExportData.BuildExportJson(db);

        JSONArray accs = exported.getJSONArray("accounts");
        JSONObject debtAcc = byName(accs, "Вася · USD");
        assertEquals("Вася", debtAcc.getJSONObject("debt").getString("counterparty"));
        assertEquals("2026-12-31", debtAcc.getJSONObject("debt").getString("due_date"));
        assertEquals("vehicle", byName(accs, "Авто").getJSONObject("asset").getString("type"));
        assertEquals("revaluation", byName(accs, "Переоценка").getString("system"));

        setUp(); // пустая база — «другой телефон»
        WalletImportData.ImportResult r = importJson(exported.toString());
        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty());

        assertEquals(1, scalar("SELECT count(*) FROM counterparty WHERE name = 'Вася' AND is_archived = 1 AND comment = 'друг'"));
        assertEquals("2026-12-31", stringOf("SELECT due_date FROM account_debt"));
        assertEquals(WalletContract.AccountAssetEntry.TYPE_VEHICLE, scalar("SELECT asset_type FROM account_asset"));
        assertEquals("m", stringOf("SELECT comment FROM account_asset"));
        assertEquals(3, scalar("SELECT system_kind FROM account WHERE name = 'Переоценка'"));
    }

    private static JSONObject byName(JSONArray arr, String name) throws Exception {
        for (int i = 0; i < arr.length(); i++)
            if (name.equals(arr.getJSONObject(i).getString("name"))) return arr.getJSONObject(i);
        throw new AssertionError("no account " + name);
    }

    private int scalar(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private String stringOf(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }
}
