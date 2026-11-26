package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletContract
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * openspec purge-synced-tombstones: чистка отправленных удалений, удаление
 * неизвестной строки, загрузка с нуля с вычисткой, параметр `ledger_id`.
 * База — та же, что у Singleton (pullAll в конце перечитывает его кэши), сид
 * вычищен, как в [SyncEnginePullDeferredTest].
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTombstonesTest {

    companion object {
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val LEDGER = "3c000000-0000-4000-8000-000000000004"

        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "operation_template_step", "operation_template", WalletDbHelper.SYNC_RELOAD_SEEN_TABLE,
        )

        private fun resetSingleton() {
            for (f in arrayOf(
                "dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"
            )) {
                val field = Singleton::class.java.getDeclaredField(f)
                field.isAccessible = true
                field.set(Singleton.instance, null)
            }
            val pairs = Singleton::class.java.getDeclaredField("currencyPairList")
            pairs.isAccessible = true
            pairs.set(Singleton.instance, CurrencyPairList())
        }

        // ── строки в формате /sync/pull ──────────────────────────────────────

        private fun accountRow(uuid: String, deleted: Boolean): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", "Acc $uuid").put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", 0)
                .put("deleted_at", if (deleted) "2026-11-04T10:00:00Z" else JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)

        private fun operationRow(uuid: String, from: String, to: String, deleted: Boolean): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("date", "2026-11-04T12:00:00")
                .put("acc_fr_uuid", from).put("acc_to_uuid", to).put("amount", "10.00").put("comment", "")
                .put("deleted_at", if (deleted) "2026-11-04T10:00:00Z" else JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)

        private fun changes(table: String, vararg rows: JSONObject): JSONObject {
            val arr = JSONArray()
            for (r in rows) arr.put(r)
            return JSONObject().put(table, arr)
        }

        private fun merge(a: JSONObject, b: JSONObject): JSONObject {
            val out = JSONObject(a.toString())
            val keys = b.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out.put(k, b.getJSONArray(k))
            }
            return out
        }
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var iconId = 0L

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, PullDeferredRows.FILE_NAME).delete()
        db = Singleton.instance.getWritableDatabase(context)
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        val icon = ContentValues()
        icon.put("name", "test_icon")
        icon.put("image", byteArrayOf(0))
        icon.put("icon_type", 1)
        icon.put("uuid", ICON)
        iconId = db.insertOrThrow("icon", null, icon) // dirty = 1: не «синкнутая»
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // ── локальные строки ─────────────────────────────────────────────────────

    private fun account(uuid: String, dirty: Int, deleted: Boolean): Long {
        val cv = ContentValues()
        cv.put("name", "Acc $uuid")
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("uuid", uuid)
        cv.put("dirty", dirty)
        if (deleted) cv.put("deleted_at", 1_700_000_000_000L)
        return db.insertOrThrow("account", null, cv)
    }

    private fun operation(uuid: String, from: Long, to: Long, dirty: Int, deleted: Boolean): Long {
        val cv = ContentValues()
        cv.put("date", "2026-11-04 12:00:00")
        cv.put("acc_fr_id", from)
        cv.put("acc_to_id", to)
        cv.put("amount", "10")
        cv.put("comment", "")
        cv.put("uuid", uuid)
        cv.put("dirty", dirty)
        if (deleted) cv.put("deleted_at", 1_700_000_000_000L)
        return db.insertOrThrow("operation", null, cv)
    }

    private fun link(uuid: String, main: Long, linked: Long, dirty: Int, deleted: Boolean) {
        val cv = ContentValues()
        cv.put("main_id", main)
        cv.put("link_id", linked)
        cv.put("uuid", uuid)
        cv.put("dirty", dirty)
        if (deleted) cv.put("deleted_at", 1_700_000_000_000L)
        db.insertOrThrow("operation_link", null, cv)
    }

    /** Шаблон с расписанием и одним шагом «остаток» (operation-templates) — ссылка шага на счета. */
    private fun recurring(from: Long, to: Long) {
        val t = ContentValues()
        t.put(WalletContract.OperationTemplateEntry.COLUMN_UUID, "tpl-$from-$to")
        t.put(WalletContract.OperationTemplateEntry.COLUMN_NAME, "Шаблон")
        t.put(WalletContract.OperationTemplateEntry.COLUMN_TOTAL_AMOUNT, 5)
        t.put(WalletContract.OperationTemplateEntry.COLUMN_FREQUENCY, 1)
        t.put(WalletContract.OperationTemplateEntry.COLUMN_START_DATE, "2026-11-01 12:00")
        val templateId = db.insertOrThrow(WalletContract.OperationTemplateEntry.TABLE_NAME, null, t)
        val cv = ContentValues()
        cv.put(WalletContract.OperationTemplateStepEntry.COLUMN_UUID, "step-$from-$to")
        cv.put(WalletContract.OperationTemplateStepEntry.COLUMN_TEMPLATE_ID, templateId)
        cv.put(WalletContract.OperationTemplateStepEntry.COLUMN_NUM, 1)
        cv.put(WalletContract.OperationTemplateStepEntry.COLUMN_FROM, from)
        cv.put(WalletContract.OperationTemplateStepEntry.COLUMN_TO, to)
        cv.put(WalletContract.OperationTemplateStepEntry.COLUMN_AMOUNT_MODE, 2)
        db.insertOrThrow(WalletContract.OperationTemplateStepEntry.TABLE_NAME, null, cv)
    }

    private fun exists(table: String, uuid: String): Boolean {
        val c = db.rawQuery("SELECT 1 FROM $table WHERE uuid = ?", arrayOf(uuid))
        try {
            return c.moveToFirst()
        } finally {
            c.close()
        }
    }

    private fun dirtyOf(table: String, uuid: String): Int {
        val c = db.rawQuery("SELECT dirty FROM $table WHERE uuid = ?", arrayOf(uuid))
        try {
            assertTrue(c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun count(table: String): Int {
        val c = db.rawQuery("SELECT COUNT(*) FROM $table", null)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    // ── 1.1 чистка отправленных удалений ─────────────────────────────────────

    @Test
    fun purge_removesSentTombstones_keepsUnsentAndReferenced() {
        val a = account("a-live", 0, false)
        val b = account("b-live", 0, false)
        val referenced = account("acc-referenced", 0, true)
        val withRule = account("acc-recurring", 0, true)
        val lonely = account("acc-lonely", 0, true)
        operation("op-sent", a, b, 0, true)
        operation("op-unsent", a, b, 1, true)
        operation("op-live", referenced, b, 0, false)
        operation("op-lonely", lonely, b, 0, true)
        recurring(withRule, b)

        val deleted = SyncEngine.purgeTombstones(db)

        assertEquals(3, deleted) // op-sent, op-lonely, acc-lonely (лист и корень в одном проходе)
        assertFalse(exists("operation", "op-sent"))
        assertFalse(exists("operation", "op-lonely"))
        assertFalse(exists("account", "acc-lonely"))
        assertTrue("неотправленное удаление уйдёт следующим проходом", exists("operation", "op-unsent"))
        assertTrue("на счёт ссылается живая операция", exists("account", "acc-referenced"))
        assertTrue("на счёт ссылается правило (CASCADE не должен его унести)", exists("account", "acc-recurring"))
        assertEquals(1, count(WalletContract.OperationTemplateStepEntry.TABLE_NAME))
        // Удаление не поднимает dirty у других строк.
        assertEquals(0, dirtyOf("account", "a-live"))
        assertEquals(0, dirtyOf("operation", "op-live"))
        assertEquals(1, dirtyOf("operation", "op-unsent"))
    }

    @Test
    fun purge_keepsDeletedOperationWithLiveLink() {
        val a = account("a", 0, false)
        val b = account("b", 0, false)
        val main = operation("op-main-deleted", a, b, 0, true)
        val linked = operation("op-linked", a, b, 0, false)
        link("link-live", main, linked, 1, false) // CASCADE унёс бы её вместе с операцией

        SyncEngine.purgeTombstones(db)

        assertTrue(exists("operation", "op-main-deleted"))
        assertTrue(exists("operation_link", "link-live"))
    }

    @Test
    fun purge_deletedLinkThenItsOperation() {
        val a = account("a", 0, false)
        val b = account("b", 0, false)
        val main = operation("op-main", a, b, 0, true)
        val linked = operation("op-linked", a, b, 0, true)
        link("link-deleted", main, linked, 0, true)

        assertEquals(3, SyncEngine.purgeTombstones(db))
        assertEquals(0, count("operation"))
        assertEquals(0, count("operation_link"))
    }

    // ── 2.1 удаление неизвестной строки ──────────────────────────────────────

    @Test
    fun applyPage_deletionOfUnknownRow_changesNothing() {
        account("a", 0, false)
        account("b", 0, false)
        val log = SyncLog()
        SyncEngine.applyPage(
            db,
            merge(
                changes("operation", operationRow("op-unknown", "a", "b", true)),
                changes("account", accountRow("acc-unknown", true))
            ),
            JSONObject(), null, log, PullDeferredRows.inMemory()
        )

        assertFalse(exists("operation", "op-unknown"))
        assertFalse(exists("account", "acc-unknown"))
        assertTrue(log.isEmpty())
    }

    @Test
    fun applyPage_deletionOfKnownRow_stillApplied() {
        val a = account("a", 0, false)
        val b = account("b", 0, false)
        operation("op-known", a, b, 0, false)
        val log = SyncLog()
        SyncEngine.applyPage(
            db, changes("operation", operationRow("op-known", "a", "b", true)),
            JSONObject(), null, log, PullDeferredRows.inMemory()
        )
        assertTrue(exists("operation", "op-known"))
        assertFalse(log.isEmpty())
    }

    // ── 3.x загрузка с нуля ──────────────────────────────────────────────────

    /** Сценарий ответов pull: по одному на вызов; IOException — обрыв сети. */
    private class ScriptedApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        fun interface Step {
            @Throws(IOException::class)
            fun answer(ledgerSeq: Long, referenceSeq: Long): SyncApiClient.PullResult
        }

        val steps = ArrayDeque<Step>()
        val calls = ArrayList<LongArray>()
        val ledgerIds = ArrayList<String?>()

        @Throws(IOException::class)
        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            calls.add(longArrayOf(ledgerSeq, referenceSeq))
            ledgerIds.add(ledgerId)
            val step = steps.poll() ?: return empty(ledgerSeq, referenceSeq) // догнали
            return step.answer(ledgerSeq, referenceSeq)
        }

        companion object {
            fun empty(ledgerSeq: Long, referenceSeq: Long): SyncApiClient.PullResult =
                SyncApiClient.PullResult(
                    SyncApiClient.PullStream(ledgerSeq, JSONObject(), false, LEDGER),
                    SyncApiClient.PullStream(referenceSeq, JSONObject(), false, null)
                )

            fun page(nextSeq: Long, ledgerChanges: JSONObject, referenceSeq: Long): SyncApiClient.PullResult =
                SyncApiClient.PullResult(
                    SyncApiClient.PullStream(nextSeq, ledgerChanges, false, LEDGER),
                    SyncApiClient.PullStream(referenceSeq, JSONObject(), false, null)
                )

            fun reset(reason: String, ledgerChanges: JSONObject, nextSeq: Long): SyncApiClient.PullResult =
                SyncApiClient.PullResult(
                    SyncApiClient.PullStream(nextSeq, ledgerChanges, true, LEDGER, reason),
                    SyncApiClient.PullStream(0, JSONObject(), false, null)
                )
        }
    }

    /** Синкнутые счета a/b и три операции: живая, удалённая на сервере, неотправленная. */
    private fun syncedLedger() {
        val a = account("a", 0, false)
        val b = account("b", 0, false)
        operation("op-keep", a, b, 0, false)
        operation("op-gone-on-server", a, b, 0, false)
        operation("op-offline", a, b, 1, false)
    }

    private fun serverState(): JSONObject =
        merge(
            changes("account", accountRow("a", false), accountRow("b", false)),
            changes("operation", operationRow("op-keep", "a", "b", false))
        )

    @Test
    fun reload_afterPurgedReset_removesRowsGoneOnServer_keepsUnsent() {
        syncedLedger()
        AppSettings.setSyncLedgerId(context, LEDGER)
        AppSettings.setLedgerSyncSeq(context, 40L)
        AppSettings.setReferenceSyncSeq(context, 5L)

        val api = ScriptedApi(context)
        val state = serverState()
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.reset("purged", state, 600) })
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(600, state, 0) })

        val log = SyncLog()
        SyncEngine.pullAll(db, api, context, log)

        // Сброс: страница выброшена, оба курсора с нуля.
        assertEquals(40L, api.calls[0][0])
        assertEquals(0L, api.calls[1][0])
        assertEquals(0L, api.calls[1][1])
        assertFalse(exists("operation", "op-gone-on-server"))
        assertTrue(exists("operation", "op-keep"))
        assertTrue("неотправленная остаётся", exists("operation", "op-offline"))
        assertTrue(exists("account", "a"))
        assertFalse(AppSettings.isFullReloadPending(context))
        assertEquals(0, count(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE))
        assertTrue(log.toDisplayText().contains("Сервер очистил старые удаления"))
    }

    @Test
    fun secondServerResetInOnePass_failsInsteadOfLooping() {
        syncedLedger()
        AppSettings.setSyncLedgerId(context, LEDGER)
        AppSettings.setLedgerSyncSeq(context, 40L)
        AppSettings.setReferenceSyncSeq(context, 5L)

        // Сервер без исправления: граница первой страницы (13) ниже purged_seq —
        // на каждый ненулевой курсор снова "purged".
        val api = ScriptedApi(context)
        val accounts = changes("account", accountRow("a", false), accountRow("b", false))
        for (i in 0 until 100) {
            api.steps.add(ScriptedApi.Step { l, _ ->
                if (l == 0L) ScriptedApi.page(13, accounts, 0)
                else ScriptedApi.reset("purged", JSONObject(), 13)
            })
        }

        try {
            SyncEngine.pullAll(db, api, context, SyncLog())
            fail("ожидалась ошибка на втором сбросе")
        } catch (expected: JSONException) {
            assertTrue(expected.message!!.contains("reset the cursor twice"))
        }
        // 40 -> сброс, 0 -> страница до 13, 13 -> второй сброс: стоп.
        assertEquals(3, api.calls.size)
        // Ничего не вычищено: загрузка не дошла до конца и продолжится.
        assertTrue(exists("operation", "op-gone-on-server"))
        assertTrue(AppSettings.isFullReloadPending(context))
    }

    @Test
    fun reload_interrupted_deletesNothing_nextPassFinishes() {
        syncedLedger()
        AppSettings.setLedgerSyncSeq(context, 0L)
        AppSettings.setReferenceSyncSeq(context, 0L)

        val api = ScriptedApi(context)
        val accounts = changes("account", accountRow("a", false), accountRow("b", false))
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(10, accounts, 0) })
        api.steps.add(ScriptedApi.Step { _, _ -> throw IOException("сеть пропала") })

        try {
            SyncEngine.pullAll(db, api, context, SyncLog())
            fail("ожидался обрыв сети")
        } catch (expected: IOException) {
            // как в SyncRunner: проход завершается «нет сети»
        }
        assertTrue(AppSettings.isFullReloadPending(context))
        assertEquals(10L, AppSettings.getLedgerSyncSeq(context))
        assertTrue("до догона ничего не вычищается", exists("operation", "op-gone-on-server"))

        // Следующий проход продолжает с курсора и вычищает.
        val keep = changes("operation", operationRow("op-keep", "a", "b", false))
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(20, keep, 0) })
        SyncEngine.pullAll(db, api, context, SyncLog())

        val resumed = api.calls[2]
        assertEquals(10L, resumed[0])
        assertFalse(exists("operation", "op-gone-on-server"))
        assertTrue(exists("operation", "op-keep"))
        assertTrue(exists("operation", "op-offline"))
        assertFalse(AppSettings.isFullReloadPending(context))
    }

    @Test
    fun reload_cursorZeroWithoutSyncedRows_isPlainLoad() {
        // Свежая установка: синкнутых строк нет — флаг не ставится.
        val api = ScriptedApi(context)
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertFalse(AppSettings.isFullReloadPending(context))
    }

    @Test
    fun reload_ledgerChanged_restartsBothStreamsWithSweep() {
        syncedLedger()
        AppSettings.setSyncLedgerId(context, "old-ledger")
        AppSettings.setLedgerSyncSeq(context, 300L)
        AppSettings.setReferenceSyncSeq(context, 9L)

        val api = ScriptedApi(context)
        val state = serverState()
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(310, JSONObject(), 9) }) // новый ledger_id
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(5, state, 0) })

        SyncEngine.pullAll(db, api, context, SyncLog())

        assertEquals("old-ledger", api.ledgerIds[0])
        assertEquals(0L, api.calls[1][0])
        assertEquals(0L, api.calls[1][1])
        assertEquals(LEDGER, api.ledgerIds[1])
        assertFalse(exists("operation", "op-gone-on-server"))
        assertTrue(exists("operation", "op-offline"))
    }

    @Test
    fun reload_oneTimeResync_setsPending() {
        syncedLedger()
        AppSettings.setLedgerSyncSeq(context, 42L)
        AppSettings.setReferenceSyncSeq(context, 7L)
        // Сброс флагов одноразовой перекачки (setUp их выставил).
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().remove("deferredRowsResyncDone").commit()

        val api = ScriptedApi(context)
        val state = serverState()
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(5, state, 0) })
        SyncEngine.pullAll(db, api, context, SyncLog())

        assertEquals(0L, api.calls[0][0])
        assertFalse(exists("operation", "op-gone-on-server"))
        assertTrue(exists("operation", "op-keep"))
    }

    @Test
    fun reload_ownIconsSurviveSweep() {
        syncedLedger()
        val own = ContentValues()
        own.put("name", "own_icon")
        own.put("image", byteArrayOf(1))
        own.put("icon_type", 1)
        own.put("uuid", "own-icon-uuid")
        own.put("dirty", 0)
        own.put(WalletContract.IconEntry.COLUMN_ORIGIN, WalletContract.IconEntry.ORIGIN_LOCAL)
        db.insertOrThrow("icon", null, own)

        val api = ScriptedApi(context)
        val state = serverState()
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(5, state, 0) })
        SyncEngine.pullAll(db, api, context, SyncLog())

        assertTrue(exists("icon", "own-icon-uuid"))
        assertFalse(exists("operation", "op-gone-on-server"))
    }

    @Test
    fun reload_matchesUuidWrittenWithoutDashes() {
        val dashed = "bd77a9c4-9dac-4bdc-8c72-1939115745eb"
        account(dashed.replace("-", ""), 0, false) // сид пишет uuid без дефисов
        val api = ScriptedApi(context)
        val state = changes("account", accountRow(dashed, false))
        api.steps.add(ScriptedApi.Step { _, _ -> ScriptedApi.page(5, state, 0) })
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertTrue(exists("account", dashed.replace("-", "")))
    }

    // ── 4.1 протокол ─────────────────────────────────────────────────────────

    @Test
    fun pullUrl_withAndWithoutLedgerId() {
        assertEquals(
            "http://h/sync/pull?ledger_seq=7&reference_seq=3&limit=500",
            SyncApiClient.pullUrl("http://h/", 7, 3, 500, null)
        )
        assertEquals(
            "http://h/sync/pull?ledger_seq=7&reference_seq=3&limit=500&ledger_id=" + LEDGER,
            SyncApiClient.pullUrl("http://h/", 7, 3, 500, LEDGER)
        )
    }

    @Test
    fun pullAll_sendsKnownLedgerId_andNoneBeforeFirstSync() {
        val api = ScriptedApi(context)
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertNull(api.ledgerIds[0])
        // Первый ответ сообщил леджер — следующий проход его передаёт.
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertEquals(LEDGER, api.ledgerIds[api.ledgerIds.size - 1])
    }

    @Test
    fun pullStream_parsesResetReason_andOldServerWithout() {
        val withReason = SyncApiClient.PullResult.fromJson(
            JSONObject(
                "{\"ledger\":{\"ledger_id\":\"" + LEDGER + "\",\"next_seq\":3,\"reset\":true," +
                    "\"reset_reason\":\"purged\",\"changes\":{}}," +
                    "\"reference\":{\"next_seq\":1,\"reset\":false,\"changes\":{}}}"
            )
        )
        assertTrue(withReason.ledger.reset)
        assertEquals("purged", withReason.ledger.resetReason)
        assertNull(withReason.reference.resetReason)

        val old = SyncApiClient.PullResult.fromJson(
            JSONObject(
                "{\"ledger\":{\"ledger_id\":\"" + LEDGER + "\",\"next_seq\":3,\"reset\":true,\"changes\":{}}," +
                    "\"reference\":{\"next_seq\":1,\"reset\":false,\"changes\":{}}}"
            )
        )
        assertTrue(old.ledger.reset)
        assertNull(old.ledger.resetReason)
    }

    // ── 3.1 апгрейд базы ─────────────────────────────────────────────────────

    @Test
    fun upgrade86to87_createsReloadTable_dataIntact() {
        val a = account("a", 0, false)
        val b = account("b", 0, false)
        operation("op", a, b, 0, false)
        db.execSQL("DROP TABLE " + WalletDbHelper.SYNC_RELOAD_SEEN_TABLE)

        WalletDbHelper(context).onUpgrade(db, 86, 87)

        assertEquals(0, count(WalletDbHelper.SYNC_RELOAD_SEEN_TABLE))
        assertEquals(1, count("operation"))
        assertEquals(2, count("account"))
    }
}
