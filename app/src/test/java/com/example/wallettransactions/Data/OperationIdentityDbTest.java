package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Один uuid — одна операция (openspec add-ai-statement-import, operation-identity):
 * миграция v83 сводит дубли и ставит уникальный индекс, {@link WalletDbCRUD#InsertOperationWithUuid}
 * не создаёт вторую строку и восстанавливает удалённую.
 */
@RunWith(RobolectricTestRunner.class)
public class OperationIdentityDbTest {

    private static final String UUID_DASHED = "5b1e0c2a-1111-4222-8333-444455556666";
    private static final String UUID_PLAIN = "5B1E0C2A111142228333444455556666";

    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private int walletId;
    private int categoryId;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();

        long iconId = insert(WalletContract.IconEntry.TABLE_NAME,
                WalletContract.IconEntry.COLUMN_NAME, "test_icon",
                WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0},
                WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
        long currencyId = insert(WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry.COLUMN_NAME, "Test Euro",
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "TST",
                WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
                WalletContract.CurrencyEntry.COLUMN_SYMBOL, "T");
        walletId = (int) insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test wallet",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
                WalletContract.AccountEntry.COLUMN_KIND, 1);
        insert(WalletContract.AccountOperatingEntry.TABLE_NAME,
                WalletContract.AccountOperatingEntry._ID, walletId,
                WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId);
        categoryId = (int) insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test taxi",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void upgradeTo83_keepsOneLiveRowPerUuidAndAddsUniqueIndex() {
        // База v82: уникального индекса ещё нет, а дубли uuid (в разном написании) есть.
        db.execSQL("DROP INDEX ux_operation_uuid");
        long first = operation(UUID_DASHED, 100);
        long second = operation(UUID_PLAIN, 100);
        operation("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 50);

        helper.onUpgrade(db, 82, 83);

        assertNull(deletedAt(first));
        assertTrue(deletedAt(second) != null);
        assertNotEquals(UUID_PLAIN, uuidOf(second));
        assertEquals(1, dirty(second));
        assertEquals(1, liveCount(UUID_DASHED));
        try {
            operation(UUID_PLAIN.toLowerCase(), 1);
            fail("индекс должен запретить второй uuid");
        } catch (SQLiteConstraintException expected) {
            // ux_operation_uuid
        }
    }

    @Test
    public void upgradeTo83_prefersLiveRowOverDeleted() {
        db.execSQL("DROP INDEX ux_operation_uuid");
        long deleted = operation(UUID_DASHED, 100);
        db.execSQL("UPDATE operation SET deleted_at = 1 WHERE _id = " + deleted);
        long live = operation(UUID_PLAIN, 100);

        helper.onUpgrade(db, 82, 83);

        assertNull(deletedAt(live));
        assertEquals(UUID_PLAIN, uuidOf(live));
        assertNotEquals(UUID_DASHED, uuidOf(deleted));
    }

    @Test
    public void insertWithUuid_newRowGetsThatUuid() {
        long id = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-12 12:00", walletId, categoryId,
                450, "YANDEX*GO");
        assertTrue(id > 0);
        assertEquals(UUID_DASHED, uuidOf(id));
        WalletDbCRUD.UuidMatch m = WalletDbCRUD.FindOperationByUuid(db, UUID_PLAIN);
        assertEquals(id, m.id);
        assertFalse(m.deleted);
    }

    @Test
    public void insertWithUuid_liveExists_noSecondRow() {
        long id = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-12 12:00", walletId, categoryId,
                450, "");
        long again = WalletDbCRUD.InsertOperationWithUuid(db, UUID_PLAIN, "2026-10-12 12:00", walletId, categoryId,
                450, "");
        assertEquals(WalletDbCRUD.UUID_TAKEN, again);
        assertEquals(1, liveCount(UUID_DASHED));
        assertTrue(id > 0);
    }

    @Test
    public void insertWithUuid_deletedExists_restoresSameRow() {
        long id = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-12 12:00", walletId, categoryId,
                450, "");
        WalletDbCRUD.DeleteOperation(db, (int) id);
        long restored = WalletDbCRUD.InsertOperationWithUuid(db, UUID_DASHED, "2026-10-13 12:00", walletId,
                categoryId, 409, "new");
        assertEquals(id, restored);
        assertNull(deletedAt(id));
        Cursor c = db.rawQuery("SELECT amount, date, comment, (SELECT COUNT(*) FROM operation) FROM operation WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            assertEquals(409, c.getDouble(0), 0.001);
            assertEquals("2026-10-13 12:00", c.getString(1));
            assertEquals("new", c.getString(2));
            assertEquals(1, c.getInt(3));
        } finally {
            c.close();
        }
    }

    @Test
    public void insertOperation_withoutUuid_unchanged() {
        long a = WalletDbCRUD.InsertOperation(db, "2026-10-12 12:00", walletId, categoryId, 1, "");
        long b = WalletDbCRUD.InsertOperation(db, "2026-10-12 12:00", walletId, categoryId, 1, "");
        assertTrue(a > 0 && b > 0 && a != b);
        assertNotEquals(uuidOf(a), uuidOf(b));
    }

    private long operation(String uuid, double amount) {
        return insert(WalletContract.OperationEntry.TABLE_NAME,
                WalletContract.OperationEntry.COLUMN_DATE, "2026-10-12 12:00",
                WalletContract.OperationEntry.COLUMN_FROM, walletId,
                WalletContract.OperationEntry.COLUMN_TO, categoryId,
                WalletContract.OperationEntry.COLUMN_AMOUNT, amount,
                WalletContract.OperationEntry.COLUMN_COMMENT, "",
                WalletContract.OperationEntry.COLUMN_UUID, uuid);
    }

    private int liveCount(String uuid) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM operation WHERE lower(replace(uuid,'-',''))=? AND deleted_at IS NULL",
                new String[]{uuid.toLowerCase().replace("-", "")});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private Long deletedAt(long id) {
        Cursor c = db.rawQuery("SELECT deleted_at FROM operation WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            c.moveToFirst();
            return c.isNull(0) ? null : c.getLong(0);
        } finally {
            c.close();
        }
    }

    private String uuidOf(long id) {
        Cursor c = db.rawQuery("SELECT uuid FROM operation WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            c.moveToFirst();
            return c.getString(0);
        } finally {
            c.close();
        }
    }

    private int dirty(long id) {
        Cursor c = db.rawQuery("SELECT dirty FROM operation WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private long insert(String table, Object... kv) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            if (value instanceof Integer) cv.put(key, (Integer) value);
            else if (value instanceof Long) cv.put(key, (Long) value);
            else if (value instanceof Double) cv.put(key, (Double) value);
            else if (value instanceof byte[]) cv.put(key, (byte[]) value);
            else cv.put(key, (String) value);
        }
        return db.insertOrThrow(table, null, cv);
    }
}
