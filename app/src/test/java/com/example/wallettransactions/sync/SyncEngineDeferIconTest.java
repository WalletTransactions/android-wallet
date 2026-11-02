package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Data.WalletContract;
import com.example.wallettransactions.Data.WalletDbHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * openspec server-managed-icons, задачи 2.1 и 2.3: push без иконок и откладывание строк,
 * у своей иконки которых нет заместителя с сервера.
 * <p>
 * Раньше (инцидент 2026-10-11) откладывались строки из-за незалитой иконки; заливки больше
 * нет, {@link SyncEngine#deferDependents} откладывает строки без иконки-заместителя.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineDeferIconTest {

    private static final String[] SYNC_TABLES = {
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link",
    };

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long currencyId;

    /** Транспорт без сети: push отвечает 200 и запоминает тело, к /sync/blobs и pull ходить нельзя. */
    private static final class RecordingApi extends SyncApiClient {
        final List<JSONObject> pushed = new ArrayList<>();
        int blobRequests;

        RecordingApi(Context ctx) {
            super(ctx, "test-token");
        }

        @Override
        HttpResult push(String batchId, JSONObject changes) {
            pushed.add(changes);
            return new HttpResult(200, new JSONObject());
        }

        @Override
        byte[] downloadBlob(String sha256Hex) {
            blobRequests++;
            throw new AssertionError("push не должен обращаться к /sync/blobs");
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit) {
            throw new AssertionError("pull в этом тесте не вызывается");
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        // Сид чистой базы — не предмет теста: всё «уже отправлено», имена иконок свободны.
        db.execSQL("UPDATE icon SET deleted_at = 1");
        for (String t : SYNC_TABLES) db.execSQL("UPDATE " + t + " SET dirty = 0 WHERE dirty <> 0");
        long seedIcon = icon("test_currency_icon", new byte[]{9}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        ContentValues cur = new ContentValues();
        cur.put("name", "Test coin");
        cur.put("iso_code", "TSC");
        cur.put("icon_id", seedIcon);
        cur.put("symbol", "T");
        cur.put("dirty", 0);
        currencyId = db.insertOrThrow("currency", null, cur);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 2.1: push без icon, без /sync/blobs ─────────────────────────────────────

    @Test
    public void push_hasNoIconTable_andNoBlobRequests() throws Exception {
        icon("Моя картинка", new byte[]{1, 2, 3}, WalletContract.IconEntry.ORIGIN_LOCAL, 1);
        long server = icon("Server icon", new byte[]{4}, WalletContract.IconEntry.ORIGIN_SERVER, 1);
        long acc = account("Test wallet", server);

        RecordingApi api = new RecordingApi(context);
        SyncLog log = new SyncLog();
        SyncEngine.pushDirty(db, api, log);

        assertEquals(1, api.pushed.size());
        JSONObject changes = api.pushed.get(0);
        assertFalse("иконки в push не уходят", changes.has("icon"));
        assertEquals(0, api.blobRequests);
        JSONObject row = changes.getJSONArray("account").getJSONObject(0);
        assertEquals(uuidOf("icon", server), row.getString("icon_uuid"));
        assertEquals(0, intOf("account", acc, "dirty"));
        assertTrue(log.getProblems().isEmpty());
    }

    // ── 2.3: заместитель своей иконки ───────────────────────────────────────────

    /** Сценарий спеки «Совпадает имя». */
    @Test
    public void ownIcon_pushedWithSameNameSubstitute_localLinkKept() throws Exception {
        icon("unknown", new byte[]{7}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long serverCinema = icon("cinema", new byte[]{5}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long ownCinema = icon("Cinema", new byte[]{6}, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long acc = account("Test wallet", ownCinema);

        RecordingApi api = new RecordingApi(context);
        SyncEngine.pushDirty(db, api, new SyncLog());

        JSONObject row = api.pushed.get(0).getJSONArray("account").getJSONObject(0);
        assertEquals(uuidOf("icon", serverCinema), row.getString("icon_uuid"));
        assertEquals(ownCinema, intOf("account", acc, "icon_id"));
        assertEquals(0, intOf("account", acc, "dirty"));
    }

    /** Сценарий спеки «Ничего похожего»; валюта — тем же путём, что счёт. */
    @Test
    public void ownIcon_withoutMatches_pushedWithUnknown() throws Exception {
        long unknown = icon("unknown", new byte[]{7}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        icon("cinema", new byte[]{5}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long own = icon("Моё фото", new byte[]{6}, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        ContentValues cv = new ContentValues();
        cv.put("icon_id", own);
        db.update("currency", cv, "_id=?", new String[]{String.valueOf(currencyId)});

        Map<String, JSONArray> byTable = SyncEngine.collectDirtyByTable(db, new ArrayList<>());

        JSONObject row = byTable.get("currency").getJSONObject(0);
        assertEquals(uuidOf("icon", unknown), row.getString("icon_uuid"));
    }

    /** Сценарий спеки «На сервере нет даже unknown». */
    @Test
    public void ownIcon_withoutSubstitute_rowAndDependentsDeferred_restSent() throws Exception {
        long own = icon("Моё фото", new byte[]{6}, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long server = icon("Server icon", new byte[]{4}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long accOwn = account("With own icon", own);
        long accOk = account("With server icon", server);
        long op = operation(accOk, accOwn);

        RecordingApi api = new RecordingApi(context);
        SyncLog log = new SyncLog();
        SyncEngine.pushDirty(db, api, log);

        assertEquals(1, api.pushed.size());
        JSONObject changes = api.pushed.get(0);
        JSONArray accounts = changes.getJSONArray("account");
        assertEquals(1, accounts.length());
        assertEquals(uuidOf("account", accOk), accounts.getJSONObject(0).getString("uuid"));
        assertFalse("операция по отложенному счёту тоже ждёт", changes.has("operation"));

        assertEquals(1, intOf("account", accOwn, "dirty"));
        assertEquals(1, intOf("operation", op, "dirty"));
        assertEquals(0, intOf("account", accOk, "dirty"));
        assertTrue(log.getProblems().toString(),
                log.getProblems().contains("нет иконки-заместителя для «Моё фото»: строка отложена"));
    }

    @Test
    public void deferDependents_defersWholeChainButKeepsUnrelatedRows() throws Exception {
        Map<String, JSONArray> byTable = new LinkedHashMap<>();
        byTable.put("account", new JSONArray()
                .put(row("acc-bad", "icon_uuid", "icon-own"))
                .put(row("acc-ok", "icon_uuid", "icon-server")));
        byTable.put("account_operating", new JSONArray()
                .put(row("acc-bad", "currency_uuid", "cur"))
                .put(row("acc-ok", "currency_uuid", "cur")));
        byTable.put("operation", new JSONArray()
                .put(row("op-bad", "acc_fr_uuid", "acc-bad", "acc_to_uuid", "acc-ok"))
                .put(row("op-ok", "acc_fr_uuid", "acc-ok", "acc_to_uuid", "acc-ok"))
                .put(row("op-deleted", "acc_fr_uuid", "acc-ok", "acc_to_uuid", "acc-ok").put("deleted", true)));
        byTable.put("operation_link", new JSONArray()
                .put(row("link-bad", "main_uuid", "op-ok", "link_uuid", "op-bad")));

        Set<String> deferred = new HashSet<>();
        deferred.add("icon-own");
        List<String> problems = new ArrayList<>();

        SyncEngine.deferDependents(byTable, deferred, problems);

        assertEquals("acc-ok", byTable.get("account").getJSONObject(0).getString("uuid"));
        assertEquals(1, byTable.get("account").length());
        assertEquals(1, byTable.get("account_operating").length());
        assertEquals(2, byTable.get("operation").length());
        // Таблица, из которой ушли все строки, не должна оставаться пустой в changes.
        assertFalse(byTable.containsKey("operation_link"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("нет иконки-заместителя"));
        assertTrue(problems.get(0).contains("4 строк"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static JSONObject row(String uuid, String... refs) throws Exception {
        JSONObject r = new JSONObject().put("uuid", uuid);
        for (int i = 0; i < refs.length; i += 2) r.put(refs[i], refs[i + 1]);
        return r;
    }

    private long icon(String name, byte[] image, int origin, int dirty) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("image", image);
        cv.put("icon_type", 0);
        cv.put("origin", origin);
        cv.put("uuid", java.util.UUID.randomUUID().toString());
        cv.put("dirty", dirty);
        return db.insertOrThrow("icon", null, cv);
    }

    private long account(String name, long iconId) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("uuid", java.util.UUID.randomUUID().toString());
        return db.insertOrThrow("account", null, cv);
    }

    private long operation(long from, long to) {
        ContentValues cv = new ContentValues();
        cv.put("date", "2026-11-02 12:00");
        cv.put("acc_fr_id", from);
        cv.put("acc_to_id", to);
        cv.put("amount", 10.5);
        cv.put("comment", "");
        cv.put("uuid", java.util.UUID.randomUUID().toString());
        return db.insertOrThrow("operation", null, cv);
    }

    private String uuidOf(String table, long id) {
        Cursor c = db.rawQuery("SELECT uuid FROM " + table + " WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertNotNull(c.getString(0));
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    private long intOf(String table, long id, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }
}
