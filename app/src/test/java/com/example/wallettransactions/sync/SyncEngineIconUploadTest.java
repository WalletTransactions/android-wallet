package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * openspec optional-icon-upload, задачи 3.1–3.8: шаг отправки своих иконок
 * ({@link SyncEngine#pushOwnIcons}) и флаг сервера из pull ({@link SyncEngine#applyServerIconFlag}).
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineIconUploadTest {

    private static final String[] SYNC_TABLES = {
            "icon", "currency", "currency_pair", "currency_pair_rate", "currency_conversion",
            "account", "account_operating", "operation", "operation_link",
    };

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private SyncEngine.Sleeper savedSleeper;

    /** Транспорт без сети: blob и push отвечают тем, что задал тест. */
    private static class FakeApi extends SyncApiClient {
        final Set<String> serverBlobs = new HashSet<>();
        final List<JSONObject> pushed = new ArrayList<>();
        int headRequests;
        int uploads;
        int uploadStatus = 200;
        JSONObject uploadBody;
        int pushStatus = 200;
        JSONObject pushBody = new JSONObject();
        /** Сколько первых HEAD обрываются, как сеть. */
        int failHeads;

        FakeApi(Context ctx) {
            super(ctx, "test-token");
        }

        @Override
        boolean blobExists(String sha256Hex) throws IOException {
            headRequests++;
            if (failHeads > 0) {
                failHeads--;
                throw new IOException("connection reset");
            }
            return serverBlobs.contains(sha256Hex);
        }

        @Override
        HttpResult uploadBlob(byte[] bytes) {
            uploads++;
            String sha = IconSubstitutes.sha256Hex(bytes);
            if (uploadStatus == 200) serverBlobs.add(sha);
            JSONObject body = uploadBody;
            if (body == null) {
                body = new JSONObject();
                try {
                    body.put("sha256", sha);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            return new HttpResult(uploadStatus, body);
        }

        @Override
        HttpResult push(String batchId, JSONObject changes) {
            pushed.add(changes);
            return new HttpResult(pushStatus, pushBody);
        }

        @Override
        byte[] downloadBlob(String sha256Hex) {
            throw new AssertionError("не вызывается");
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId) {
            throw new AssertionError("не вызывается");
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        // Сид чистой базы — не предмет теста: иконки сида мягко удалены, всё «уже отправлено».
        db.execSQL("UPDATE icon SET deleted_at = 1");
        for (String t : SYNC_TABLES) db.execSQL("UPDATE " + t + " SET dirty = 0 WHERE dirty <> 0");
        savedSleeper = SyncEngine.sleeper;
        SyncEngine.sleeper = ms -> { };
        AppSettings.setIconUploadEnabled(context, false);
        AppSettings.setServerIconUploadAllowed(context, false);
    }

    @After
    public void tearDown() {
        SyncEngine.sleeper = savedSleeper;
        helper.close();
    }

    // ── Успех ───────────────────────────────────────────────────────────────────

    @Test
    public void ownIcon_uploadedPushedAndBecomesServerIcon() throws Exception {
        long own = icon("Моя картинка", new byte[]{1, 2, 3}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        SyncLog log = new SyncLog();

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, log));

        assertEquals(1, api.uploads);
        assertEquals(1, api.pushed.size());
        JSONObject row = api.pushed.get(0).getJSONArray("icon").getJSONObject(0);
        assertEquals(uuidOf(own), row.getString("uuid"));
        assertEquals("Моя картинка", row.getString("name"));
        assertEquals(IconSubstitutes.sha256Hex(new byte[]{1, 2, 3}), row.getString("content_sha256"));
        assertEquals(1, api.pushed.get(0).length()); // только icon — счета уходят отдельно
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf(own, "origin"));
        assertEquals(0, intOf(own, "dirty"));
        assertTrue(log.getProblems().isEmpty());
        assertTrue(log.toDisplayText().contains("Отправлено") || log.toDisplayText().contains("Sent"));
    }

    @Test
    public void acceptedIcon_isReferencedByItsOwnUuidInAccountPush() throws Exception {
        long own = icon("Кино", new byte[]{4, 5}, WalletContract.IconEntry.ORIGIN_LOCAL);
        long acc = account("Кошелёк", own);
        FakeApi api = new FakeApi(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());
        api.pushed.clear();
        SyncEngine.pushDirty(db, api, new SyncLog());

        JSONObject accountRow = api.pushed.get(0).getJSONArray("account").getJSONObject(0);
        assertEquals(uuidOf(own), accountRow.getString("icon_uuid"));
        assertEquals(0, intOfTable("account", acc, "dirty"));
    }

    @Test
    public void blobAlreadyOnServer_isNotUploadedAgain() throws Exception {
        byte[] image = {7, 7, 7};
        icon("Уже там", image, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        api.serverBlobs.add(IconSubstitutes.sha256Hex(image));

        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals(0, api.uploads);
        assertEquals(1, api.pushed.size());
    }

    @Test
    public void sameImageAsServerIcon_isNotSent() throws Exception {
        byte[] image = {9, 9};
        icon("Сервер", image, WalletContract.IconEntry.ORIGIN_SERVER);
        long own = icon("Своя копия", image, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, new SyncLog()));

        assertEquals(0, api.headRequests);
        assertEquals(0, api.pushed.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"));
    }

    @Test
    public void deletedOwnIcon_isNotSent() throws Exception {
        long own = icon("Удалённая", new byte[]{3}, WalletContract.IconEntry.ORIGIN_LOCAL);
        db.execSQL("UPDATE icon SET deleted_at = 5 WHERE _id = " + own);
        FakeApi api = new FakeApi(context);

        SyncEngine.pushOwnIcons(db, api, new SyncLog());

        assertEquals(0, api.headRequests);
        assertEquals(0, api.pushed.size());
    }

    // ── Отказ по флагу ─────────────────────────────────────────────────────────

    @Test
    public void blob403Disabled_refusesAndKeepsIconOwn() throws Exception {
        long own = icon("Моя", new byte[]{1}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        api.uploadStatus = 403;
        api.uploadBody = new JSONObject().put("error", "icon_upload_disabled");

        assertEquals(SyncEngine.IconUploadResult.REFUSED, SyncEngine.pushOwnIcons(db, api, new SyncLog()));

        assertEquals(1, api.uploads); // без повторов
        assertEquals(0, api.pushed.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"));
    }

    @Test
    public void iconsRejectedInPush_refusesAndKeepsIconOwn() throws Exception {
        long own = icon("Моя", new byte[]{1}, WalletContract.IconEntry.ORIGIN_LOCAL);
        icon("Вторая", new byte[]{2}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        api.pushBody = new JSONObject().put("seq", 5).put("conflicts", new JSONArray())
                .put("icons_rejected", new JSONObject().put("error", "icon_upload_disabled")
                        .put("uuids", new JSONArray().put(uuidOf(own))));

        assertEquals(SyncEngine.IconUploadResult.REFUSED, SyncEngine.pushOwnIcons(db, api, new SyncLog()));

        assertEquals("шаг прерван на первой иконке", 1, api.pushed.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"));
    }

    @Test
    public void disableIconUpload_turnsSettingsOffAndNotes() {
        AppSettings.setIconUploadEnabled(context, true);
        AppSettings.setServerIconUploadAllowed(context, true);
        SyncLog log = new SyncLog();

        SyncEngine.disableIconUpload(context, log);

        assertFalse(AppSettings.isIconUploadEnabled(context));
        assertFalse(AppSettings.isServerIconUploadAllowed(context));
        assertTrue("заметка, не проблема", log.getProblems().isEmpty());
        assertFalse(log.isEmpty());
    }

    // ── Сбои отдельной иконки ──────────────────────────────────────────────────

    @Test
    public void push409ForOneIcon_skipsItAndSendsNext() throws Exception {
        long first = icon("Занятое имя", new byte[]{1}, WalletContract.IconEntry.ORIGIN_LOCAL);
        long second = icon("Свободное", new byte[]{2}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context) {
            @Override
            HttpResult push(String batchId, JSONObject changes) {
                super.push(batchId, changes);
                try {
                    String uuid = changes.getJSONArray("icon").getJSONObject(0).getString("uuid");
                    if (uuid.equals(uuidOf(first))) {
                        return new HttpResult(409, new JSONObject().put("error", "duplicate_key"));
                    }
                    return new HttpResult(200, new JSONObject());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };
        SyncLog log = new SyncLog();

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, log));

        assertEquals(2, api.pushed.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(first, "origin"));
        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf(second, "origin"));
        assertTrue("не «частично»", log.getProblems().isEmpty());
        assertTrue(log.toDisplayText().contains("Занятое имя"));
    }

    @Test
    public void blob5xxAfterRetries_skipsIcon() throws Exception {
        long own = icon("Моя", new byte[]{1}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        api.uploadStatus = 503;
        api.uploadBody = new JSONObject();

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, new SyncLog()));

        assertEquals(SyncEngine.BLOB_UPLOAD_ATTEMPTS, api.uploads);
        assertEquals(0, api.pushed.size());
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"));
    }

    @Test
    public void transientNetworkDrop_retriedAndSucceeds() throws Exception {
        long own = icon("Моя", new byte[]{1}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        api.failHeads = 1;

        assertEquals(SyncEngine.IconUploadResult.DONE, SyncEngine.pushOwnIcons(db, api, new SyncLog()));

        assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, intOf(own, "origin"));
    }

    @Test
    public void networkGoneAfterRetries_throwsAsNoNetwork() throws Exception {
        long own = icon("Моя", new byte[]{1}, WalletContract.IconEntry.ORIGIN_LOCAL);
        FakeApi api = new FakeApi(context);
        api.failHeads = SyncEngine.BLOB_UPLOAD_ATTEMPTS;

        try {
            SyncEngine.pushOwnIcons(db, api, new SyncLog());
            fail("ожидался IOException");
        } catch (IOException expected) {
            // проход завершится «нет сети»
        }
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, intOf(own, "origin"));
    }

    // ── Флаг сервера из pull ───────────────────────────────────────────────────

    @Test
    public void serverFlag_allowedIsRemembered() {
        SyncEngine.applyServerIconFlag(context, true, new SyncLog());
        assertTrue(AppSettings.isServerIconUploadAllowed(context));
        assertFalse("выбор пользователя не меняется", AppSettings.isIconUploadEnabled(context));
        assertFalse(AppSettings.shouldUploadOwnIcons(context));
    }

    @Test
    public void serverFlag_turnedOffWhileEnabled_disablesAndNotes() {
        AppSettings.setServerIconUploadAllowed(context, true);
        AppSettings.setIconUploadEnabled(context, true);
        assertTrue(AppSettings.shouldUploadOwnIcons(context));
        SyncLog log = new SyncLog();

        SyncEngine.applyServerIconFlag(context, false, log);

        assertFalse(AppSettings.isIconUploadEnabled(context));
        assertFalse(AppSettings.isServerIconUploadAllowed(context));
        assertFalse(log.isEmpty());
        assertTrue(log.getProblems().isEmpty());
    }

    @Test
    public void serverFlag_offWhileSettingOff_noNote() {
        SyncLog log = new SyncLog();
        SyncEngine.applyServerIconFlag(context, false, log);
        assertTrue(log.isEmpty());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private long icon(String name, byte[] image, int origin) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("image", image);
        cv.put("icon_type", 0);
        cv.put("origin", origin);
        cv.put("uuid", java.util.UUID.randomUUID().toString());
        cv.put("dirty", 0);
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

    private String uuidOf(long iconId) {
        Cursor c = db.rawQuery("SELECT uuid FROM icon WHERE _id = ?", new String[]{String.valueOf(iconId)});
        try {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    private int intOf(long iconId, String column) {
        return intOfTable("icon", iconId, column);
    }

    private int intOfTable(String table, long id, String column) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id = ?", new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }
}
