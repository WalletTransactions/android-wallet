package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Data.WalletDbHelper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * openspec currency-ui-tweaks: `show_in_equivalent` в push/pull и `is_shared`
 * из `ledger_id` (design D1, D6). Контракт сервера — wallet-api openapi.yaml,
 * `/sync/pull`: строка — полный дамп колонок, включая `ledger_id`.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineCurrencyFlagsTest {

    companion object {
        private const val ICON_UUID = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val CUR_SHARED = "1a000000-0000-4000-8000-000000000001"
        private const val CUR_OWN = "1a000000-0000-4000-8000-000000000002"
        private const val PAIR_UUID = "2b000000-0000-4000-8000-000000000003"
        private const val LEDGER = "3c000000-0000-4000-8000-000000000004"

        private fun currencyRow(uuid: String, ledgerId: Any?, iso: String): JSONObject =
            JSONObject()
                .put("uuid", uuid)
                .put("ledger_id", ledgerId)
                .put("name", "Test $iso")
                .put("iso_code", iso)
                .put("icon_uuid", ICON_UUID)
                .put("symbol", iso)
                .put("precision", 2)
                .put("deleted_at", JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)
    }

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        val icon = ContentValues()
        icon.put("name", "test_icon")
        icon.put("image", byteArrayOf(0))
        icon.put("icon_type", 1)
        icon.put("uuid", ICON_UUID)
        db.insertOrThrow("icon", null, icon)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun applyCurrency_sharedFromNullLedgerId_ownFromLedgerId_cleanAfterPull() {
        val log = SyncLog()
        SyncEngine.applyCurrency(
            db, currencyRow(CUR_SHARED, JSONObject.NULL, "TSA")
                .put("show_in_equivalent", false), log
        )
        SyncEngine.applyCurrency(
            db, currencyRow(CUR_OWN, LEDGER, "TSB")
                .put("show_in_equivalent", true), log
        )

        val shared = row("currency", CUR_SHARED, "is_shared", "show_in_equivalent", "dirty")
        assertEquals(1, shared[0])
        assertEquals(0, shared[1])
        assertEquals(0, shared[2])
        val own = row("currency", CUR_OWN, "is_shared", "show_in_equivalent", "dirty")
        assertEquals(0, own[0])
        assertEquals(1, own[1])
        assertEquals(0, own[2])

        // Повторный pull той же строки (update, а не insert) — тоже без dirty.
        SyncEngine.applyCurrency(
            db, currencyRow(CUR_SHARED, JSONObject.NULL, "TSA")
                .put("show_in_equivalent", false), log
        )
        assertEquals(0, row("currency", CUR_SHARED, "dirty")[0])
        assertEquals(1, row("currency", CUR_SHARED, "is_shared")[0])
    }

    @Test
    fun applyCurrency_rowWithoutFlag_keepsLocalFlag() {
        val log = SyncLog()
        SyncEngine.applyCurrency(
            db, currencyRow(CUR_OWN, LEDGER, "TSB")
                .put("show_in_equivalent", false), log
        )
        assertEquals(0, row("currency", CUR_OWN, "show_in_equivalent")[0])

        // Старый сервер: ключа нет — локальный 0 не сбрасывается в DEFAULT/1.
        SyncEngine.applyCurrency(db, currencyRow(CUR_OWN, LEDGER, "TSB"), log)
        assertEquals(0, row("currency", CUR_OWN, "show_in_equivalent")[0])
        assertEquals(0, row("currency", CUR_OWN, "dirty")[0])
    }

    @Test
    fun applyCurrencyPair_isSharedFromLedgerId_clean() {
        val log = SyncLog()
        SyncEngine.applyCurrency(db, currencyRow(CUR_SHARED, JSONObject.NULL, "TSA"), log)
        SyncEngine.applyCurrency(db, currencyRow(CUR_OWN, JSONObject.NULL, "TSB"), log)
        val pair = JSONObject()
            .put("uuid", PAIR_UUID)
            .put("ledger_id", JSONObject.NULL)
            .put("symbol", "TSATSB")
            .put("base_currency_uuid", CUR_SHARED)
            .put("quote_currency_uuid", CUR_OWN)
            .put("precision", 4)
            .put("deleted_at", JSONObject.NULL)
            .put("updated_by", JSONObject.NULL)
        SyncEngine.applyCurrencyPair(db, pair, log)
        var p = row("currency_pair", PAIR_UUID, "is_shared", "dirty")
        assertEquals(1, p[0])
        assertEquals(0, p[1])

        SyncEngine.applyCurrencyPair(db, pair.put("ledger_id", LEDGER), log)
        p = row("currency_pair", PAIR_UUID, "is_shared", "dirty")
        assertEquals(0, p[0])
        assertEquals(0, p[1])
    }

    @Test
    fun push_currencyRowCarriesBooleanFlag() {
        val iconId = idOf("icon", ICON_UUID)
        val cv = ContentValues()
        cv.put("name", "Test coin")
        cv.put("iso_code", "TSC")
        cv.put("icon_id", iconId)
        cv.put("symbol", "T")
        cv.put("show_in_equivalent", 0)
        cv.put("uuid", CUR_OWN)
        db.insertOrThrow("currency", null, cv)

        val rows = SyncEngine.collectDirtyCurrencies(
            db,
            SyncEngine.PushIconRefs(db, HashSet(), ArrayList())
        )
        var pushed: JSONObject? = null
        for (i in 0 until rows.length()) {
            if (CUR_OWN == rows.getJSONObject(i).getString("uuid")) pushed = rows.getJSONObject(i)
        }
        assertTrue(pushed != null)
        assertTrue(pushed!!.get("show_in_equivalent") is Boolean)
        assertFalse(pushed.getBoolean("show_in_equivalent"))
        assertEquals(ICON_UUID, pushed.getString("icon_uuid"))
    }

    /** 2.4: повторный pull уже известной иконки не перекачивает байты (любой вызов api упал бы). */
    @Test
    fun prefetch_skipsKnownIcons() {
        val icons = JSONArray().put(
            JSONObject()
                .put("uuid", ICON_UUID)
                .put("content_sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        )
        val api = object : SyncApiClient(context, "test-token") {
            override fun downloadBlob(sha256Hex: String): ByteArray = throw AssertionError("не должно качать")
        }
        val blobs = SyncEngine.prefetchNewIconBlobs(db, icons, api)
        assertTrue(blobs.isEmpty())
    }

    private fun idOf(table: String, uuid: String): Long {
        val c = db.rawQuery("SELECT _id FROM $table WHERE uuid=?", arrayOf(uuid))
        try {
            assertTrue(c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun row(table: String, uuid: String, vararg columns: String): IntArray {
        val c = db.rawQuery(
            "SELECT " + columns.joinToString(", ") + " FROM " + table + " WHERE uuid=?",
            arrayOf(uuid)
        )
        try {
            assertTrue("$table $uuid не найдена", c.moveToFirst())
            return IntArray(columns.size) { c.getInt(it) }
        } finally {
            c.close()
        }
    }
}
