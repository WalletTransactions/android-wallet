package com.example.wallettransactions.Data

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Правило имён счетов (без учёта регистра, «ё» = «е»): индекс v80 его держит, миграция
 * v80 разводит старые дубли, поиск по имени его соблюдает.
 * База — голая таблица account в памяти: схема целиком (onCreate с сидом) тесту не нужна.
 */
@RunWith(RobolectricTestRunner::class)
class AccountNamesTest {

    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE account (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "deleted_at TEXT)"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun search_matches_substring_ignoring_case_yo_and_edge_spaces() {
        assertTrue(AccountNames.matchesSearch("Алёна", "алена"))
        assertTrue(AccountNames.matchesSearch("Вася", "  АС "))
        assertTrue(AccountNames.matchesSearch("Вася", ""))
        assertTrue(AccountNames.matchesSearch("Вася", null))
        assertFalse(AccountNames.matchesSearch("Вася", "петя"))
    }

    @Test
    fun search_treats_special_characters_as_plain_text() {
        assertFalse(AccountNames.matchesSearch("Вася", "("))
        assertFalse(AccountNames.matchesSearch("Вася", ".*["))
        assertTrue(AccountNames.matchesSearch("Вася (брат)", "(бр"))
    }

    @Test
    fun key_ignores_case_and_treats_yo_as_ye() {
        assertEquals("кафе", AccountNames.key(db, "КАФЁ"))
        assertEquals("кафе", AccountNames.key(db, "Кафе"))
        assertEquals("елка", AccountNames.key(db, "Ёлка"))
        assertEquals("cafe", AccountNames.key(db, "CAFE"))
        assertEquals("мой кошелек 2", AccountNames.key(db, "Мой Кошелёк 2"))
    }

    @Test
    fun migration_renames_duplicates_and_index_rejects_new_ones() {
        insert("Кафе", null)
        insert("кафе", null) // дубль → «кафе (2)»
        insert("КАФЁ", null) // дубль → «КАФЁ (3)»: «(2)» уже занято
        insert("кафе", "2026-01-01") // удалённый — не мешает и не трогается
        insert("Такси", null)

        WalletDbHelper.migrateAccountNameIndex(db)

        val live = liveNames()
        assertEquals(4, live.size)
        assertTrue(live.contains("Кафе"))
        assertTrue(live.contains("кафе (2)"))
        assertTrue(live.contains("КАФЁ (3)"))
        assertTrue(live.contains("Такси"))

        try {
            insert("ТАКСИ", null)
            fail("индекс должен отклонить имя, совпадающее без учёта регистра")
        } catch (expected: SQLiteConstraintException) {
            // ok
        }
        insert("такси", "2026-01-02") // удалённые строки индекс не видит

        assertTrue(WalletDbCRUD.GetAccountIdByName(db, "кафё") > 0)
        assertEquals(-1, WalletDbCRUD.GetAccountIdByName(db, "Кино"))
    }

    @Test
    fun is_account_name_taken_ignores_the_account_itself() {
        WalletDbHelper.migrateAccountNameIndex(db)
        val id = insert("Кафе", null)

        assertTrue(WalletDbCRUD.IsAccountNameTaken(db, "КАФЁ", -1))
        assertFalse(WalletDbCRUD.IsAccountNameTaken(db, "кафе", id.toInt()))
        assertFalse(WalletDbCRUD.IsAccountNameTaken(db, "Кино", -1))
    }

    private fun insert(name: String, deletedAt: String?): Long {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("deleted_at", deletedAt)
        return db.insertOrThrow("account", null, cv)
    }

    private fun liveNames(): List<String> {
        val out = ArrayList<String>()
        val c = db.rawQuery("SELECT name FROM account WHERE deleted_at IS NULL", null)
        try {
            while (c.moveToNext()) out.add(c.getString(0))
        } finally {
            c.close()
        }
        return out
    }
}
