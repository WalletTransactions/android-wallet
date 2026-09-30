package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.DataItems.Operations.RecurringOperation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Генератор повторяющихся операций ({@link RecurringOperationsDb#generateDue}) на настоящей
 * схеме БД: заводит наступившие повторы ровно один раз, двигает {@code last_date},
 * пропускает выключенные правила и правила на мягко удалённый счёт.
 */
@RunWith(RobolectricTestRunner.class)
public class RecurringOperationsDbTest {

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
                WalletContract.AccountEntry.COLUMN_NAME, "Test rent",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId);
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private long insert(String table, Object... kv) {
        ContentValues cv = new ContentValues();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof byte[]) cv.put((String) kv[i], (byte[]) v);
            else if (v instanceof Number) cv.put((String) kv[i], ((Number) v).longValue());
            else cv.put((String) kv[i], (String) v);
        }
        return db.insertOrThrow(table, null, cv);
    }

    private RecurringOperation monthlyRent() {
        RecurringOperation r = new RecurringOperation();
        r.accFromId = walletId;
        r.accToId = categoryId;
        r.amount = 500;
        r.comment = "Аренда";
        r.frequency = RecurringOperation.Frequency.MONTHLY;
        r.startDate = LocalDateTime.of(2026, 1, 5, 10, 0);
        return r;
    }

    private int countOperations() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM operation WHERE acc_fr_id = ? AND acc_to_id = ?",
                new String[]{String.valueOf(walletId), String.valueOf(categoryId)});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    @Test
    public void createsDueOccurrencesOnceAndAdvancesLastDate() {
        RecurringOperation r = monthlyRent();
        RecurringOperationsDb.insert(db, r);

        LocalDateTime now = LocalDateTime.of(2026, 3, 20, 12, 0);
        assertEquals(3, RecurringOperationsDb.generateDue(db, now));
        assertEquals(0, RecurringOperationsDb.generateDue(db, now));
        assertEquals(3, countOperations());

        RecurringOperation stored = RecurringOperationsDb.load(db, r.id);
        assertEquals(LocalDateTime.of(2026, 3, 5, 10, 0), stored.lastDate);

        assertEquals(1, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 4, 5, 10, 0)));
        assertEquals(4, countOperations());
    }

    @Test
    public void respectsEndDateAndInactiveRules() {
        RecurringOperation r = monthlyRent();
        r.endDate = LocalDate.of(2026, 2, 10);
        RecurringOperationsDb.insert(db, r);

        RecurringOperation off = monthlyRent();
        off.isActive = false;
        RecurringOperationsDb.insert(db, off);

        assertEquals(2, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 12, 1, 0, 0)));
    }

    @Test
    public void stopsAfterEndDateEvenWhenRuleStaysActive() {
        RecurringOperation r = monthlyRent();
        r.frequency = RecurringOperation.Frequency.DAILY;
        r.startDate = LocalDateTime.of(2026, 9, 1, 10, 0);
        r.endDate = LocalDate.of(2026, 9, 3);
        RecurringOperationsDb.insert(db, r);

        // Приложение открывают каждый день: и до, и после «даты по».
        for (int day = 1; day <= 10; day++) {
            RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 9, day, 12, 0));
        }
        assertEquals(3, countOperations());

        RecurringOperation stored = RecurringOperationsDb.load(db, r.id);
        assertEquals(LocalDateTime.of(2026, 9, 3, 10, 0), stored.lastDate);
        assertEquals(false, stored.isRunning(LocalDateTime.of(2026, 9, 10, 12, 0)));
    }

    @Test
    public void endedRuleStillCatchesUpOccurrencesBeforeEndDate() {
        RecurringOperation r = monthlyRent();
        r.endDate = LocalDate.of(2026, 3, 5);
        RecurringOperationsDb.insert(db, r);

        // Приложение не открывали с января — повторы до «даты по» досоздаются, после — нет.
        assertEquals(3, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 9, 30, 12, 0)));
        assertEquals(0, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 10, 30, 12, 0)));
    }

    @Test
    public void skipsRuleOnSoftDeletedAccount() {
        RecurringOperationsDb.insert(db, monthlyRent());
        db.execSQL("UPDATE account SET deleted_at = 1 WHERE _id = " + categoryId);

        assertEquals(0, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 3, 20, 12, 0)));
    }

    @Test
    public void hardDeletedAccountCascadesToRule() {
        RecurringOperationsDb.insert(db, monthlyRent());
        db.execSQL("DELETE FROM account WHERE _id = " + categoryId);

        assertEquals(0, RecurringOperationsDb.loadAll(db).size());
    }
}
