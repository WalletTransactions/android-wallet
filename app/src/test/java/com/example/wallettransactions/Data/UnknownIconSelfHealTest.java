package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.DataItems.Icon;
import com.example.wallettransactions.Singleton;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * Регресс issue #18: после «Удалить ВСЁ» без пересева справочников таблица {@code icon}
 * пустая, {@link com.example.wallettransactions.DataItems.IconList#getUnknownIcon} отдавал
 * null — и тап по напоминанию (экран новой операции, TransactionActivity.bindSlot) падал NPE.
 * Singleton.loadIconList теперь сам заводит заглушку "unknown" в БД.
 */
@RunWith(RobolectricTestRunner.class)
public class UnknownIconSelfHealTest {

    /** Те же таблицы, что ResetDataActivity.ALL_TABLES. */
    private static final String[] ALL_TABLES = {
            "operation", "operation_link", "account_operating", "account",
            "currency_conversion", "currency_pair_rate", "currency_pair", "currency", "icon",
            "recurring_operation",
    };

    private Context context;
    private SQLiteDatabase db;

    @Before
    public void setUp() throws Exception {
        resetSingleton();
        context = ApplicationProvider.getApplicationContext();
        db = Singleton.instance.getWritableDatabase(context);
        // Как runHardWipe с reseed = false.
        for (String table : ALL_TABLES) db.execSQL("DELETE FROM " + table);
        db.execSQL("DELETE FROM sqlite_sequence");
    }

    @Test
    public void emptyIconTable_getIconList_seedsUnknownIconIntoDb() {
        Icon unknown = Singleton.instance.getIconList(context).getUnknownIcon();

        assertNotNull("заглушка должна появиться", unknown);
        assertNotNull(unknown.getBitmap());
        assertEquals(1, countIcons("unknown"));
        assertEquals("только заглушка, без остальных сид-иконок", 1, countIcons(null));
        assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, originOf(unknown.getId()));
    }

    @Test
    public void reloadAfterWipe_seedsUnknownIcon() {
        Singleton.instance.getIconList(context); // прогретый кэш, как до вайпа
        db.execSQL("DELETE FROM icon");

        Singleton.instance.reloadIconList(context);

        assertNotNull(Singleton.instance.getIconList(context).getUnknownIcon());
        assertEquals(1, countIcons("unknown"));
    }

    @Test
    public void existingUnknownIcon_isNotDuplicated() {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, "unknown");
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 2);
        long id = db.insert(WalletContract.IconEntry.TABLE_NAME, null, cv);

        Icon unknown = Singleton.instance.getIconList(context).getUnknownIcon();

        assertNotNull(unknown);
        assertEquals(id, unknown.getId());
        assertEquals(1, countIcons(null));
    }

    private int countIcons(String name) {
        Cursor c = name == null
                ? db.rawQuery("SELECT COUNT(*) FROM icon", null)
                : db.rawQuery("SELECT COUNT(*) FROM icon WHERE name = ?", new String[]{name});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private int originOf(long iconId) {
        Cursor c = db.rawQuery("SELECT origin FROM icon WHERE _id = ?",
                new String[]{String.valueOf(iconId)});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    /** Singleton — статическое поле, переживает тесты; см. HardWipeCacheInvalidationTest. */
    private void resetSingleton() throws Exception {
        for (String f : new String[]{"dbHelper", "readableDatabase", "writeableDatabase",
                "iconList", "accounts", "currencyList"}) {
            Field field = Singleton.class.getDeclaredField(f);
            field.setAccessible(true);
            field.set(Singleton.instance, null);
        }
    }
}
