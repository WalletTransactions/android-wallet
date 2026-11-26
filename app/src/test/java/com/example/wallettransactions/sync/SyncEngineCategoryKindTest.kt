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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * openspec category-kind-income-expense, группа 2: `account.category_kind` в push
 * (сервер без поля отвечает 400 invalid_row) и в pull (поле необязательное, значение вне
 * 0..2 — «Оба», строка не помечается неотправленной).
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineCategoryKindTest {

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private lateinit var log: SyncLog
    private var iconId = 0L
    private lateinit var iconUuid: String

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        db.execSQL("UPDATE icon SET deleted_at = 1") // сид не предмет теста
        // Счета стартового набора заняли бы имена (uq_account_name) и попали бы в push.
        db.execSQL("DELETE FROM operation_link")
        db.execSQL("DELETE FROM operation")
        db.execSQL("DELETE FROM operation_template")
        db.execSQL("DELETE FROM account_asset")
        db.execSQL("DELETE FROM account_debt")
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        log = SyncLog()
        iconUuid = UUID.randomUUID().toString()
        val cv = ContentValues()
        cv.put("name", "test")
        cv.put("image", byteArrayOf(1))
        cv.put("icon_type", 0)
        cv.put("origin", 0)
        cv.put("uuid", iconUuid)
        cv.put("dirty", 0)
        iconId = db.insertOrThrow("icon", null, cv)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    // ── 2.1 push ────────────────────────────────────────────────────────────

    @Test
    fun push_accountRowCarriesCategoryKind_forEveryValue() {
        val expense = category("Продукты", 0, true)
        val income = category("Зарплата", 1, true)
        val both = category("Подарки", 2, true)

        val byTable = SyncEngine.collectDirtyByTable(db, ArrayList())

        val rows = byTable["account"]!!
        assertEquals(3, rows.length())
        assertEquals(0, kindInPush(rows, expense))
        assertEquals(1, kindInPush(rows, income))
        assertEquals(2, kindInPush(rows, both))
    }

    @Test
    fun push_walletRowCarriesBoth() {
        val uuid = UUID.randomUUID().toString()
        val cv = ContentValues()
        cv.put("name", "Наличные")
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("kind", 1)
        cv.put("uuid", uuid)
        cv.put("dirty", 1)
        db.insertOrThrow("account", null, cv)

        val rows = SyncEngine.collectDirtyByTable(db, ArrayList())["account"]!!

        assertEquals(2, kindInPush(rows, uuid))
        // Остальные обязательные колонки на месте: сервер читает их без дефолта.
        val row = rowByUuid(rows, uuid)
        assertTrue(row.has("system_kind"))
        assertTrue(row.has("kind"))
        assertTrue(row.has("category_kind"))
    }

    // ── 2.2 pull ────────────────────────────────────────────────────────────

    @Test
    fun pull_storesValueFromServer_andLeavesRowClean() {
        val uuid = UUID.randomUUID().toString()

        SyncEngine.applyAccount(db, serverRow(uuid, "Зарплата", 1), log, SyncEngine.PullIconGuard(db))

        assertEquals(1, intWhere("category_kind", uuid))
        assertEquals(0, intWhere("dirty", uuid))
    }

    @Test
    fun pull_updatesExistingCategory_withoutMakingItDirty() {
        val uuid = category("Продукты", 2, false)

        SyncEngine.applyAccount(db, serverRow(uuid, "Продукты", 0), log, SyncEngine.PullIconGuard(db))

        assertEquals(0, intWhere("category_kind", uuid))
        assertEquals(0, intWhere("dirty", uuid))
    }

    @Test
    fun pull_rowWithoutField_isAppliedAsBoth_notDeferred() {
        val uuid = UUID.randomUUID().toString()
        val row = serverRow(uuid, "Старый сервер", 1)
        row.remove("category_kind")

        val applied = SyncEngine.applyAccount(db, row, log, SyncEngine.PullIconGuard(db))

        assertTrue(applied)
        assertEquals(2, intWhere("category_kind", uuid))
    }

    @Test
    fun pull_valueOutOfRange_becomesBoth() {
        val uuid = UUID.randomUUID().toString()

        SyncEngine.applyAccount(db, serverRow(uuid, "Мусор", 7), log, SyncEngine.PullIconGuard(db))

        assertEquals(2, intWhere("category_kind", uuid))
    }

    @Test
    fun pull_sameRowTwice_changesNothing() {
        val uuid = UUID.randomUUID().toString()
        val row = serverRow(uuid, "Подработка", 1)

        SyncEngine.applyAccount(db, row, log, SyncEngine.PullIconGuard(db))
        SyncEngine.applyAccount(db, row, log, SyncEngine.PullIconGuard(db))

        assertEquals(1, intWhere("category_kind", uuid))
        assertEquals(0, intWhere("dirty", uuid))
        assertEquals(1, count("account", "uuid = '$uuid'"))
    }

    // ── 2.3 строка без поля не попадает в «отложенные» ─────────────────────

    @Test
    fun pull_rowWithoutField_doesNotLandInLogOfSkippedRows() {
        val uuid = UUID.randomUUID().toString()
        val row = serverRow(uuid, "Без поля", 0)
        row.remove("category_kind")

        val applied = SyncEngine.applyAccount(db, row, log, SyncEngine.PullIconGuard(db))

        // false означал бы «строку откладываем»; она применена, и локально она есть.
        assertTrue(applied)
        assertEquals(1, count("account", "uuid = '$uuid'"))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private fun serverRow(uuid: String, name: String, categoryKind: Int): JSONObject =
        JSONObject()
            .put("uuid", uuid)
            .put("name", name)
            .put("comment", "")
            .put("icon_uuid", iconUuid)
            .put("is_archived", false)
            .put("system_kind", 0)
            .put("kind", 0)
            .put("category_kind", categoryKind)
            .put("deleted_at", JSONObject.NULL)
            .put("updated_by", JSONObject.NULL)

    /** Обычная категория с заданным видом; `dirty` — ждёт ли отправки. */
    private fun category(name: String, categoryKind: Int, dirty: Boolean): String {
        val uuid = UUID.randomUUID().toString()
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("comment", "")
        cv.put("icon_id", iconId)
        cv.put("kind", 0)
        cv.put("system_kind", 0)
        cv.put("category_kind", categoryKind)
        cv.put("uuid", uuid)
        cv.put("dirty", if (dirty) 1 else 0)
        db.insertOrThrow("account", null, cv)
        return uuid
    }

    private fun rowByUuid(rows: JSONArray, uuid: String): JSONObject {
        for (i in 0 until rows.length()) {
            if (uuid == rows.getJSONObject(i).getString("uuid")) return rows.getJSONObject(i)
        }
        throw AssertionError("в push нет строки $uuid")
    }

    private fun kindInPush(rows: JSONArray, uuid: String): Int =
        rowByUuid(rows, uuid).getInt("category_kind")

    private fun intWhere(column: String, uuid: String): Int {
        val c = db.rawQuery("SELECT $column FROM account WHERE uuid = ?", arrayOf(uuid))
        try {
            assertTrue("account $uuid не найден", c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun count(table: String, where: String): Int {
        val c = db.rawQuery("SELECT count(*) FROM $table WHERE $where", null)
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }
}
