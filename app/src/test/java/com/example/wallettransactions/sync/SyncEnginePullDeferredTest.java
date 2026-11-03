package com.example.wallettransactions.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
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

/**
 * Строка pull, чей родитель приходит на БОЛЕЕ ПОЗДНЕЙ странице, не теряется
 * (найдено на проверке #5, 2026-11-03): сервер отдаёт строку под её последним
 * seq, и валюта, у которой недавно сняли флаг эквивалента, уезжает в хвост
 * потока — а {@code account_operating} её кошелька остаётся в начале. Раньше
 * строка пропускалась с {@code Log.w}, курсор уходил дальше, и кошелёк навсегда
 * оставался без валюты: NPE в ленте «Операций», ClassCastException в пикере
 * эквивалента.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEnginePullDeferredTest {

    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String GEL = "736cb366-1336-4b33-560d-5b6fad9f0236";
    private static final String KES = "3d180d39-d091-72ff-498e-15c7124fea43";
    private static final String WALLET = "bd77a9c4-9dac-8bdc-0c72-1939115745eb";

    /** Тот же список, что в HardWipeCacheInvalidationTest / ResetDataActivity. */
    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        new File(context.getNoBackupFilesDir(), PullDeferredRows.FILE_NAME).delete();
        // База самого Singleton: pullAll в конце перечитывает его кэши (счета,
        // валюты, пары). Сид чистим: его PNG-иконки не влезают в CursorWindow
        // под Robolectric (см. HardWipeCacheInvalidationTest.setUp), а счёт
        // «Georgian» и валюта GEL из сида столкнулись бы с тестовыми строками.
        db = Singleton.instance.getWritableDatabase(context);
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        ContentValues icon = new ContentValues();
        icon.put("name", "test_icon");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON);
        db.insertOrThrow("icon", null, icon);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    /** Singleton — статическое поле, переживает тесты (как в HardWipeCacheInvalidationTest). */
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

    // ── строки в формате /sync/pull ──────────────────────────────────────────

    private static JSONObject currency(String uuid, String iso) throws Exception {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", "3c000000-0000-4000-8000-000000000004")
                .put("name", "Test " + iso).put("iso_code", iso).put("icon_uuid", ICON)
                .put("symbol", iso).put("precision", 2).put("show_in_equivalent", false)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject wallet() throws Exception {
        return new JSONObject()
                .put("uuid", WALLET).put("name", "Test wallet TSG").put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false)
                .put("system_kind", 0).put("kind", 1)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject accountOperating(String currencyUuid) throws Exception {
        return new JSONObject()
                .put("uuid", WALLET).put("currency_uuid", currencyUuid)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject changes(Object... tableAndRows) throws Exception {
        JSONObject out = new JSONObject();
        for (int i = 0; i < tableAndRows.length; i += 2) {
            JSONArray rows = new JSONArray();
            for (JSONObject row : (JSONObject[]) tableAndRows[i + 1]) rows.put(row);
            out.put((String) tableAndRows[i], rows);
        }
        return out;
    }

    private static JSONObject[] rows(JSONObject... r) {
        return r;
    }

    // ── applyPage ────────────────────────────────────────────────────────────

    @Test
    public void accountOperating_waitsForCurrencyFromLaterPage() throws Exception {
        PullDeferredRows deferred = PullDeferredRows.inMemory();
        SyncLog log = new SyncLog();

        // Страница 1: кошелёк и его валютная строка, валюты ещё нет.
        SyncEngine.applyPage(db, changes(
                "account", rows(wallet()),
                "account_operating", rows(accountOperating(GEL))), new JSONObject(), null, log, deferred);
        assertNull(walletCurrencyIso());
        assertEquals(1, deferred.size());

        // Страница 2: валюта (её недавно правили — она в хвосте потока).
        SyncEngine.applyPage(db, changes("currency", rows(currency(GEL, "TSG"))),
                new JSONObject(), null, log, deferred);
        assertEquals("TSG", walletCurrencyIso());
        assertTrue(deferred.isEmpty());
    }

    @Test
    public void deferredRow_supersededByNewerVersionInPage() throws Exception {
        PullDeferredRows deferred = PullDeferredRows.inMemory();
        SyncLog log = new SyncLog();
        SyncEngine.applyPage(db, changes("currency", rows(currency(KES, "TSK"))), new JSONObject(), null, log, deferred);
        SyncEngine.applyPage(db, changes(
                "account", rows(wallet()),
                "account_operating", rows(accountOperating(GEL))), new JSONObject(), null, log, deferred);
        assertEquals(1, deferred.size());

        // Новая версия той же строки (кошелёк перевели на KES) приходит вместе с GEL:
        // старая отложенная (GEL) не должна лечь поверх новой.
        SyncEngine.applyPage(db, changes(
                "currency", rows(currency(GEL, "TSG")),
                "account_operating", rows(accountOperating(KES))), new JSONObject(), null, log, deferred);
        assertEquals("TSK", walletCurrencyIso());
        assertTrue(deferred.isEmpty());
    }

    @Test
    public void deferredRows_surviveProcessRestart() throws Exception {
        PullDeferredRows deferred = PullDeferredRows.load(context);
        deferred.add("account_operating", accountOperating(GEL));
        deferred.save();

        PullDeferredRows reloaded = PullDeferredRows.load(context);
        assertEquals(1, reloaded.size());
        assertEquals("account_operating: 1", reloaded.summary());

        reloaded.clear();
        reloaded.save();
        assertFalse(new File(context.getNoBackupFilesDir(), PullDeferredRows.FILE_NAME).exists());
    }

    // ── pullAll целиком ──────────────────────────────────────────────────────

    /**
     * Как на телефоне: полная загрузка, account_operating на первой странице,
     * валюта на последней. После pull у кошелька есть валюта, курсоры дошли до конца.
     */
    @Test
    public void pullAll_currencyOnLastPage_walletGetsCurrency() throws Exception {
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
        AppSettings.setLedgerSyncSeq(context, 0L);
        AppSettings.setReferenceSyncSeq(context, 0L);

        FakeApi api = new FakeApi(context);
        api.page(1, changes("account", rows(wallet()), "account_operating", rows(accountOperating(GEL))));
        api.page(2, changes("currency", rows(currency(KES, "TSK"))));
        api.page(3, changes("currency", rows(currency(GEL, "TSG"))));

        SyncLog log = new SyncLog();
        SyncEngine.pullAll(db, api, context, log);

        assertEquals("TSG", walletCurrencyIso());
        assertEquals(3L, AppSettings.getLedgerSyncSeq(context));
        assertTrue(PullDeferredRows.load(context).isEmpty());
        assertTrue(log.getProblems().isEmpty());
    }

    /** Родителя нет и в конце потока — строка не теряется, а синк говорит об этом. */
    @Test
    public void pullAll_parentNeverArrives_rowKeptAndReported() throws Exception {
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
        AppSettings.setLedgerSyncSeq(context, 0L);
        AppSettings.setReferenceSyncSeq(context, 0L);

        FakeApi api = new FakeApi(context);
        api.page(1, changes("account", rows(wallet()), "account_operating", rows(accountOperating(GEL))));

        SyncLog log = new SyncLog();
        SyncEngine.pullAll(db, api, context, log);
        assertNull(walletCurrencyIso());
        assertEquals(1, PullDeferredRows.load(context).size());
        assertEquals(1, log.getProblems().size());

        // Следующий синк приносит валюту — отложенная строка применяется.
        api.page(2, changes("currency", rows(currency(GEL, "TSG"))));
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertEquals("TSG", walletCurrencyIso());
        assertTrue(PullDeferredRows.load(context).isEmpty());
    }

    /** Уже испорченная база: первый синк после обновления один раз тянет всё с нуля. */
    @Test
    public void pullAll_oneTimeResync_resetsCursors() throws Exception {
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setLedgerSyncSeq(context, 42L);
        AppSettings.setReferenceSyncSeq(context, 7L);

        FakeApi api = new FakeApi(context);
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertEquals(0L, api.calls.get(0)[0]);
        assertEquals(0L, api.calls.get(0)[1]);
        assertTrue(AppSettings.isDeferredRowsResyncDone(context));

        // Второй раз — уже без сброса.
        AppSettings.setLedgerSyncSeq(context, 42L);
        api.calls.clear();
        SyncEngine.pullAll(db, api, context, new SyncLog());
        assertEquals(42L, api.calls.get(0)[0]);
    }

    // ── вспомогательное ──────────────────────────────────────────────────────

    /** ISO валюты кошелька через account_operating; null — строки нет. */
    private String walletCurrencyIso() {
        Cursor c = db.rawQuery("SELECT c.iso_code FROM account a"
                + " JOIN account_operating ao ON ao._id = a._id AND ao.deleted_at IS NULL"
                + " JOIN currency c ON c._id = ao.currency_id WHERE a.uuid = ?", new String[]{WALLET});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Поток леджера страницами: страница с seq N отдаётся, если курсор меньше N.
     * Поток reference пуст.
     */
    private static final class FakeApi extends SyncApiClient {
        private final java.util.Map<Long, JSONObject> pages = new java.util.TreeMap<>();
        /** Курсоры каждого вызова pull: [ledger, reference]. */
        final java.util.List<long[]> calls = new java.util.ArrayList<>();

        FakeApi(Context ctx) {
            super(ctx, "test-token");
        }

        void page(long seq, JSONObject changes) {
            pages.put(seq, changes);
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit) {
            calls.add(new long[]{ledgerSeq, referenceSeq});
            for (java.util.Map.Entry<Long, JSONObject> e : pages.entrySet()) {
                if (e.getKey() > ledgerSeq) {
                    return new PullResult(
                            new PullStream(e.getKey(), e.getValue(), false, null),
                            new PullStream(referenceSeq, new JSONObject(), false, null));
                }
            }
            return new PullResult(
                    new PullStream(ledgerSeq, new JSONObject(), false, null),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }
    }
}
