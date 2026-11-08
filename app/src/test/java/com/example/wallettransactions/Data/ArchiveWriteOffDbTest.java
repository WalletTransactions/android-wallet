package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.Activities.ArchiveWriteOffDialog;
import com.example.wallettransactions.AppSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * openspec archive-wallet-writeoff: итоговый остаток, проводка списания (направление,
 * округление, дата, комментарий), откат вместе с транзакцией сохранения и условие показа листа.
 */
@RunWith(RobolectricTestRunner.class)
public class ArchiveWriteOffDbTest {

    private static final String COMMENT = "Wallet archived";

    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;
    private int rub;
    private int cash;
    private int other;
    private int salary;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        AppSettings.setLedgerRereadPending(context, false);
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        db.execSQL("DELETE FROM operation_link");
        db.execSQL("DELETE FROM operation");
        db.execSQL("DELETE FROM recurring_operation");
        db.execSQL("DELETE FROM account_asset");
        db.execSQL("DELETE FROM account_operating");
        db.execSQL("DELETE FROM account");
        db.execSQL("DELETE FROM currency_conversion");
        db.execSQL("DELETE FROM currency_pair_rate");
        db.execSQL("DELETE FROM currency_pair");
        db.execSQL("DELETE FROM currency");
        db.execSQL("DELETE FROM icon");
        iconId = insert("icon", "name", "test_icon", "image", new byte[]{0}, "icon_type", 1);
        rub = (int) insert("currency", "name", "Ruble", "iso_code", "RUB", "icon_id", iconId, "symbol", "R");
        cash = (int) WalletDbCRUD.InsertOperatingAccount(db, "Cash", "", (int) iconId, rub);
        other = category("Other");
        salary = category("Salary");
    }

    @After
    public void tearDown() {
        helper.close();
    }

    // ── 1.1 остаток и проводка ─────────────────────────────────────────────

    @Test
    public void positiveBalance_expenseWalletToCategory_atEndOfDay_balanceZero() {
        op("2026-11-01 10:00", salary, cash, 100);
        op("2026-11-02 12:00", cash, other, 62.5);

        assertEquals(WalletDbCRUD.WriteOffResult.written,
                WalletDbCRUD.WriteOffBalance(db, cash, other, "2026-11-07", 2, COMMENT));

        Cursor c = db.rawQuery("SELECT acc_fr_id, acc_to_id, amount, date, comment, uuid FROM operation"
                + " WHERE comment = ?", new String[]{COMMENT});
        try {
            assertTrue(c.moveToFirst());
            assertEquals(cash, c.getInt(0));
            assertEquals(other, c.getInt(1));
            assertEquals(37.5, c.getDouble(2), 1e-9);
            assertEquals("2026-11-07 23:59", c.getString(3));
            assertFalse("uuid для синка", c.isNull(5));
            assertFalse(c.moveToNext());
        } finally {
            c.close();
        }
        assertEquals(0, WalletDbLoader.LoadAccountBalance(db, cash), 1e-9);
    }

    @Test
    public void negativeBalance_incomeCategoryToWallet() {
        op("2026-11-01 10:00", cash, other, 12);

        assertEquals(WalletDbCRUD.WriteOffResult.written,
                WalletDbCRUD.WriteOffBalance(db, cash, other, "2026-11-07", 2, COMMENT));

        assertEquals(1, intQuery("SELECT count(*) FROM operation WHERE acc_fr_id = " + other
                + " AND acc_to_id = " + cash + " AND amount = 12 AND comment = '" + COMMENT + "'"));
        assertEquals(0, WalletDbLoader.LoadAccountBalance(db, cash), 1e-9);
    }

    @Test
    public void zeroAfterRounding_noOperation() {
        // 0.1 + 0.2 − 0.3 в double — не ровно 0.
        op("2026-11-01 10:00", salary, cash, 0.1);
        op("2026-11-01 11:00", salary, cash, 0.2);
        op("2026-11-01 12:00", cash, other, 0.3);
        assertTrue(WalletDbLoader.LoadAccountBalance(db, cash) != 0);
        int before = intQuery("SELECT count(*) FROM operation");

        assertEquals(0, WalletDbCRUD.RoundedWalletBalance(db, cash, 2), 0);
        assertEquals(WalletDbCRUD.WriteOffResult.zero,
                WalletDbCRUD.WriteOffBalance(db, cash, other, "2026-11-07", 2, COMMENT));
        assertEquals(before, intQuery("SELECT count(*) FROM operation"));
    }

    @Test
    public void roundsToCurrencyPrecision() {
        op("2026-11-01 10:00", salary, cash, 10.006);
        assertEquals(10.01, WalletDbCRUD.RoundedWalletBalance(db, cash, 2), 1e-9);
        assertEquals(10, WalletDbCRUD.RoundedWalletBalance(db, cash, 0), 1e-9);
    }

    @Test
    public void balanceIsTotal_includesOperationsAfterChosenDay_skipsDeleted() {
        op("2026-11-01 10:00", salary, cash, 100);
        op("2026-11-20 10:00", salary, cash, 50);
        long deleted = op("2026-11-03 10:00", salary, cash, 1000);
        db.execSQL("UPDATE operation SET deleted_at = '2026-11-04 00:00' WHERE _id = " + deleted);

        WalletDbCRUD.WriteOffBalance(db, cash, other, "2026-11-07", 2, COMMENT);

        assertEquals(150, doubleQuery("SELECT amount FROM operation WHERE comment = '" + COMMENT + "'"), 1e-9);
        assertEquals(0, WalletDbLoader.LoadAccountBalance(db, cash), 1e-9);
    }

    @Test
    public void noCategory_failed_noOperation() {
        op("2026-11-01 10:00", salary, cash, 100);
        assertEquals(WalletDbCRUD.WriteOffResult.failed,
                WalletDbCRUD.WriteOffBalance(db, cash, 0, "2026-11-07", 2, COMMENT));
        assertEquals(0, intQuery("SELECT count(*) FROM operation WHERE comment = '" + COMMENT + "'"));
    }

    @Test
    public void outerTransactionNotSuccessful_rollsBackWriteOffAndArchive() {
        op("2026-11-01 10:00", salary, cash, 100);

        db.beginTransaction();
        try {
            WalletDbCRUD.UpdateAccount(db, cash, "Cash", "", (int) iconId, true);
            WalletDbCRUD.WriteOffBalance(db, cash, other, "2026-11-07", 2, COMMENT);
        } finally {
            db.endTransaction();
        }

        assertEquals(0, intQuery("SELECT count(*) FROM operation WHERE comment = '" + COMMENT + "'"));
        assertEquals(0, intQuery("SELECT is_archived FROM account WHERE _id = " + cash));
    }

    @Test
    public void outerTransactionSuccessful_commitsBoth() {
        op("2026-11-01 10:00", salary, cash, 100);

        db.beginTransaction();
        try {
            WalletDbCRUD.UpdateAccount(db, cash, "Cash", "", (int) iconId, true);
            WalletDbCRUD.WriteOffBalance(db, cash, other, "2026-11-07", 2, COMMENT);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        assertEquals(1, intQuery("SELECT count(*) FROM operation WHERE comment = '" + COMMENT + "'"));
        assertEquals(1, intQuery("SELECT is_archived FROM account WHERE _id = " + cash));
    }

    // ── 2.1 когда показывать лист ──────────────────────────────────────────

    @Test
    public void isOffered_onlyForPlainWalletBeingArchivedWithBalance() {
        assertTrue(ArchiveWriteOffDialog.isOffered(true, false, true, false, false, 37.5));
        assertTrue("минус тоже списывается", ArchiveWriteOffDialog.isOffered(true, false, true, false, false, -12));

        assertFalse("нулевой остаток", ArchiveWriteOffDialog.isOffered(true, false, true, false, false, 0));
        assertFalse("категория", ArchiveWriteOffDialog.isOffered(false, false, true, false, false, 37.5));
        assertFalse("уже был в архиве", ArchiveWriteOffDialog.isOffered(true, true, true, false, false, 37.5));
        assertFalse("архив не ставится", ArchiveWriteOffDialog.isOffered(true, false, false, false, false, 37.5));
        assertFalse("актив", ArchiveWriteOffDialog.isOffered(true, false, true, true, true, 37.5));
        assertFalse("отмечен активом сейчас", ArchiveWriteOffDialog.isOffered(true, false, true, false, true, 37.5));
        assertFalse("актив снят сейчас", ArchiveWriteOffDialog.isOffered(true, false, true, true, false, 37.5));
    }

    // ── помощники ──────────────────────────────────────────────────────────

    private int category(String name) {
        return (int) insert("account", "name", name, "comment", "", "icon_id", iconId,
                "system_kind", 0, "kind", 0, "uuid", java.util.UUID.randomUUID().toString());
    }

    private long op(String date, int from, int to, double amount) {
        return WalletDbCRUD.InsertOperation(db, date, from, to, amount, "");
    }

    private int intQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private double doubleQuery(String sql) {
        Cursor c = db.rawQuery(sql, null);
        try {
            assertTrue(sql, c.moveToFirst());
            return c.getDouble(0);
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
