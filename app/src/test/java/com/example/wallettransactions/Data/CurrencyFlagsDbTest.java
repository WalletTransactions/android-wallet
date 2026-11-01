package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.IconList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * openspec currency-ui-tweaks: миграция v85 ({@code show_in_equivalent}, {@code is_shared}),
 * загрузка флагов, запись флага формой валюты, запрет смены кода валюты из пары,
 * удаление своей пары вместе с курсами.
 */
@RunWith(RobolectricTestRunner.class)
public class CurrencyFlagsDbTest {

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        iconId = insert(WalletContract.IconEntry.TABLE_NAME,
                WalletContract.IconEntry.COLUMN_NAME, "test_icon",
                WalletContract.IconEntry.COLUMN_IMAGE, new byte[]{0},
                WalletContract.IconEntry.COLUMN_ICON_TYPE, 1);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 1.1 / 1.2: миграция v84 → v85 ───────────────────────────────────────────

    @Test
    public void upgradeTo85_addsColumnsWithoutTouchingDirty_andResetsReferenceCursor() {
        // База v84: таблицы без новых колонок, с тем же триггером dirty, что createDirtyTrigger.
        SQLiteDatabase old = SQLiteDatabase.create(null);
        try {
            old.execSQL("CREATE TABLE currency (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                    + "iso_code TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 1)");
            old.execSQL("CREATE TABLE currency_pair (_id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, "
                    + "dirty INTEGER NOT NULL DEFAULT 1)");
            for (String t : new String[]{"currency", "currency_pair"}) {
                old.execSQL("CREATE TRIGGER trg_" + t + "_dirty AFTER UPDATE ON " + t
                        + " WHEN NEW.dirty = OLD.dirty BEGIN UPDATE " + t
                        + " SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1; END;");
            }
            old.execSQL("INSERT INTO currency (name, iso_code, dirty) VALUES ('A', 'AAA', 0), ('B', 'BBB', 1)");
            old.execSQL("INSERT INTO currency_pair (symbol, dirty) VALUES ('AAABBB', 0)");

            AppSettings.setReferenceSyncSeq(context, 42);
            AppSettings.setLedgerSyncSeq(context, 7);

            WalletDbHelper.addCurrencyFlagsV85(old, context);

            Cursor c = old.rawQuery("SELECT iso_code, dirty, show_in_equivalent, is_shared FROM currency ORDER BY _id", null);
            try {
                assertTrue(c.moveToNext());
                assertEquals(0, c.getInt(1));
                assertEquals(1, c.getInt(2));
                assertEquals(0, c.getInt(3));
                assertTrue(c.moveToNext());
                assertEquals(1, c.getInt(1));
                assertEquals(1, c.getInt(2));
                assertEquals(0, c.getInt(3));
            } finally {
                c.close();
            }
            Cursor p = old.rawQuery("SELECT dirty, is_shared FROM currency_pair", null);
            try {
                assertTrue(p.moveToFirst());
                assertEquals(0, p.getInt(0));
                assertEquals(0, p.getInt(1));
            } finally {
                p.close();
            }
            assertEquals(0, AppSettings.getReferenceSyncSeq(context));
            assertEquals(7, AppSettings.getLedgerSyncSeq(context));

            // Повторный вызов (база, уже пересобранная текущим телом) не падает на duplicate column.
            WalletDbHelper.addCurrencyFlagsV85(old, context);
        } finally {
            old.close();
        }
    }

    @Test
    public void onUpgradeFrom84_runsV85Step() {
        AppSettings.setReferenceSyncSeq(context, 42);
        helper.onUpgrade(db, 84, 85);
        assertEquals(0, AppSettings.getReferenceSyncSeq(context));
    }

    // ── 1.3: загрузка флагов ────────────────────────────────────────────────────

    @Test
    public void loadCurrencyList_readsBothFlags() {
        long own = currency("Test own", "TSA");
        long shared = currency("Test shared", "TSB");
        db.execSQL("UPDATE currency SET show_in_equivalent = 0 WHERE _id = " + own);
        db.execSQL("UPDATE currency SET is_shared = 1 WHERE _id = " + shared);

        CurrencyList list = new CurrencyList();
        WalletDbLoader.LoadCurrencyList(db, list, new IconList());

        Currency a = list.getCurrencyById((int) own);
        Currency b = list.getCurrencyById((int) shared);
        assertNotNull(a);
        assertNotNull(b);
        assertFalse(a.isShowInEquivalent());
        assertFalse(a.isShared());
        assertTrue(b.isShowInEquivalent());
        assertTrue(b.isShared());
    }

    // ── 4.3: флаг в Insert/UpdateCurrency ───────────────────────────────────────

    @Test
    public void insertAndUpdateCurrency_storeFlagAndMarkDirty() {
        long id = WalletDbCRUD.InsertCurrency(db, "Test coin", "TSC", "T", 2, (int) iconId, false);
        assertTrue(id > 0);
        assertEquals(0, intCol("currency", "show_in_equivalent", id));
        assertEquals(1, intCol("currency", "dirty", id));

        db.execSQL("UPDATE currency SET dirty = 0 WHERE _id = " + id);
        assertEquals(1, WalletDbCRUD.UpdateCurrency(db, (int) id, "Test coin", "TSC", "T", 2, (int) iconId, true));
        assertEquals(1, intCol("currency", "show_in_equivalent", id));
        assertEquals(1, intCol("currency", "dirty", id));
    }

