package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.WalletDbCRUD
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * openspec debts, раздел 2: `counterparty`/`account_debt` в push (ключи
 * wallet-api) и pull, конфликты `409 duplicate_name`/`counterparty_in_use`,
 * однократная повторная отправка и перечит ledger после шага v91.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineDebtsTest {

    companion object {
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val CURRENCY = "c0000000-0000-4000-8000-000000000001"
        private const val LOAN = "a1000000-0000-4000-8000-000000000001"
        private const val VASYA = "d1000000-0000-4000-8000-000000000001"
        private const val LEDGER = "3c000000-0000-4000-8000-000000000004"

        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_asset", "account_debt", "counterparty",
            "counterparty_contact", "account_operating", "account",
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

        private fun accountRow(uuid: String, name: String): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false).put("system_kind", 0).put("kind", 1)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        private fun counterpartyRow(uuid: String, name: String, deleted: Boolean): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("name", name).put("comment", "")
                .put("icon_uuid", JSONObject.NULL).put("is_archived", false)
                .put("deleted_at", if (deleted) "2026-11-13T10:00:00Z" else JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)

        private fun debtRow(uuid: String, counterparty: String, due: String?, deleted: Boolean): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("counterparty_uuid", counterparty)
                .put("comment", "").put("due_date", due ?: JSONObject.NULL)
                .put("deleted_at", if (deleted) "2026-11-13T10:00:00Z" else JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var iconId = 0L
    private var currencyId = 0L

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, PullDeferredRows.FILE_NAME).delete()
        db = Singleton.instance.getWritableDatabase(context)
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        val icon = ContentValues()
        icon.put("name", "unknown")
        icon.put("image", byteArrayOf(0))
        icon.put("icon_type", 1)
        icon.put("uuid", ICON)
        icon.put("dirty", 0)
        iconId = db.insertOrThrow("icon", null, icon)
        val cur = ContentValues()
        cur.put("name", "Доллар")
        cur.put("iso_code", "USD")
        cur.put("icon_id", iconId)
        cur.put("symbol", "\$")
        cur.put("uuid", CURRENCY)
        cur.put("dirty", 0)
        currencyId = db.insertOrThrow("currency", null, cur)
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
        AppSettings.setLedgerRereadPending(context, false)
        AppSettings.setDebtsRedirtyPending(context, false)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // ── 2.1 push и pull ─────────────────────────────────────────────────────

    @Test
    fun push_counterpartyAndDebt_inServerOrder_withNullableKeysAlwaysPresent() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "сосед")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, currencyId.toInt(), "", null)
        val cpUuid = string("SELECT uuid FROM counterparty WHERE _id = $cp")
        val loanUuid = string("SELECT uuid FROM account WHERE _id = $loan")

        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())

        val order = ArrayList(byTable.keys)
        assertTrue(order.indexOf("counterparty") > order.indexOf("account_operating"))
        assertTrue(order.indexOf("account_debt") > order.indexOf("counterparty"))
        val c = byTable["counterparty"]!!.getJSONObject(0)
        assertEquals(cpUuid, c.getString("uuid"))
        assertEquals("Вася", c.getString("name"))
        assertEquals("сосед", c.getString("comment"))
        assertTrue("icon_uuid уходит и при null", c.has("icon_uuid") && c.isNull("icon_uuid"))
        assertFalse(c.getBoolean("is_archived"))
        assertFalse(c.getBoolean("deleted"))
        val d = byTable["account_debt"]!!.getJSONObject(0)
        assertEquals(loanUuid, d.getString("uuid"))
        assertEquals(cpUuid, d.getString("counterparty_uuid"))
        assertEquals("", d.getString("comment"))
        assertTrue(
            "due_date уходит и при null — иначе снятый срок не доедет",
            d.has("due_date") && d.isNull("due_date")
        )
        assertFalse(d.getBoolean("deleted"))
    }

    @Test
    fun push_dueDate_sentAsIsoDate() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        WalletDbCRUD.CreateDebtWallet(db, cp, currencyId.toInt(), "", "2026-12-31")
        val d = SyncEngine.collectDirtyByTable(db, ArrayList())["account_debt"]!!.getJSONObject(0)
        assertEquals("2026-12-31", d.getString("due_date"))
    }

    @Test
    fun pull_appliesCounterpartyAndDebt_defersDebtWithoutCounterparty() {
        val ledger = JSONObject()
            .put("account", JSONArray().put(accountRow(LOAN, "Вася · USD")))
            .put(
                "account_operating", JSONArray().put(
                    JSONObject()
                        .put("uuid", LOAN).put("currency_uuid", CURRENCY).put("deleted_at", JSONObject.NULL)
                )
            )
            .put("counterparty", JSONArray().put(counterpartyRow(VASYA, "Вася", false)))
            .put(
                "account_debt", JSONArray()
                    .put(debtRow(LOAN, VASYA, "2026-12-31", false))
                    .put(debtRow(LOAN, "d9000000-0000-4000-8000-000000000009", null, false))
            )
        val deferred = PullDeferredRows.inMemory()

        val touched = SyncEngine.applyPage(db, ledger, JSONObject(), null, SyncLog(), deferred)

        assertTrue(touched.contains("counterparty"))
        assertTrue(touched.contains("account_debt"))
        assertEquals("Вася", string("SELECT name FROM counterparty WHERE uuid = '$VASYA'"))
        assertEquals(0L, number("SELECT dirty FROM counterparty"))
        val accountId = number("SELECT _id FROM account WHERE uuid = '$LOAN'")
        assertEquals(accountId, number("SELECT _id FROM account_debt WHERE uuid = '$LOAN'"))
        assertEquals(VASYA, string("SELECT counterparty_uuid FROM account_debt"))
        assertEquals("2026-12-31", string("SELECT due_date FROM account_debt"))
        assertEquals(0L, number("SELECT dirty FROM account_debt"))
        assertFalse("заём на неизвестного контрагента отложен", deferred.isEmpty())
    }

    @Test
    fun pull_debtBeforeCounterparty_appliedWhenCounterpartyArrives() {
        localSyncedLoan()
        val deferred = PullDeferredRows.inMemory()
        SyncEngine.applyPage(
            db, JSONObject()
                .put("account_debt", JSONArray().put(debtRow(LOAN, VASYA, null, false))),
            JSONObject(), null, SyncLog(), deferred
        )
        assertEquals(0L, number("SELECT count(*) FROM account_debt"))
        assertFalse(deferred.isEmpty())

        SyncEngine.applyPage(
            db, JSONObject()
                .put("counterparty", JSONArray().put(counterpartyRow(VASYA, "Вася", false))),
            JSONObject(), null, SyncLog(), deferred
        )
        assertEquals(1L, number("SELECT count(*) FROM account_debt WHERE deleted_at IS NULL"))
        assertTrue(deferred.isEmpty())
    }

    @Test
    fun pull_counterpartyTombstone_withLiveLocalDebt_keepsItAliveAndDirty() {
        localSyncedLoan()
        SyncEngine.applyPage(
            db, JSONObject()
                .put("counterparty", JSONArray().put(counterpartyRow(VASYA, "Вася", false)))
                .put("account_debt", JSONArray().put(debtRow(LOAN, VASYA, null, false))),
            JSONObject(), null, SyncLog(), PullDeferredRows.inMemory()
        )

        SyncEngine.applyPage(
            db, JSONObject()
                .put("counterparty", JSONArray().put(counterpartyRow(VASYA, "Вася", true))),
            JSONObject(), null, SyncLog(), PullDeferredRows.inMemory()
        )

        assertEquals(
            "займ не теряет контрагента", 0L,
            number("SELECT count(*) FROM counterparty WHERE deleted_at IS NOT NULL")
        )
        assertEquals("уйдёт снова", 1L, number("SELECT dirty FROM counterparty"))
    }

    // ── 2.2 конфликты ───────────────────────────────────────────────────────

    @Test
    fun duplicateName_sameCounterpartyOnTwoDevices_debtsPointToSurvivor() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val loan = WalletDbCRUD.CreateDebtWallet(db, cp, currencyId.toInt(), "", null)
        val ours = string("SELECT uuid FROM counterparty WHERE _id = $cp")
        insertContact(ours)
        val api = ScriptedApi(context)
        api.pushAnswers.add(
            SyncApiClient.HttpResult(
                409, JSONObject()
                    .put("error", "duplicate_name")
                    .put(
                        "conflicts", JSONArray().put(
                            JSONObject()
                                .put("table", "counterparty").put("reason", "duplicate_name")
                                .put("uuid", ours).put("existing_uuid", VASYA)
                        )
                    )
            )
        )

        val log = SyncLog()
        SyncEngine.pushDirty(db, api, log)

        assertEquals("второй раунд ушёл", 2, api.pushed.size)
        assertEquals(VASYA, string("SELECT uuid FROM counterparty WHERE _id = $cp"))
        assertEquals(VASYA, string("SELECT counterparty_uuid FROM account_debt WHERE _id = $loan"))
        assertEquals(VASYA, string("SELECT counterparty_uuid FROM counterparty_contact"))
        val retried = api.pushed[1]
        assertEquals(VASYA, retried.getJSONArray("counterparty").getJSONObject(0).getString("uuid"))
        assertEquals(VASYA, retried.getJSONArray("account_debt").getJSONObject(0).getString("counterparty_uuid"))
        assertEquals(0L, number("SELECT count(*) FROM counterparty WHERE dirty = 1"))
    }

    @Test
    fun counterpartyInUse_restoresCounterparty_andSendsAgain() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val uuid = string("SELECT uuid FROM counterparty WHERE _id = $cp")
        db.execSQL("UPDATE counterparty SET dirty = 0")
        assertEquals(WalletDbCRUD.DeleteCounterpartyResult.deleted, WalletDbCRUD.DeleteCounterparty(db, cp))
        val api = ScriptedApi(context)
        api.pushAnswers.add(
            SyncApiClient.HttpResult(
                409, JSONObject()
                    .put("error", "counterparty_in_use").put("table", "counterparty").put("uuid", uuid)
            )
        )

        val log = SyncLog()
        SyncEngine.pushDirty(db, api, log)

        assertEquals(2, api.pushed.size)
        assertNull(
            "контрагент снова в списке",
            nullableString("SELECT deleted_at FROM counterparty WHERE _id = $cp")
        )
        assertFalse(api.pushed[1].getJSONArray("counterparty").getJSONObject(0).getBoolean("deleted"))
        assertFalse(log.isEmpty())
    }

    @Test
    fun parseCounterpartyInUse_onlyThatShape() {
        assertEquals(
            VASYA, SyncEngine.parseCounterpartyInUse(
                409, JSONObject()
                    .put("error", "counterparty_in_use").put("table", "counterparty").put("uuid", VASYA)
            )
        )
        assertNull(
            SyncEngine.parseCounterpartyInUse(
                400, JSONObject()
                    .put("error", "counterparty_in_use").put("table", "counterparty").put("uuid", VASYA)
            )
        )
        assertNull(
            SyncEngine.parseCounterpartyInUse(
                409, JSONObject()
                    .put("error", "missing_reference").put("table", "counterparty").put("uuid", VASYA)
            )
        )
        assertNull(
            SyncEngine.parseCounterpartyInUse(
                409, JSONObject()
                    .put("error", "counterparty_in_use").put("table", "account").put("uuid", VASYA)
            )
        )
    }

    // ── 2.3 повторная отправка и перечит после v91 ──────────────────────────

    @Test
    fun redirty_onceAfterUpgrade_marksLiveRowsOnly() {
        val cp = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        val gone = WalletDbCRUD.CreateCounterparty(db, "Удалённый", "")
        WalletDbCRUD.CreateDebtWallet(db, cp, currencyId.toInt(), "", null)
        WalletDbCRUD.DeleteCounterparty(db, gone)
        db.execSQL("UPDATE counterparty SET dirty = 0")
        db.execSQL("UPDATE account_debt SET dirty = 0")
        AppSettings.setDebtsRedirtyPending(context, true)

        SyncEngine.redirtyDebtsIfPending(db, context, SyncLog())

        assertEquals(1L, number("SELECT dirty FROM counterparty WHERE _id = $cp"))
        assertEquals(
            "удалённый не воскресает и не отправляется", 0L,
            number("SELECT dirty FROM counterparty WHERE _id = $gone")
        )
        assertEquals(1L, number("SELECT dirty FROM account_debt"))
        assertFalse(AppSettings.isDebtsRedirtyPending(context))

        db.execSQL("UPDATE counterparty SET dirty = 0")
        SyncEngine.redirtyDebtsIfPending(db, context, SyncLog())
        assertEquals("второй раз — ничего", 0L, number("SELECT dirty FROM counterparty WHERE _id = $cp"))
    }

    @Test
    fun reread_pullsLedgerFromZero_keepsReference_appliesCounterparty() {
        AppSettings.setSyncLedgerId(context, LEDGER)
        AppSettings.setLedgerSyncSeq(context, 40L)
        AppSettings.setReferenceSyncSeq(context, 5L)
        AppSettings.setLedgerRereadPending(context, true)
        localSyncedLoan()

        val api = ScriptedApi(context)
        api.steps.add(ScriptedApi.Step { _, r ->
            ScriptedApi.page(
                45, JSONObject()
                    .put("counterparty", JSONArray().put(counterpartyRow(VASYA, "Вася", false)))
                    .put("account_debt", JSONArray().put(debtRow(LOAN, VASYA, null, false))), r
            )
        })
        SyncEngine.pullAll(db, api, context, SyncLog())

        assertEquals(0L, api.calls[0][0])
        assertEquals(5L, api.calls[0][1])
        assertEquals(LEDGER, AppSettings.getSyncLedgerId(context))
        assertFalse(AppSettings.isLedgerRereadPending(context))
        assertEquals(1L, number("SELECT count(*) FROM account_debt"))

        val again = ScriptedApi(context)
        SyncEngine.pullAll(db, again, context, SyncLog())
        assertEquals(45L, again.calls[0][0])
    }

    @Test
    fun upgradeStep_raisesRedirtyFlag() {
        val helper = WalletDbHelper(context)
        helper.onUpgrade(db, 90, 91)
        assertTrue(AppSettings.isDebtsRedirtyPending(context))
        assertTrue(AppSettings.isLedgerRereadPending(context))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun localSyncedLoan() {
        val acc = ContentValues()
        acc.put("name", "Вася · USD")
        acc.put("comment", "")
        acc.put("icon_id", iconId)
        acc.put("kind", 1)
        acc.put("uuid", LOAN)
        acc.put("dirty", 0)
        val id = db.insertOrThrow("account", null, acc)
        val op = ContentValues()
        op.put("_id", id)
        op.put("currency_id", currencyId)
        op.put("uuid", LOAN)
        op.put("dirty", 0)
        db.insertOrThrow("account_operating", null, op)
    }

    private fun insertContact(counterpartyUuid: String) {
        val cv = ContentValues()
        cv.put("counterparty_uuid", counterpartyUuid)
        cv.put("lookup_key", "lk")
        db.insertOrThrow("counterparty_contact", null, cv)
    }

    private fun number(sql: String): Long {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun string(sql: String): String {
        val s = nullableString(sql)
        assertTrue(sql, s != null)
        return s!!
    }

    private fun nullableString(sql: String): String? {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return if (c.isNull(0)) null else c.getString(0)
        } finally {
            c.close()
        }
    }

    /** Pull — сценарий по шагам; push — очередь ответов, дальше 200. */
    private class ScriptedApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        fun interface Step {
            @Throws(IOException::class, JSONException::class)
            fun answer(ledgerSeq: Long, referenceSeq: Long): SyncApiClient.PullResult
        }

        val steps = ArrayDeque<Step>()
        val pushAnswers = ArrayDeque<SyncApiClient.HttpResult>()
        val calls = ArrayList<LongArray>()
        val pushed = ArrayList<JSONObject>()

        override fun push(batchId: String, changes: JSONObject): SyncApiClient.HttpResult {
            try {
                pushed.add(JSONObject(changes.toString()))
            } catch (e: JSONException) {
                throw IllegalStateException(e)
            }
            val answer = pushAnswers.poll()
            return answer ?: SyncApiClient.HttpResult(200, JSONObject())
        }

        @Throws(IOException::class)
        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            calls.add(longArrayOf(ledgerSeq, referenceSeq))
            val step = steps.poll()
                ?: return SyncApiClient.PullResult(
                    SyncApiClient.PullStream(ledgerSeq, JSONObject(), false, LEDGER),
                    SyncApiClient.PullStream(referenceSeq, JSONObject(), false, null)
                )
            try {
                return step.answer(ledgerSeq, referenceSeq)
            } catch (e: JSONException) {
                throw IOException(e)
            }
        }

        companion object {
            fun page(nextSeq: Long, ledgerChanges: JSONObject, referenceSeq: Long): SyncApiClient.PullResult =
                SyncApiClient.PullResult(
                    SyncApiClient.PullStream(nextSeq, ledgerChanges, false, LEDGER),
                    SyncApiClient.PullStream(referenceSeq, JSONObject(), false, null)
                )
        }
    }
}
