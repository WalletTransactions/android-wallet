package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.Data.CurrencyRoutes
import com.example.wallettransactions.Data.DerivedRates
import com.example.wallettransactions.Data.WalletDbHelper
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.Singleton
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
import java.io.File

/**
 * openspec currency-conversion-routes, синк: шаги маршрута (`currency_conversion`) в push по
 * контракту wallet-api (ключи, порядок после `currency_pair`, tombstone) и в pull (оба шага,
 * шаги по частям, ссылка на неизвестную пару откладывается), составной курс после pull.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineCurrencyRoutesTest {

    companion object {
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val BTC = "c0000000-0000-4000-8000-0000000000b1"
        private const val EUR = "c0000000-0000-4000-8000-0000000000e1"
        private const val RUB = "c0000000-0000-4000-8000-0000000000a1"
        private const val P_BTCEUR = "b0000000-0000-4000-8000-000000000001"
        private const val P_EURRUB = "b0000000-0000-4000-8000-000000000002"

        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_asset", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation", WalletDbHelper.SYNC_RELOAD_SEEN_TABLE,
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

        private fun stepRow(uuid: String, num: Int, pairUuid: String): JSONObject =
            JSONObject().put("uuid", uuid)
                .put("from_currency_uuid", BTC).put("to_currency_uuid", RUB)
                .put("num", num).put("currency_pair_uuid", pairUuid).put("is_reverse", false)
                .put("deleted_at", JSONObject.NULL)

        private fun rateRow(uuid: String, pairUuid: String, date: String, rate: String): JSONObject =
            JSONObject().put("uuid", uuid).put("currency_pair_uuid", pairUuid)
                .put("date", date).put("rate", rate).put("deleted_at", JSONObject.NULL)
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var iconId = 0L
    private var btc = 0L
    private var eur = 0L
    private var rub = 0L

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, PullDeferredRows.FILE_NAME).delete()
        db = Singleton.instance.getWritableDatabase(context)
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1, "uuid", ICON, "dirty", 0)
        btc = currency("Bitcoin", "BTC", "B", BTC)
        eur = currency("Euro", "EUR", "E", EUR)
        rub = currency("Ruble", "RUB", "R", RUB)
        insert(
            "currency_pair", "symbol", "BTCEUR", "base_currency_id", btc, "quote_currency_id", eur,
            "uuid", P_BTCEUR, "dirty", 0
        )
        insert(
            "currency_pair", "symbol", "EURRUB", "base_currency_id", eur, "quote_currency_id", rub,
            "uuid", P_EURRUB, "dirty", 0
        )
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
        AppSettings.setLedgerRereadPending(context, false)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // ── push ────────────────────────────────────────────────────────────────────

    @Test
    fun push_routeSteps_sentAfterPairs_withContractKeys() {
        db.execSQL("UPDATE currency_pair SET dirty = 1")
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc, eur, rub)).ok())

        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())

        val order = ArrayList(byTable.keys)
        assertTrue(
            "шаги после пар, на которые ссылаются",
            order.indexOf("currency_conversion") > order.indexOf("currency_pair")
        )
        val steps = byTable["currency_conversion"]!!
        assertEquals(2, steps.length())
        for (i in 0 until steps.length()) {
            val row = steps.getJSONObject(i)
            assertEquals(BTC, row.getString("from_currency_uuid"))
            assertEquals(RUB, row.getString("to_currency_uuid"))
            assertFalse(row.getBoolean("is_reverse"))
            assertFalse(row.getBoolean("deleted"))
            assertEquals(if (row.getInt("num") == 1) P_BTCEUR else P_EURRUB, row.getString("currency_pair_uuid"))
        }
    }

    @Test
    fun push_deletedRoute_sentAsTombstones() {
        assertTrue(CurrencyRoutes.create(db, longArrayOf(btc, eur, rub)).ok())
        db.execSQL("UPDATE currency_conversion SET dirty = 0") // как после успешного push
        CurrencyRoutes.delete(db, btc, rub)

        val steps = SyncEngine.collectDirtyByTable(db, ArrayList())["currency_conversion"]!!
        assertEquals(2, steps.length())
        for (i in 0 until steps.length()) assertTrue(steps.getJSONObject(i).getBoolean("deleted"))
    }

    // ── pull ────────────────────────────────────────────────────────────────────

    @Test
    fun pull_bothSteps_createRoute_andCompositeRateAppears() {
        val ledger = JSONObject()
            .put(
                "currency_pair_rate", JSONArray()
                    .put(rateRow("d0000000-0000-4000-8000-000000000001", P_BTCEUR, "2026-01-01", "90000"))
                    .put(rateRow("d0000000-0000-4000-8000-000000000002", P_EURRUB, "2026-01-01", "100"))
            )
            .put(
                "currency_conversion", JSONArray()
                    .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR))
                    .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB))
            )
        SyncEngine.applyPage(db, ledger, JSONObject(), null, SyncLog(), PullDeferredRows.inMemory())

        assertEquals(1, CurrencyRoutes.list(db).size)
        assertEquals(
            "строки с сервера не помечены к отправке", 0,
            intQuery("SELECT count(*) FROM currency_conversion WHERE dirty = 1")
        )
        DerivedRates.ensureFresh(db)
        assertEquals(
            9000000.0, doubleQuery(
                "SELECT rate FROM v_currency_conversion_rate WHERE from_currency_id = " +
                    btc + " AND to_currency_id = " + rub + " AND date <= '2026-02-01' ORDER BY date DESC LIMIT 1"
            ), 1e-6
        )
    }

    @Test
    fun pull_stepsInSeparateBatches_noRateUntilChainIsComplete() {
        val rates = JSONObject().put(
            "currency_pair_rate", JSONArray()
                .put(rateRow("d0000000-0000-4000-8000-000000000001", P_BTCEUR, "2026-01-01", "90000"))
                .put(rateRow("d0000000-0000-4000-8000-000000000002", P_EURRUB, "2026-01-01", "100"))
        )
        SyncEngine.applyPage(db, rates, JSONObject(), null, SyncLog(), PullDeferredRows.inMemory())

        SyncEngine.applyPage(
            db, JSONObject().put(
                "currency_conversion", JSONArray()
                    .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR))
            ),
            JSONObject(), null, SyncLog(), PullDeferredRows.inMemory()
        )
        DerivedRates.ensureFresh(db)
        assertEquals("недостроенная цепочка", 0, CurrencyRoutes.list(db).size)
        assertEquals(
            0, intQuery(
                "SELECT count(*) FROM currency_rate_derived WHERE from_currency_id = " + btc +
                    " AND to_currency_id = " + rub
            )
        )

        SyncEngine.applyPage(
            db, JSONObject().put(
                "currency_conversion", JSONArray()
                    .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB))
            ),
            JSONObject(), null, SyncLog(), PullDeferredRows.inMemory()
        )
        DerivedRates.ensureFresh(db)
        assertEquals(1, CurrencyRoutes.list(db).size)
        assertTrue(
            intQuery(
                "SELECT count(*) FROM currency_rate_derived WHERE from_currency_id = " + btc +
                    " AND to_currency_id = " + rub
            ) > 0
        )
    }

    @Test
    fun pull_stepWithUnknownPair_isSkipped() {
        val ledger = JSONObject().put(
            "currency_conversion", JSONArray()
                .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, "b0000000-0000-4000-8000-0000000000ff"))
        )
        SyncEngine.applyPage(db, ledger, JSONObject(), null, SyncLog(), PullDeferredRows.inMemory())
        assertEquals(0, intQuery("SELECT count(*) FROM currency_conversion"))
    }

    @Test
    fun pull_tombstonedSteps_removeRouteFromList() {
        val steps = JSONObject().put(
            "currency_conversion", JSONArray()
                .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR))
                .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB))
        )
        SyncEngine.applyPage(db, steps, JSONObject(), null, SyncLog(), PullDeferredRows.inMemory())
        assertEquals(1, CurrencyRoutes.list(db).size)

        val deleted = JSONObject().put(
            "currency_conversion", JSONArray()
                .put(stepRow("e0000000-0000-4000-8000-000000000001", 1, P_BTCEUR).put("deleted_at", 1700000000000L))
                .put(stepRow("e0000000-0000-4000-8000-000000000002", 2, P_EURRUB).put("deleted_at", 1700000000000L))
        )
        SyncEngine.applyPage(db, deleted, JSONObject(), null, SyncLog(), PullDeferredRows.inMemory())
        assertEquals(0, CurrencyRoutes.list(db).size)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun currency(name: String, iso: String, symbol: String, uuid: String): Long =
        insert(
            "currency", "name", name, "iso_code", iso, "icon_id", iconId, "symbol", symbol,
            "uuid", uuid, "dirty", 0
        )

    private fun intQuery(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun doubleQuery(sql: String): Double {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getDouble(0)
        } finally {
            c.close()
        }
    }

    private fun insert(table: String, vararg kv: Any?): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            when (val value = kv[i + 1]) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is Double -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String?)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
