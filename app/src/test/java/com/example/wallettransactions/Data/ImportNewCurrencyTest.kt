package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * android-wallet#54 (и #29): импорт счёта с валютой, которой нет в базе, падал
 * {@code NOT NULL constraint failed: currency.symbol} — формат не несёт символа, а INSERT не
 * ставил колонку. Раньше все валюты файла были в прежнем сиде, а в новом наборе их 15.
 * Валюта «уже есть» — только среди живых.
 */
@RunWith(RobolectricTestRunner::class)
class ImportNewCurrencyTest {

    companion object {
        private val ALL_TABLES = arrayOf(
                "operation", "operation_link", "account_operating", "account_asset", "account",
                "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
                "operation_template_step", "operation_template"
        )
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var unknownIcon: Long = 0

    @Before
    @Throws(Exception::class)
    fun setUp() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        db.execSQL("DELETE FROM sqlite_sequence")
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown")
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        unknownIcon = db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    @Test
    fun json_newCurrency_nativeSymbol_andWalletCreated() {
        val r = WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(("{\"accounts\":["
                        + "{\"name\":\"T usd\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}]}")
                        .toByteArray(StandardCharsets.UTF_8)))

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals("$", stringOf("SELECT symbol FROM currency WHERE iso_code = 'USD'"))
        assertEquals(2L, scalar("SELECT precision FROM currency WHERE iso_code = 'USD'"))
        assertEquals("кошелёк в новой валюте", 1L, scalar(
                "SELECT count(*) FROM account_operating ao JOIN account a ON a._id = ao._id"
                        + " JOIN currency c ON c._id = ao.currency_id"
                        + " WHERE a.name = 'T usd' AND c.iso_code = 'USD'"))
    }

    @Test
    fun csv_newCurrency_nativeSymbol_andWalletCreated() {
        val n = WalletImportData.FillAccountDataFromFile(context,
                ByteArrayInputStream(("AccountName,AccountComment,AccountIconName,"
                        + "CurrencyName,CurrencyIso,CurrencyIconName\n"
                        + "T eur,c,unknown,Euro,EUR,unknown\n").toByteArray(StandardCharsets.UTF_8)))

        assertEquals(1, n)
        assertEquals("€", stringOf("SELECT symbol FROM currency WHERE iso_code = 'EUR'"))
        assertEquals(1L, scalar("SELECT count(*) FROM account_operating WHERE currency_id = "
                + "(SELECT _id FROM currency WHERE iso_code = 'EUR')"))
    }

    @Test
    fun existingLiveCurrency_notDuplicated() {
        val json = "{\"accounts\":[{\"name\":\"T one\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}," +
                "{\"name\":\"T two\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}]}"
        WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(1L, scalar("SELECT count(*) FROM currency WHERE iso_code = 'USD' AND deleted_at IS NULL"))
        assertEquals(2L, scalar("SELECT count(*) FROM account_operating"))
    }

    /** Родной символ и точность по ISO; неизвестный код — сам код, точность 2. */
    @Test
    fun symbolAndPrecision_nativeWithIsoFallback() {
        assertEquals("₽", WalletImportData.currencySymbolFor("RUB"))
        assertEquals("₴", WalletImportData.currencySymbolFor("uah"))
        assertEquals("£", WalletImportData.currencySymbolFor("GBP"))
        assertEquals("ZZZ", WalletImportData.currencySymbolFor("ZZZ"))
        assertEquals("", WalletImportData.currencySymbolFor(null))
        assertEquals(0, WalletImportData.currencyPrecisionFor("JPY"))
        assertEquals(2, WalletImportData.currencyPrecisionFor("EUR"))
        assertEquals(2, WalletImportData.currencyPrecisionFor("ZZZ"))
    }

    /** Удалённая валюта с тем же кодом не блокирует вставку и не подхватывается кошельком. */
    @Test
    fun deletedCurrencySameIso_newOneCreated_walletUsesLive() {
        val cv = ContentValues()
        cv.put(WalletContract.CurrencyEntry.COLUMN_NAME, "Old dollar")
        cv.put(WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "USD")
        cv.put(WalletContract.CurrencyEntry.COLUMN_SYMBOL, "$")
        cv.put(WalletContract.CurrencyEntry.COLUMN_ICON_ID, unknownIcon)
        cv.put(WalletContract.CurrencyEntry.COLUMN_UUID, "dead-usd")
        cv.put(WalletContract.CurrencyEntry.COLUMN_DELETED_AT, 1_000L)
        val deletedId = db.insertOrThrow(WalletContract.CurrencyEntry.TABLE_NAME, null, cv)

        val r = WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(("{\"accounts\":["
                        + "{\"name\":\"T usd\",\"currency\":{\"name\":\"US Dollar\",\"iso\":\"USD\"}}]}")
                        .toByteArray(StandardCharsets.UTF_8)))

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        val liveId = scalar("SELECT _id FROM currency WHERE iso_code = 'USD' AND deleted_at IS NULL")
        assertTrue("новая живая валюта, не удалённая", liveId != deletedId)
        assertEquals("кошелёк на живой валюте", liveId, scalar(
                "SELECT ao.currency_id FROM account_operating ao JOIN account a ON a._id = ao._id"
                        + " WHERE a.name = 'T usd'"))
    }

    private fun scalar(sql: String): Long {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun stringOf(sql: String): String {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }
}
