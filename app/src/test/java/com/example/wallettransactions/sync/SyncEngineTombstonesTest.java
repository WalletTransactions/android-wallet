package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.Singleton;

import org.json.JSONArray;
import org.json.JSONException;
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
import java.util.List;

/**
 * openspec purge-synced-tombstones: чистка отправленных удалений, удаление
 * неизвестной строки, загрузка с нуля с вычисткой, параметр {@code ledger_id}.
 * База — та же, что у Singleton (pullAll в конце перечитывает его кэши), сид
 * вычищен, как в {@link SyncEnginePullDeferredTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineTombstonesTest {

    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String LEDGER = "3c000000-0000-4000-8000-000000000004";

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation", WalletDbHelper.SYNC_RELOAD_SEEN_TABLE,
    };

    private Context context;
    private SQLiteDatabase db;
    private long iconId;

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
        iconId = db.insertOrThrow("icon", null, icon); // dirty = 1: не «синкнутая»
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
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

    // ── локальные строки ─────────────────────────────────────────────────────

    private long account(String uuid, int dirty, boolean deleted) {
        ContentValues cv = new ContentValues();
        cv.put("name", "Acc " + uuid);
        cv.put("comment", "");
        cv.put("icon_id", iconId);
        cv.put("uuid", uuid);
        cv.put("dirty", dirty);
        if (deleted) cv.put("deleted_at", 1_700_000_000_000L);
        return db.insertOrThrow("account", null, cv);
    }

    private long operation(String uuid, long from, long to, int dirty, boolean deleted) {
        ContentValues cv = new ContentValues();
        cv.put("date", "2026-11-04 12:00:00");
        cv.put("acc_fr_id", from);
        cv.put("acc_to_id", to);
        cv.put("amount", "10");
        cv.put("comment", "");
        cv.put("uuid", uuid);
        cv.put("dirty", dirty);
        if (deleted) cv.put("deleted_at", 1_700_000_000_000L);
        return db.insertOrThrow("operation", null, cv);
    }

    private void link(String uuid, long main, long linked, int dirty, boolean deleted) {
        ContentValues cv = new ContentValues();
        cv.put("main_id", main);
        cv.put("link_id", linked);
        cv.put("uuid", uuid);
        cv.put("dirty", dirty);
        if (deleted) cv.put("deleted_at", 1_700_000_000_000L);
        db.insertOrThrow("operation_link", null, cv);
    }

    private void recurring(long from, long to) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_FROM, from);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_TO, to);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_AMOUNT, 5);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_FREQUENCY, 1);
        cv.put(WalletContract.RecurringOperationEntry.COLUMN_START_DATE, "2026-11-01");
        db.insertOrThrow(WalletContract.RecurringOperationEntry.TABLE_NAME, null, cv);
    }

    private boolean exists(String table, String uuid) {
        Cursor c = db.rawQuery("SELECT 1 FROM " + table + " WHERE uuid = ?", new String[]{uuid});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private int dirtyOf(String table, String uuid) {
        Cursor c = db.rawQuery("SELECT dirty FROM " + table + " WHERE uuid = ?", new String[]{uuid});
        try {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private int count(String table) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null);
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    // ── строки в формате /sync/pull ──────────────────────────────────────────

    private static JSONObject accountRow(String uuid, boolean deleted) throws Exception {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", "Acc " + uuid).put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", 0)
                .put("deleted_at", deleted ? "2026-11-04T10:00:00Z" : JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    private static JSONObject operationRow(String uuid, String from, String to, boolean deleted) throws Exception {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("date", "2026-11-04T12:00:00")
                .put("acc_fr_uuid", from).put("acc_to_uuid", to).put("amount", "10.00").put("comment", "")
                .put("deleted_at", deleted ? "2026-11-04T10:00:00Z" : JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    private static JSONObject changes(String table, JSONObject... rows) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject r : rows) arr.put(r);
        return new JSONObject().put(table, arr);
    }

    private static JSONObject merge(JSONObject a, JSONObject b) throws Exception {
        JSONObject out = new JSONObject(a.toString());
        java.util.Iterator<String> keys = b.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, b.getJSONArray(k));
        }
        return out;
    }

    // ── 1.1 чистка отправленных удалений ─────────────────────────────────────

    @Test
    public void purge_removesSentTombstones_keepsUnsentAndReferenced() {
        long a = account("a-live", 0, false);
        long b = account("b-live", 0, false);
        long referenced = account("acc-referenced", 0, true);
        long withRule = account("acc-recurring", 0, true);
        long lonely = account("acc-lonely", 0, true);
        operation("op-sent", a, b, 0, true);
        operation("op-unsent", a, b, 1, true);
        operation("op-live", referenced, b, 0, false);
        operation("op-lonely", lonely, b, 0, true);
        recurring(withRule, b);

        int deleted = SyncEngine.purgeTombstones(db);

        assertEquals(3, deleted); // op-sent, op-lonely, acc-lonely (лист и корень в одном проходе)
        assertFalse(exists("operation", "op-sent"));
        assertFalse(exists("operation", "op-lonely"));
        assertFalse(exists("account", "acc-lonely"));
        assertTrue("неотправленное удаление уйдёт следующим проходом", exists("operation", "op-unsent"));
        assertTrue("на счёт ссылается живая операция", exists("account", "acc-referenced"));
        assertTrue("на счёт ссылается правило (CASCADE не должен его унести)", exists("account", "acc-recurring"));
        assertEquals(1, count(WalletContract.RecurringOperationEntry.TABLE_NAME));
        // Удаление не поднимает dirty у других строк.
        assertEquals(0, dirtyOf("account", "a-live"));
        assertEquals(0, dirtyOf("operation", "op-live"));
        assertEquals(1, dirtyOf("operation", "op-unsent"));
    }

    @Test
    public void purge_keepsDeletedOperationWithLiveLink() {
        long a = account("a", 0, false);
        long b = account("b", 0, false);
        long main = operation("op-main-deleted", a, b, 0, true);
        long linked = operation("op-linked", a, b, 0, false);
        link("link-live", main, linked, 1, false); // CASCADE унёс бы её вместе с операцией

        SyncEngine.purgeTombstones(db);

        assertTrue(exists("operation", "op-main-deleted"));
        assertTrue(exists("operation_link", "link-live"));
    }

    @Test
    public void purge_deletedLinkThenItsOperation() {
        long a = account("a", 0, false);
        long b = account("b", 0, false);
        long main = operation("op-main", a, b, 0, true);
        long linked = operation("op-linked", a, b, 0, true);
        link("link-deleted", main, linked, 0, true);

        assertEquals(3, SyncEngine.purgeTombstones(db));
        assertEquals(0, count("operation"));
        assertEquals(0, count("operation_link"));
    }

    // ── 2.1 удаление неизвестной строки ──────────────────────────────────────

    @Test
    public void applyPage_deletionOfUnknownRow_changesNothing() throws Exception {
        account("a", 0, false);
        account("b", 0, false);
        SyncLog log = new SyncLog();
        SyncEngine.applyPage(db,
                merge(changes("operation", operationRow("op-unknown", "a", "b", true)),
                        changes("account", accountRow("acc-unknown", true))),
                new JSONObject(), null, log, PullDeferredRows.inMemory());

        assertFalse(exists("operation", "op-unknown"));
        assertFalse(exists("account", "acc-unknown"));
        assertTrue(log.isEmpty());
    }

    @Test
    public void applyPage_deletionOfKnownRow_stillApplied() throws Exception {
        long a = account("a", 0, false);
        long b = account("b", 0, false);
        operation("op-known", a, b, 0, false);
        SyncLog log = new SyncLog();
        SyncEngine.applyPage(db, changes("operation", operationRow("op-known", "a", "b", true)),
                new JSONObject(), null, log, PullDeferredRows.inMemory());
        assertTrue(exists("operation", "op-known"));
        assertFalse(log.isEmpty());
    }

    // ── 3.x загрузка с нуля ──────────────────────────────────────────────────

    /** Сценарий ответов pull: по одному на вызов; IOException — обрыв сети. */
    private static final class ScriptedApi extends SyncApiClient {
        interface Step {
            PullResult answer(long ledgerSeq, long referenceSeq) throws IOException;
        }

        final Deque<Step> steps = new ArrayDeque<>();
        final List<long[]> calls = new ArrayList<>();
        final List<String> ledgerIds = new ArrayList<>();

        ScriptedApi(Context ctx) {
            super(ctx, "test-token");
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId) throws IOException {
            calls.add(new long[]{ledgerSeq, referenceSeq});
            ledgerIds.add(ledgerId);
            Step step = steps.poll();
            if (step == null) return empty(ledgerSeq, referenceSeq); // догнали
            return step.answer(ledgerSeq, referenceSeq);
        }

        static PullResult empty(long ledgerSeq, long referenceSeq) {
            return new PullResult(new PullStream(ledgerSeq, new JSONObject(), false, LEDGER),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }

        static PullResult page(long nextSeq, JSONObject ledgerChanges, long referenceSeq) {
            return new PullResult(new PullStream(nextSeq, ledgerChanges, false, LEDGER),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }

        static PullResult reset(String reason, JSONObject ledgerChanges, long nextSeq) {
            return new PullResult(new PullStream(nextSeq, ledgerChanges, true, LEDGER, reason),
                    new PullStream(0, new JSONObject(), false, null));
        }
    }

    /** Синкнутые счета a/b и три операции: живая, удалённая на сервере, неотправленная. */
    private void syncedLedger() {
        long a = account("a", 0, false);
        long b = account("b", 0, false);
        operation("op-keep", a, b, 0, false);
        operation("op-gone-on-server", a, b, 0, false);
        operation("op-offline", a, b, 1, false);
    }

    private JSONObject serverState() throws Exception {
        return merge(changes("account", accountRow("a", false), accountRow("b", false)),
                changes("operation", operationRow("op-keep", "a", "b", false)));
    }

    @Test
    public void reload_afterPurgedReset_removesRowsGoneOnServer_keepsUnsent() throws Exception {
        syncedLedger();
        AppSettings.setSyncLedgerId(context, LEDGER);
        AppSettings.setLedgerSyncSeq(context, 40L);
        AppSettings.setReferenceSyncSeq(context, 5L);

        ScriptedApi api = new ScriptedApi(context);
        JSONObject state = serverState();
        api.steps.add((l, r) -> ScriptedApi.reset("purged", state, 600));
        api.steps.add((l, r) -> ScriptedApi.page(600, state, 0));

        SyncLog log = new SyncLog();
        SyncEngine.pullAll(db, api, context, log);

        // Сброс: страница выброшена, оба курсора с нуля.
        assertEquals(40L, api.calls.get(0)[0]);
        assertEquals(0L, api.calls.get(1)[0]);
        assertEquals(0L, api.calls.get(1)[1]);
        assertFalse(exists("operation", "op-gone-on-server"));
        assertTrue(exists("operation", "op-keep"));
        assertTrue("неотправленная остаётся", exists("operation", "op-offline"));
        assertTrue(exists("account", "a"));
        assertFalse(AppSettings.isFullReloadPending(context));
        assertEquals(0, count(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE));
        assertTrue(log.toDisplayText().contains("Сервер очистил старые удаления"));
    }

    @Test
    public void secondServerResetInOnePass_failsInsteadOfLooping() throws Exception {
        syncedLedger();
        AppSettings.setSyncLedgerId(context, LEDGER);
        AppSettings.setLedgerSyncSeq(context, 40L);
        AppSettings.setReferenceSyncSeq(context, 5L);

        // Сервер без исправления: граница первой страницы (13) ниже purged_seq —
        // на каждый ненулевой курсор снова "purged".
        ScriptedApi api = new ScriptedApi(context);
        JSONObject accounts = changes("account", accountRow("a", false), accountRow("b", false));
        for (int i = 0; i < 100; i++) {
            api.steps.add((l, r) -> l == 0
                    ? ScriptedApi.page(13, accounts, 0)
                    : ScriptedApi.reset("purged", new JSONObject(), 13));
        }

        try {
            SyncEngine.pullAll(db, api, context, new SyncLog());
            fail("ожидалась ошибка на втором сбросе");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("reset the cursor twice"));
        }
        // 40 -> сброс, 0 -> страница до 13, 13 -> второй сброс: стоп.
        assertEquals(3, api.calls.size());
        // Ничего не вычищено: загрузка не дошла до конца и продолжится.
        assertTrue(exists("operation", "op-gone-on-server"));
        assertTrue(AppSettings.isFullReloadPending(context));
    }

    @Test
    public void reload_interrupted_deletesNothing_nextPassFinishes() throws Exception {
        syncedLedger();
        AppSettings.setLedgerSyncSeq(context, 0L);
        AppSettings.setReferenceSyncSeq(context, 0L);

        ScriptedApi api = new ScriptedApi(context);
        JSONObject accounts = changes("account", accountRow("a", false), accountRow("b", false));
        api.steps.add((l, r) -> ScriptedApi.page(10, accounts, 0));
        api.steps.add((l, r) -> {
            throw new IOException("сеть пропала");
        });

        try {
            SyncEngine.pullAll(db, api, context, new SyncLog());
            fail("ожидался обрыв сети");
        } catch (IOException expected) {
            // как в SyncRunner: проход завершается «нет сети»
        }
        assertTrue(AppSettings.isFullReloadPending(context));
        assertEquals(10L, AppSettings.getLedgerSyncSeq(context));
        assertTrue("до догона ничего не вычищается", exists("operation", "op-gone-on-server"));

        // Следующий проход продолжает с курсора и вычищает.
        JSONObject keep = changes("operation", operationRow("op-keep", "a", "b", false));
        api.steps.add((l, r) -> ScriptedApi.page(20, keep, 0));
        SyncEngine.pullAll(db, api, context, new SyncLog());

        long[] resumed = api.calls.get(2);
        assertEquals(10L, resumed[0]);
        assertFalse(exists("operation", "op-gone-on-server"));
        assertTrue(exists("operation", "op-keep"));
        assertTrue(exists("operation", "op-offline"));
        assertFalse(AppSettings.isFullReloadPending(context));
    }

    @Test
    public void reload_cursorZeroWithoutSyncedRows_isPlainLoad() throws Exception {
        // Свежая установка: синкнутых строк нет — флаг не ставится.
        ScriptedApi api = new ScriptedApi(context);
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertFalse(AppSettings.isFullReloadPending(context));
    }

    @Test
    public void reload_ledgerChanged_restartsBothStreamsWithSweep() throws Exception {
        syncedLedger();
        AppSettings.setSyncLedgerId(context, "old-ledger");
        AppSettings.setLedgerSyncSeq(context, 300L);
        AppSettings.setReferenceSyncSeq(context, 9L);

        ScriptedApi api = new ScriptedApi(context);
        JSONObject state = serverState();
        api.steps.add((l, r) -> ScriptedApi.page(310, new JSONObject(), 9)); // новый ledger_id
        api.steps.add((l, r) -> ScriptedApi.page(5, state, 0));

        SyncEngine.pullAll(db, api, context, new SyncLog());

        assertEquals("old-ledger", api.ledgerIds.get(0));
        assertEquals(0L, api.calls.get(1)[0]);
        assertEquals(0L, api.calls.get(1)[1]);
        assertEquals(LEDGER, api.ledgerIds.get(1));
        assertFalse(exists("operation", "op-gone-on-server"));
        assertTrue(exists("operation", "op-offline"));
    }

    @Test
    public void reload_oneTimeResync_setsPending() throws Exception {
        syncedLedger();
        AppSettings.setLedgerSyncSeq(context, 42L);
        AppSettings.setReferenceSyncSeq(context, 7L);
        // Сброс флагов одноразовой перекачки (setUp их выставил).
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().remove("deferredRowsResyncDone").commit();

        ScriptedApi api = new ScriptedApi(context);
        JSONObject state = serverState();
        api.steps.add((l, r) -> ScriptedApi.page(5, state, 0));
        SyncEngine.pullAll(db, api, context, new SyncLog());

        assertEquals(0L, api.calls.get(0)[0]);
        assertFalse(exists("operation", "op-gone-on-server"));
        assertTrue(exists("operation", "op-keep"));
    }

    @Test
    public void reload_ownIconsSurviveSweep() throws Exception {
        syncedLedger();
        ContentValues own = new ContentValues();
        own.put("name", "own_icon");
        own.put("image", new byte[]{1});
        own.put("icon_type", 1);
        own.put("uuid", "own-icon-uuid");
        own.put("dirty", 0);
        own.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL);
        db.insertOrThrow("icon", null, own);

        ScriptedApi api = new ScriptedApi(context);
        JSONObject state = serverState();
        api.steps.add((l, r) -> ScriptedApi.page(5, state, 0));
        SyncEngine.pullAll(db, api, context, new SyncLog());

        assertTrue(exists("icon", "own-icon-uuid"));
        assertFalse(exists("operation", "op-gone-on-server"));
    }

    @Test
    public void reload_matchesUuidWrittenWithoutDashes() throws Exception {
        String dashed = "bd77a9c4-9dac-4bdc-8c72-1939115745eb";
        account(dashed.replace("-", ""), 0, false); // сид пишет uuid без дефисов
        ScriptedApi api = new ScriptedApi(context);
        JSONObject state = changes("account", accountRow(dashed, false));
        api.steps.add((l, r) -> ScriptedApi.page(5, state, 0));
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertTrue(exists("account", dashed.replace("-", "")));
    }

    // ── 4.1 протокол ─────────────────────────────────────────────────────────

    @Test
    public void pullUrl_withAndWithoutLedgerId() {
        assertEquals("http://h/sync/pull?ledger_seq=7&reference_seq=3&limit=500",
                SyncApiClient.pullUrl("http://h/", 7, 3, 500, null));
        assertEquals("http://h/sync/pull?ledger_seq=7&reference_seq=3&limit=500&ledger_id=" + LEDGER,
                SyncApiClient.pullUrl("http://h/", 7, 3, 500, LEDGER));
    }

    @Test
    public void pullAll_sendsKnownLedgerId_andNoneBeforeFirstSync() throws Exception {
        ScriptedApi api = new ScriptedApi(context);
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertNull(api.ledgerIds.get(0));
        // Первый ответ сообщил леджер — следующий проход его передаёт.
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertEquals(LEDGER, api.ledgerIds.get(api.ledgerIds.size() - 1));
    }

    @Test
    public void pullStream_parsesResetReason_andOldServerWithout() throws Exception {
        SyncApiClient.PullResult withReason = SyncApiClient.PullResult.fromJson(new JSONObject(
                "{\"ledger\":{\"ledger_id\":\"" + LEDGER + "\",\"next_seq\":3,\"reset\":true,"
                        + "\"reset_reason\":\"purged\",\"changes\":{}},"
                        + "\"reference\":{\"next_seq\":1,\"reset\":false,\"changes\":{}}}"));
        assertTrue(withReason.ledger.reset);
        assertEquals("purged", withReason.ledger.resetReason);
        assertNull(withReason.reference.resetReason);

        SyncApiClient.PullResult old = SyncApiClient.PullResult.fromJson(new JSONObject(
                "{\"ledger\":{\"ledger_id\":\"" + LEDGER + "\",\"next_seq\":3,\"reset\":true,\"changes\":{}},"
                        + "\"reference\":{\"next_seq\":1,\"reset\":false,\"changes\":{}}}"));
        assertTrue(old.ledger.reset);
        assertNull(old.ledger.resetReason);
    }

    // ── 3.1 апгрейд базы ─────────────────────────────────────────────────────

    @Test
    public void upgrade86to87_createsReloadTable_dataIntact() {
        long a = account("a", 0, false);
        long b = account("b", 0, false);
        operation("op", a, b, 0, false);
        db.execSQL("DROP TABLE " + WalletDbHelper.SYNC_RELOAD_SEEN_TABLE);

        new WalletDbHelper(context).onUpgrade(db, 86, 87);

        assertEquals(0, count(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE));
        assertEquals(1, count("operation"));
        assertEquals(2, count("account"));
    }
}
