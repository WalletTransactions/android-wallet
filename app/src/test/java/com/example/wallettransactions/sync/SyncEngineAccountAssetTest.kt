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
 * openspec capital-assets, синк: `account_asset` в push (ключи по контракту wallet-api)
 * и pull, переклейка uuid при 409 и однократный перечит потока ledger после шага v89.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineAccountAssetTest {

    companion object {
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val CURRENCY = "c0000000-0000-4000-8000-000000000001"
        private const val FLAT = "a1000000-0000-4000-8000-000000000001"
        private const val LEDGER = "3c000000-0000-4000-8000-000000000004"

        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_asset", "account_operating", "account",
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

        private fun assetRow(uuid: String, type: Int, include: Boolean): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER).put("asset_type", type)
                .put("include_in_net_worth", include).put("comment", "")
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)
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
        icon.put("name", "test_icon")
        icon.put("image", byteArrayOf(0))
        icon.put("icon_type", 1)
        icon.put("uuid", ICON)
        icon.put("dirty", 0)
        iconId = db.insertOrThrow("icon", null, icon)
        val cur = ContentValues()
        cur.put("name", "Рубль")
        cur.put("iso_code", "RUB")
        cur.put("icon_id", iconId)
        cur.put("symbol", "₽")
        cur.put("uuid", CURRENCY)
        cur.put("dirty", 0)
        currencyId = db.insertOrThrow("currency", null, cur)
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
        AppSettings.setLedgerRereadPending(context, false)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // ── 2.1 push и pull ─────────────────────────────────────────────────────

    @Test
    fun push_dirtyAsset_sentAfterAccountOperating_withContractKeys() {
        val flat = WalletDbCRUD.InsertOperatingAccount(db, "Квартира", "", iconId.toInt(), currencyId.toInt()).toInt()
        WalletDbCRUD.SetAccountAsset(db, flat, 2, false)
        val uuid = string("SELECT uuid FROM account WHERE _id = $flat")

        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())

        val order = ArrayList(byTable.keys)
        assertTrue(order.indexOf("account_asset") > order.indexOf("account_operating"))
        val row = byTable["account_asset"]!!.getJSONObject(0)
        assertEquals(uuid, row.getString("uuid"))
        assertEquals(2, row.getInt("asset_type"))
        assertFalse(row.getBoolean("include_in_net_worth"))
        assertEquals("", row.getString("comment"))
        assertFalse(row.getBoolean("deleted"))
    }

    @Test
    fun pull_appliesAssetToAccountWithSameUuid_defersWithoutAccount() {
        val ledger = JSONObject()
            .put("account", JSONArray().put(accountRow(FLAT, "Квартира")))
            .put(
                "account_operating", JSONArray().put(
                    JSONObject()
                        .put("uuid", FLAT).put("currency_uuid", CURRENCY).put("deleted_at", JSONObject.NULL)
                )
            )
            .put(
                "account_asset", JSONArray()
                    .put(assetRow(FLAT, 1, true))
                    .put(assetRow("ffff0000-0000-4000-8000-000000000009", 3, true))
            )
        val deferred = PullDeferredRows.inMemory()

        val touched = SyncEngine.applyPage(db, ledger, JSONObject(), null, SyncLog(), deferred)

        assertTrue(touched.contains("account_asset"))
        val accountId = number("SELECT _id FROM account WHERE uuid = '$FLAT'")
        assertEquals(accountId, number("SELECT _id FROM account_asset WHERE uuid = '$FLAT'"))
        assertEquals(1L, number("SELECT asset_type FROM account_asset"))
        assertEquals(0L, number("SELECT dirty FROM account_asset"))
        assertEquals(
            "строка без счёта отложена, а не потеряна и не уронила страницу", 1L,
            number("SELECT count(*) FROM account_asset")
        )
        assertFalse(deferred.isEmpty())
    }

    // ── 8.2 журнал синка: признак актива без строки account (android-wallet#42) ──

    @Test
    fun pull_onlyAssetRow_logsAssetFlag_soScreensRecalculate() {
        localSyncedAccount()
        val log = SyncLog()

        SyncEngine.applyPage(
            db, JSONObject()
                .put("account_asset", JSONArray().put(assetRow(FLAT, 1, true))),
            JSONObject(), null, log, PullDeferredRows.inMemory()
        )

        assertFalse("пустой журнал — SyncRunner не поднимет ревизию, табы не пересчитаются", log.isEmpty())
        assertTrue(log.toDisplayText(), log.toDisplayText().contains("Признак актива"))
        assertTrue(log.toDisplayText(), log.toDisplayText().contains("Добавлено"))

        val again = SyncLog()
        SyncEngine.applyPage(
            db, JSONObject()
                .put("account_asset", JSONArray().put(assetRow(FLAT, 2, true))),
            JSONObject(), null, again, PullDeferredRows.inMemory()
        )
        assertTrue(again.toDisplayText(), again.toDisplayText().contains("Изменено"))
    }

    @Test
    fun push_onlyAssetDirty_logsAssetFlagSent() {
        localSyncedAccount()
        val flat = number("SELECT _id FROM account WHERE uuid = '$FLAT'")
        WalletDbCRUD.SetAccountAsset(db, flat.toInt(), 1, true)
        val api = ScriptedApi(context)
        val log = SyncLog()

        SyncEngine.pushDirty(db, api, log)

        assertEquals(1, api.pushed.size)
        assertFalse("ушла только строка признака", api.pushed[0].has("account"))
        assertTrue(log.toDisplayText(), log.toDisplayText().contains("Признак актива"))
    }

    // ── 2.2 переклейка при 409 duplicate_name ───────────────────────────────

    @Test
    fun conflict_rekeysAccountOperatingAndAsset() {
        val flat = WalletDbCRUD.InsertOperatingAccount(db, "Квартира", "", iconId.toInt(), currencyId.toInt()).toInt()
        WalletDbCRUD.SetAccountAsset(db, flat, 1, true)
        val ours = string("SELECT uuid FROM account WHERE _id = $flat")
        val survivor = "b2000000-0000-4000-8000-000000000002"

        SyncEngine.resolveAccountConflicts(
            db, JSONArray().put(
                JSONObject()
                    .put("table", "account").put("reason", "duplicate_name")
                    .put("uuid", ours).put("existing_uuid", survivor)
            )
        )

        assertEquals(survivor, string("SELECT uuid FROM account WHERE _id = $flat"))
        assertEquals(survivor, string("SELECT uuid FROM account_operating WHERE _id = $flat"))
        assertEquals(survivor, string("SELECT uuid FROM account_asset WHERE _id = $flat"))
    }

    // ── 2.3 однократный перечит ledger ──────────────────────────────────────

    @Test
    fun reread_pullsLedgerFromZero_keepsReferenceAndLedgerId_onlyOnce() {
        AppSettings.setSyncLedgerId(context, LEDGER)
        AppSettings.setLedgerSyncSeq(context, 40L)
        AppSettings.setReferenceSyncSeq(context, 5L)
        AppSettings.setLedgerRereadPending(context, true)
        localSyncedAccount() // синкнутые строки есть — это не повод для загрузки с вычисткой

        val api = ScriptedApi(context)
        val page = JSONObject()
            .put("account_asset", JSONArray().put(assetRow(FLAT, 1, true)))
        api.steps.add(ScriptedApi.Step { _, r -> ScriptedApi.page(45, page, r) })
        SyncEngine.pullAll(db, api, context, SyncLog())

        assertEquals(0L, api.calls[0][0])
        assertEquals("reference не тронут", 5L, api.calls[0][1])
        assertEquals(LEDGER, AppSettings.getSyncLedgerId(context))
        assertFalse(AppSettings.isLedgerRereadPending(context))
        assertFalse("перечит — не загрузка с вычисткой", AppSettings.isFullReloadPending(context))
        assertEquals(45L, AppSettings.getLedgerSyncSeq(context))
        assertEquals("актив, пропущенный старой версией, применён", 1L, number("SELECT count(*) FROM account_asset"))

        val again = ScriptedApi(context)
        SyncEngine.pullAll(db, again, context, SyncLog())
        assertEquals("второй проход — с сохранённой позиции", 45L, again.calls[0][0])
    }

    @Test
    fun reread_interrupted_continuesFromLastAppliedPage() {
        AppSettings.setSyncLedgerId(context, LEDGER)
        AppSettings.setLedgerSyncSeq(context, 40L)
        AppSettings.setReferenceSyncSeq(context, 5L)
        AppSettings.setLedgerRereadPending(context, true)
        localSyncedAccount()

        val api = ScriptedApi(context)
        api.steps.add(ScriptedApi.Step { _, r ->
            ScriptedApi.page(
                10, JSONObject()
                    .put("account_asset", JSONArray().put(assetRow(FLAT, 1, true))), r
            )
        })
        api.steps.add(ScriptedApi.Step { _, _ -> throw IOException("нет сети") })
        try {
            SyncEngine.pullAll(db, api, context, SyncLog())
            fail("ожидался обрыв")
        } catch (expected: IOException) {
            // ok
        }
        assertEquals(10L, AppSettings.getLedgerSyncSeq(context))
        assertFalse(AppSettings.isLedgerRereadPending(context))

        val next = ScriptedApi(context)
        next.steps.add(ScriptedApi.Step { _, r ->
            ScriptedApi.page(
                12, JSONObject()
                    .put("account_asset", JSONArray().put(assetRow(FLAT, 1, true))), r
            )
        })
        SyncEngine.pullAll(db, next, context, SyncLog())
        assertEquals(10L, next.calls[0][0])
        assertEquals("upsert по uuid — без дублей", 1L, number("SELECT count(*) FROM account_asset"))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun localSyncedAccount() {
        val acc = ContentValues()
        acc.put("name", "Квартира")
        acc.put("comment", "")
        acc.put("icon_id", iconId)
        acc.put("kind", 1)
        acc.put("uuid", FLAT)
        acc.put("dirty", 0)
        val id = db.insertOrThrow("account", null, acc)
        val op = ContentValues()
        op.put("_id", id)
        op.put("currency_id", currencyId)
        op.put("uuid", FLAT)
        op.put("dirty", 0)
        db.insertOrThrow("account_operating", null, op)
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
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    /** Сценарий ответов pull: по одному на вызов; IOException — обрыв сети. */
    private class ScriptedApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        fun interface Step {
            @Throws(IOException::class, JSONException::class)
            fun answer(ledgerSeq: Long, referenceSeq: Long): SyncApiClient.PullResult
        }

        val steps = ArrayDeque<Step>()
        val calls = ArrayList<LongArray>()
        val pushed = ArrayList<JSONObject>()

        override fun push(batchId: String, changes: JSONObject): SyncApiClient.HttpResult {
            pushed.add(changes)
            return SyncApiClient.HttpResult(200, JSONObject())
        }

        @Throws(IOException::class)
        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            calls.add(longArrayOf(ledgerSeq, referenceSeq))
            val step = steps.poll() ?: return empty(ledgerSeq, referenceSeq)
            try {
                return step.answer(ledgerSeq, referenceSeq)
            } catch (e: JSONException) {
                throw IOException(e)
            }
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
        }
    }
}
