package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Singleton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Поле {@code archived} у счетов JSON-обмена: необязательное чтение при импорте (нет поля —
 * архивность не трогаем) и запись в экспорт только для архивных.
 */
@RunWith(RobolectricTestRunner::class)
class ImportExportArchivedTest {

    companion object {
        private val ALL_TABLES = arrayOf(
                "operation", "operation_link", "account_debt", "counterparty_contact", "counterparty",
                "account_operating", "account_asset", "account",
                "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
                "recurring_operation"
        )
        private const val USD = "\"currency\":{\"iso\":\"USD\"}"

        private fun find(accounts: JSONArray, name: String): JSONObject {
            for (i in 0 until accounts.length()) {
                if (name == accounts.getJSONObject(i).getString("name")) return accounts.getJSONObject(i)
            }
            throw AssertionError("в экспорте нет счёта $name")
        }

        private fun resetSingleton() {
            for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                    "iconList", "accounts", "currencyList")) {
                val field = Singleton::class.java.getDeclaredField(f)
                field.isAccessible = true
                field.set(Singleton.instance, null)
            }
        }
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    @Throws(Exception::class)
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        wipe()
    }

    @Test
    fun import_archivedTrue_marksNewWallet() {
        val r = importJson("{\"accounts\":["
                + "{\"name\":\"Старый\"," + USD + ",\"archived\":true},"
                + "{\"name\":\"Живой\"," + USD + ",\"archived\":false},"
                + "{\"name\":\"Без поля\"," + USD + "}]}")

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(1, archivedOf("Старый"))
        assertEquals(0, archivedOf("Живой"))
        assertEquals(0, archivedOf("Без поля"))
    }

    @Test
    fun import_existingWallet_withField_changesArchive() {
        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + "}]}")

        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + ",\"archived\":true}]}")
        assertEquals(1, archivedOf("Счёт"))

        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + ",\"archived\":false}]}")
        assertEquals(0, archivedOf("Счёт"))
    }

    @Test
    fun import_existingArchivedWallet_withoutField_staysArchived() {
        importJson("{\"accounts\":[{\"name\":\"Счёт\"," + USD + ",\"archived\":true}]}")

        importJson("{\"accounts\":[{\"name\":\"Счёт\",\"comment\":\"новый комментарий\"," + USD + "}]}")

        assertEquals(1, archivedOf("Счёт"))
    }

    @Test
    fun export_writesArchivedOnlyForArchived() {
        importJson("{\"accounts\":["
                + "{\"name\":\"Старый\"," + USD + ",\"archived\":true},"
                + "{\"name\":\"Живой\"," + USD + "}]}")

        val accs = WalletExportData.BuildExportJson(db).getJSONArray("accounts")

        assertTrue(find(accs, "Старый").getBoolean("archived"))
        assertFalse("у неархивного ключа нет", find(accs, "Живой").has("archived"))
    }

    @Test
    fun roundTrip_exportThenImportOnEmpty_keepsArchive() {
        importJson("{\"accounts\":["
                + "{\"name\":\"Старый\"," + USD + ",\"archived\":true},"
                + "{\"name\":\"Живой\"," + USD + "}]}")
        val exported = WalletExportData.BuildExportJson(db)

        wipe()
        val r = importJson(exported.toString())

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(1, archivedOf("Старый"))
        assertEquals(0, archivedOf("Живой"))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun importJson(json: String): WalletImportData.ImportResult {
        return WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun wipe() {
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        db.execSQL("DELETE FROM sqlite_sequence")
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown")
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    private fun archivedOf(accountName: String): Int {
        val c = db.rawQuery("SELECT is_archived FROM account WHERE name = ? AND deleted_at IS NULL",
                arrayOf(accountName))
        try {
            assertTrue("нет счёта $accountName", c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
