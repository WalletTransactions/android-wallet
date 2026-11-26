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
 * openspec category-kind-income-expense, задача 5.2 (спека category-kind, «Вид в JSON-обмене»):
 * {@code category_kind} у счетов JSON-экспорта и необязательное чтение при импорте.
 */
@RunWith(RobolectricTestRunner::class)
class ImportExportCategoryKindTest {

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

    // ── импорт ──────────────────────────────────────────────────────────────

    @Test
    fun import_categoryKind_isStored() {
        val r = importJson("{\"accounts\":["
                + "{\"name\":\"Зарплата\",\"category_kind\":1},"
                + "{\"name\":\"Продукты\",\"category_kind\":0},"
                + "{\"name\":\"Подарки\",\"category_kind\":2}]}")

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(1, kindOf("Зарплата"))
        assertEquals(0, kindOf("Продукты"))
        assertEquals(2, kindOf("Подарки"))
    }

    @Test
    fun import_oldFileWithoutField_isBoth_andDoesNotFail() {
        val r = importJson("{\"accounts\":[{\"name\":\"Кафе\"},{\"name\":\"Такси\"}]}")

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(2, kindOf("Кафе"))
        assertEquals(2, kindOf("Такси"))
    }

    @Test
    fun import_valueOutOfRange_isBoth() {
        importJson("{\"accounts\":[{\"name\":\"Мусор\",\"category_kind\":9},{\"name\":\"Минус\",\"category_kind\":-3}]}")

        assertEquals(2, kindOf("Мусор"))
        assertEquals(2, kindOf("Минус"))
    }

    @Test
    fun import_existingCategory_withoutField_keepsItsKind() {
        importJson("{\"accounts\":[{\"name\":\"Зарплата\",\"category_kind\":1}]}")

        importJson("{\"accounts\":[{\"name\":\"Зарплата\",\"comment\":\"новый комментарий\"}]}")

        assertEquals(1, kindOf("Зарплата"))
    }

    @Test
    fun import_existingCategory_withField_updatesKind() {
        importJson("{\"accounts\":[{\"name\":\"Подработка\"}]}")

        importJson("{\"accounts\":[{\"name\":\"Подработка\",\"category_kind\":1}]}")

        assertEquals(1, kindOf("Подработка"))
    }

    @Test
    fun import_walletAndSystemCategories_ignoreKind() {
        importJson("{\"accounts\":["
                + "{\"name\":\"Кошелёк\"," + USD + ",\"category_kind\":0},"
                + "{\"name\":\"Transfer\",\"category_kind\":1},"
                + "{\"name\":\"Переоценка\",\"system\":\"revaluation\",\"category_kind\":0}]}")

        assertEquals("кошелёк", 2, kindOf("Кошелёк"))
        assertEquals("системная Transfer", 2, kindOf("Transfer"))
        assertEquals("переоценка", 2, kindOf("Переоценка"))
    }

    // ── экспорт ─────────────────────────────────────────────────────────────

    @Test
    fun export_writesKindForRegularCategoriesOnly() {
        importJson("{\"accounts\":["
                + "{\"name\":\"Зарплата\",\"category_kind\":1},"
                + "{\"name\":\"Продукты\",\"category_kind\":0},"
                + "{\"name\":\"Подарки\"},"
                + "{\"name\":\"Кошелёк\"," + USD + "},"
                + "{\"name\":\"Переоценка\",\"system\":\"revaluation\"}]}")

        val accs = WalletExportData.BuildExportJson(db).getJSONArray("accounts")

        assertEquals(1, find(accs, "Зарплата").getInt("category_kind"))
        assertEquals(0, find(accs, "Продукты").getInt("category_kind"))
        assertEquals(2, find(accs, "Подарки").getInt("category_kind"))
        assertFalse("у кошелька ключа нет", find(accs, "Кошелёк").has("category_kind"))
        assertFalse("у переоценки ключа нет", find(accs, "Переоценка").has("category_kind"))
    }

    @Test
    fun export_formatDescription_mentionsTheField() {
        val exported = WalletExportData.BuildExportJson(db)
        assertTrue(exported.toString().contains("category_kind"))
    }

    // ── круг ────────────────────────────────────────────────────────────────

    @Test
    fun roundTrip_exportThenImportOnEmpty_keepsKinds() {
        importJson("{\"accounts\":["
                + "{\"name\":\"Зарплата\",\"category_kind\":1},"
                + "{\"name\":\"Продукты\",\"category_kind\":0},"
                + "{\"name\":\"Подарки\",\"category_kind\":2},"
                + "{\"name\":\"Кошелёк\"," + USD + "}]}")
        val exported = WalletExportData.BuildExportJson(db)

        wipe()
        val r = importJson(exported.toString())

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(1, kindOf("Зарплата"))
        assertEquals(0, kindOf("Продукты"))
        assertEquals(2, kindOf("Подарки"))
        assertEquals(2, kindOf("Кошелёк"))
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

    private fun kindOf(accountName: String): Int {
        val c = db.rawQuery("SELECT category_kind FROM account WHERE name = ? AND deleted_at IS NULL",
                arrayOf(accountName))
        try {
            assertTrue("нет счёта $accountName", c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
