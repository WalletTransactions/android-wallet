package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.Singleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Регресс issue #18: после «Удалить ВСЁ» без пересева справочников таблица {@code icon}
 * пустая, {@link com.example.wallettransactions.DataItems.IconList#getUnknownIcon} отдавал
 * null — и тап по напоминанию (экран новой операции, TransactionActivity.bindSlot) падал NPE.
 * Singleton.loadIconList теперь сам заводит заглушку "unknown" в БД.
 */
@RunWith(RobolectricTestRunner::class)
class UnknownIconSelfHealTest {

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

    @Before
    @Throws(Exception::class)
    fun setUp() {
        resetSingleton()
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
        // Как runHardWipe с reseed = false.
        for (table in ALL_TABLES) db.execSQL("DELETE FROM $table")
        db.execSQL("DELETE FROM sqlite_sequence")
    }

    @Test
    fun emptyIconTable_getIconList_seedsUnknownIconIntoDb() {
        val unknown = Singleton.instance.getIconList(context).unknownIcon

        assertNotNull("заглушка должна появиться", unknown)
        assertNotNull(unknown!!.bitmap)
        assertEquals(1, countIcons("unknown"))
        assertEquals("только заглушка, без остальных сид-иконок", 1, countIcons(null))
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, originOf(unknown.id.toLong()))
    }

    @Test
    fun reloadAfterWipe_seedsUnknownIcon() {
        Singleton.instance.getIconList(context) // прогретый кэш, как до вайпа
        db.execSQL("DELETE FROM icon")

        Singleton.instance.reloadIconList(context)

        assertNotNull(Singleton.instance.getIconList(context).unknownIcon)
        assertEquals(1, countIcons("unknown"))
    }

    @Test
    fun existingUnknownIcon_isNotDuplicated() {
        val cv = ContentValues()
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown")
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0))
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 2)
        val id = db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv)

        val unknown = Singleton.instance.getIconList(context).unknownIcon

        assertNotNull(unknown)
        assertEquals(id, unknown!!.id.toLong())
        assertEquals(1, countIcons(null))
    }

    private fun countIcons(name: String?): Int {
        val c = if (name == null)
            db.rawQuery("SELECT COUNT(*) FROM icon", null)
        else
            db.rawQuery("SELECT COUNT(*) FROM icon WHERE name = ?", arrayOf(name))
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun originOf(iconId: Long): Int {
        val c = db.rawQuery("SELECT origin FROM icon WHERE _id = ?",
                arrayOf(iconId.toString()))
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    /** Singleton — статическое поле, переживает тесты; см. HardWipeCacheInvalidationTest. */
    @Throws(Exception::class)
    private fun resetSingleton() {
        for (f in arrayOf("dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList")) {
            val field = Singleton::class.java.getDeclaredField(f)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
    }
}
