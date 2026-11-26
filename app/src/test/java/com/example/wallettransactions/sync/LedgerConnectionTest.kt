package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletDbCRUD
import com.example.wallettransactions.Data.WalletDbHelper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Первое подключение к кошельку (openspec new-user-default-set, spec ledger-connection):
 * принятие серверной строки по ключу, удаление нетронутого набора, вопрос об операциях без
 * входа, «Удалить с телефона». База — чистая установка с русским набором.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class LedgerConnectionTest {

    companion object {
        private const val ICON = "0c000000-0000-4000-8000-000000000001"
        private const val LEDGER = "3c000000-0000-4000-8000-000000000004"
        private const val SRV_EUR = "1a000000-0000-4000-8000-000000000011"
        private const val SRV_USD = "1a000000-0000-4000-8000-000000000012"
        private const val SRV_PAIR = "2b000000-0000-4000-8000-000000000021"
        private const val SRV_GROCERIES = "4d000000-0000-4000-8000-000000000031"
        private const val SRV_CAFE = "4d000000-0000-4000-8000-000000000032"
        private const val SRV_CASH = "4d000000-0000-4000-8000-000000000033"

        private fun currencyRow(uuid: String, ledgerId: Any?, iso: String, name: String): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", ledgerId).put("name", name).put("iso_code", iso)
                .put("icon_uuid", ICON).put("symbol", iso).put("precision", 2)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        private fun accountRow(uuid: String, name: String, kind: Int): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", name)
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", kind)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)
    }

    private lateinit var ctx: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("wallet.db")
        helper = WalletDbHelper(ctx)
        db = helper.writableDatabase
        val icon = ContentValues()
        icon.put("name", "server icon")
        icon.put("image", byteArrayOf(1))
        icon.put("icon_type", 2)
        icon.put("uuid", ICON)
        icon.put("origin", 0)
        icon.put("dirty", 0)
        db.insertOrThrow("icon", null, icon)
        SyncEngine.adoptLocalByKey = true
    }

    @After
    fun tearDown() {
        SyncEngine.adoptLocalByKey = false
        AppSettings.setLedgerConnectionPending(ctx, false)
        helper.close()
    }

    // ── Принятие по ключу ─────────────────────────────────────────────────────

    @Test
    fun sharedCurrency_replacesSeedCurrency_sameLocalId() {
        val eurId = longValue("SELECT _id FROM currency WHERE iso_code = 'EUR'")
        assertTrue(SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), SyncLog()))
        assertEquals(1, count("SELECT count(*) FROM currency WHERE iso_code = 'EUR'"))
        val c = db.rawQuery("SELECT _id, uuid, is_shared, dirty, from_seed FROM currency WHERE iso_code = 'EUR'", null)
        try {
            assertTrue(c.moveToFirst())
            assertEquals(eurId, c.getLong(0))
            assertEquals(SRV_EUR, c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(0, c.getInt(3))
            assertEquals(0, c.getInt(4))
        } finally {
            c.close()
        }
    }

    @Test
    fun pairAdoptedBySymbol() {
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), SyncLog())
        SyncEngine.applyCurrency(db, currencyRow(SRV_USD, JSONObject.NULL, "USD", "US Dollar"), SyncLog())
        val pairId = longValue("SELECT _id FROM currency_pair WHERE symbol = 'EURUSD'")
        SyncEngine.applyCurrencyPair(
            db, JSONObject()
                .put("uuid", SRV_PAIR).put("ledger_id", JSONObject.NULL).put("symbol", "EURUSD")
                .put("base_currency_uuid", SRV_EUR).put("quote_currency_uuid", SRV_USD)
                .put("precision", 5).put("deleted_at", JSONObject.NULL), SyncLog()
        )
        assertEquals(1, count("SELECT count(*) FROM currency_pair WHERE symbol = 'EURUSD'"))
        assertEquals(pairId, longValue("SELECT _id FROM currency_pair WHERE uuid = '$SRV_PAIR'"))
    }

    @Test
    fun categoryWithOperations_replacedByServerOne_opsStayAndWaitForPush() {
        val groceries = longValue("SELECT _id FROM account WHERE name = 'Продукты'")
        val salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'")
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", salary.toInt(), groceries.toInt(), 5.0, "")
        WalletDbCRUD.InsertOperation(db, "2026-11-02 10:00", salary.toInt(), groceries.toInt(), 7.0, "")

        SyncEngine.applyAccount(db, accountRow(SRV_GROCERIES, "продукты", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        assertEquals(1, count("SELECT count(*) FROM account WHERE replace(lower(name), 'ё', 'е') = 'продукты'"))
        assertEquals(groceries, longValue("SELECT _id FROM account WHERE uuid = '$SRV_GROCERIES'"))
        assertEquals(0, count("SELECT dirty FROM account WHERE _id = $groceries"))
        assertEquals(2, count("SELECT count(*) FROM operation WHERE acc_to_id = $groceries AND dirty = 1"))
    }

    @Test
    fun otherName_bothCategoriesStay() {
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Кафе'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Кафе и рестораны'"))
    }

    @Test
    fun walletAdopted_accountOperatingTakesServerUuid() {
        // «Наличные» — кошелёк самого набора: тот, кто уже вёл «Наличные» на вебе, при входе
        // получает его серверный uuid, а не второй кошелёк с тем же именем.
        val cash = longValue("SELECT _id FROM account WHERE name = 'Наличные'")
        assertTrue(cash > 0)
        SyncEngine.applyAccount(db, accountRow(SRV_CASH, "наличные", 1), SyncLog(), SyncEngine.PullIconGuard(db))
        assertEquals(cash, longValue("SELECT _id FROM account WHERE uuid = '$SRV_CASH'"))
        assertEquals(SRV_CASH, string("SELECT uuid FROM account_operating WHERE _id = $cash"))
        assertEquals(0, count("SELECT dirty FROM account_operating WHERE _id = $cash"))
    }

    @Test
    fun outsideConnection_collisionSkippedAsBefore() {
        SyncEngine.adoptLocalByKey = false
        val before = string("SELECT uuid FROM currency WHERE iso_code = 'EUR'")
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), SyncLog())
        assertEquals(before, string("SELECT uuid FROM currency WHERE iso_code = 'EUR'"))
        assertEquals(0, count("SELECT count(*) FROM currency WHERE uuid = '$SRV_EUR'"))
    }

    // ── Шаг после загрузки ────────────────────────────────────────────────────

    @Test
    fun emptyLedger_nothingDropped_push() {
        val before = count("SELECT count(*) FROM account")
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, false))
        assertEquals(before, count("SELECT count(*) FROM account"))
        // Пустой кошелёк на сервере получает набор целиком, в том числе два кошелька.
        assertEquals(2, count("SELECT count(*) FROM account WHERE kind = 1"))
        assertEquals(2, count("SELECT count(*) FROM account_operating"))
    }

    @Test
    fun nonEmptyLedger_untouchedSeedWalletsDropped_withTheirCurrency() {
        assertEquals(2, count("SELECT count(*) FROM account_operating"))
        assertEquals(1, count("SELECT count(*) FROM currency WHERE iso_code = 'RUB'"))
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        LedgerConnection.dropUntouchedSeed(db)

        assertEquals(
            "«Наличные» и «Кредитная карта» без операций не уходят в живой кошелёк", 0,
            count("SELECT count(*) FROM account WHERE name IN ('Наличные', 'Кредитная карта')")
        )
        assertEquals(0, count("SELECT count(*) FROM account_operating"))
        assertEquals(0, count("SELECT count(*) FROM account WHERE kind = 1"))
        // Рубль нужен был только этим кошелькам и парам набора — вместе с ними ушёл.
        assertEquals(0, count("SELECT count(*) FROM currency WHERE iso_code = 'RUB'"))
    }

    @Test
    fun nonEmptyLedger_seedWalletWithOperationStays() {
        val salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'")
        val cash = longValue("SELECT _id FROM account WHERE name = 'Наличные'")
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", salary.toInt(), cash.toInt(), 100.0, "")
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        LedgerConnection.dropUntouchedSeed(db)

        assertEquals("кошелёк с операцией остаётся", 1, count("SELECT count(*) FROM account WHERE name = 'Наличные'"))
        assertEquals(1, count("SELECT count(*) FROM account_operating WHERE _id = $cash"))
        assertEquals(
            "а пустой «Кредитная карта» — уходит", 0,
            count("SELECT count(*) FROM account WHERE name = 'Кредитная карта'")
        )
        assertEquals(
            "валюта оставленного кошелька остаётся", 1,
            count("SELECT count(*) FROM currency WHERE iso_code = 'RUB'")
        )
    }

    @Test
    fun nonEmptyLedger_renamedSeedWalletStays() {
        val cash = longValue("SELECT _id FROM account WHERE name = 'Наличные'").toInt()
        val icon = longValue("SELECT icon_id FROM account WHERE _id = $cash").toInt()
        WalletDbCRUD.UpdateAccount(db, cash, "Копилка", "Копилка", icon, false)
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        LedgerConnection.dropUntouchedSeed(db)

        assertEquals(
            "правка снимает метку набора — кошелёк человека остаётся", 1,
            count("SELECT count(*) FROM account WHERE name = 'Копилка'")
        )
        assertEquals(1, count("SELECT count(*) FROM account_operating WHERE _id = $cash"))
    }

    @Test
    fun nonEmptyLedger_seedWalletMarkedAsAssetStays() {
        val cash = longValue("SELECT _id FROM account WHERE name = 'Наличные'")
        WalletDbCRUD.SetAccountAsset(db, cash.toInt(), 1, true)
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        LedgerConnection.dropUntouchedSeed(db)

        assertEquals(
            "кошелёк, отмеченный активом, не нетронутый", 1,
            count("SELECT count(*) FROM account WHERE name = 'Наличные'")
        )
    }

    @Test
    fun emptyLedger_withOfflineOps_noQuestion() {
        val taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'")
        val salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'")
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", salary.toInt(), taxi.toInt(), 5.0, "")
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, false))
    }

    @Test
    fun nonEmptyLedger_untouchedSeedDropped_usedOwnSystemStay() {
        val taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'")
        val salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'")
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", salary.toInt(), taxi.toInt(), 5.0, "")
        WalletDbCRUD.InsertAccount(db, "Шаурма", "", iconId().toInt(), false)
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), SyncLog())
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        LedgerConnection.dropUntouchedSeed(db)

        assertEquals(0, count("SELECT count(*) FROM account WHERE name = 'Домашние животные'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Такси'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Зарплата'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Шаурма'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Transfer commission'"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE name = 'Кафе'"))
        // Пары набора без курсов ушли, за ними — валюты, на которые больше ничего не ссылается.
        assertEquals(0, count("SELECT count(*) FROM currency_pair"))
        assertEquals(1, count("SELECT count(*) FROM currency WHERE iso_code = 'EUR'"))
        assertEquals(0, count("SELECT count(*) FROM currency WHERE iso_code = 'THB'"))
    }

    @Test
    fun nonEmptyLedger_offlineOps_ask_untilTransferChosen() {
        val taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'")
        val salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'")
        for (i in 0 until 7) {
            WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", salary.toInt(), taxi.toInt(), 5.0, "")
        }
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))

        assertEquals(LedgerConnection.Step.ASK, LedgerConnection.afterPull(db, false))
        assertEquals(7, LedgerConnection.offlineOperationCount(db))
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, true))
    }

    @Test
    fun nonEmptyLedger_noOfflineOps_noQuestion() {
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))
        assertEquals(LedgerConnection.Step.PUSH, LedgerConnection.afterPull(db, false))
    }

    @Test
    fun discardOffline_unsentGone_serverRowsAndOwnIconsStay() {
        val taxi = longValue("SELECT _id FROM account WHERE name = 'Такси'")
        val salary = longValue("SELECT _id FROM account WHERE name = 'Зарплата'")
        WalletDbCRUD.InsertOperation(db, "2026-11-01 10:00", salary.toInt(), taxi.toInt(), 5.0, "")
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))
        val icons = count("SELECT count(*) FROM icon")

        LedgerConnection.discardOffline(db)

        assertEquals(0, count("SELECT count(*) FROM operation"))
        assertEquals(0, count("SELECT count(*) FROM account WHERE dirty <> 0"))
        assertEquals(0, count("SELECT count(*) FROM currency WHERE dirty <> 0"))
        assertEquals(0, count("SELECT count(*) FROM currency_pair WHERE dirty <> 0"))
        assertEquals(1, count("SELECT count(*) FROM account WHERE uuid = '$SRV_CAFE'"))
        assertEquals(icons, count("SELECT count(*) FROM icon"))
    }

    // ── Признак подключения (design D4) ───────────────────────────────────────

    @Test
    fun connectionStarts_onFreshDb_andSurvivesPartiallyAdoptedRows() {
        AppSettings.setLedgerConnectionPending(ctx, false)
        // Чистая установка: иконок с сервера ещё нет (иконка из setUp — как своя).
        val own = ContentValues()
        own.put("origin", 1)
        db.update("icon", own, "uuid = ?", arrayOf(ICON))
        SyncRunner.startConnectionIfFresh(ctx, db)
        assertTrue(AppSettings.isLedgerConnectionPending(ctx))
        // Часть загрузки принята (строка уже dirty = 0) — подключение не закончено.
        SyncEngine.applyCurrency(db, currencyRow(SRV_EUR, JSONObject.NULL, "EUR", "Euro"), SyncLog())
        SyncRunner.startConnectionIfFresh(ctx, db)
        assertTrue(AppSettings.isLedgerConnectionPending(ctx))
        AppSettings.setConnectionDecision(ctx, AppSettings.ConnectionDecision.ASKED, 3)
        AppSettings.setLedgerConnectionPending(ctx, false)
        assertFalse(AppSettings.isLedgerConnectionPending(ctx))
        assertEquals(AppSettings.ConnectionDecision.NONE, AppSettings.getConnectionDecision(ctx))
    }

    @Test
    fun hardWipeWhileLinked_connectionPullsFromZero() {
        // «Удалить всё безвозвратно» при живом входе: база пустая, курсоры прежние.
        AppSettings.setLedgerConnectionPending(ctx, false)
        val own = ContentValues()
        own.put("origin", 1)
        db.update("icon", own, "uuid = ?", arrayOf(ICON))
        AppSettings.setLedgerSyncSeq(ctx, 93)
        AppSettings.setReferenceSyncSeq(ctx, 13)
        SyncRunner.startConnectionIfFresh(ctx, db)
        assertTrue(AppSettings.isLedgerConnectionPending(ctx))
        assertEquals(0L, AppSettings.getLedgerSyncSeq(ctx))
        assertEquals(0L, AppSettings.getReferenceSyncSeq(ctx))
        // Прерванное подключение продолжает с достигнутой позиции.
        AppSettings.setLedgerSyncSeq(ctx, 40)
        SyncRunner.startConnectionIfFresh(ctx, db)
        assertEquals(40L, AppSettings.getLedgerSyncSeq(ctx))
    }

    @Test
    fun alreadySynced_noConnection() {
        AppSettings.setLedgerConnectionPending(ctx, false)
        SyncEngine.adoptLocalByKey = false
        SyncEngine.applyAccount(db, accountRow(SRV_CAFE, "Кафе", 0), SyncLog(), SyncEngine.PullIconGuard(db))
        SyncRunner.startConnectionIfFresh(ctx, db)
        assertFalse(AppSettings.isLedgerConnectionPending(ctx))
    }

    // ── Push без метки (starter-set, «Метка только на телефоне») ──────────────

    @Test
    fun pushRows_haveNoFromSeed() {
        val server = ContentValues()
        server.put("origin", 0)
        // Все иконки — «с сервера», заместители не нужны; картинка маленькая — курсор
        // Robolectric не читает строки с PNG набора целиком (SQLiteBlobTooBigException).
        server.put("image", byteArrayOf(1))
        db.update("icon", server, null, null)
        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())
        var rows = 0
        for (table in arrayOf("account", "currency", "currency_pair")) {
            val arr = byTable[table]
            assertNotNull(table, arr)
            for (i in 0 until arr!!.length()) {
                rows++
                val keys = arr.getJSONObject(i).keys()
                while (keys.hasNext()) assertFalse(table, keys.next().contains("from_seed"))
            }
        }
        assertTrue(rows > 0)
    }

    // ── Тексты диалога (spec ledger-connection) ──────────────────────────────

    @Test
    fun dialogTexts() {
        assertEquals(
            "На телефоне 7 операций, внесённых до входа. Перенести их в кошелёк на сервере?",
            LedgerConnection.questionText(7)
        )
        assertEquals(
            "На телефоне 1 операция, внесённая до входа. Перенести их в кошелёк на сервере?",
            LedgerConnection.questionText(1)
        )
        assertEquals(
            "На телефоне 2 операции, внесённые до входа. Перенести их в кошелёк на сервере?",
            LedgerConnection.questionText(2)
        )
        assertEquals("Удалить 7 операций с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(7))
        assertEquals("Удалить 1 операцию с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(1))
        assertEquals("Удалить 11 операций с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(11))
        assertEquals("Удалить 21 операцию с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(21))
        assertEquals("Удалить 34 операции с телефона? Отменить это нельзя.", LedgerConnection.confirmDiscardText(34))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun iconId(): Long = longValue("SELECT _id FROM icon WHERE uuid = '$ICON'")

    private fun count(sql: String): Int = longValue(sql).toInt()

    private fun longValue(sql: String): Long {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun string(sql: String): String? {
        val c = db.rawQuery(sql, null)
        try {
            if (!c.moveToFirst()) return null
            return c.getString(0)
        } finally {
            c.close()
        }
    }
}
