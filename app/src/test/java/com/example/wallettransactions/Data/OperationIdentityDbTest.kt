package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Один uuid — одна операция (openspec add-ai-statement-import, operation-identity):
 * миграция v83 сводит дубли и ставит уникальный индекс, {@link WalletDbCRUD#InsertOperationWithUuid}
 * не создаёт вторую строку и восстанавливает удалённую.
 */
@RunWith(RobolectricTestRunner::class)
class OperationIdentityDbTest {

    companion object {
        private const val UUID_DASHED = "5b1e0c2a-1111-4222-8333-444455556666"
        private const val UUID_PLAIN = "5B1E0C2A111142228333444455556666"
    }

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var walletId: Int = 0
    private var categoryId: Int = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase

        val iconId = insert(WalletContract.IconEntry.TABLE_NAME,
                WalletContract.IconEntry.COLUMN_NAME, "test_icon",
                WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0),
                WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        val currencyId = insert(WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry.COLUMN_NAME, "Test Euro",
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "TST",
                WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
                WalletContract.CurrencyEntry.COLUMN_SYMBOL, "T")
        walletId = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test wallet",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
                WalletContract.AccountEntry.COLUMN_KIND, 1).toInt()
        insert(WalletContract.AccountOperatingEntry.TABLE_NAME,
                WalletContract.AccountOperatingEntry._ID, walletId,
                WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId)
        categoryId = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test taxi",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId).toInt()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun upgradeTo83_keepsOneLiveRowPerUuidAndAddsUniqueIndex() {
        // База v82: уникального индекса ещё нет, а дубли uuid (в разном написании) есть.
        db.execSQL("DROP INDEX ux_operation_uuid")
        val first = operation(UUID_DASHED, 100.0)
        val second = operation(UUID_PLAIN, 100.0)
        operation("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 50.0)

        helper.onUpgrade(db, 82, 83)

        assertNull(deletedAt(first))
        assertTrue(deletedAt(second) != null)
        assertNotEquals(UUID_PLAIN, uuidOf(second))
        assertEquals(1, dirty(second))
        assertEquals(1, liveCount(UUID_DASHED))
        try {
            operation(UUID_PLAIN.lowercase(), 1.0)
            fail("индекс должен запретить второй uuid")
        } catch (expected: SQLiteConstraintException) {
            // ux_operation_uuid
        }
    }

    @Test
    fun upgradeTo83_prefersLiveRowOverDeleted() {
        db.execSQL("DROP INDEX ux_operation_uuid")
        val deleted = operation(UUID_DASHED, 100.0)
        db.execSQL("UPDATE operation SET deleted_at = 1 WHERE _id = $deleted")
        val live = operation(UUID_PLAIN, 100.0)

        helper.onUpgrade(db, 82, 83)

        assertNull(deletedAt(live))
        assertEquals(UUID_PLAIN, uuidOf(live))
        assertNotEquals(UUID_DASHED, uuidOf(deleted))
    }

    @Test
    fun insertWithUuid_newRowGetsThatUuid() {
        val id = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-12 12:00", walletId, categoryId,
                450.0, "YANDEX*GO")
        assertTrue(id > 0)
        assertEquals(UUID_DASHED, uuidOf(id))
        val m = WalletDbCRUD.FindOperationByUuid(db, UUID_PLAIN)!!
        assertEquals(id, m.id)
        assertFalse(m.deleted)
    }

    @Test
    fun insertWithUuid_liveExists_noSecondRow() {
        val id = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-12 12:00", walletId, categoryId,
                450.0, "")
        val again = WalletDbCRUD.InsertOperationWithUuid(db, UUID_PLAIN, "2026-10-12 12:00", walletId, categoryId,
                450.0, "")
        assertEquals(WalletDbCRUD.UUID_TAKEN, again)
        assertEquals(1, liveCount(UUID_DASHED))
        assertTrue(id > 0)
    }

    @Test
    fun insertWithUuid_deletedExists_restoresSameRow() {
        val id = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-12 12:00", walletId, categoryId,
                450.0, "")
        WalletDbCRUD.DeleteOperation(db, id.toInt())
        val restored = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-13 12:00", walletId,
                categoryId, 409.0, "new")
        assertEquals(id, restored)
        assertNull(deletedAt(id))
        val c = db.rawQuery("SELECT amount, date, comment, (SELECT COUNT(*) FROM operation) FROM operation WHERE _id=?",
                arrayOf(id.toString()))
        try {
            assertTrue(c.moveToFirst())
            assertEquals(409.0, c.getDouble(0), 0.001)
            assertEquals("2026-10-13 12:00", c.getString(1))
            assertEquals("new", c.getString(2))
            assertEquals(1, c.getInt(3))
        } finally {
            c.close()
        }
    }

    @Test
    fun insertOperation_withoutUuid_unchanged() {
        val a = WalletDbCRUD.InsertOperation(db, "2026-10-12 12:00", walletId, categoryId, 1.0, "")
        val b = WalletDbCRUD.InsertOperation(db, "2026-10-12 12:00", walletId, categoryId, 1.0, "")
        assertTrue(a > 0 && b > 0 && a != b)
        assertNotEquals(uuidOf(a), uuidOf(b))
    }

    private fun operation(uuid: String, amount: Double): Long {
        return insert(WalletContract.OperationEntry.TABLE_NAME,
                WalletContract.OperationEntry.COLUMN_DATE, "2026-10-12 12:00",
                WalletContract.OperationEntry.COLUMN_FROM, walletId,
                WalletContract.OperationEntry.COLUMN_TO, categoryId,
                WalletContract.OperationEntry.COLUMN_AMOUNT, amount,
                WalletContract.OperationEntry.COLUMN_COMMENT, "",
                WalletContract.OperationEntry.COLUMN_UUID, uuid)
    }

    private fun liveCount(uuid: String): Int {
        val c = db.rawQuery("SELECT COUNT(*) FROM operation WHERE lower(replace(uuid,'-',''))=? AND deleted_at IS NULL",
                arrayOf(uuid.lowercase().replace("-", "")))
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun deletedAt(id: Long): Long? {
        val c = db.rawQuery("SELECT deleted_at FROM operation WHERE _id=?", arrayOf(id.toString()))
        try {
            c.moveToFirst()
            return if (c.isNull(0)) null else c.getLong(0)
        } finally {
            c.close()
        }
    }

    private fun uuidOf(id: Long): String {
        val c = db.rawQuery("SELECT uuid FROM operation WHERE _id=?", arrayOf(id.toString()))
        try {
            c.moveToFirst()
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun dirty(id: Long): Int {
        val c = db.rawQuery("SELECT dirty FROM operation WHERE _id=?", arrayOf(id.toString()))
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            val value = kv[i + 1]
            when (value) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is Double -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }
}
