package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Правило имён счетов (без учёта регистра, «ё» = «е»): индекс v80 его держит, миграция
 * v80 разводит старые дубли, поиск по имени его соблюдает.
 * База — голая таблица account в памяти: схема целиком (onCreate с сидом) тесту не нужна.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountNamesTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE account (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "deleted_at TEXT)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void key_ignores_case_and_treats_yo_as_ye() {
        assertEquals("кафе", AccountNames.key(db, "КАФЁ"));
        assertEquals("кафе", AccountNames.key(db, "Кафе"));
        assertEquals("елка", AccountNames.key(db, "Ёлка"));
        assertEquals("cafe", AccountNames.key(db, "CAFE"));
        assertEquals("мой кошелек 2", AccountNames.key(db, "Мой Кошелёк 2"));
    }

    @Test
    public void migration_renames_duplicates_and_index_rejects_new_ones() {
        insert("Кафе", null);
        insert("кафе", null);       // дубль → «кафе (2)»
        insert("КАФЁ", null);       // дубль → «КАФЁ (3)»: «(2)» уже занято
        insert("кафе", "2026-01-01"); // удалённый — не мешает и не трогается
        insert("Такси", null);

        WalletDbHelper.migrateAccountNameIndex(db);

        List<String> live = liveNames();
        assertEquals(4, live.size());
        assertTrue(live.contains("Кафе"));
        assertTrue(live.contains("кафе (2)"));
        assertTrue(live.contains("КАФЁ (3)"));
        assertTrue(live.contains("Такси"));

        try {
            insert("ТАКСИ", null);
            fail("индекс должен отклонить имя, совпадающее без учёта регистра");
        } catch (SQLiteConstraintException expected) {
            // ok
        }
        insert("такси", "2026-01-02"); // удалённые строки индекс не видит

        assertTrue(WalletDbCRUD.GetAccountIdByName(db, "кафё") > 0);
        assertEquals(-1, WalletDbCRUD.GetAccountIdByName(db, "Кино"));
    }

    @Test
    public void is_account_name_taken_ignores_the_account_itself() {
        WalletDbHelper.migrateAccountNameIndex(db);
        long id = insert("Кафе", null);

        assertTrue(WalletDbCRUD.IsAccountNameTaken(db, "КАФЁ", -1));
        assertFalse(WalletDbCRUD.IsAccountNameTaken(db, "кафе", (int) id));
        assertFalse(WalletDbCRUD.IsAccountNameTaken(db, "Кино", -1));
    }

    private long insert(String name, String deletedAt) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("deleted_at", deletedAt);
        return db.insertOrThrow("account", null, cv);
    }

    private List<String> liveNames() {
        List<String> out = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT name FROM account WHERE deleted_at IS NULL", null);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }
}
