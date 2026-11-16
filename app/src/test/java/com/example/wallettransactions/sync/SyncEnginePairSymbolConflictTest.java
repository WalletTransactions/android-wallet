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
import com.example.wallettransactions.Data.WalletDbHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * openspec sync-pair-symbol-adopt: общая пара и пара кошелька с одним символом (на сервере два
 * уникальных индекса, на телефоне один). Раньше пара кошелька молча терялась, а её курсы вечно
 * висели в отложенных (воспроизведено на qa 2026-11-16: 31 пара, 135 курсов).
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEnginePairSymbolConflictTest {

    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String CUR_A = "1a000000-0000-4000-8000-000000000001";
    private static final String CUR_B = "1a000000-0000-4000-8000-000000000002";
    private static final String SHARED_PAIR = "2b000000-0000-4000-8000-000000000001";
    private static final String LEDGER_PAIR = "2b000000-0000-4000-8000-000000000002";
    private static final String LEDGER = "3c000000-0000-4000-8000-000000000004";

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        ContentValues icon = new ContentValues();
        icon.put("name", "test_icon");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON);
        db.insertOrThrow("icon", null, icon);
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrency(db, currencyRow(CUR_A, "TSA"), log);
        SyncEngine.applyCurrency(db, currencyRow(CUR_B, "TSB"), log);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private static JSONObject currencyRow(String uuid, String iso) throws Exception {
        return new JSONObject().put("uuid", uuid).put("ledger_id", JSONObject.NULL)
                .put("name", "Test " + iso).put("iso_code", iso).put("icon_uuid", ICON)
                .put("symbol", iso).put("precision", 2)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject pairRow(String uuid, Object ledgerId) throws Exception {
        return new JSONObject().put("uuid", uuid).put("ledger_id", ledgerId)
                .put("symbol", "TSATSB").put("base_currency_uuid", CUR_A)
                .put("quote_currency_uuid", CUR_B).put("precision", 4)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject rateRow(String uuid, String pairUuid, String date, String rate) throws Exception {
        return new JSONObject().put("uuid", uuid).put("ledger_id", LEDGER)
                .put("currency_pair_uuid", pairUuid).put("date", date).put("rate", rate)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    @Test
    public void ledgerPairReplacesSyncedSharedPair_keepsIdAndRates() throws Exception {
        SyncLog log = new SyncLog();
        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log));
        long id = idOf(SHARED_PAIR);
        ContentValues rate = new ContentValues();
        rate.put("currency_pair_id", id);
        rate.put("date", "2020-01-01");
        rate.put("rate", "70.5");
        rate.put("uuid", "4d000000-0000-4000-8000-000000000001");
        rate.put("dirty", 0);
        db.insertOrThrow("currency_pair_rate", null, rate);

        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log));

        assertEquals(1, count("currency_pair", "symbol='TSATSB' AND deleted_at IS NULL"));
        assertEquals(id, idOf(LEDGER_PAIR));
        assertEquals(0, count("currency_pair", "uuid='" + SHARED_PAIR + "'"));
        assertEquals(0, intOf("currency_pair", LEDGER_PAIR, "is_shared"));
        assertEquals(0, intOf("currency_pair", LEDGER_PAIR, "dirty"));
        assertEquals(1, count("currency_pair_rate", "currency_pair_id=" + id));
    }

    @Test
    public void sharedPairYieldsToSyncedLedgerPair_notFailed() throws Exception {
        SyncLog log = new SyncLog();
        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log));

        // true — строка обработана, а не отложена
        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log));

        assertEquals(1, count("currency_pair", "symbol='TSATSB' AND deleted_at IS NULL"));
        assertEquals(1, count("currency_pair", "uuid='" + LEDGER_PAIR + "'"));
        assertEquals(0, count("currency_pair", "uuid='" + SHARED_PAIR + "'"));
    }

    @Test
    public void arrivalOrderDoesNotMatter() throws Exception {
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log);
        SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log);
        String a = snapshot();

        context.deleteDatabase("wallet.db");
        helper.close();
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        ContentValues icon = new ContentValues();
        icon.put("name", "test_icon");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON);
        db.insertOrThrow("icon", null, icon);
        SyncEngine.applyCurrency(db, currencyRow(CUR_A, "TSA"), log);
        SyncEngine.applyCurrency(db, currencyRow(CUR_B, "TSB"), log);
        SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log);
        SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log);

        assertEquals(a, snapshot());
    }

    @Test
    public void ledgerPairReplacesUnsentLocalPair_outsideConnection() throws Exception {
        ContentValues own = new ContentValues();
        own.put("symbol", "TSATSB");
        own.put("base_currency_id", idOfCurrency(CUR_A));
        own.put("quote_currency_id", idOfCurrency(CUR_B));
        own.put("precision", 5);
        own.put("uuid", "5e000000-0000-4000-8000-000000000001");
        db.insertOrThrow("currency_pair", null, own); // dirty = 1 по умолчанию
        assertFalse(SyncEngine.adoptLocalByKey);

        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), new SyncLog()));

        assertEquals(1, count("currency_pair", "symbol='TSATSB' AND deleted_at IS NULL"));
        assertEquals(0, intOf("currency_pair", LEDGER_PAIR, "dirty"));
    }

    @Test
    public void deferredRateAppliesAfterPairReplaced() throws Exception {
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log);

        // Пара кошелька и её курс приходят одной страницей — курс находит родителя по новому uuid.
        JSONObject ledger = new JSONObject()
                .put("currency_pair", new JSONArray().put(pairRow(LEDGER_PAIR, LEDGER)))
                .put("currency_pair_rate", new JSONArray()
                        .put(rateRow("4d000000-0000-4000-8000-000000000002", LEDGER_PAIR, "2020-02-01", "71.5")));
        PullDeferredRows deferred = PullDeferredRows.inMemory();
        SyncEngine.applyPage(db, ledger, new JSONObject(), null, log, deferred);

        assertTrue(deferred.isEmpty());
        assertEquals(1, count("currency_pair_rate", "date='2020-02-01'"));
    }

    @Test
    public void unresolvableConflict_staysDeferred() throws Exception {
        // Живая пара с тем же символом, но другой индекс не даёт заменить: валюты пары нет →
        // пара откладывается, а не объявляется обработанной.
        JSONObject bad = pairRow(LEDGER_PAIR, LEDGER).put("base_currency_uuid", "9f000000-0000-4000-8000-0000000000ff");
        JSONObject ledger = new JSONObject().put("currency_pair", new JSONArray().put(bad));
        PullDeferredRows deferred = PullDeferredRows.inMemory();
        SyncEngine.applyPage(db, ledger, new JSONObject(), null, new SyncLog(), deferred);
        assertFalse(deferred.isEmpty());
    }

    @Test
    public void pairSymbolResyncFlag_isOneShot() {
        assertFalse(AppSettings.isPairSymbolResyncDone(context));
        AppSettings.setPairSymbolResyncDone(context);
        assertTrue(AppSettings.isPairSymbolResyncDone(context));
    }

    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        Cursor c = db.rawQuery("SELECT uuid, symbol, is_shared, dirty FROM currency_pair"
                + " WHERE symbol = 'TSATSB' AND deleted_at IS NULL ORDER BY uuid", null);
        try {
            while (c.moveToNext()) {
                sb.append(c.getString(0)).append('|').append(c.getString(1)).append('|')
                        .append(c.getInt(2)).append('|').append(c.getInt(3)).append(';');
            }
        } finally {
            c.close();
        }
        return sb.toString();
    }

    private long idOf(String pairUuid) {
        Cursor c = db.rawQuery("SELECT _id FROM currency_pair WHERE uuid=?", new String[]{pairUuid});
        try {
            assertTrue("пара " + pairUuid + " не найдена", c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private long idOfCurrency(String uuid) {
        Cursor c = db.rawQuery("SELECT _id FROM currency WHERE uuid=?", new String[]{uuid});
        try {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private int count(String table, String where) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private int intOf(String table, String uuid, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE uuid=?", new String[]{uuid});
        try {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
