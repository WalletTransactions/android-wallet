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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * openspec debts, раздел 2: {@code counterparty}/{@code account_debt} в push (ключи
 * wallet-api) и pull, конфликты {@code 409 duplicate_name}/{@code counterparty_in_use},
 * однократная повторная отправка и перечит ledger после шага v91.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncEngineDebtsTest {

    private static final String ICON = "0b9d6f3e-1111-4222-8333-444455556666";
    private static final String CURRENCY = "c0000000-0000-4000-8000-000000000001";
    private static final String LOAN = "a1000000-0000-4000-8000-000000000001";
    private static final String VASYA = "d1000000-0000-4000-8000-000000000001";
    private static final String LEDGER = "3c000000-0000-4000-8000-000000000004";

    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_asset", "account_debt", "counterparty",
            "counterparty_contact", "account_operating", "account",
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
        icon.put("name", "unknown");
        icon.put("image", new byte[]{0});
        icon.put("icon_type", 1);
        icon.put("uuid", ICON);
        icon.put("dirty", 0);
        iconId = db.insertOrThrow("icon", null, icon);
        ContentValues cur = new ContentValues();
        cur.put("name", "Доллар");
        cur.put("iso_code", "USD");
        cur.put("icon_id", iconId);
        cur.put("symbol", "$");
        cur.put("uuid", CURRENCY);
        cur.put("dirty", 0);
        currencyId = db.insertOrThrow("currency", null, cur);
        AppSettings.setUuidFormatResyncDone(context);
        AppSettings.setDeferredRowsResyncDone(context);
        AppSettings.setLedgerRereadPending(context, false);
        AppSettings.setDebtsRedirtyPending(context, false);
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
    public void push_counterpartyAndDebt_inServerOrder_withNullableKeysAlwaysPresent() throws Exception {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "сосед");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, (int) currencyId, "", null);
        String cpUuid = string("SELECT uuid FROM counterparty WHERE _id = " + cp);
        String loanUuid = string("SELECT uuid FROM account WHERE _id = " + loan);

        Map<String, JSONArray> byTable = SyncEngine.collectDirtyByTable(db, new ArrayList<>());

        List<String> order = new ArrayList<>(byTable.keySet());
        assertTrue(order.indexOf("counterparty") > order.indexOf("account_operating"));
        assertTrue(order.indexOf("account_debt") > order.indexOf("counterparty"));
        JSONObject c = byTable.get("counterparty").getJSONObject(0);
        assertEquals(cpUuid, c.getString("uuid"));
        assertEquals("Вася", c.getString("name"));
        assertEquals("сосед", c.getString("comment"));
        assertTrue("icon_uuid уходит и при null", c.has("icon_uuid") && c.isNull("icon_uuid"));
        assertFalse(c.getBoolean("is_archived"));
        assertFalse(c.getBoolean("deleted"));
        JSONObject d = byTable.get("account_debt").getJSONObject(0);
        assertEquals(loanUuid, d.getString("uuid"));
        assertEquals(cpUuid, d.getString("counterparty_uuid"));
        assertEquals("", d.getString("comment"));
        assertTrue("due_date уходит и при null — иначе снятый срок не доедет",
                d.has("due_date") && d.isNull("due_date"));
        assertFalse(d.getBoolean("deleted"));
    }

    @Test
    public void push_dueDate_sentAsIsoDate() throws Exception {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        WalletDbCRUD.CreateDebtWallet(db, cp, (int) currencyId, "", "2026-12-31");
        JSONObject d = SyncEngine.collectDirtyByTable(db, new ArrayList<>()).get("account_debt").getJSONObject(0);
        assertEquals("2026-12-31", d.getString("due_date"));
    }

    @Test
    public void pull_appliesCounterpartyAndDebt_defersDebtWithoutCounterparty() throws Exception {
        JSONObject ledger = new JSONObject()
                .put("account", new JSONArray().put(accountRow(LOAN, "Вася · USD")))
                .put("account_operating", new JSONArray().put(new JSONObject()
                        .put("uuid", LOAN).put("currency_uuid", CURRENCY).put("deleted_at", JSONObject.NULL)))
                .put("counterparty", new JSONArray().put(counterpartyRow(VASYA, "Вася", false)))
                .put("account_debt", new JSONArray()
                        .put(debtRow(LOAN, VASYA, "2026-12-31", false))
                        .put(debtRow(LOAN, "d9000000-0000-4000-8000-000000000009", null, false)));
        PullDeferredRows deferred = PullDeferredRows.inMemory();

        Set<String> touched = SyncEngine.applyPage(db, ledger, new JSONObject(), null, new SyncLog(), deferred);

        assertTrue(touched.contains("counterparty"));
        assertTrue(touched.contains("account_debt"));
        assertEquals("Вася", string("SELECT name FROM counterparty WHERE uuid = '" + VASYA + "'"));
        assertEquals(0, number("SELECT dirty FROM counterparty"));
        long accountId = number("SELECT _id FROM account WHERE uuid = '" + LOAN + "'");
        assertEquals(accountId, number("SELECT _id FROM account_debt WHERE uuid = '" + LOAN + "'"));
        assertEquals(VASYA, string("SELECT counterparty_uuid FROM account_debt"));
        assertEquals("2026-12-31", string("SELECT due_date FROM account_debt"));
        assertEquals(0, number("SELECT dirty FROM account_debt"));
        assertFalse("заём на неизвестного контрагента отложен", deferred.isEmpty());
    }

    @Test
    public void pull_debtBeforeCounterparty_appliedWhenCounterpartyArrives() throws Exception {
        localSyncedLoan();
        PullDeferredRows deferred = PullDeferredRows.inMemory();
        SyncEngine.applyPage(db, new JSONObject()
                .put("account_debt", new JSONArray().put(debtRow(LOAN, VASYA, null, false))),
                new JSONObject(), null, new SyncLog(), deferred);
        assertEquals(0, number("SELECT count(*) FROM account_debt"));
        assertFalse(deferred.isEmpty());

        SyncEngine.applyPage(db, new JSONObject()
                .put("counterparty", new JSONArray().put(counterpartyRow(VASYA, "Вася", false))),
                new JSONObject(), null, new SyncLog(), deferred);
        assertEquals(1, number("SELECT count(*) FROM account_debt WHERE deleted_at IS NULL"));
        assertTrue(deferred.isEmpty());
    }

    @Test
    public void pull_counterpartyTombstone_withLiveLocalDebt_keepsItAliveAndDirty() throws Exception {
        localSyncedLoan();
        SyncEngine.applyPage(db, new JSONObject()
                .put("counterparty", new JSONArray().put(counterpartyRow(VASYA, "Вася", false)))
                .put("account_debt", new JSONArray().put(debtRow(LOAN, VASYA, null, false))),
                new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());

        SyncEngine.applyPage(db, new JSONObject()
                .put("counterparty", new JSONArray().put(counterpartyRow(VASYA, "Вася", true))),
                new JSONObject(), null, new SyncLog(), PullDeferredRows.inMemory());

        assertEquals("займ не теряет контрагента", 0, number(
                "SELECT count(*) FROM counterparty WHERE deleted_at IS NOT NULL"));
        assertEquals("уйдёт снова", 1, number("SELECT dirty FROM counterparty"));
    }

    // ── 2.2 конфликты ───────────────────────────────────────────────────────

    @Test
    public void duplicateName_sameCounterpartyOnTwoDevices_debtsPointToSurvivor() throws Exception {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int loan = WalletDbCRUD.CreateDebtWallet(db, cp, (int) currencyId, "", null);
        String ours = string("SELECT uuid FROM counterparty WHERE _id = " + cp);
        insertContact(ours);
        ScriptedApi api = new ScriptedApi(context);
        api.pushAnswers.add(new SyncApiClient.HttpResult(409, new JSONObject()
                .put("error", "duplicate_name")
                .put("conflicts", new JSONArray().put(new JSONObject()
                        .put("table", "counterparty").put("reason", "duplicate_name")
                        .put("uuid", ours).put("existing_uuid", VASYA)))));

        SyncLog log = new SyncLog();
        SyncEngine.pushDirty(db, api, log);

        assertEquals("второй раунд ушёл", 2, api.pushed.size());
        assertEquals(VASYA, string("SELECT uuid FROM counterparty WHERE _id = " + cp));
        assertEquals(VASYA, string("SELECT counterparty_uuid FROM account_debt WHERE _id = " + loan));
        assertEquals(VASYA, string("SELECT counterparty_uuid FROM counterparty_contact"));
        JSONObject retried = api.pushed.get(1);
        assertEquals(VASYA, retried.getJSONArray("counterparty").getJSONObject(0).getString("uuid"));
        assertEquals(VASYA, retried.getJSONArray("account_debt").getJSONObject(0).getString("counterparty_uuid"));
        assertEquals(0, number("SELECT count(*) FROM counterparty WHERE dirty = 1"));
    }

    @Test
    public void counterpartyInUse_restoresCounterparty_andSendsAgain() throws Exception {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        String uuid = string("SELECT uuid FROM counterparty WHERE _id = " + cp);
        db.execSQL("UPDATE counterparty SET dirty = 0");
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.deleted, WalletDbCRUD.DeleteCounterparty(db, cp));
        ScriptedApi api = new ScriptedApi(context);
        api.pushAnswers.add(new SyncApiClient.HttpResult(409, new JSONObject()
                .put("error", "counterparty_in_use").put("table", "counterparty").put("uuid", uuid)));

        SyncLog log = new SyncLog();
        SyncEngine.pushDirty(db, api, log);

        assertEquals(2, api.pushed.size());
        assertNull("контрагент снова в списке", nullableString(
                "SELECT deleted_at FROM counterparty WHERE _id = " + cp));
        assertFalse(api.pushed.get(1).getJSONArray("counterparty").getJSONObject(0).getBoolean("deleted"));
        assertFalse(log.isEmpty());
    }

    @Test
    public void parseCounterpartyInUse_onlyThatShape() throws Exception {
        assertEquals(VASYA, SyncEngine.parseCounterpartyInUse(409, new JSONObject()
                .put("error", "counterparty_in_use").put("table", "counterparty").put("uuid", VASYA)));
        assertNull(SyncEngine.parseCounterpartyInUse(400, new JSONObject()
                .put("error", "counterparty_in_use").put("table", "counterparty").put("uuid", VASYA)));
        assertNull(SyncEngine.parseCounterpartyInUse(409, new JSONObject()
                .put("error", "missing_reference").put("table", "counterparty").put("uuid", VASYA)));
        assertNull(SyncEngine.parseCounterpartyInUse(409, new JSONObject()
                .put("error", "counterparty_in_use").put("table", "account").put("uuid", VASYA)));
    }

    // ── 2.3 повторная отправка и перечит после v91 ──────────────────────────

    @Test
    public void redirty_onceAfterUpgrade_marksLiveRowsOnly() throws Exception {
        int cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        int gone = WalletDbCRUD.CreateCounterparty(db, "Удалённый", "");
        WalletDbCRUD.CreateDebtWallet(db, cp, (int) currencyId, "", null);
        WalletDbCRUD.DeleteCounterparty(db, gone);
        db.execSQL("UPDATE counterparty SET dirty = 0");
        db.execSQL("UPDATE account_debt SET dirty = 0");
        AppSettings.setDebtsRedirtyPending(context, true);

        SyncEngine.redirtyDebtsIfPending(db, context, new SyncLog());

        assertEquals(1, number("SELECT dirty FROM counterparty WHERE _id = " + cp));
        assertEquals("удалённый не воскресает и не отправляется", 0,
                number("SELECT dirty FROM counterparty WHERE _id = " + gone));
        assertEquals(1, number("SELECT dirty FROM account_debt"));
        assertFalse(AppSettings.isDebtsRedirtyPending(context));

        db.execSQL("UPDATE counterparty SET dirty = 0");
        SyncEngine.redirtyDebtsIfPending(db, context, new SyncLog());
        assertEquals("второй раз — ничего", 0, number("SELECT dirty FROM counterparty WHERE _id = " + cp));
    }

    @Test
    public void reread_pullsLedgerFromZero_keepsReference_appliesCounterparty() throws Exception {
        AppSettings.setSyncLedgerId(context, LEDGER);
        AppSettings.setLedgerSyncSeq(context, 40L);
        AppSettings.setReferenceSyncSeq(context, 5L);
        AppSettings.setLedgerRereadPending(context, true);
        localSyncedLoan();

        ScriptedApi api = new ScriptedApi(context);
        api.steps.add((l, r) -> ScriptedApi.page(45, new JSONObject()
                .put("counterparty", new JSONArray().put(counterpartyRow(VASYA, "Вася", false)))
                .put("account_debt", new JSONArray().put(debtRow(LOAN, VASYA, null, false))), r));
        SyncEngine.pullAll(db, api, context, new SyncLog());

        assertEquals(0L, api.calls.get(0)[0]);
        assertEquals(5L, api.calls.get(0)[1]);
        assertEquals(LEDGER, AppSettings.getSyncLedgerId(context));
        assertFalse(AppSettings.isLedgerRereadPending(context));
        assertEquals(1, number("SELECT count(*) FROM account_debt"));

        ScriptedApi again = new ScriptedApi(context);
        SyncEngine.pullAll(db, again, context, new SyncLog());
        assertEquals(45L, again.calls.get(0)[0]);
    }

    @Test
    public void upgradeStep_raisesRedirtyFlag() {
        WalletDbHelper helper = new WalletDbHelper(context);
        helper.onUpgrade(db, 90, 91);
        assertTrue(AppSettings.isDebtsRedirtyPending(context));
        assertTrue(AppSettings.isLedgerRereadPending(context));
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private void localSyncedLoan() {
        ContentValues acc = new ContentValues();
        acc.put("name", "Вася · USD");
        acc.put("comment", "");
        acc.put("icon_id", iconId);
        acc.put("kind", 1);
        acc.put("uuid", LOAN);
        acc.put("dirty", 0);
        long id = db.insertOrThrow("account", null, acc);
        ContentValues op = new ContentValues();
        op.put("_id", id);
        op.put("currency_id", currencyId);
        op.put("uuid", LOAN);
        op.put("dirty", 0);
        db.insertOrThrow("account_operating", null, op);
    }

    private void insertContact(String counterpartyUuid) {
        ContentValues cv = new ContentValues();
        cv.put("counterparty_uuid", counterpartyUuid);
        cv.put("lookup_key", "lk");
        db.insertOrThrow("counterparty_contact", null, cv);
    }

    private static JSONObject accountRow(String uuid, String name) throws org.json.JSONException {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", 1)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject counterpartyRow(String uuid, String name, boolean deleted) throws org.json.JSONException {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", "")
                .put("icon_uuid", JSONObject.NULL).put("is_archived", false)
                .put("deleted_at", deleted ? "2026-11-13T10:00:00Z" : JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
    }

    private static JSONObject debtRow(String uuid, String counterparty, String due, boolean deleted)
            throws org.json.JSONException {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("counterparty_uuid", counterparty)
                .put("comment", "").put("due_date", due == null ? JSONObject.NULL : due)
                .put("deleted_at", deleted ? "2026-11-13T10:00:00Z" : JSONObject.NULL)
                .put("updated_by", JSONObject.NULL);
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
        String s = nullableString(sql);
        assertTrue(sql, s != null);
        return s;
    }

    private String nullableString(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.isNull(0) ? null : c.getString(0);
        } finally {
            c.close();
        }
    }

    /** Pull — сценарий по шагам; push — очередь ответов, дальше 200. */
    private static final class ScriptedApi extends SyncApiClient {
        interface Step {
            PullResult answer(long ledgerSeq, long referenceSeq) throws IOException, org.json.JSONException;
        }

        final Deque<Step> steps = new ArrayDeque<>();
        final Deque<HttpResult> pushAnswers = new ArrayDeque<>();
        final List<long[]> calls = new ArrayList<>();
        final List<JSONObject> pushed = new ArrayList<>();

        ScriptedApi(Context ctx) {
            super(ctx, "test-token");
        }

        @Override
        HttpResult push(String batchId, JSONObject changes) {
            try {
                pushed.add(new JSONObject(changes.toString()));
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
            HttpResult answer = pushAnswers.poll();
            return answer != null ? answer : new HttpResult(200, new JSONObject());
        }

        @Override
        PullResult pull(long ledgerSeq, long referenceSeq, int limit, String ledgerId) throws IOException {
            calls.add(new long[]{ledgerSeq, referenceSeq});
            Step step = steps.poll();
            if (step == null) {
                return new PullResult(new PullStream(ledgerSeq, new JSONObject(), false, LEDGER),
                        new PullStream(referenceSeq, new JSONObject(), false, null));
            }
            try {
                return step.answer(ledgerSeq, referenceSeq);
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }

        static PullResult page(long nextSeq, JSONObject ledgerChanges, long referenceSeq) {
            return new PullResult(new PullStream(nextSeq, ledgerChanges, false, LEDGER),
                    new PullStream(referenceSeq, new JSONObject(), false, null));
        }
    }
}
