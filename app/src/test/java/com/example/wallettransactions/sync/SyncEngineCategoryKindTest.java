package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
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

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * openspec category-kind-income-expense, группа 2: {@code account.category_kind} в push
 * (сервер без поля отвечает 400 invalid_row) и в pull (поле необязательное, значение вне
 * 0..2 — «Оба», строка не помечается неотправленной).
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineCategoryKindTest {

    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private SyncLog log;
    private long iconId;
    private String iconUuid;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        db.execSQL("UPDATE icon SET deleted_at = 1"); // сид не предмет теста
        // Счета стартового набора заняли бы имена (uq_account_name) и попали бы в push.
        db.execSQL("DELETE FROM operation_link");
        db.execSQL("DELETE FROM operation");
        db.execSQL("DELETE FROM recurring_operation");
        db.execSQL("DELETE FROM account_asset");
        db.execSQL("DELETE FROM account_debt");
        db.execSQL("DELETE FROM account_operating");
        db.execSQL("DELETE FROM account");
        log = new SyncLog();
        iconUuid = UUID.randomUUID().toString();
        ContentValues cv = new ContentValues();
        cv.put("name", "test");
        cv.put("image", new byte[]{1});
        cv.put("icon_type", 0);
        cv.put("origin", 0);
        cv.put("uuid", iconUuid);
        cv.put("dirty", 0);
        iconId = db.insertOrThrow("icon", null, cv);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 2.1 push ────────────────────────────────────────────────────────────

    @Test
    public void push_accountRowCarriesCategoryKind_forEveryValue() throws Exception {
        String expense = category("Продукты", 0, true);
        String income = category("Зарплата", 1, true);
        String both = category("Подарки", 2, true);

        Map<String, JSONArray> byTable = SyncEngine.collectDirtyByTable(db, new ArrayList<>());

        JSONArray rows = byTable.get("account");
        assertEquals(3, rows.length());
        assertEquals(0, kindInPush(rows, expense));
        assertEquals(1, kindInPush(rows, income));
        assertEquals(2, kindInPush(rows, both));
    }

    @Test
    public void push_walletRowCarriesBoth() throws Exception {
        String uuid = UUID.randomUUID().toString();
        ContentValues cv = new ContentValues();
        cv.put("name", "Наличные");
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("kind", 1);
        cv.put("uuid", uuid);
        cv.put("dirty", 1);
        db.insertOrThrow("account", null, cv);

        JSONArray rows = SyncEngine.collectDirtyByTable(db, new ArrayList<>()).get("account");

        assertEquals(2, kindInPush(rows, uuid));
        // Остальные обязательные колонки на месте: сервер читает их без дефолта.
        JSONObject row = rowByUuid(rows, uuid);
        assertTrue(row.has("system_kind"));
        assertTrue(row.has("kind"));
        assertTrue(row.has("category_kind"));
    }

    // ── 2.2 pull ────────────────────────────────────────────────────────────

    @Test
    public void pull_storesValueFromServer_andLeavesRowClean() throws Exception {
        String uuid = UUID.randomUUID().toString();

        SyncEngine.applyAccount(db, serverRow(uuid, "Зарплата", 1), log, new SyncEngine.PullIconGuard(db));

        assertEquals(1, intWhere("category_kind", uuid));
        assertEquals(0, intWhere("dirty", uuid));
    }

    @Test
    public void pull_updatesExistingCategory_withoutMakingItDirty() throws Exception {
        String uuid = category("Продукты", 2, false);

        SyncEngine.applyAccount(db, serverRow(uuid, "Продукты", 0), log, new SyncEngine.PullIconGuard(db));

        assertEquals(0, intWhere("category_kind", uuid));
        assertEquals(0, intWhere("dirty", uuid));
    }

    @Test
    public void pull_rowWithoutField_isAppliedAsBoth_notDeferred() throws Exception {
        String uuid = UUID.randomUUID().toString();
        JSONObject row = serverRow(uuid, "Старый сервер", 1);
        row.remove("category_kind");

        boolean applied = SyncEngine.applyAccount(db, row, log, new SyncEngine.PullIconGuard(db));

        assertTrue(applied);
        assertEquals(2, intWhere("category_kind", uuid));
    }

    @Test
    public void pull_valueOutOfRange_becomesBoth() throws Exception {
        String uuid = UUID.randomUUID().toString();

        SyncEngine.applyAccount(db, serverRow(uuid, "Мусор", 7), log, new SyncEngine.PullIconGuard(db));

        assertEquals(2, intWhere("category_kind", uuid));
    }

    @Test
    public void pull_sameRowTwice_changesNothing() throws Exception {
        String uuid = UUID.randomUUID().toString();
        JSONObject row = serverRow(uuid, "Подработка", 1);

        SyncEngine.applyAccount(db, row, log, new SyncEngine.PullIconGuard(db));
        SyncEngine.applyAccount(db, row, log, new SyncEngine.PullIconGuard(db));

        assertEquals(1, intWhere("category_kind", uuid));
        assertEquals(0, intWhere("dirty", uuid));
        assertEquals(1, count("account", "uuid = '" + uuid + "'"));
    }

    // ── 2.3 строка без поля не попадает в «отложенные» ─────────────────────

    @Test
    public void pull_rowWithoutField_doesNotLandInLogOfSkippedRows() throws Exception {
        String uuid = UUID.randomUUID().toString();
        JSONObject row = serverRow(uuid, "Без поля", 0);
        row.remove("category_kind");

        boolean applied = SyncEngine.applyAccount(db, row, log, new SyncEngine.PullIconGuard(db));

        // false означал бы «строку откладываем»; она применена, и локально она есть.
        assertTrue(applied);
        assertEquals(1, count("account", "uuid = '" + uuid + "'"));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private JSONObject serverRow(String uuid, String name, int categoryKind) throws Exception {
        return new JSONObject()
                .put("uuid", uuid)
                .put("name", name)
                .put("comment", "")
                .put("icon_uuid", iconUuid)
                .put("is_archived", false)
                .put("system_kind", 0)
                .put("kind", 0)
                .put("category_kind", categoryKind)
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    /** Обычная категория с заданным видом; {@code dirty} — ждёт ли отправки. */
    private String category(String name, int categoryKind, boolean dirty) {
        String uuid = UUID.randomUUID().toString();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("kind", 0);
        cv.put("system_kind", 0);
        cv.put("category_kind", categoryKind);
        cv.put("uuid", uuid);
        cv.put("dirty", dirty ? 1 : 0);
        db.insertOrThrow("account", null, cv);
        return uuid;
    }

    private static JSONObject rowByUuid(JSONArray rows, String uuid) throws Exception {
        for (int i = 0; i < rows.length(); i++) {
            if (uuid.equals(rows.getJSONObject(i).getString("uuid"))) return rows.getJSONObject(i);
        }
        throw new AssertionError("в push нет строки " + uuid);
    }

    private static int kindInPush(JSONArray rows, String uuid) throws Exception {
        return rowByUuid(rows, uuid).getInt("category_kind");
    }

    private int intWhere(String column, String uuid) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM account WHERE uuid = ?", new String[]{uuid});
        try {
            assertTrue("account " + uuid + " не найден", c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private int count(String table, String where) {
        Cursor c = db.rawQuery("SELECT count(*) FROM " + table + " WHERE " + where, null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
