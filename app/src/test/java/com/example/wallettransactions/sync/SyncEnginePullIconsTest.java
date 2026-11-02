package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * openspec server-managed-icons, задачи 3.1 и 3.2: иконки из pull — {@code origin = 0},
 * конфликт имени со своей иконкой, своя иконка счёта/валюты не затирается заместителем.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEnginePullIconsTest {

    private static final int LOCAL = WalletContract.IconEntry.ORIGIN_LOCAL;
    private static final int SERVER = WalletContract.IconEntry.ORIGIN_SERVER;

    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private SyncLog log;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        db.execSQL("UPDATE icon SET deleted_at = 1"); // сид — не предмет теста, имена свободны
        log = new SyncLog();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 3.1: applyIcon ──────────────────────────────────────────────────────────

    @Test
    public void newServerIcon_insertedAsFromServer() throws Exception {
        byte[] image = {1, 2};
        String uuid = UUID.randomUUID().toString();
        SyncEngine.applyIcon(db, iconRow(uuid, "Admin icon", image), blobs(image), log);

        assertEquals(SERVER, intWhere("icon", "origin", "uuid=?", uuid));
        assertEquals(0, intWhere("icon", "dirty", "uuid=?", uuid));
    }

    @Test
    public void rowWithUuidOfOwnIcon_becomesFromServer() throws Exception {
        String uuid = UUID.randomUUID().toString();
        long id = icon("Pushed before", new byte[]{3}, LOCAL, uuid);
        SyncEngine.applyIcon(db, iconRow(uuid, "Pushed before", new byte[]{3}), new HashMap<>(), log);

        assertEquals(SERVER, intWhere("icon", "origin", "_id=?", String.valueOf(id)));
        assertEquals(0, intWhere("icon", "dirty", "_id=?", String.valueOf(id)));
    }

    /** Сценарий спеки «Администратор добавил иконку с именем своей». */
    @Test
    public void nameConflictWithOwnIcon_ownRenamed_serverAdded_linksKept() throws Exception {
        long own = icon("Cinema", new byte[]{5}, LOCAL, UUID.randomUUID().toString());
        long acc = account("Кино", own);

        byte[] serverImage = {6};
        String serverUuid = UUID.randomUUID().toString();
        SyncEngine.applyIcon(db, iconRow(serverUuid, "Cinema", serverImage), blobs(serverImage), log);

        assertEquals("Cinema (своя)", stringWhere("icon", "name", "_id=?", String.valueOf(own)));
        assertEquals(LOCAL, intWhere("icon", "origin", "_id=?", String.valueOf(own)));
        assertEquals("Cinema", stringWhere("icon", "name", "uuid=?", serverUuid));
        assertEquals(SERVER, intWhere("icon", "origin", "uuid=?", serverUuid));
        assertEquals(own, intWhere("account", "icon_id", "_id=?", String.valueOf(acc)));

        // «(своя)» уже занято — следующая своя станет «(своя 2)».
        long own2 = icon("Bar", new byte[]{7}, LOCAL, UUID.randomUUID().toString());
        icon("Bar (своя)", new byte[]{8}, LOCAL, UUID.randomUUID().toString());
        byte[] barImage = {9};
        SyncEngine.applyIcon(db, iconRow(UUID.randomUUID().toString(), "Bar", barImage), blobs(barImage), log);
        assertEquals("Bar (своя 2)", stringWhere("icon", "name", "_id=?", String.valueOf(own2)));
    }

    /** Своя с той же картинкой (seed.json после чистой установки) — та же иконка, без дубля. */
    @Test
    public void nameConflictWithSameImage_ownAdoptsServerIdentity() throws Exception {
        byte[] image = {4, 4, 4};
        long own = icon("Bar", image, LOCAL, UUID.randomUUID().toString());
        String serverUuid = UUID.randomUUID().toString();

        Map<String, byte[]> prefetched = SyncEngine.prefetchNewIconBlobs(db,
                new JSONArray().put(iconRow(serverUuid, "Bar", image)), null); // api = null: качать нельзя
        assertTrue(prefetched.isEmpty());

        SyncEngine.applyIcon(db, iconRow(serverUuid, "Bar", image), prefetched, log);

        assertEquals(serverUuid, stringWhere("icon", "uuid", "_id=?", String.valueOf(own)));
        assertEquals(SERVER, intWhere("icon", "origin", "_id=?", String.valueOf(own)));
        assertEquals(0, intWhere("icon", "dirty", "_id=?", String.valueOf(own)));
        assertEquals(1, intWhere("icon", "COUNT(*)", "name=? AND deleted_at IS NULL", "Bar"));
    }

    @Test
    public void nameConflictWithServerIcon_skippedAsBefore() throws Exception {
        long existing = icon("Cinema", new byte[]{5}, SERVER, UUID.randomUUID().toString());
        byte[] image = {6};
        String uuid = UUID.randomUUID().toString();
        SyncEngine.applyIcon(db, iconRow(uuid, "Cinema", image), blobs(image), log);

        assertEquals(0, intWhere("icon", "COUNT(*)", "uuid=?", uuid));
        assertEquals("Cinema", stringWhere("icon", "name", "_id=?", String.valueOf(existing)));
    }

    // ── 3.2: своя иконка не затирается заместителем ────────────────────────────

    /** Сценарий спеки «Сервер вернул заместитель». */
    @Test
    public void accountWithOwnIcon_substituteFromServer_ownIconKept_otherFieldsUpdated() throws Exception {
        String unknownUuid = UUID.randomUUID().toString();
        icon("unknown", new byte[]{1}, SERVER, unknownUuid);
        long own = icon("Моё фото", new byte[]{2}, LOCAL, UUID.randomUUID().toString());
        long acc = account("Кошелёк", own);
        String accUuid = stringWhere("account", "uuid", "_id=?", String.valueOf(acc));

        SyncEngine.applyAccount(db, accountRow(accUuid, "Кошелёк 2", unknownUuid), log,
                new SyncEngine.PullIconGuard(db));

        assertEquals(own, intWhere("account", "icon_id", "_id=?", String.valueOf(acc)));
        assertEquals("Кошелёк 2", stringWhere("account", "name", "_id=?", String.valueOf(acc)));
        assertEquals(0, intWhere("account", "dirty", "_id=?", String.valueOf(acc)));
    }

    /** Сценарий спеки «Иконку сменили на другом устройстве». */
    @Test
    public void accountWithOwnIcon_otherServerIcon_applied() throws Exception {
        icon("unknown", new byte[]{1}, SERVER, UUID.randomUUID().toString());
        String otherUuid = UUID.randomUUID().toString();
        long other = icon("Taxi", new byte[]{3}, SERVER, otherUuid);
        long own = icon("Моё фото", new byte[]{2}, LOCAL, UUID.randomUUID().toString());
        long acc = account("Кошелёк", own);
        String accUuid = stringWhere("account", "uuid", "_id=?", String.valueOf(acc));

        SyncEngine.applyAccount(db, accountRow(accUuid, "Кошелёк", otherUuid), log,
                new SyncEngine.PullIconGuard(db));

        assertEquals(other, intWhere("account", "icon_id", "_id=?", String.valueOf(acc)));
    }

    @Test
    public void currencyWithOwnIcon_sameNameSubstitute_ownIconKept() throws Exception {
        String substitute = UUID.randomUUID().toString();
        icon("flag xx", new byte[]{1}, SERVER, substitute);
        long own = icon("Flag XX", new byte[]{2}, LOCAL, UUID.randomUUID().toString());
        ContentValues cv = new ContentValues();
        cv.put("name", "Test XX");
        cv.put("iso_code", "TXX");
        cv.put("icon_id", own);
        cv.put("symbol", "X");
        String curUuid = UUID.randomUUID().toString();
        cv.put("uuid", curUuid);
        long cur = db.insertOrThrow("currency", null, cv);

        JSONObject row = new JSONObject()
                .put("uuid", curUuid).put("ledger_id", JSONObject.NULL)
                .put("name", "Test XX").put("iso_code", "TXX").put("icon_uuid", substitute)
                .put("symbol", "X").put("precision", 3)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
        SyncEngine.applyCurrency(db, row, log);

        assertEquals(own, intWhere("currency", "icon_id", "_id=?", String.valueOf(cur)));
        assertEquals(3, intWhere("currency", "precision", "_id=?", String.valueOf(cur)));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static JSONObject iconRow(String uuid, String name, byte[] image) throws Exception {
        return new JSONObject()
                .put("uuid", uuid)
                .put("ledger_id", JSONObject.NULL)
                .put("name", name)
                .put("icon_type", 0)
                .put("content_sha256", IconSubstitutes.sha256Hex(image))
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    private static JSONObject accountRow(String uuid, String name, String iconUuid) throws Exception {
        return new JSONObject()
                .put("uuid", uuid)
                .put("name", name)
                .put("comment", "")
                .put("icon_uuid", iconUuid)
                .put("is_archived", false)
                .put("system_kind", 0)
                .put("kind", 1)
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    private static Map<String, byte[]> blobs(byte[] image) {
        Map<String, byte[]> m = new HashMap<>();
        m.put(IconSubstitutes.sha256Hex(image), image);
        return m;
    }

    private long icon(String name, byte[] image, int origin, String uuid) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("image", image);
        cv.put("icon_type", 0);
        cv.put("origin", origin);
        cv.put("uuid", uuid);
        cv.put("dirty", 0);
        return db.insertOrThrow("icon", null, cv);
    }

    private long account(String name, long iconId) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("kind", 1);
        cv.put("uuid", UUID.randomUUID().toString());
        cv.put("dirty", 0);
        return db.insertOrThrow("account", null, cv);
    }

    private long intWhere(String table, String column, String where, String arg) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE " + where, new String[]{arg});
        try {
            assertTrue(table + " " + where + " " + arg + " не найдена", c.moveToFirst());
            return c.getLong(0);
        } finally {
            c.close();
        }
    }

    private String stringWhere(String table, String column, String where, String arg) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE " + where, new String[]{arg});
        try {
            assertTrue(table + " " + where + " " + arg + " не найдена", c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }
}
