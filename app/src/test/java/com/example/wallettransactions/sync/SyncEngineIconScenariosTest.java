package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * openspec optional-icon-upload — сценарии на стыке своих и серверных иконок: та же картинка,
 * то же имя, два устройства одного леджера отправляют одну иконку одновременно, потерянный
 * ответ. Сервер — модель с состоянием ({@link ModelServer}): blob по SHA-256, иконки леджера с
 * уникальным именем ({@code uq_icon_name_ledger} → {@code 409 duplicate_key}) и upsert по uuid,
 * как wallet-api. Второе устройство — это состояние сервера плюс строка pull
 * ({@link SyncEngine#applyIcon}).
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineIconScenariosTest {

    private static final String[] SYNC_TABLES = {
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link", "counterparty",
    };

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private SyncEngine.Sleeper savedSleeper;

    /** Иконка леджера на сервере. */
    private static final class ServerIcon {
        final String name;
        final String sha256;

        ServerIcon(String name, String sha256) {
            this.name = name;
            this.sha256 = sha256;
        }
    }

    /** wallet-api в объёме шага иконок: blob, icon (upsert по uuid, уникальное имя), прочее — запись. */
    private static class ModelServer extends SyncApiClient {
        final Set<String> blobs = new HashSet<>();
        final Map<String, ServerIcon> icons = new LinkedHashMap<>();
        final List<JSONObject> pushed = new ArrayList<>();
        int uploads;
        int heads;
        /** Применить следующий push иконки, но «потерять» ответ — IOException клиенту. */
        boolean loseNextIconResponse;

        ModelServer(Context ctx) {
            super(ctx, "test-token");
        }

        /** Иконка, которую отправило другое устройство леджера (вместе с её содержимым). */
        String addIconFromOtherDevice(String name, byte[] image) {
            String uuid = UUID.randomUUID().toString();
            String sha = IconSubstitutes.sha256Hex(image);
            blobs.add(sha);
            icons.put(uuid, new ServerIcon(name, sha));
            return uuid;
        }

        @Override
        boolean blobExists(String sha256Hex) {
            heads++;
            return blobs.contains(sha256Hex);
        }

        @Override
        HttpResult uploadBlob(byte[] bytes) {
            uploads++;
            String sha = IconSubstitutes.sha256Hex(bytes);
            blobs.add(sha); // ON CONFLICT DO NOTHING
            try {
                return new HttpResult(200, new JSONObject().put("sha256", sha));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        @Override
        HttpResult push(String batchId, JSONObject changes) throws IOException {
            pushed.add(changes);
            try {
                JSONArray iconRows = changes.optJSONArray("icon");
                if (iconRows != null) {
                    for (int i = 0; i < iconRows.length(); i++) {
                        JSONObject row = iconRows.getJSONObject(i);
                        String uuid = row.getString("uuid");
                        String name = row.getString("name");
                        String sha = row.getString("content_sha256");
                        if (!blobs.contains(sha)) {
                            return new HttpResult(409, new JSONObject().put("error", "missing_reference")
                                    .put("table", "blob").put("uuid", sha));
                        }
                        for (Map.Entry<String, ServerIcon> e : icons.entrySet()) {
                            if (!e.getKey().equals(uuid) && e.getValue().name.equalsIgnoreCase(name)) {
                                return new HttpResult(409, new JSONObject().put("error", "duplicate_key")
                                        .put("constraint", "uq_icon_name_ledger"));
                            }
                        }
                        icons.put(uuid, new ServerIcon(name, sha)); // upsert по uuid
                    }
                    if (loseNextIconResponse) {
                        loseNextIconResponse = false;
                        throw new IOException("connection reset after commit");
                    }
                }
                return new HttpResult(200, new JSONObject().put("seq", pushed.size()).put("conflicts", new JSONArray()));
            } catch (org.json.JSONException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        byte[] downloadBlob(String sha256Hex) {
            throw new AssertionError("не вызывается");
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId) {
            throw new AssertionError("pull моделируется через applyIcon");
        }

        int iconPushes() {
            int n = 0;
            for (JSONObject c : pushed) if (c.has("icon")) n++;
            return n;
        }

        JSONObject lastRowOf(String table) throws Exception {
            for (int i = pushed.size() - 1; i >= 0; i--) {
                JSONArray rows = pushed.get(i).optJSONArray(table);
                if (rows != null && rows.length() > 0) return rows.getJSONObject(rows.length() - 1);
            }
            return null;
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        db.execSQL("UPDATE icon SET deleted_at = 1");
        for (String t : SYNC_TABLES) db.execSQL("UPDATE " + t + " SET dirty = 0 WHERE dirty <> 0");
        savedSleeper = SyncEngine.sleeper;
        SyncEngine.sleeper = ms -> { };
    }

    @After
    public void tearDown() {
        SyncEngine.sleeper = savedSleeper;
        helper.close();
    }

    // ── Своя иконка = иконка с сервера ─────────────────────────────────────────

    /** Та же картинка, другое имя: не грузится ни в этом проходе, ни в следующем; счёт — на серверную. */
    @Test
    public void ownSameImageAsServerIcon_neverUploaded_accountReferencesServerIcon() throws Exception {
        byte[] img = {1, 1, 1};
        long server = icon("Кафе", img, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long own = icon("Моё кафе", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long acc = account("Кошелёк", own, 1);
        ModelServer api = new ModelServer(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        SyncEngine.pushDirty(db, api, new SyncLog());
        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals(0, api.heads);
        assertEquals(0, api.iconPushes());
        assertEquals(uuidOf("icon", server), api.lastRowOf("account").getString("icon_uuid"));
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", own, "origin"));
        assertEquals(0, intOf("account", acc, "dirty"));
    }

    /** Та же картинка у иконки с сервера, удалённой только на телефоне (dirty = 1): на сервере жива — не грузим. */
    @Test
    public void ownSameImageAsLocallyDeletedServerIcon_skipped() throws Exception {
        byte[] img = {2, 2};
        long server = icon("Такси", img, WalletContract.IconEntry.ORIGIN_SERVER, 1);
        db.execSQL("UPDATE icon SET deleted_at = 5 WHERE _id = " + server);
        icon("Моё такси", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        ModelServer api = new ModelServer(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals(0, api.iconPushes());
    }

    /** Та же картинка у иконки, удалённой на сервере (tombstone из pull, dirty = 0): заместителем не считается — грузим. */
    @Test
    public void ownSameImageAsServerTombstone_uploaded() throws Exception {
        byte[] img = {3, 3};
        // Вставкой, не UPDATE: UPDATE deleted_at поднял бы trg_icon_dirty, и это было бы локальное удаление.
        ContentValues tomb = new ContentValues();
        tomb.put("name", "Старое");
        tomb.put("image", img);
        tomb.put("icon_type", 0);
        tomb.put("origin", WalletContract.IconEntry.ORIGIN_SERVER);
        tomb.put("uuid", UUID.randomUUID().toString());
        tomb.put("deleted_at", 5L);
        tomb.put("dirty", 0);
        db.insertOrThrow("icon", null, tomb);
        long own = icon("Моё", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        ModelServer api = new ModelServer(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals(1, api.iconPushes());
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", own, "origin"));
    }

    /** Две свои с одной картинкой на одном телефоне: уходит первая, вторую заменяет она же. */
    @Test
    public void twoOwnIconsSameImage_onlyFirstUploaded_secondUsesIt() throws Exception {
        byte[] img = {4, 4, 4};
        long first = icon("Собака", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long second = icon("Пёс", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long acc = account("Корм", second, 1);
        ModelServer api = new ModelServer(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        SyncEngine.pushDirty(db, api, new SyncLog());

        assertEquals(1, api.uploads);
        assertEquals(1, api.iconPushes());
        assertEquals(1, api.icons.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", first, "origin"));
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", second, "origin"));
        assertEquals(uuidOf("icon", first), api.lastRowOf("account").getString("icon_uuid"));
        assertEquals(0, intOf("account", acc, "dirty"));
    }

    // ── Счета, ушедшие с заместителем ──────────────────────────────────────────

    /** Счёт и валюта уже на сервере с заместителем — после приёма иконки уходят снова, с её uuid. */
    @Test
    public void acceptedIcon_resendsSyncedAccountAndCurrencyWithOwnUuid() throws Exception {
        icon("unknown", new byte[]{9}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long own = icon("Кот", new byte[]{5, 5}, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long acc = account("Кошачий", own, 0);
        long deletedAcc = account("Удалённый", own, 0);
        db.execSQL("UPDATE account SET deleted_at = 7 WHERE _id = " + deletedAcc);
        db.execSQL("UPDATE account SET dirty = 0 WHERE _id = " + deletedAcc);
        long cur = currency("Котокоин", "KTC", own, 0);
        ModelServer api = new ModelServer(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        assertEquals(1, intOf("account", acc, "dirty"));
        assertEquals(1, intOf("currency", cur, "dirty"));
        assertEquals("удалённый счёт не трогаем", 0, intOf("account", deletedAcc, "dirty"));

        SyncEngine.pushDirty(db, api, new SyncLog());
        assertEquals(uuidOf("icon", own), api.lastRowOf("account").getString("icon_uuid"));
        assertEquals(uuidOf("icon", own), api.lastRowOf("currency").getString("icon_uuid"));
        assertEquals(0, intOf("account", acc, "dirty"));
    }

    // ── Два устройства одного леджера одновременно ─────────────────────────────

    /**
     * Одна и та же иконка (имя и картинка) с двух устройств: A успел первым. У B содержимое уже на
     * сервере (HEAD 200, без загрузки), push — 409 по имени: иконка остаётся своей, проход не
     * «частично». Pull приносит иконку A — своя B становится ею (uuid A), счёт B уходит снова
     * уже с uuid A, повторной отправки иконки нет.
     */
    @Test
    public void raceSameNameSameImage_loserAdoptsWinnersIcon_andResendsAccount() throws Exception {
        byte[] img = {6, 6, 6};
        ModelServer api = new ModelServer(context);
        String uuidA = api.addIconFromOtherDevice("Велосипед", img);
        icon("unknown", new byte[]{9}, WalletContract.IconEntry.ORIGIN_SERVER, 0);
        long ownB = icon("Велосипед", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long accB = account("Велик", ownB, 0); // ушёл раньше с заместителем
        SyncLog log = new SyncLog();

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, log));
        assertEquals("содержимое уже есть — не грузим", 0, api.uploads);
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", ownB, "origin"));
        assertTrue(log.getProblems().isEmpty());
        assertTrue(log.toDisplayText().contains("Велосипед"));

        SyncEngine.applyIcon(db, pullRow(uuidA, "Велосипед", img), new HashMap<>(), new SyncLog(), true);

        assertTrue(IconSubstitutes.sameUuid(uuidA, uuidOf("icon", ownB)));
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", ownB, "origin"));
        assertEquals("перенятая — одна строка, не дубль", 1, countIcons("Велосипед"));
        assertEquals(1, intOf("account", accB, "dirty"));

        int pushesBefore = api.iconPushes();
        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        SyncEngine.pushDirty(db, api, new SyncLog());
        assertEquals(pushesBefore, api.iconPushes());
        assertTrue(IconSubstitutes.sameUuid(uuidA, api.lastRowOf("account").getString("icon_uuid")));
        assertEquals(1, api.icons.size());
    }

    /**
     * Одно имя, разные картинки: B получает 409, pull иконки A переименовывает свою B в
     * «<имя> (своя)», следующий проход её отправляет — на сервере обе.
     */
    @Test
    public void raceSameNameDifferentImage_loserRenamedOnPull_thenUploaded() throws Exception {
        byte[] imgA = {7, 7};
        byte[] imgB = {8, 8};
        ModelServer api = new ModelServer(context);
        String uuidA = api.addIconFromOtherDevice("Море", imgA);
        long ownB = icon("Море", imgB, WalletContract.IconEntry.ORIGIN_LOCAL, 0);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", ownB, "origin"));

        Map<String, byte[]> blobs = new HashMap<>();
        blobs.put(IconSubstitutes.sha256Hex(imgA), imgA);
        SyncEngine.applyIcon(db, pullRow(uuidA, "Море", imgA), blobs, new SyncLog(), true);
        String renamed = stringOf("icon", ownB, "name");
        assertFalse("своя переименована", "Море".equals(renamed));
        assertTrue(renamed, renamed.startsWith("Море"));

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, new SyncLog()));
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", ownB, "origin"));
        assertEquals(2, api.icons.size());
        assertEquals(renamed, api.icons.get(uuidOf("icon", ownB)).name);
    }

    /**
     * Одна картинка, разные имена: A уже отправил, B ещё не видел его иконку — грузить содержимое
     * не нужно (HEAD 200), push проходит: на сервере две иконки с одной картинкой, это допустимо.
     * После pull иконки A у B больше нечего отправлять.
     */
    @Test
    public void raceSameImageDifferentName_bothAccepted_noDuplicateUpload() throws Exception {
        byte[] img = {10, 10};
        ModelServer api = new ModelServer(context);
        String uuidA = api.addIconFromOtherDevice("Горы", img);
        long ownB = icon("Скалы", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals(0, api.uploads);
        assertEquals(2, api.icons.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", ownB, "origin"));

        Map<String, byte[]> blobs = new HashMap<>();
        blobs.put(IconSubstitutes.sha256Hex(img), img);
        SyncEngine.applyIcon(db, pullRow(uuidA, "Горы", img), blobs, new SyncLog(), true);
        int before = api.iconPushes();
        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        assertEquals(before, api.iconPushes());
    }

    /** B увидел иконку A раньше, чем отправил свою с той же картинкой: своя не грузится, счёт — на иконку A. */
    @Test
    public void otherDeviceIconPulledFirst_ownWithSameImageNotUploaded() throws Exception {
        byte[] img = {11, 11};
        ModelServer api = new ModelServer(context);
        String uuidA = api.addIconFromOtherDevice("Лес", img);
        Map<String, byte[]> blobs = new HashMap<>();
        blobs.put(IconSubstitutes.sha256Hex(img), img);
        SyncEngine.applyIcon(db, pullRow(uuidA, "Лес", img), blobs, new SyncLog(), true);
        long ownB = icon("Мой лес", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        account("Дача", ownB, 1);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        SyncEngine.pushDirty(db, api, new SyncLog());

        assertEquals(0, api.iconPushes());
        assertTrue(IconSubstitutes.sameUuid(uuidA, api.lastRowOf("account").getString("icon_uuid")));
    }

    // ── Потерянный ответ ───────────────────────────────────────────────────────

    /** Сервер принял иконку, ответ потерялся: проход «нет сети»; следующий — тот же uuid, без загрузки, без дубля. */
    @Test
    public void lostResponse_nextPassIsIdempotent() throws Exception {
        long own = icon("Чай", new byte[]{12, 12}, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        ModelServer api = new ModelServer(context);
        api.loseNextIconResponse = true;

        try {
            SyncEngine.pushOwnIcons(db, api, new SyncLog());
            fail("ожидался IOException");
        } catch (IOException expected) {
            // «нет сети»
        }
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf("icon", own, "origin"));
        assertEquals(1, api.icons.size());

        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals("второй раз содержимое не грузится", 1, api.uploads);
        assertEquals(1, api.icons.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", own, "origin"));
    }

    // ── Перенимание на pull ────────────────────────────────────────────────────

    /** Во время подключения к кошельку перенимание не помечает счета: LedgerConnection удаляет dirty <> 0. */
    @Test
    public void adoptionDuringLedgerConnection_doesNotRedirty() throws Exception {
        byte[] img = {13, 13};
        long own = icon("Сад", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        long acc = account("Огород", own, 0);

        SyncEngine.applyIcon(db, pullRow(UUID.randomUUID().toString(), "Сад", img), new HashMap<>(),
                new SyncLog(), false);

        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf("icon", own, "origin"));
        assertEquals(0, intOf("account", acc, "dirty"));
    }

    /** Контрагент хранит icon_uuid строкой — при перенимании ссылка переписывается на серверный uuid. */
    @Test
    public void adoption_rewritesCounterpartyIconUuid() throws Exception {
        byte[] img = {14, 14};
        long own = icon("Друг", img, WalletContract.IconEntry.ORIGIN_LOCAL, 0);
        String ownUuid = uuidOf("icon", own);
        ContentValues cp = new ContentValues();
        cp.put("name", "Петя");
        cp.put("uuid", UUID.randomUUID().toString());
        cp.put("icon_uuid", ownUuid);
        long cpId = db.insertOrThrow("counterparty", null, cp);
        String serverUuid = UUID.randomUUID().toString();

        SyncEngine.applyIcon(db, pullRow(serverUuid, "Друг", img), new HashMap<>(), new SyncLog(), true);

        assertEquals(serverUuid, stringOf("counterparty", cpId, "icon_uuid"));
        assertNotNull(stringOf("icon", own, "uuid"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static JSONObject pullRow(String uuid, String name, byte[] image) throws Exception {
        return new JSONObject().put("uuid", uuid).put("name", name)
                .put("content_sha256", IconSubstitutes.sha256Hex(image)).put("icon_type", 0)
                .put("ledger_id", UUID.randomUUID().toString()).put("seq", 10)
                .put("deleted_at", JSONObject.NULL).put("updated_at", "2026-11-22T12:00:00Z")
                .put("updated_by", JSONObject.NULL);
    }

    private long icon(String name, byte[] image, int origin, int dirty) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("image", image);
        cv.put("icon_type", 0);
        cv.put("origin", origin);
        cv.put("uuid", UUID.randomUUID().toString());
        cv.put("dirty", dirty);
        return db.insertOrThrow("icon", null, cv);
    }

    private long account(String name, long iconId, int dirty) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("uuid", UUID.randomUUID().toString());
        long id = db.insertOrThrow("account", null, cv);
        db.execSQL("UPDATE account SET dirty = " + dirty + " WHERE _id = " + id + " AND dirty <> " + dirty);
        return id;
    }

    private long currency(String name, String iso, long iconId, int dirty) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("iso_code", iso);
        cv.put("icon_id", iconId);
        cv.put("symbol", "K");
        cv.put("uuid", UUID.randomUUID().toString());
        long id = db.insertOrThrow("currency", null, cv);
        db.execSQL("UPDATE currency SET dirty = " + dirty + " WHERE _id = " + id + " AND dirty <> " + dirty);
        return id;
    }

    private int countIcons(String name) {
        Cursor c = db.rawQuery("SELECT count(*) FROM icon WHERE name = ? AND deleted_at IS NULL", new String[]{name});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private String uuidOf(String table, long id) {
        return stringOf(table, id, "uuid");
    }

    private String stringOf(String table, long id, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id = ?", new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    private int intOf(String table, long id, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id = ?", new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
