package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletDbHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;

/**
 * openspec currency-ui-tweaks: {@code show_in_equivalent} в push/pull и {@code is_shared}
 * из {@code ledger_id} (design D1, D6). Контракт сервера — wallet-api openapi.yaml,
 * {@code /sync/pull}: строка — полный дамп колонок, включая {@code ledger_id}.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineCurrencyFlagsTest {

    private static final String ICON_UUID = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String CUR_SHARED = "1a000000-0000-4000-8000-000000000001";
    private static final String CUR_OWN = "1a000000-0000-4000-8000-000000000002";
    private static final String PAIR_UUID = "2b000000-0000-4000-8000-000000000003";
    private static final String LEDGER = "3c000000-0000-4000-8000-000000000004";

    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        ContentValues icon = new ContentValues();
        icon.put("name", "test_icon");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON_UUID);
        db.insertOrThrow("icon", null, icon);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private static JSONObject currencyRow(String uuid, Object ledgerId, String iso) throws Exception {
        return new JSONObject()
                .put("uuid", uuid)
                .put("ledger_id", ledgerId)
                .put("name", "Test " + iso)
                .put("iso_code", iso)
                .put("icon_uuid", ICON_UUID)
                .put("symbol", iso)
                .put("precision", 2)
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    @Test
    public void applyCurrency_sharedFromNullLedgerId_ownFromLedgerId_cleanAfterPull() throws Exception {
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrency(db, currencyRow(CUR_SHARED, JSONObject.NULL, "TSA")
                .put("show_in_equivalent", false), log);
        SyncEngine.applyCurrency(db, currencyRow(CUR_OWN, LEDGER, "TSB")
                .put("show_in_equivalent", true), log);

        int[] shared = row("currency", CUR_SHARED, "is_shared", "show_in_equivalent", "dirty");
        assertEquals(1, shared[0]);
        assertEquals(0, shared[1]);
        assertEquals(0, shared[2]);
        int[] own = row("currency", CUR_OWN, "is_shared", "show_in_equivalent", "dirty");
        assertEquals(0, own[0]);
        assertEquals(1, own[1]);
        assertEquals(0, own[2]);

        // Повторный pull той же строки (update, а не insert) — тоже без dirty.
        SyncEngine.applyCurrency(db, currencyRow(CUR_SHARED, JSONObject.NULL, "TSA")
                .put("show_in_equivalent", false), log);
        assertEquals(0, row("currency", CUR_SHARED, "dirty")[0]);
        assertEquals(1, row("currency", CUR_SHARED, "is_shared")[0]);
    }

    @Test
    public void applyCurrency_rowWithoutFlag_keepsLocalFlag() throws Exception {
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrency(db, currencyRow(CUR_OWN, LEDGER, "TSB")
                .put("show_in_equivalent", false), log);
        assertEquals(0, row("currency", CUR_OWN, "show_in_equivalent")[0]);

        // Старый сервер: ключа нет — локальный 0 не сбрасывается в DEFAULT/1.
        SyncEngine.applyCurrency(db, currencyRow(CUR_OWN, LEDGER, "TSB"), log);
        assertEquals(0, row("currency", CUR_OWN, "show_in_equivalent")[0]);
        assertEquals(0, row("currency", CUR_OWN, "dirty")[0]);
    }

    @Test
    public void applyCurrencyPair_isSharedFromLedgerId_clean() throws Exception {
        SyncLog log = new SyncLog();
        SyncEngine.applyCurrency(db, currencyRow(CUR_SHARED, JSONObject.NULL, "TSA"), log);
        SyncEngine.applyCurrency(db, currencyRow(CUR_OWN, JSONObject.NULL, "TSB"), log);
        JSONObject pair = new JSONObject()
                .put("uuid", PAIR_UUID)
                .put("ledger_id", JSONObject.NULL)
                .put("symbol", "TSATSB")
                .put("base_currency_uuid", CUR_SHARED)
                .put("quote_currency_uuid", CUR_OWN)
                .put("precision", 4)
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
        SyncEngine.applyCurrencyPair(db, pair, log);
        int[] p = row("currency_pair", PAIR_UUID, "is_shared", "dirty");
        assertEquals(1, p[0]);
        assertEquals(0, p[1]);

        SyncEngine.applyCurrencyPair(db, pair.put("ledger_id", LEDGER), log);
        p = row("currency_pair", PAIR_UUID, "is_shared", "dirty");
        assertEquals(0, p[0]);
        assertEquals(0, p[1]);
    }

    @Test
    public void push_currencyRowCarriesBooleanFlag() throws Exception {
        long iconId = idOf("icon", ICON_UUID);
        ContentValues cv = new ContentValues();
        cv.put("name", "Test coin");
        cv.put("iso_code", "TSC");
        cv.put("icon_id", iconId);
        cv.put("symbol", "T");
        cv.put("show_in_equivalent", 0);
        cv.put("uuid", CUR_OWN);
        db.insertOrThrow("currency", null, cv);

        JSONArray rows = SyncEngine.collectDirtyCurrencies(db);
        JSONObject pushed = null;
        for (int i = 0; i < rows.length(); i++) {
            if (CUR_OWN.equals(rows.getJSONObject(i).getString("uuid"))) pushed = rows.getJSONObject(i);
        }
        assertTrue(pushed != null);
        assertTrue(pushed.get("show_in_equivalent") instanceof Boolean);
        assertFalse(pushed.getBoolean("show_in_equivalent"));
        assertEquals(ICON_UUID, pushed.getString("icon_uuid"));
    }

    /** 2.4: повторный pull уже известной иконки не перекачивает байты (api = null — любой вызов упал бы). */
    @Test
    public void prefetch_skipsKnownIcons() throws Exception {
        JSONArray icons = new JSONArray().put(new JSONObject()
                .put("uuid", ICON_UUID)
                .put("content_sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        Map<String, byte[]> blobs = SyncEngine.prefetchNewIconBlobs(db, icons, null);
        assertTrue(blobs.isEmpty());
    }

    private long idOf(String table, String uuid) {
        Cursor c = db.rawQuery("SELECT _id FROM " + table + " WHERE uuid=?", new String[]{uuid});
        try {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private int[] row(String table, String uuid, String... columns) {
        Cursor c = db.rawQuery("SELECT " + String.join(", ", columns) + " FROM " + table + " WHERE uuid=?",
                new String[]{uuid});
        try {
            assertTrue(table + " " + uuid + " не найдена", c.moveToFirst());
            int[] out = new int[columns.length];
            for (int i = 0; i < columns.length; i++) out[i] = c.getInt(i);
            return out;
        } finally {
            c.close();
        }
    }
}
