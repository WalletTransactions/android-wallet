package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.CurrencyRoutes;
import com.example.wallettransactions.Data.DerivedRates;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * openspec currency-conversion-routes, синк: шаги маршрута ({@code currency_conversion}) в push по
 * контракту wallet-api (ключи, порядок после {@code currency_pair}, tombstone) и в pull (оба шага,
 * шаги по частям, ссылка на неизвестную пару откладывается), составной курс после pull.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineCurrencyRoutesTest {

    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String BTC = "c0000000-0000-4000-8000-0000000000b1";
    private static final String EUR = "c0000000-0000-4000-8000-0000000000e1";
    private static final String RUB = "c0000000-0000-4000-8000-0000000000a1";
    private static final String P_BTCEUR = "b0000000-0000-4000-8000-000000000001";
    private static final String P_EURRUB = "b0000000-0000-4000-8000-000000000002";

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_asset", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation", WalletDbHelper.SYNC_RELOAD_SEEN_TABLE,
    };

    private Context context;
    private SQLiteDatabase db;
    private long iconId;
    private long btc, eur, rub;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        new File(context.getNoBackupFilesDir(), PullDeferredRows.FILE_NAME).delete();
        db = Singleton.instance.getWritableDatabase(context);
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        iconId = insert("icon", "name", "test_icon", "image", new byte[]{0}, "icon_type", 1, "uuid", ICON, "dirty", 0);
        btc = currency("Bitcoin", "BTC", "B", BTC);
        eur = currency("Euro", "EUR", "E", EUR);
        rub = currency("Ruble", "RUB", "R", RUB);
        insert("currency_pair", "symbol", "BTCEUR", "base_currency_id", btc, "quote_currency_id", eur,
                "uuid", P_BTCEUR, "dirty", 0);
        insert("currency_pair", "symbol", "EURRUB", "base_currency_id", eur, "quote_currency_id", rub,
                "uuid", P_EURRUB, "dirty", 0);
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
        AppSettings.setPairSymbolResyncDone(context);
        AppSettings.setLedgerRereadPending(context, false);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    private static void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
        Field pairs = Singleton.class.getDeclaredField("currencyPairList");
        pairs.setAccessible(true);
        pairs.set(Singleton.instance, new CurrencyPairList());
    }

    // ── push ────────────────────────────────────────────────────────────────────

    @Test
    public void push_routeSteps_sentAfterPairs_withContractKeys() throws Exception {
        db.execSQL("UPDATE currency_pair SET dirty = 1");
        assertTrue(CurrencyRoutes.create(db, new long[]{btc, eur, rub}).ok());

        Map<String, JSONArray> byTable = SyncEngine.collectDirtyByTable(db, new ArrayList<>());

        List<String> order = new ArrayList<>(byTable.keySet());
        assertTrue("шаги после пар, на которые ссылаются",
                order.indexOf("currency_conversion") > order.indexOf("currency_pair"));
        JSONArray steps = byTable.get("currency_conversion");
        assertEquals(2, steps.length());
        for (int i = 0; i < steps.length(); i++) {
            JSONObject row = steps.getJSONObject(i);
            assertEquals(BTC, row.getString("from_currency_uuid"));
            assertEquals(RUB, row.getString("to_currency_uuid"));
            assertFalse(row.getBoolean("is_reverse"));
            assertFalse(row.getBoolean("deleted"));
            assertEquals(row.getInt("num") == 1 ? P_BTCEUR : P_EURRUB, row.getString("currency_pair_uuid"));
        }
    }

    @Test
    public void push_deletedRoute_sentAsTombstones() throws Exception {
        assertTrue(CurrencyRoutes.create(db, new long[]{btc, eur, rub}).ok());
        db.execSQL("UPDATE currency_conversion SET dirty = 0"); // как после успешного push
        CurrencyRoutes.delete(db, btc, rub);

        JSONArray steps = SyncEngine.collectDirtyByTable(db, new ArrayList<>()).get("currency_conversion");
        assertEquals(2, steps.length());
        for (int i = 0; i < steps.length(); i++) assertTrue(steps.getJSONObject(i).getBoolean("deleted"));
    }

    // ── pull ────────────────────────────────────────────────────────────────────

    @Test
    public void pull_bothSteps_createRoute_andCompositeRateAppears() throws Exception {
        JSONObject ledger = new JSONObject()
                .put("currency_pair_rate", new JSONArray()
                        .put(rateRow("d0000000-0000-4000-8000-000000000001", P_BTCEUR, "2026-01-01", "90000"))
                        .put(rateRow("d0000000-0000-4000-8000-000000000002", P_EURRUB, "2026-01-01", "100")))
                .put("currency_conversion", new JSONArray()
                        .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR))
                        .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB)));
        SyncEngine.applyPage(db, ledger, new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());

        assertEquals(1, CurrencyRoutes.list(db).size());
        assertEquals("строки с сервера не помечены к отправке", 0,
                intQuery("SELECT count(*) FROM currency_conversion WHERE dirty = 1"));
        DerivedRates.ensureFresh(db);
        assertEquals(9000000.0, doubleQuery("SELECT rate FROM v_currency_conversion_rate WHERE from_currency_id = "
                + btc + " AND to_currency_id = " + rub + " AND date <= '2026-02-01' ORDER BY date DESC LIMIT 1"), 1e-6);
    }

    @Test
    public void pull_stepsInSeparateBatches_noRateUntilChainIsComplete() throws Exception {
        JSONObject rates = new JSONObject().put("currency_pair_rate", new JSONArray()
                .put(rateRow("d0000000-0000-4000-8000-000000000001", P_BTCEUR, "2026-01-01", "90000"))
                .put(rateRow("d0000000-0000-4000-8000-000000000002", P_EURRUB, "2026-01-01", "100")));
        SyncEngine.applyPage(db, rates, new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());

        SyncEngine.applyPage(db, new JSONObject().put("currency_conversion", new JSONArray()
                        .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR))),
                new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());
        DerivedRates.ensureFresh(db);
        assertEquals("недостроенная цепочка", 0, CurrencyRoutes.list(db).size());
        assertEquals(0, intQuery("SELECT count(*) FROM currency_rate_derived WHERE from_currency_id = " + btc
                + " AND to_currency_id = " + rub));

        SyncEngine.applyPage(db, new JSONObject().put("currency_conversion", new JSONArray()
                        .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB))),
                new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());
        DerivedRates.ensureFresh(db);
        assertEquals(1, CurrencyRoutes.list(db).size());
        assertTrue(intQuery("SELECT count(*) FROM currency_rate_derived WHERE from_currency_id = " + btc
                + " AND to_currency_id = " + rub) > 0);
    }

    @Test
    public void pull_stepWithUnknownPair_isSkipped() throws Exception {
        JSONObject ledger = new JSONObject().put("currency_conversion", new JSONArray()
                .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, "b0000000-0000-4000-8000-0000000000ff")));
        SyncEngine.applyPage(db, ledger, new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());
        assertEquals(0, intQuery("SELECT count(*) FROM currency_conversion"));
    }

    @Test
    public void pull_tombstonedSteps_removeRouteFromList() throws Exception {
        JSONObject steps = new JSONObject().put("currency_conversion", new JSONArray()
                .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR))
                .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB)));
        SyncEngine.applyPage(db, steps, new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());
        assertEquals(1, CurrencyRoutes.list(db).size());

        JSONObject deleted = new JSONObject().put("currency_conversion", new JSONArray()
                .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR).put("deleted_at", 1700000000000L))
                .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB).put("deleted_at", 1700000000000L)));
        SyncEngine.applyPage(db, deleted, new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());
        assertEquals(0, CurrencyRoutes.list(db).size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static JSONObject stepRow(String uuid, int num, String pairUuid) throws Exception {
        return new JSONObject().put("uuid", uuid)
                .put("from_currency_uuid", BTC).put("to_currency_uuid", RUB)
                .put("num", num).put("currency_pair_uuid", pairUuid).put("is_reverse", false)
                .put("deleted_at", JSONObject.NULL);
    }

    private static JSONObject rateRow(String uuid, String pairUuid, String date, String rate) throws Exception {
        return new JSONObject().put("uuid", uuid).put("currency_pair_uuid", pairUuid)
                .put("date", date).put("rate", rate).put("deleted_at", JSONObject.NULL);
    }

    private long currency(String name, String iso, String symbol, String uuid) {
        return insert("currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol,
                "uuid", uuid, "dirty", 0);
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
