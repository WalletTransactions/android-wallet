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
 * android-wallet#50 (openspec fix-icon-reset-substitutes), задача 3.2: импорт счетов ставит
 * только живые иконки. Раньше не найденная иконка давала {@code icon_id = 1}, а у валюты
 * {@code LEFT JOIN icon} без {@code deleted_at} — 22 счёта владельца на удалённой
 * {@code Groceries} ({@code _id = 1}).
 */
@RunWith(RobolectricTestRunner::class)
class ImportLiveIconTest {

    companion object {
        /** Те же таблицы, что ResetDataActivity.ALL_TABLES. */
        private val ALL_TABLES = arrayOf(
                "operation", "operation_link", "account_operating", "account",
                "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
                "recurring_operation"
        )
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var deletedGroceries: Long = 0
    private var unknown: Long = 0
    private var coffee: Long = 0

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
        // Лёгкие иконки вместо PNG сида — см. HardWipeCacheInvalidationTest про CursorWindow.
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        db.execSQL("DELETE FROM sqlite_sequence")
        deletedGroceries = icon("Groceries", 1_000L)
        unknown = icon("unknown", null)
        coffee = icon("Coffee", null)
        assertEquals("как у владельца: удалённая иконка под _id = 1", 1L, deletedGroceries)
    }

    /**
     * Сценарий спеки «Иконки нет среди живых». Новая валюта из импорта здесь не проверяется:
     * её INSERT без {@code symbol} падает на NOT NULL независимо от иконки (не эта задача).
     */
    @Test
    fun deletedIcon_importedAsUnknown() {
        val r = importJson("{\"accounts\":["
                + "{\"name\":\"T wallet\",\"icon\":\"Groceries\"},"
                + "{\"name\":\"T missing\",\"icon\":\"Нет такой\"}]}")

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(unknown, iconIdOf("account", "T wallet"))
        assertEquals(unknown, iconIdOf("account", "T missing"))
    }

    @Test
    fun liveIcon_importedAsIs() {
        importJson("{\"accounts\":[{\"name\":\"T coffee\",\"icon\":\"Coffee\"}]}")

        assertEquals(coffee, iconIdOf("account", "T coffee"))
    }

    private fun importJson(json: String): WalletImportData.ImportResult {
        return WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun icon(name: String, deletedAt: Long?): Long {
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name)
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        if (deletedAt != null) cv.put(WalletContract.IconEntry.COLUMN_DELETED_AT, deletedAt)
        return db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    private fun iconIdOf(table: String, name: String): Long {
        return scalar("SELECT icon_id FROM $table WHERE name = '$name' AND deleted_at IS NULL")
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
}