    @Test
    public void updateCurrency_sharedRowUntouched() {
        long id = currency("Test shared", "TSD");
        db.execSQL("UPDATE currency SET is_shared = 1, dirty = 0 WHERE _id = " + id);

        assertEquals(0, WalletDbCRUD.UpdateCurrency(db, (int) id, "Renamed", "TSD", "T", 2, (int) iconId, false));
        assertEquals(1, intCol("currency", "show_in_equivalent", id));
        assertEquals(0, intCol("currency", "dirty", id));
    }

    // ── 4.5: код валюты из пары ─────────────────────────────────────────────────

    @Test
    public void isoChange_blockedOnlyForCurrencyInLivePair() {
        long xau = currency("Test gold", "TSX");
        currency("Test dollar", "TSU");
        long pair = WalletDbCRUD.InsertCurrencyPair(db, "TSX", "TSU", 2);
        assertTrue(pair > 0);

        assertTrue(WalletDbCRUD.IsIsoChangeBlocked(db, (int) xau, "GLD"));
        assertFalse(WalletDbCRUD.IsIsoChangeBlocked(db, (int) xau, "TSX"));
        assertFalse(WalletDbCRUD.IsIsoChangeBlocked(db, (int) xau, "tsx"));
        assertFalse("новая валюта", WalletDbCRUD.IsIsoChangeBlocked(db, -1, "GLD"));

        long free = currency("Test free", "TSF");
        assertFalse(WalletDbCRUD.IsIsoChangeBlocked(db, (int) free, "TSG"));

        WalletDbCRUD.DeleteCurrencyPair(db, (int) pair);
        assertFalse("пара удалена — код можно менять", WalletDbCRUD.IsIsoChangeBlocked(db, (int) xau, "GLD"));
    }

    // ── 3.1: удаление пары ──────────────────────────────────────────────────────

    @Test
    public void deleteCurrencyPair_softDeletesPairAndLiveRates() {
        currency("Test euro", "TSE");
        currency("Test dollar", "TSU");
        long pair = WalletDbCRUD.InsertCurrencyPair(db, "TSE", "TSU", 4);
        WalletDbCRUD.InsertOrReplaceRate(db, "TSETSU", "2026-10-01", 1.1);
        WalletDbCRUD.InsertOrReplaceRate(db, "TSETSU", "2026-10-02", 1.2);
        db.execSQL("UPDATE currency_pair SET dirty = 0 WHERE _id = " + pair);
        db.execSQL("UPDATE currency_pair_rate SET dirty = 0 WHERE currency_pair_id = " + pair);

        assertEquals(1, WalletDbCRUD.DeleteCurrencyPair(db, (int) pair));

        assertNotNull(longCol("currency_pair", "deleted_at", pair));
        assertEquals(1, intCol("currency_pair", "dirty", pair));
        Cursor c = db.rawQuery("SELECT deleted_at, dirty FROM currency_pair_rate WHERE currency_pair_id = ?",
                new String[]{String.valueOf(pair)});
        try {
            assertEquals(2, c.getCount());
            while (c.moveToNext()) {
                assertFalse(c.isNull(0));
                assertEquals(1, c.getInt(1));
            }
        } finally {
            c.close();
        }
        // Повторно — уже удалена.
        assertEquals(0, WalletDbCRUD.DeleteCurrencyPair(db, (int) pair));
    }

    @Test
    public void deleteCurrencyPair_sharedPairUntouched() {
        currency("Test euro", "TSE");
        currency("Test dollar", "TSU");
        long pair = WalletDbCRUD.InsertCurrencyPair(db, "TSE", "TSU", 4);
        WalletDbCRUD.InsertOrReplaceRate(db, "TSETSU", "2026-10-01", 1.1);
        db.execSQL("UPDATE currency_pair SET is_shared = 1, dirty = 0 WHERE _id = " + pair);
        db.execSQL("UPDATE currency_pair_rate SET dirty = 0 WHERE currency_pair_id = " + pair);

        assertEquals(0, WalletDbCRUD.DeleteCurrencyPair(db, (int) pair));

        assertNull(longCol("currency_pair", "deleted_at", pair));
        assertEquals(0, intCol("currency_pair", "dirty", pair));
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM currency_pair_rate WHERE currency_pair_id = ? "
                + "AND deleted_at IS NULL AND dirty = 0", new String[]{String.valueOf(pair)});
        try {
            c.moveToFirst();
            assertEquals(1, c.getInt(0));
        } finally {
            c.close();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private long currency(String name, String iso) {
        return insert(WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry.COLUMN_NAME, name,
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, iso,
                WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
                WalletContract.CurrencyEntry.COLUMN_SYMBOL, iso);
    }

    private int intCol(String table, String column, long id) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private Long longCol(String table, String column, long id) {
        Cursor c = db.rawQuery("SELECT " + column + " FROM " + table + " WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            assertTrue(c.moveToFirst());
            return c.isNull(0) ? null : c.getLong(0);
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
            else if (value instanceof byte[]) cv.put(key, (byte[]) value);
            else cv.put(key, (String) value);
        }
        return db.insertOrThrow(table, null, cv);
    }
}
