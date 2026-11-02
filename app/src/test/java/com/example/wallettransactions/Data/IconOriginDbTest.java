package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.DataItems.IconList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * openspec server-managed-icons, задача 1.1: миграция v86 ({@code icon.origin}) и
 * происхождение иконок, заведённых на самом телефоне.
 */
@RunWith(RobolectricTestRunner.class)
public class IconOriginDbTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    @Test
    public void upgradeTo86_dirtyIconBecomesLocal_restFromServer_dirtyCleared() {
        // База v85: icon без origin, с тем же триггером dirty, что createDirtyTrigger.
        SQLiteDatabase old = SQLiteDatabase.create(null);
        try {
            old.execSQL("CREATE TABLE icon (_id INTEGER PRIMARY KEY AUTOINCREMENT, name INT NOT NULL, "
                    + "image BLOB NOT NULL, icon_type INT NOT NULL, dirty INTEGER NOT NULL DEFAULT 1, "
                    + "deleted_at INTEGER)");
            old.execSQL("CREATE TRIGGER trg_icon_dirty AFTER UPDATE ON icon WHEN NEW.dirty = OLD.dirty "
                    + "BEGIN UPDATE icon SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1; END;");
            old.execSQL("INSERT INTO icon (name, image, icon_type, dirty) VALUES "
                    + "('sent', x'00', 0, 0), ('pending', x'01', 0, 1), ('sent2', x'02', 1, 0)");

            WalletDbHelper.markIconOriginV86(old);

            Cursor c = old.rawQuery("SELECT name, origin, dirty FROM icon ORDER BY _id", null);
            try {
                assertTrue(c.moveToNext());
                assertEquals("sent", c.getString(0));
                assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, c.getInt(1));
                assertEquals(0, c.getInt(2));
                assertTrue(c.moveToNext());
                assertEquals("pending", c.getString(0));
                assertEquals(WalletContract.IconEntry.ORIGIN_LOCAL, c.getInt(1));
                assertEquals(0, c.getInt(2));
                assertTrue(c.moveToNext());
                assertEquals(WalletContract.IconEntry.ORIGIN_SERVER, c.getInt(1));
                assertEquals("триггер не должен пометить чистую строку", 0, c.getInt(2));
            } finally {
                c.close();
            }

            // База, уже пересобранная текущим телом (апгрейд из < 72), не падает на duplicate column.
            WalletDbHelper.markIconOriginV86(old);
        } finally {
            old.close();
        }
    }

    @Test
    public void onUpgradeFrom85_runsV86Step() {
        helper.onUpgrade(db, 85, 86);
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM icon WHERE dirty <> 0", null);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        } finally {
            c.close();
        }
    }

    /** 4.1: признак «с сервера» в модели {@code Icon} — из {@code icon.origin}. */
    @Test
    public void loadIcons_fromServerFlagFollowsOrigin() {
        // Сид не нужен (а крупный PNG из drawable не лезет в CursorWindow Robolectric).
        db.execSQL("UPDATE icon SET deleted_at = 1");
        long server = insertIcon("srv", WalletContract.IconEntry.ORIGIN_SERVER);
        long own = insertIcon("own", WalletContract.IconEntry.ORIGIN_LOCAL);
        IconList list = new IconList();
        WalletDbLoader.LoadIcons(db, list);
        assertTrue(list.getIconById((int) server).isFromServer());
        assertFalse(list.getIconById((int) own).isFromServer());
    }

    private long insertIcon(String name, int origin) {
        ContentValues cv = new ContentValues();
        cv.put(WalletContract.IconEntry.COLUMN_NAME, name);
        cv.put(WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0});
        cv.put(WalletContract.IconEntry.COLUMN_ICON_TYPE, 0);
        cv.put(WalletContract.IconEntry.COLUMN_ORIGIN, origin);
        return db.insertOrThrow(WalletContract.IconEntry.TABLE_NAME, null, cv);
    }

    /** Иконки из seed.json заведены на телефоне — на сервере их uuid нет, они свои. */
    @Test
    public void freshInstall_seedIconsAreLocal() {
        Cursor c = db.rawQuery("SELECT COUNT(*), SUM(origin = 0) FROM icon", null);
        try {
            assertTrue(c.moveToFirst());
            assertTrue("seed.json заводит иконки", c.getInt(0) > 0);
            assertEquals(0, c.getInt(1));
        } finally {
            c.close();
        }
    }
}
