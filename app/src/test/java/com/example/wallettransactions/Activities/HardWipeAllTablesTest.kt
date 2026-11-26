package com.example.wallettransactions.Activities

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

/**
 * «Полная очистка базы» (ResetDataActivity.runHardWipe) молча откатывалась на устройстве с
 * капитальными активами: `account_asset` ссылается на `account` по FK, но её не
 * было в списке очищаемых таблиц, и COMMIT падал (P20, 2026-11-10).
 */
@RunWith(RobolectricTestRunner::class)
class HardWipeAllTablesTest {

    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        // Новая база на каждый тест: Singleton держит её между тестами одного процесса.
        for (name in arrayOf("dbHelper", "readableDatabase", "writeableDatabase")) {
            val field = Singleton::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(Singleton.instance, null)
        }
        context = ApplicationProvider.getApplicationContext()
        db = Singleton.instance.getWritableDatabase(context)
    }

    /** Новая таблица БД обязана попасть в ALL_TABLES — иначе очистка забудет её или упадёт на FK. */
    @Test
    fun everyDataTableIsInWipeList() {
        val wiped = HashSet(ResetDataActivity.ALL_TABLES.toList())
        val missing = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (!NOT_WIPED.contains(name) && !wiped.contains(name)) missing.add(name)
            }
        }
        assertTrue("таблицы не в ALL_TABLES: $missing", missing.isEmpty())
    }

    /** Живой сценарий: счёт с активом, очистка в одной транзакции — COMMIT проходит. */
    @Test
    fun wipeWithAssetRow_commits() {
        db.execSQL("DELETE FROM account_asset")
        // Кошельки набора («Наличные», «Кредитная карта») держат account_operating.
        db.execSQL("DELETE FROM account_operating")
        db.execSQL("DELETE FROM account")
        // Чистая база уже засеяна набором — берём любую его иконку для FK счёта.
        val iconId = android.database.DatabaseUtils.longForQuery(db, "SELECT min(_id) FROM icon", null)
        db.execSQL("INSERT INTO account (_id, name, comment, icon_id) VALUES (9001, 'Квартира', '', $iconId)")
        db.execSQL("INSERT INTO account_asset (_id, asset_type) VALUES (9001, 1)")

        db.beginTransaction()
        try {
            ResetDataActivity.deleteAllRows(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        for (table in ResetDataActivity.ALL_TABLES) {
            db.rawQuery("SELECT count(*) FROM $table", null).use { c ->
                c.moveToFirst()
                assertEquals(table, 0, c.getInt(0))
            }
        }
    }

    companion object {
        /** Служебные таблицы, которые не очищаются: не данные пользователя. */
        private val NOT_WIPED = hashSetOf("android_metadata", "sqlite_sequence", "sync_reload_seen")
    }
}
