package com.example.wallettransactions.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SyncEngine.localUuid]: сервер отдаёт uuid в канонической форме с дефисами,
 * а локально он бывает и без них (`DEFAULT lower(hex(randomblob(16)))`).
 *
 * Реальный инцидент 2026-10-10: трата из Telegram-бота пришла в pull со ссылкой
 * `acc_fr_uuid = d0ba17f9-b0bf-c1ae-ed9c-61def5dc0c4b`, а счёт на телефоне
 * хранил `d0ba17f9b0bfc1aeed9c61def5dc0c4b` — операцию молча пропустили.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineLocalUuidTest {

    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE account (_id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL)")
        db.execSQL("INSERT INTO account (uuid) VALUES ('d0ba17f9b0bfc1aeed9c61def5dc0c4b')")
        db.execSQL("INSERT INTO account (uuid) VALUES ('1ab88231-3894-5135-9742-eafa3c4b4add')")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun dashedServerUuid_findsPlainLocal() {
        assertEquals(
            "d0ba17f9b0bfc1aeed9c61def5dc0c4b",
            SyncEngine.localUuid(db, "account", "d0ba17f9-b0bf-c1ae-ed9c-61def5dc0c4b")
        )
    }

    @Test
    fun plainUuid_findsDashedLocal() {
        assertEquals(
            "1ab88231-3894-5135-9742-eafa3c4b4add",
            SyncEngine.localUuid(db, "account", "1ab88231389451359742eafa3c4b4add")
        )
    }

    @Test
    fun exactAndUpperCaseMatch() {
        assertEquals(
            "d0ba17f9b0bfc1aeed9c61def5dc0c4b",
            SyncEngine.localUuid(db, "account", "d0ba17f9b0bfc1aeed9c61def5dc0c4b")
        )
        assertEquals(
            "1ab88231-3894-5135-9742-eafa3c4b4add",
            SyncEngine.localUuid(db, "account", "1AB88231-3894-5135-9742-EAFA3C4B4ADD")
        )
    }

    /** Тот же триггер, что WalletDbHelper.createDirtyTrigger: обновление с сервера не должно делать строку dirty. */
    @Test
    fun updateFromServer_keepsCleanRowClean() {
        db.execSQL(
            "CREATE TABLE operation (_id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, " +
                "amount TEXT, dirty INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TRIGGER trg_operation_dirty AFTER UPDATE ON operation WHEN NEW.dirty = OLD.dirty " +
                "BEGIN UPDATE operation SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1; END;"
        )
        db.execSQL("INSERT INTO operation (uuid, amount, dirty) VALUES ('a', '1', 0), ('b', '1', 1)")

        for (uuid in arrayOf("a", "b")) {
            val cv = ContentValues()
            cv.put("amount", "2")
            cv.put("dirty", 0)
            SyncEngine.updateFromServer(db, "operation", cv, uuid)
        }

        val c = db.rawQuery("SELECT amount, dirty FROM operation ORDER BY uuid", null)
        try {
            while (c.moveToNext()) {
                assertEquals("2", c.getString(0))
                assertEquals(0, c.getInt(1))
            }
        } finally {
            c.close()
        }

        // Обычная локальная правка (dirty не упомянут) по-прежнему помечает строку.
        db.execSQL("UPDATE operation SET amount = '3' WHERE uuid = 'a'")
        val d = db.rawQuery("SELECT dirty FROM operation WHERE uuid = 'a'", null)
        try {
            d.moveToFirst()
            assertEquals(1, d.getInt(0))
        } finally {
            d.close()
        }
    }

    @Test
    fun unknownOrNull_returnsNull() {
        assertNull(SyncEngine.localUuid(db, "account", "00000000-0000-0000-0000-000000000000"))
        assertNull(SyncEngine.localUuid(db, "account", null))
    }
}
