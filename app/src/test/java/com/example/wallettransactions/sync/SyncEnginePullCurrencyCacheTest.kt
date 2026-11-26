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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * Правки валют с веба (android-wallet#5, пункт 4.6) видны в кэше [Singleton]
 * сразу после pull — список «Валюты» и пикеры читают именно его, без перезапуска.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEnginePullCurrencyCacheTest {

    companion object {
        private const val LEDGER = "4e195796-4f2a-492d-b6b9-4761d6b0d25b"
        private const val ICON = "0b9d6f3e-1111-4222-8333-444455556666"
        private const val GEL = "736cb366-1336-4b33-560d-5b6fad9f0236"
        private const val KES = "3d180d39-d091-72ff-498e-15c7124fea43"
        private const val CHF = "86243e00-85e1-4af6-91cc-dbf2c0376ab2"

        private val ALL_TABLES = arrayOf(
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "operation_template_step", "operation_template",
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

        private fun currency(uuid: String, iso: String, name: String, deleted: Boolean): JSONObject =
            JSONObject()
                .put("uuid", uuid).put("ledger_id", LEDGER)
                .put("name", name).put("iso_code", iso).put("icon_uuid", ICON)
                .put("symbol", iso).put("precision", 2).put("show_in_equivalent", true)
                .put("deleted_at", if (deleted) "2026-11-04T17:34:29Z" else JSONObject.NULL)
                .put("updated_by", JSONObject.NULL)

        private fun currencies(vararg rows: JSONObject): JSONObject {
            val arr = JSONArray()
            for (r in rows) arr.put(r)
            return JSONObject().put("currency", arr)
        }
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

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
        db.insertOrThrow("icon", null, icon)
        AppSettings.setUuidFormatResyncDone(context)
        AppSettings.setDeferredRowsResyncDone(context)
        AppSettings.setPairSymbolResyncDone(context)
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    /** Первый синк: GEL и KES в базе и в кэше, курсоры на голове. */
    private fun syncedTwoCurrencies(api: CurrencyPullApi) {
        api.steps.add(CurrencyPullApi.Step { _, r ->
            CurrencyPullApi.page(
                10,
                currencies(
                    currency(GEL, "GEL", "Georgian lari", false),
                    currency(KES, "KES", "Kenyan shilling", false)
                ), r
            )
        })
        AppSettings.setSyncLedgerId(context, LEDGER)
        SyncEngine.pullAll(db, api, context, SyncLog())
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun pull_newRenamedDeletedCurrency_visibleInSingletonCache() {
        val api = CurrencyPullApi(context)
        // Кэш загружен ДО синка — как при открытом приложении.
        val cache = Singleton.instance.getCurrencyList(context)
        syncedTwoCurrencies(api)
        assertNotNull(cache.getCurrencyByISO("GEL"))
        assertNotNull(cache.getCurrencyByISO("KES"))

        // С веба: создана CHF, GEL переименована, KES удалена.
        api.steps.add(CurrencyPullApi.Step { _, r ->
            CurrencyPullApi.page(
                13,
                currencies(
                    currency(CHF, "CHF", "Швейцарский франк", false),
                    currency(GEL, "GEL", "Лари", false),
                    currency(KES, "KES", "Kenyan shilling", true)
                ), r
            )
        })
        SyncEngine.pullAll(db, api, context, SyncLog())
        ShadowLooper.idleMainLooper()

        val after = Singleton.instance.getCurrencyList(context)
        assertNotNull("новая валюта с веба", after.getCurrencyByISO("CHF"))
        assertEquals("Лари", after.getCurrencyByISO("GEL")!!.name)
        assertNull("удалённая на вебе", after.getCurrencyByISO("KES"))
        assertEquals(2, after.size)
    }

    private class CurrencyPullApi(ctx: Context) : SyncApiClient(ctx, "test-token") {
        fun interface Step {
            @Throws(Exception::class)
            fun answer(ledgerSeq: Long, referenceSeq: Long): SyncApiClient.PullResult
        }

        val steps = ArrayDeque<Step>()

        @Throws(IOException::class)
        override fun pull(ledgerSeq: Long, referenceSeq: Long, limit: Int, ledgerId: String?): SyncApiClient.PullResult {
            val step = steps.poll() ?: return page(ledgerSeq, JSONObject(), referenceSeq)
            try {
                return step.answer(ledgerSeq, referenceSeq)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
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
