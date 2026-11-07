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

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * Первое подключение к кошельку (openspec new-user-default-set, spec ledger-connection):
 * принятие серверной строки по ключу, удаление нетронутого набора, вопрос об операциях без
 * входа, «Удалить с телефона». База — чистая установка с русским набором.
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "ru")
public class LedgerConnectionTest {

    private static final String ICON = "0c000000-0000-4000-8000-000000000001";
    private static final String LEDGER = "3c000000-0000-4000-8000-000000000004";
    private static final String SRV_EUR = "1a000000-0000-4000-8000-000000000011";
    private static final String SRV_USD = "1a000000-0000-4000-8000-000000000012";
    private static final String SRV_PAIR = "2b000000-0000-4000-8000-000000000021";
    private static final String SRV_GROCERIES = "4d000000-0000-4000-8000-000000000031";
    private static final String SRV_CAFE = "4d000000-0000-4000-8000-000000000032";
    private static final String SRV_CASH = "4d000000-0000-4000-8000-000000000033";

    private Context ctx;
    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(ctx);
        db = helper.getWritableDatabase();
        ContentValues icon = new ContentValues();
        icon.put("name", "server icon");
        icon.put("image", new byte[]{1});
        icon.put("icon_type", 2);
        icon.put("uuid", ICON);
        icon.put("origin", 0);
        icon.put("dirty", 0);
        db.insertOrThrow("icon", null, icon);
        SyncEngine.adoptLocalByKey = true;
    }

    @After
    public void tearDown() {
        SyncEngine.adoptLocalByKey = false;
        AppSettings.setLedgerConnectionPending(ctx, false);
        helper.close();
    }

    // ── Принятие по ключу ─────────────────────────────────────────────────────

    @Test
    public void sharedCurrency_replacesSeedCurrency_sameLocalId() throws Exception {
        long eurId = longValue("SELECT _id FROM currency WHERE iso_code = 'EUR'");
        assertTrue(SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), new SyncLog()));
        assertEquals(1, count("SELECT count(*) FROM currency WHERE iso_code = 'EUR'"));
        Cursor c = db.rawQuery("SELECT _id, uuid, is_shared, dirty, from_seed FROM currency WHERE iso_code = 'EUR'", null);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(eurId, c.getLong(0));
            assertEquals(SRV_EUR, c.getString(1));
            assertEquals(1, c.getInt(2));
            assertEquals(0, c.getInt(3));
            assertEquals(0, c.getInt(4));
        } finally {
            c.close();
        }
    }

    @Test
    public void pairAdoptedBySymbol() throws Exception {
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), new SyncLog());
        SyncEngine.applyCurrency(db, currencyRow(SRV_USD, JSONObject.NULL, "USD", "US Dollar"), new SyncLog());
        long pairId = longValue("SELECT _id FROM currency_pair WHERE symbol = 'EURUSD'");
        SyncEngine.applyCurrencyPair(db, new JSONObject()
                .put("uuid", SRV_PAIR).put("ledger_id", JSONObject.NULL).put("symbol", "EURUSD")
                .put("base_currency_uuid", SRV_EUR).put("quote_currency_uuid", SRV_USD)
                .put("precision", 5).put("deleted_at", JSONObject.NULL), new SyncLog());
        assertEquals(1, count("SELECT count(*) FROM currency_pair WHERE symbol = 'EURUSD'"));
        assertEquals(pairId, longValue("SELECT _id FROM currency_pair WHERE uuid = '" + SRV_PAIR + "'"));
    }

    @Test
    public void categoryWithOperations_replacedByServerOne_opsStayAndWaitForPush() throws Exception {
        long groceries = longValue("SELECT _id FROM account WHERE name = 'Продукты'");
        long salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'");
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", (int) salary, (int) groceries, 5, "");
        WalletDbCRUD.InsertOperation(db, "2026-11-02 10:00", (int) salary, (int) groceries, 7, "");

        SyncEngine.applyAccount(db, accountRow(SRV_GROCERIES, "продукты", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));

        assertEquals(1, count("SELECT count(*) FROM account WHERE replace(lower(name), 'ё', 'е') = 'продукты'"));
        assertEquals(groceries, longValue("SELECT _id FROM account WHERE uuid = '" + SRV_GROCERIES + "'"));
        assertEquals(0, count("SELECT dirty FROM account WHERE _id = " + groceries));
        assertEquals(2, count("SELECT count(*) FROM operation WHERE acc_to_id = " + groceries + " AND dirty = 1"));
    }

    @Test
    public void otherName_bothCategoriesStay() throws Exception {
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Кафе'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Кафе и рестораны'"));
    }

    @Test
    public void walletAdopted_accountOperatingTakesServerUuid() throws Exception {
        long eur = longValue("SELECT _id FROM currency WHERE iso_code = 'EUR'");
        long cash = WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", (int) iconId(), (int) eur);
        assertTrue(cash > 0);
        SyncEngine.applyAccount(db, accountRow(SRV_CASH, "наличные", 1), new SyncLog(), new SyncEngine.PullIconGuard(db));
        assertEquals(cash, longValue("SELECT _id FROM account WHERE uuid = '" + SRV_CASH + "'"));
        assertEquals(SRV_CASH, string("SELECT uuid FROM account_operating WHERE _id = " + cash));
        assertEquals(0, count("SELECT dirty FROM account_operating WHERE _id = " + cash));
    }

    @Test
    public void outsideConnection_collisionSkippedAsBefore() throws Exception {
        SyncEngine.adoptLocalByKey = false;
        String before = string("SELECT uuid FROM currency WHERE iso_code = 'EUR'");
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), new SyncLog());
        assertEquals(before, string("SELECT uuid FROM currency WHERE iso_code = 'EUR'"));
        assertEquals(0, count("SELECT count(*) FROM currency WHERE uuid = '" + SRV_EUR + "'"));
    }

    // ── Шаг после загрузки ────────────────────────────────────────────────────

    @Test
    public void emptyLedger_nothingDropped_push() {
        int before = count("SELECT count(*) FROM account");
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, false));
        assertEquals(before, count("SELECT count(*) FROM account"));
    }

    @Test
    public void emptyLedger_withOfflineOps_noQuestion() {
        long taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'");
        long salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'");
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", (int) salary, (int) taxi, 5, "");
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, false));
    }

    @Test
    public void nonEmptyLedger_untouchedSeedDropped_usedOwnSystemStay() throws Exception {
        long taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'");
        long salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'");
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", (int) salary, (int) taxi, 5, "");
        WalletDbCRUD.InsertAccount(db, "Шаурма", "", (int) iconId(), false);
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), new SyncLog());
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));

        LedgerConnection.dropUntouchedSeed(db);

        assertEquals(0, count("SELECT count(*) FROM account WHERE name = 'Домашние животные'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Такси'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Зарплата'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Шаурма'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer commission'"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Кафе'"));
        // Пары набора без курсов ушли, за ними — валюты, на которые больше ничего не ссылается.
        assertEquals(0, count("SELECT count(*) FROM currency_pair"));
        assertEquals(1, count("SELECT count(*) FROM currency WHERE iso_code = 'EUR'"));
        assertEquals(0, count("SELECT count(*) FROM currency WHERE iso_code = 'THB'"));
    }

    @Test
    public void nonEmptyLedger_offlineOps_ask_untilTransferChosen() throws Exception {
        long taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'");
        long salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'");
        for (int i = 0; i < 7; i++) {
            WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", (int) salary, (int) taxi, 5, "");
        }
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));

        assertEquals(LedgerConnection.Step.ASK, LedgerConnection.afterPull(db, false));
        assertEquals(7, LedgerConnection.offlineOperationCount(db));
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, true));
    }

    @Test
    public void nonEmptyLedger_noOfflineOps_noQuestion() throws Exception {
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, false));
    }

    @Test
    public void discardOffline_unsentGone_serverRowsAndOwnIconsStay() throws Exception {
        long taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'");
        long salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'");
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", (int) salary, (int) taxi, 5, "");
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));
        int icons = count("SELECT count(*) FROM icon");

        LedgerConnection.discardOffline(db);

        assertEquals(0, count("SELECT count(*) FROM operation"));
        assertEquals(0, count("SELECT count(*) FROM account WHERE dirty <> 0"));
        assertEquals(0, count("SELECT count(*) FROM currency WHERE dirty <> 0"));
        assertEquals(0, count("SELECT count(*) FROM currency_pair WHERE dirty <> 0"));
        assertEquals(1, count("SELECT count(*) FROM account WHERE uuid = '" + SRV_CAFE + "'"));
        assertEquals(icons, count("SELECT count(*) FROM icon"));
    }

    // ── Признак подключения (design D4) ───────────────────────────────────────

    @Test
    public void connectionStarts_onFreshDb_andSurvivesPartiallyAdoptedRows() throws Exception {
        AppSettings.setLedgerConnectionPending(ctx, false);
        // Чистая установка: иконок с сервера ещё нет (иконка из setUp — как своя).
        ContentValues own = new ContentValues();
        own.put("origin", 1);
        db.update("icon", own, "uuid = ?", new String[]{ICON});
        SyncRunner.startConnectionIfFresh(ctx, db);
        assertTrue(AppSettings.isLedgerConnectionPending(ctx));
        // Часть загрузки принята (строка уже dirty = 0) — подключение не закончено.
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), new SyncLog());
        SyncRunner.startConnectionIfFresh(ctx, db);
        assertTrue(AppSettings.isLedgerConnectionPending(ctx));
        AppSettings.setConnectionDecision(ctx, AppSettings.ConnectionDecision.ASKED, 3);
        AppSettings.setLedgerConnectionPending(ctx, false);
        assertFalse(AppSettings.isLedgerConnectionPending(ctx));
        assertEquals(AppSettings.ConnectionDecision.NONE, AppSettings.getConnectionDecision(ctx));
    }

    @Test
    public void alreadySynced_noConnection() throws Exception {
        AppSettings.setLedgerConnectionPending(ctx, false);
        SyncEngine.adoptLocalByKey = false;
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), new SyncLog(), new SyncEngine.PullIconGuard(db));
        SyncRunner.startConnectionIfFresh(ctx, db);
        assertFalse(AppSettings.isLedgerConnectionPending(ctx));
    }

    // ── Push без метки (starter-set, «Метка только на телефоне») ──────────────

    @Test
    public void pushRows_haveNoFromSeed() throws Exception {
        ContentValues server = new ContentValues();
        server.put("origin", 0);
        // Все иконки — «с сервера», заместители не нужны; картинка маленькая — курсор
        // Robolectric не читает строки с PNG набора целиком (SQLiteBlobTooBigException).
        server.put("image", new byte[]{1});
        db.update("icon", server, null, null);
        Map<String, JSONArray> byTable = SyncEngine.collectDirtyByTable(db, new ArrayList<>());
        int rows = 0;
        for (String table : new String[]{"account", "currency", "currency_pair"}) {
            JSONArray arr = byTable.get(table);
            assertNotNull(table, arr);
            for (int i = 0; i < arr.length(); i++) {
                rows++;
                Iterator<String> keys = arr.getJSONObject(i).keys();
                while (keys.hasNext()) assertFalse(table, keys.next().contains("from_seed"));
            }
        }
        assertTrue(rows > 0);
    }

    // ── Тексты диалога (spec ledger-connection) ──────────────────────────────

    @Test
    public void dialogTexts() {
        assertEquals("На телефоне 7 операций, внесённых до входа. Перенести их в кошелёк на сервере?",
                LedgerConnection.questionText(7));
        assertEquals("На телефоне 1 операция, внесённая до входа. Перенести их в кошелёк на сервере?",
                LedgerConnection.questionText(1));
        assertEquals("На телефоне 2 операции, внесённые до входа. Перенести их в кошелёк на сервере?",
                LedgerConnection.questionText(2));
        assertEquals("Удалить 7 операций с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(7));
        assertEquals("Удалить 1 операцию с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(1));
        assertEquals("Удалить 11 операций с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(11));
        assertEquals("Удалить 21 операцию с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(21));
        assertEquals("Удалить 34 операции с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(34));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JSONObject currencyRow(String uuid, Object ledgerId, String iso, String name) throws Exception {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", ledgerId).put("name", name).put("iso_code", iso)
                .put("icon_uuid", ICON).put("symbol", iso).put("precision", 2)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private static JSONObject accountRow(String uuid, String name, int kind) throws Exception {
        return new JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", name)
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", kind)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL);
    }

    private long iconId() {
        return longValue("SELECT _id FROM icon WHERE uuid = '" + ICON + "'");
    }

    private int count(String sql) {
        return (int) longValue(sql);
    }

    private long longValue(String sql) {
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
            if (!c.moveToFirst()) return null;
            return c.getString(0);
        } finally {
            c.close();
        }
    }
}
