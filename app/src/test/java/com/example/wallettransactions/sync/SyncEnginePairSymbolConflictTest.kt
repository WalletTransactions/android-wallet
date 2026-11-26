package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
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
 * openspec sync-pair-symbol-adopt: общая пара и пара кошелька с одним символом (на сервере два
 * уникальных индекса, на телефоне один). Раньше пара кошелька молча терялась, а её курсы вечно
 * висели в отложенных (воспроизведено на qa 2026-11-16: 31 пара, 135 курсов).
 */
@RunWith(RobolectricTestRunner::class)
class SyncEnginePairSymbolConflictTest {

    companion object {
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val CUR_A = "1a000000-0000-4000-8000-000000000001"
        private const val CUR_B = "1a000000-0000-4000-8000-000000000002"
        private const val SHARED_PAIR = "2b000000-0000-4000-8000-000000000001"
        private const val LEDGER_PAIR = "2b000000-0000-4000-8000-000000000002"
        private const val LEDGER = "3c000000-0000-4000-8000-000000000004"

        private fun currencyRow(uuid: String, iso: String): JSONObject =
            JSONObject().put("uuid", uuid).put("ledger_id", JSONObject.NULL)
                .put("name", "Test $iso").put("iso_code", iso).put("icon_uuid", ICON)
                .put("symbol", iso).put("precision", 2)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        private fun pairRow(uuid: String, ledgerId: Any?): JSONObject =
            JSONObject().put("uuid", uuid).put("ledger_id", ledgerId)
                .put("symbol", "TSATSB").put("base_currency_uuid", CUR_A)
                .put("quote_currency_uuid", CUR_B).put("precision", 4)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        private fun rateRow(uuid: String, pairUuid: String, date: String, rate: String): JSONObject =
            JSONObject().put("uuid", uuid).put("ledger_id", LEDGER)
                .put("currency_pair_uuid", pairUuid).put("date", date).put("rate", rate)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)
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
        icon.put("uuid", ICON)
        db.insertOrThrow("icon", null, icon)
        val log = SyncLog()
        SyncEngine.applyCurrency(db, currencyRow(CUR_A, "TSA"), log)
        SyncEngine.applyCurrency(db, currencyRow(CUR_B, "TSB"), log)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun ledgerPairReplacesSyncedSharedPair_keepsIdAndRates() {
        val log = SyncLog()
        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log))
        val id = idOf(SHARED_PAIR)
        val rate = ContentValues()
        rate.put("currency_pair_id", id)
        rate.put("date", "2020-01-01")
        rate.put("rate", "70.5")
        rate.put("uuid", "4d000000-0000-4000-8000-000000000001")
        rate.put("dirty", 0)
        db.insertOrThrow("currency_pair_rate", null, rate)

        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log))

        assertEquals(1, count("currency_pair", "symbol='TSATSB' AND deleted_at IS NULL"))
        assertEquals(id, idOf(LEDGER_PAIR))
        assertEquals(0, count("currency_pair", "uuid='$SHARED_PAIR'"))
        assertEquals(0, intOf("currency_pair", LEDGER_PAIR, "is_shared"))
        assertEquals(0, intOf("currency_pair", LEDGER_PAIR, "dirty"))
        assertEquals(1, count("currency_pair_rate", "currency_pair_id=$id"))
    }

    @Test
    fun sharedPairYieldsToSyncedLedgerPair_notFailed() {
        val log = SyncLog()
        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log))

        // true — строка обработана, а не отложена
        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log))

        assertEquals(1, count("currency_pair", "symbol='TSATSB' AND deleted_at IS NULL"))
        assertEquals(1, count("currency_pair", "uuid='$LEDGER_PAIR'"))
        assertEquals(0, count("currency_pair", "uuid='$SHARED_PAIR'"))
    }

    @Test
    fun arrivalOrderDoesNotMatter() {
        val log = SyncLog()
        SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log)
        SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log)
        val a = snapshot()

        context.deleteDatabase("wallet.db")
        helper.close()
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        val icon = ContentValues()
        icon.put("name", "test_icon")
        icon.put("image", byteArrayOf(0))
        icon.put("icon_type", 1)
        icon.put("uuid", ICON)
        db.insertOrThrow("icon", null, icon)
        SyncEngine.applyCurrency(db, currencyRow(CUR_A, "TSA"), log)
        SyncEngine.applyCurrency(db, currencyRow(CUR_B, "TSB"), log)
        SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), log)
        SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log)

        assertEquals(a, snapshot())
    }

    @Test
    fun ledgerPairReplacesUnsentLocalPair_outsideConnection() {
        val own = ContentValues()
        own.put("symbol", "TSATSB")
        own.put("base_currency_id", idOfCurrency(CUR_A))
        own.put("quote_currency_id", idOfCurrency(CUR_B))
        own.put("precision", 5)
        own.put("uuid", "5e000000-0000-4000-8000-000000000001")
        db.insertOrThrow("currency_pair", null, own) // dirty = 1 по умолчанию
        assertFalse(SyncEngine.adoptLocalByKey)

        assertTrue(SyncEngine.applyCurrencyPair(db, pairRow(LEDGER_PAIR, LEDGER), SyncLog()))

        assertEquals(1, count("currency_pair", "symbol='TSATSB' AND deleted_at IS NULL"))
        assertEquals(0, intOf("currency_pair", LEDGER_PAIR, "dirty"))
    }

    @Test
    fun deferredRateAppliesAfterPairReplaced() {
        val log = SyncLog()
        SyncEngine.applyCurrencyPair(db, pairRow(SHARED_PAIR, JSONObject.NULL), log)

        // Пара кошелька и её курс приходят одной страницей — курс находит родителя по новому uuid.
        val ledger = JSONObject()
            .put("currency_pair", JSONArray().put(pairRow(LEDGER_PAIR, LEDGER)))
            .put(
                "currency_pair_rate", JSONArray()
                    .put(rateRow("4d000000-0000-4000-8000-000000000002", LEDGER_PAIR, "2020-02-01", "71.5"))
            )
        val deferred = PullDeferredRows.inMemory()
        SyncEngine.applyPage(db, ledger, JSONObject(), null, log, deferred)

        assertTrue(deferred.isEmpty())
        assertEquals(1, count("currency_pair_rate", "date='2020-02-01'"))
    }

    @Test
    fun unresolvableConflict_staysDeferred() {
        // Живая пара с тем же символом, но другой индекс не даёт заменить: валюты пары нет →
        // пара откладывается, а не объявляется обработанной.
        val bad = pairRow(LEDGER_PAIR, LEDGER).put("base_currency_uuid", "9f000000-0000-4000-8000-0000000000ff")
        val ledger = JSONObject().put("currency_pair", JSONArray().put(bad))
        val deferred = PullDeferredRows.inMemory()
        SyncEngine.applyPage(db, ledger, JSONObject(), null, SyncLog(), deferred)
        assertFalse(deferred.isEmpty())
    }

    @Test
    fun pairSymbolResyncFlag_isOneShot() {
        assertFalse(AppSettings.isPairSymbolResyncDone(context))
        AppSettings.setPairSymbolResyncDone(context)
        assertTrue(AppSettings.isPairSymbolResyncDone(context))
    }

    private fun snapshot(): String {
        val sb = StringBuilder()
        val c = db.rawQuery(
            "SELECT uuid, symbol, is_shared, dirty FROM currency_pair" +
                " WHERE symbol = 'TSATSB' AND deleted_at IS NULL ORDER BY uuid", null
        )
        try {
            while (c.moveToNext()) {
                sb.append(c.getString(0)).append('|').append(c.getString(1)).append('|')
                    .append(c.getInt(2)).append('|').append(c.getInt(3)).append(';')
            }
        } finally {
            c.close()
        }
        return sb.toString()
    }

    private fun idOf(pairUuid: String): Long {
        val c = db.rawQuery("SELECT _id FROM currency_pair WHERE uuid=?", arrayOf(pairUuid))
        try {
            assertTrue("пара $pairUuid не найдена", c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun idOfCurrency(uuid: String): Long {
        val c = db.rawQuery("SELECT _id FROM currency WHERE uuid=?", arrayOf(uuid))
        try {
            assertTrue(c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun count(table: String, where: String): Int {
        val c = db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun intOf(table: String, uuid: String, column: String): Int {
        val c = db.rawQuery("SELECT $column FROM $table WHERE uuid=?", arrayOf(uuid))
        try {
            assertTrue(c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
