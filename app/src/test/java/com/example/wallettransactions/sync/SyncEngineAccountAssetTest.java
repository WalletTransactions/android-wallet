package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletDbCRUD;
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
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * openspec capital-assets, синк: {@code account_asset} в push (ключи по контракту wallet-api)
 * и pull, переклейка uuid при 409 и однократный перечит потока ledger после шага v88.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineAccountAssetTest {

    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String CURRENCY = "c0000000-0000-4000-8000-000000000001";
    private static final String FLAT = "a1000000-0000-4000-8000-000000000001";
    private static final String LEDGER = "3c000000-0000-4000-8000-000000000004";

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_asset", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation", WalletDbHelper.SYNC_RELOAD_SEEN_TABLE,
    };

    private Context context;
    private SQLiteDatabase db;
    private long iconId;
    private long currencyId;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        new File(context.getNoBackupFilesDir(), PullDeferredRows.FILE_NAME).delete();
        db = Singleton.instance.getWritableDatabase(context);
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        ContentValues icon = new ContentValues();
        icon.put("name", "test_icon");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON);
        icon.put("dirty", 0);
        iconId = db.insertOrThrow("icon", null, icon);
        ContentValues cur = new ContentValues();
        cur.put("name", "Рубль");
        cur.put("iso_code", "RUB");
        cur.put("icon_id", iconId);
        cur.put("symbol", "₽");
        cur.put("uuid", CURRENCY);
        cur.put("dirty", 0);
        currencyId = db.insertOrThrow("currency", null, cur);
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
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

    // ── 2.1 push и pull ─────────────────────────────────────────────────────

    @Test
    public void push_dirtyAsset_sentAfterAccountOperating_withContractKeys() throws Exception {
        int flat = (int) WalletDbCRUD.InsertOperatingAccount(db, "Квартира", "", (int) iconId, (int) currencyId);
        WalletDbCRUD.SetAccountAsset(db, flat, 2, false);
        String uuid = string("SELECT uuid FROM account WHERE _id = " + flat);

        Map<String, JSONArray> byTable = SyncEngine.collectDirtyByTable(db, new ArrayList<>());

        List<String> order = new ArrayList<>(byTable.keySet());
        assertTrue(order.indexOf("account_asset") > order.indexOf("account_operating"));
        JSONObject row = byTable.get("account_asset").getJSONObject(0);
        assertEquals(uuid, row.getString("uuid"));
        assertEquals(2, row.getInt("asset_type"));
        assertFalse(row.getBoolean("include_in_net_worth"));
        assertEquals("", row.getString("comment"));
        assertFalse(row.getBoolean("deleted"));
    }

    @Test
    public void pull_appliesAssetToAccountWithSameUuid_defersWithoutAccount() throws Exception {
        JSONObject ledger = new JSONObject()
                .put("account", new JSONArray().put(accountRow(FLAT, "Квартира")))
                .put("account_operating", new JSONArray().put(new JSONObject()
                        .put("uuid", FLAT).put("currency_uuid", CURRENCY).put("deleted_at", JSONObject.NULL)))
                .put("account_asset", new JSONArray()
                        .put(assetRow(FLAT, 1, true))
                        .put(assetRow("ffff0000-0000-4000-8000-000000000009", 3, true)));
        PullDeferredRows deferred = PullDeferredRows.inMemory();

        java.util.Set<String> touched = SyncEngine.applyPage(db, ledger, new JSONObject(), null, new SyncLog(), deferred);

        assertTrue(touched.contains("account_asset"));
        long accountId = number("SELECT _id FROM account WHERE uuid = '" + FLAT + "'");
        assertEquals(accountId, number("SELECT _id FROM account_asset WHERE uuid = '" + FLAT + "'"));
        assertEquals(1, number("SELECT asset_type FROM account_asset"));
        assertEquals(0, number("SELECT dirty FROM account_asset"));
        assertEquals("строка без счёта отложена, а не потеряна и не уронила страницу", 1, number(
                "SELECT count(*) FROM account_asset") );
        assertFalse(deferred.isEmpty());
    }

    // ── 2.2 переклейка при 409 duplicate_name ───────────────────────────────

    @Test
    public void conflict_rekeysAccountOperatingAndAsset() throws Exception {
        int flat = (int) WalletDbCRUD.InsertOperatingAccount(db, "Квартира", "", (int) iconId, (int) currencyId);
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true);
        String ours = string("SELECT uuid FROM account WHERE _id = " + flat);
        String survivor = "b2000000-0000-4000-8000-000000000002";

        SyncEngine.resolveAccountConflicts(db, new JSONArray().put(new JSONObject()
                .put("table", "account").put("reason", "duplicate_name")
                .put("uuid", ours).put("existing_uuid", survivor)));

        assertEquals(survivor, string("SELECT uuid FROM account WHERE _id = " + flat));
        assertEquals(survivor, string("SELECT uuid FROM account_operating WHERE _id = " + flat));
        assertEquals(survivor, string("SELECT uuid FROM account_asset WHERE _id = " + flat));
    }

    // ── 2.3 однократный перечит ledger ──────────────────────────────────────

    @Test
    public void reread_pullsLedgerFromZero_keepsReferenceAndLedgerId_onlyOnce() throws Exception {
        AppSettings.setSyncLedgerId(context, LEDGER);
        AppSettings.setLedgerSyncSeq(context, 40L);
        AppSettings.setReferenceSyncSeq(context, 5L);
        AppSettings.setLedgerRereadPending(context, true);
        localSyncedAccount(); // синкнутые строки есть — это не повод для загрузки с вычисткой

        ScriptedApi api = new ScriptedApi(context);
        JSONObject page = new JSONObject()
                .put("account_asset", new JSONArray().put(assetRow(FLAT, 1, true)));
        api.steps.add((l, r) -> ScriptedApi.page(45, page, r));
        SyncEngine.pullAll(db, api, context, new SyncLog());

        assertEquals(0L, api.calls.get(0)[0]);
        assertEquals("reference не тронут", 5L, api.calls.get(0)[1]);
        assertEquals(LEDGER, AppSettings.getSyncLedgerId(context));
        assertFalse(AppSettings.isLedgerRereadPending(context));
        assertFalse("перечит — не загрузка с вычисткой", AppSettings.isFullReloadPending(context));
        assertEquals(45L, AppSettings.getLedgerSyncSeq(context));
        assertEquals("актив, пропущенный старой версией, применён", 1, number("SELECT count(*) FROM account_asset"));

        ScriptedApi again = new ScriptedApi(context);
        SyncEngine.pullAll(db, again, context, new SyncLog());
        assertEquals("второй проход — с сохранённой позиции", 45L, again.calls.get(0)[0]);
    }

    @Test
    public void reread_interrupted_continuesFromLastAppliedPage() throws Exception {
        AppSettings.setSyncLedgerId(context, LEDGER);
        AppSettings.setLedgerSyncSeq(context, 40L);
        AppSettings.setReferenceSyncSeq(context, 5L);
        AppSettings.setLedgerRereadPending(context, true);
        localSyncedAccount();

        ScriptedApi api = new ScriptedApi(context);
        api.steps.add((l, r) -> ScriptedApi.page(10, new JSONObject()
                .put("account_asset", new JSONArray().put(assetRow(FLAT, 1, true))), r));
        api.steps.add((l, r) -> { throw new IOException("нет сети"); });
        try {
            SyncEngine.pullAll(db, api, context, new SyncLog());
            fail("ожидался обрыв");
        } catch (IOException expected) {
            // ok
        }
        assertEquals(10L, AppSettings.getLedgerSyncSeq(context));
        assertFalse(AppSettings.isLedgerRereadPending(context));

        ScriptedApi next = new ScriptedApi(context);
        next.steps.add((l, r) -> ScriptedApi.page(12, new JSONObject()
                .put("account_asset", new JSONArray().put(assetRow(FLAT, 1, true))), r));
        SyncEngine.pullAll(db, next, context, new SyncLog());
        assertEquals(10L, next.calls.get(0)[0]);
        assertEquals("upsert по uuid — без дублей", 1, number("SELECT count(*) FROM account_asset"));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private void localSyncedAccount() {
        ContentValues acc = new ContentValues();
        acc.put("name", "Квартира");
        acc.put("comment", "");
        acc.put("icon_id", iconId);
        acc.put("kind", 1);
        acc.put("uuid", FLAT);
        acc.put("dirty", 0);
        long id = db.insertOrThrow("account", null, acc);
        ContentValues op = new ContentValues();
        op.put("_id", id);
        op.put("currency_id", currencyId);
        op.put("uuid", FLAT);
        op.put("dirty", 0);
        db.insertOrThrow("account_operating", null, op);
    }

    private static JSONObject accountRow(String uuid, String name) throws org.json.JSONException {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", 1)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject assetRow(String uuid, int type, boolean include) throws org.json.JSONException {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("asset_type", type)
                .put("include_in_net_worth", include).put("comment", "")
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private long number(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private String string(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    /** Сценарий ответов pull: по одному на вызов; IOException — обрыв сети. */
    private static final class ScriptedApi extends SyncApiClient {
        interface Step {
            PullResult answer(long ledgerSeq, long referenceSeq) throws IOException, org.json.JSONException;
        }

        final Deque<Step> steps = new ArrayDeque<>();
        final List<long[]> calls = new ArrayList<>();

        ScriptedApi(Context ctx) {
            super(ctx, "test-token");
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId) throws IOException {
            calls.add(new long[]{ledgerSeq, referenceSeq});
            Step step = steps.poll();
            if (step == null) return empty(ledgerSeq, referenceSeq);
            try {
                return step.answer(ledgerSeq, referenceSeq);
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }

        static PullResult empty(long ledgerSeq, long referenceSeq) {
            return new PullResult(new PullStream(ledgerSeq, new JSONObject(), false, LEDGER),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }

        static PullResult page(long nextSeq, JSONObject ledgerChanges, long referenceSeq) {
            return new PullResult(new PullStream(nextSeq, ledgerChanges, false, LEDGER),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }
    }
}
