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
 * openspec json-debts-assets-format: контрагенты, блоки {@code debt}/{@code asset} и
 * {@code "system": "revaluation"} в файловом JSON — импорт, экспорт и круг «экспорт → импорт».
 */
@RunWith(RobolectricTestRunner::class)
class ImportDebtsAssetsJsonTest {

    companion object {
        private val ALL_TABLES = arrayOf(
                "operation", "operation_link", "account_debt", "counterparty_contact", "counterparty",
                "account_operating", "account_asset", "account",
                "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
                "recurring_operation"
        )

        private const val USD = "\"currency\":{\"iso\":\"USD\"}"

        private fun byName(arr: JSONArray, name: String): JSONObject {
            for (i in 0 until arr.length())
                if (name == arr.getJSONObject(i).getString("name")) return arr.getJSONObject(i)
            throw AssertionError("no account $name")
        }
    }

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

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
        db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv)
    }

    private fun importJson(json: String): WalletImportData.ImportResult {
        return WalletImportData.FillOperationDataFromJson(context,
                ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun debtWallet_newCounterpartyCreatedOnTheFly() {
        val r = importJson("{\"accounts\":[{\"name\":\"Вася · USD\"," + USD
                + ",\"debt\":{\"counterparty\":\"Вася\",\"comment\":\"за билеты\",\"due_date\":\"2026-12-31\"}}]}")

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(1, scalar("SELECT count(*) FROM counterparty WHERE name = 'Вася'"))
        assertEquals("за билеты", stringOf("SELECT comment FROM account_debt"))
        assertEquals("2026-12-31", stringOf("SELECT due_date FROM account_debt"))
        assertEquals("uuid долга = uuid счёта", 1, scalar("SELECT count(*) FROM account_debt ad"
                + " JOIN account a ON a._id = ad._id AND a.uuid = ad.uuid"
                + " JOIN counterparty cp ON cp.uuid = ad.counterparty_uuid WHERE a.name = 'Вася · USD'"))
    }

    @Test
    fun debtWallet_existingCounterpartyMatchedByNormalizedName() {
        importJson("{\"counterparties\":[{\"name\":\"Алёна\"}]}")
        importJson("{\"accounts\":[{\"name\":\"Алена · USD\"," + USD
                + ",\"debt\":{\"counterparty\":\" алена \"}}]}")

        assertEquals("второго контрагента нет", 1, scalar("SELECT count(*) FROM counterparty"))
        assertEquals(1, scalar("SELECT count(*) FROM account_debt"))
    }

    @Test
    fun counterparties_updatedNotDuplicated_archivedFlagApplied() {
        importJson("{\"counterparties\":[{\"name\":\"Петя\",\"comment\":\"a\"}]}")
        val r = importJson(
                "{\"counterparties\":[{\"name\":\"петя\",\"comment\":\"b\",\"archived\":true}]}")

        assertEquals(1, r.imported)
        assertEquals(1, scalar("SELECT count(*) FROM counterparty"))
        assertEquals("b", stringOf("SELECT comment FROM counterparty"))
        assertEquals(1, scalar("SELECT is_archived FROM counterparty"))
    }

    @Test
    fun asset_markedWithTypeAndNetWorth() {
        val r = importJson("{\"accounts\":[{\"name\":\"Квартира\"," + USD
                + ",\"asset\":{\"type\":\"real_estate\",\"include_in_net_worth\":false,\"comment\":\"центр\"}}]}")

        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())
        assertEquals(WalletContract.AccountAssetEntry.TYPE_REAL_ESTATE, scalar("SELECT asset_type FROM account_asset"))
        assertEquals(0, scalar("SELECT include_in_net_worth FROM account_asset"))
        assertEquals("центр", stringOf("SELECT comment FROM account_asset"))
    }

    @Test
    fun debtAndAsset_together_skippedWithoutPartialAccount() {
        val r = importJson("{\"accounts\":[{\"name\":\"X\"," + USD
                + ",\"debt\":{\"counterparty\":\"Z\"},\"asset\":{\"type\":\"other\"}},"
                + "{\"name\":\"Y\"," + USD + "}]}")

        assertEquals(1, r.skipped.size)
        assertTrue(r.skipped[0], r.skipped[0].startsWith("accounts#0 — "))
        assertEquals("X не заведён", 0, scalar("SELECT count(*) FROM account WHERE name = 'X'"))
        assertEquals("остальное применено", 1, scalar("SELECT count(*) FROM account WHERE name = 'Y'"))
        assertEquals(0, scalar("SELECT count(*) FROM counterparty"))
    }

    @Test
    fun blockOnCategory_badType_badDueDate_oppositeFlag_allSkipped() {
        importJson("{\"accounts\":[{\"name\":\"Долг · USD\"," + USD + ",\"debt\":{\"counterparty\":\"К\"}}]}")
        val r = importJson("{\"accounts\":["
                + "{\"name\":\"Кат\",\"asset\":{\"type\":\"other\"}},"
                + "{\"name\":\"A1\"," + USD + ",\"asset\":{\"type\":\"boat\"}},"
                + "{\"name\":\"D1\"," + USD + ",\"debt\":{\"counterparty\":\"К\",\"due_date\":\"2026-02-30\"}},"
                + "{\"name\":\"Долг · USD\"," + USD + ",\"asset\":{\"type\":\"other\"}}]}")

        assertEquals(r.skipped.toString(), 4, r.skipped.size)
        assertEquals(0, scalar("SELECT count(*) FROM account_asset"))
        assertEquals("кошелёк-долг не стал активом", 1, scalar("SELECT count(*) FROM account_debt"))
        assertEquals(0, scalar("SELECT count(*) FROM account WHERE name IN ('A1','D1')"))
    }

    @Test
    fun revaluation_stamped_andNoSecondCategory() {
        importJson("{\"accounts\":[{\"name\":\"Переоценка\",\"system\":\"revaluation\"}]}")
        assertEquals(3, scalar("SELECT system_kind FROM account WHERE name = 'Переоценка'"))

        importJson("{\"accounts\":[{\"name\":\"Другое имя\",\"system\":\"revaluation\"}]}")
        assertEquals("вторая системная не заведена", 0, scalar("SELECT count(*) FROM account WHERE name = 'Другое имя'"))
        assertEquals(1, scalar("SELECT count(*) FROM account WHERE system_kind = 3"))
    }

    @Test
    fun fileWithoutNewFields_importsAsBefore() {
        val r = importJson("{\"accounts\":[{\"name\":\"Cash\"," + USD + "}]}")

        assertTrue(r.skipped.isEmpty())
        assertEquals(0, scalar("SELECT count(*) FROM counterparty"))
        assertEquals(0, scalar("SELECT count(*) FROM account_debt"))
        assertEquals(0, scalar("SELECT count(*) FROM account_asset"))
    }

    @Test
    fun export_hasAllSections_noContactNoSyncFields() {
        val empty = WalletExportData.BuildExportJson(db)
        for (k in arrayOf("counterparties", "accounts", "currency_pairs", "rates", "operations"))
            assertTrue(k, empty.has(k))
        assertEquals(0, empty.getJSONArray("counterparties").length())

        importJson("{\"counterparties\":[{\"name\":\"Вася\"}],\"accounts\":[{\"name\":\"Вася · USD\"," + USD
                + ",\"debt\":{\"counterparty\":\"Вася\"}}]}")
        db.execSQL("INSERT INTO counterparty_contact (counterparty_uuid, lookup_key)"
                + " SELECT uuid, 'k1' FROM counterparty")
        val text = WalletExportData.BuildExportJson(db).toString()
        assertFalse(text.contains("lookup_key"))
        assertFalse(text.contains("\"uuid\""))
        assertFalse(text.contains("k1"))
    }

    /** Круг: телефон A → файл → пустой телефон B; контрагенты, долги со сроком, активы, переоценка. */
    @Test
    fun roundTrip_exportThenImportOnEmpty_restoresEverything() {
        importJson("{\"counterparties\":[{\"name\":\"Вася\",\"comment\":\"друг\",\"archived\":true}],"
                + "\"accounts\":["
                + "{\"name\":\"Вася · USD\"," + USD + ",\"debt\":{\"counterparty\":\"Вася\",\"comment\":\"c\",\"due_date\":\"2026-12-31\"}},"
                + "{\"name\":\"Авто\"," + USD + ",\"asset\":{\"type\":\"vehicle\",\"include_in_net_worth\":true,\"comment\":\"m\"}},"
                + "{\"name\":\"Переоценка\",\"system\":\"revaluation\"}]}")
        val exported = WalletExportData.BuildExportJson(db)

        val accs = exported.getJSONArray("accounts")
        val debtAcc = byName(accs, "Вася · USD")
        assertEquals("Вася", debtAcc.getJSONObject("debt").getString("counterparty"))
        assertEquals("2026-12-31", debtAcc.getJSONObject("debt").getString("due_date"))
        assertEquals("vehicle", byName(accs, "Авто").getJSONObject("asset").getString("type"))
        assertEquals("revaluation", byName(accs, "Переоценка").getString("system"))

        setUp() // пустая база — «другой телефон»
        val r = importJson(exported.toString())
        assertTrue("skipped: " + r.skipped, r.skipped.isEmpty())

        assertEquals(1, scalar("SELECT count(*) FROM counterparty WHERE name = 'Вася' AND is_archived = 1 AND comment = 'друг'"))
        assertEquals("2026-12-31", stringOf("SELECT due_date FROM account_debt"))
        assertEquals(WalletContract.AccountAssetEntry.TYPE_VEHICLE, scalar("SELECT asset_type FROM account_asset"))
        assertEquals("m", stringOf("SELECT comment FROM account_asset"))
        assertEquals(3, scalar("SELECT system_kind FROM account WHERE name = 'Переоценка'"))
    }

    private fun scalar(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun stringOf(sql: String): String? {
        val c = db.rawQuery(sql, null)
        try {
            return if (c.moveToFirst()) c.getString(0) else null
        } finally {
            c.close()
        }
    }
}
