package com.example.wallettransactions.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList
import com.example.wallettransactions.Singleton
import org.json.JSONArray
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
import java.util.TreeMap

/**
 * Строка pull, чей родитель приходит на БОЛЕЕ ПОЗДНЕЙ странице, не теряется
 * (найдено на проверке #5, 2026-11-03): сервер отдаёт строку под её последним
 * seq, и валюта, у которой недавно сняли флаг эквивалента, уезжает в хвост
 * потока — а `account_operating` её кошелька остаётся в начале. Раньше
 * строка пропускалась с `Log.w`, курсор уходил дальше, и кошелёк навсегда
 * оставался без валюты: NPE в ленте «Операций», ClassCastException в пикере
 * эквивалента.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEnginePullDeferredTest {

    companion object {
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val GEL = "736cb366-1336-4b33-560d-5b6fad9f0236"
        private const val KES = "3d180d39-d091-72ff-498e-15c7124fea43"
        private const val WALLET = "bd77a9c4-9dac-8bdc-0c72-1939115745eb"

        /** Тот же список, что в HardWipeCacheInvalidationTest / ResetDataActivity. */
        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
        )

        /** Singleton — статическое поле, переживает тесты (как в HardWipeCacheInvalidationTest). */
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

        private fun currency(uuid: String, iso: String): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", "3c000000-0000-4000-8000-000000000004")
                .put("name", "Test $iso").put("iso_code", iso).put("icon_uuid", ICON)
                .put("symbol", iso).put("precision", 2).put("show_in_equivalent", false)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        private fun wallet(): JSONObject =
            JSONObject()
                .put("uuid", WALLET).put("name", "Test wallet TSG").put("comment", "")
                .put("icon_uuid", ICON).put("is_archived", false)
                .put("system_kind", 0).put("kind", 1)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        private fun accountOperating(currencyUuid: String): JSONObject =
            JSONObject()
                .put("uuid", WALLET).put("currency_uuid", currencyUuid)
                .put("deleted_at", JSONObject.NULL).put("updated_by", JSONObject.NULL)

        @Suppress("UNCHECKED_CAST")
        private fun changes(vararg tableAndRows: Any): JSONObject {
            val out = JSONObject()
            var i = 0
            while (i < tableAndRows.size) {
                val rows = JSONArray()
                for (row in tableAndRows[i + 1] as Array<JSONObject>) rows.put(row)
                out.put(tableAndRows[i] as String, rows)
                i += 2
            }
            return out
        }

        private fun rows(vararg r: JSONObject): Array<JSONObject> = arrayOf(*r)
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, PullDeferredRows.FILE_NAME).delete()
        // База самого Singleton: pullAll в конце перечитывает его кэши (счета,
        // валюты, пары). Сид чистим: его PNG-иконки не влезают в CursorWindow
        // под Robolectric (см. HardWipeCacheInvalidationTest.setUp), а счёт
        // «Georgian» и валюта GEL из сида столкнулись бы с тестовыми строками.
        db = Singleton.instance.getWritableDatabase(context)
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        val icon = ContentValues()
        icon.put("name", "test_icon")
        icon.put("image", byteArrayOf(0))
        icon.put("icon_type", 1)
        icon.put("uuid", ICON)
        db.insertOrThrow("icon", null, icon)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // ── applyPage ────────────────────────────────────────────────────────────

    @Test
    fun accountOperating_waitsForCurrencyFromLaterPage() {
        val deferred = PullDeferredRows.inMemory()
        val log = SyncLog()

        // Страница 1: кошелёк и его валютная строка, валюты ещё нет.
        SyncEngine.applyPage(
            db, changes(
                "account", rows(wallet()),
                "account_operating", rows(accountOperating(GEL))
            ), JSONObject(), null, log, deferred
        )
        assertNull(walletCurrencyIso())
        assertEquals(1, deferred.size())

        // Страница 2: валюта (её недавно правили — она в хвосте потока).
        SyncEngine.applyPage(
            db, changes("currency", rows(currency(GEL, "TSG"))),
            JSONObject(), null, log, deferred
        )
        assertEquals("TSG", walletCurrencyIso())
        assertTrue(deferred.isEmpty())
    }

    @Test
    fun deferredRow_supersededByNewerVersionInPage() {
        val deferred = PullDeferredRows.inMemory()
        val log = SyncLog()
        SyncEngine.applyPage(db, changes("currency", rows(currency(KES, "TSK"))), JSONObject(), null, log, deferred)
        SyncEngine.applyPage(
            db, changes(
                "account", rows(wallet()),
                "account_operating", rows(accountOperating(GEL))
            ), JSONObject(), null, log, deferred
        )
        assertEquals(1, deferred.size())

        // Новая версия той же строки (кошелёк перевели на KES) приходит вместе с GEL:
        // старая отложенная (GEL) не должна лечь поверх новой.
        SyncEngine.applyPage(
            db, changes(
                "currency", rows(currency(GEL, "TSG")),
                "account_operating", rows(accountOperating(KES))
            ), JSONObject(), null, log, deferred
        )
        assertEquals("TSK", walletCurrencyIso())
        assertTrue(deferred.isEmpty())
    }

    @Test
    fun deferredRows_surviveProcessRestart() {
        val deferred = PullDeferredRows.load(context)
        deferred.add("account_operating", accountOperating(GEL))
        deferred.save()

        val reloaded = PullDeferredRows.load(context)
        assertEquals(1, reloaded.size())
        assertEquals("account_operating: 1", reloaded.summary())

        reloaded.clear()
        reloaded.save()
        assertFalse(File(context.noBackupFilesDir, PullDeferredRows.FILE_NAME).exists())
    }

    // ── pullAll целиком ──────────────────────────────────────────────────────

    /**
     * Как на телефоне: полная загрузка, account_operating на первой странице,
     * валюта на последней. После pull у кошелька есть валюта, курсоры дошли до конца.
     */
    @Test
    fun pullAll_currencyOnLastPage_walletGetsCurrency() {
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
        AppSettings.setLedgerSyncSeq(context, 0L)
        AppSettings.setReferenceSyncSeq(context, 0L)

        val api = FakeApi(context)
        api.page(1, changes("account", rows(wallet()), "account_operating", rows(accountOperating(GEL))))
        api.page(2, changes("currency", rows(currency(KES, "TSK"))))
        api.page(3, changes("currency", rows(currency(GEL, "TSG"))))

        val log = SyncLog()
        SyncEngine.pullAll(db, api, context, log)

        assertEquals("TSG", walletCurrencyIso())
        assertEquals(3L, AppSettings.getLedgerSyncSeq(context))
        assertTrue(PullDeferredRows.load(context).isEmpty())
        assertTrue(log.getProblems().isEmpty())
    }

    /** Родителя нет и в конце потока — строка не теряется, а синк говорит об этом. */
    @Test
    fun pullAll_parentNeverArrives_rowKeptAndReported() {
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
        AppSettings.setLedgerSyncSeq(context, 0L)
        AppSettings.setReferenceSyncSeq(context, 0L)

        val api = FakeApi(context)
        api.page(1, changes("account", rows(wallet()), "account_operating", rows(accountOperating(GEL))))

        val log = SyncLog()
        SyncEngine.pullAll(db, api, context, log)
        assertNull(walletCurrencyIso())
        assertEquals(1, PullDeferredRows.load(context).size())
        assertEquals(1, log.getProblems().size)

        // Следующий синк приносит валюту — отложенная строка применяется.
        api.page(2, changes("currency", rows(currency(GEL, "TSG"))))
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertEquals("TSG", walletCurrencyIso())
        assertTrue(PullDeferredRows.load(context).isEmpty())
    }

    /** Уже испорченная база: первый синк после обновления один раз тянет всё с нуля. */
    @Test
    fun pullAll_oneTimeResync_resetsCursors() {
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setLedgerSyncSeq(context, 42L)
        AppSettings.setReferenceSyncSeq(context, 7L)

        val api = FakeApi(context)
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertEquals(0L, api.calls[0][0])
        assertEquals(0L, api.calls[0][1])
        assertTrue(AppSettings.isDeferredRowsResyncDone(context))

        // Второй раз — уже без сброса.
        AppSettings.setLedgerSyncSeq(context, 42L)
        api.calls.clear()
        SyncEngine.pullAll(db, api, context, SyncLog())
        assertEquals(42L, api.calls[0][0])
    }

    // ── вспомогательное ──────────────────────────────────────────────────────

    /** ISO валюты кошелька через account_operating; null — строки нет. */
    private fun walletCurrencyIso(): String? {
        val c = db.rawQuery(
            "SELECT c.iso_code FROM account a" +
                " JOIN account_operating ao ON ao._id = a._id AND ao.deleted_at IS NULL" +
                " JOIN currency c ON c._id = ao.currency_id WHERE a.uuid = ?", arrayOf(WALLET)
        )
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }

    /**
     * Поток леджера страницами: страница с seq N отдаётся, если курсор меньше N.
     * Поток reference пуст.
     */
    private class FakeApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        private val pages = TreeMap<Long, JSONObject>()

        /** Курсоры каждого вызова pull: [ledger, reference]. */
        val calls = ArrayList<LongArray>()

        fun page(seq: Long, changes: JSONObject) {
            pages[seq] = changes
        }

        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            calls.add(longArrayOf(ledgerSeq, referenceSeq))
            for ((key, value) in pages) {
                if (key > ledgerSeq) {
                    return SyncApiClient.PullResult(
                        SyncApiClient.PullStream(key, value, false, null),
                        SyncApiClient.PullStream(referenceSeq, JSONObject(), false, null)
                    )
                }
            }
            return SyncApiClient.PullResult(
                SyncApiClient.PullStream(ledgerSeq, JSONObject(), false, null),
                SyncApiClient.PullStream(referenceSeq, JSONObject(), false, null)
            )
        }
    }
}
