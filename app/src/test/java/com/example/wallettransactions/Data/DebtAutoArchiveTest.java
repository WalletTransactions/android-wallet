package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.example.wallettransactions.AppSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * openspec wallets-filter-and-debt-autoarchive, спека debts «Авто-архив погашенного займа»
 * (design D2, D3): архив ставится при переходе остатка «≠ 0 → 0» между {@code begin} и
 * {@code finish}, а не при каждом нуле.
 */
@RunWith(RobolectricTestRunner.class)
public class DebtAutoArchiveTest {

    private static final String DAY = "2026-11-01 12:00";

    private Context context;
    private WalletDbHelper helper;
    private SQLiteDatabase db;
    private long iconId;
    private int usd;
    private int cash;
    private int vasya;
    private int loan;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("wallet.db");
        AppSettings.setLedgerRereadPending(context, false);
        helper = new WalletDbHelper(context);
        db = helper.getWritableDatabase();
        for (String t : new String[]{"operation_link", "operation", "recurring_operation", "account_asset",
                "account_debt", "counterparty", "account_operating", "account", "currency_conversion",
                "currency_pair_rate", "currency_pair", "currency", "icon"}) {
            db.execSQL("DELETE FROM " + t);
        }
        iconId = insert("icon", "name", "test_icon", "image", new byte[]{0}, "icon_type", 1);
        insert("icon", "name", "unknown", "image", new byte[]{0}, "icon_type", 1);
        usd = (int) insert("currency", "name", "Доллар", "iso_code", "USD", "icon_id", iconId, "symbol", "$");
        cash = (int) WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", (int) iconId, usd);
        vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "");
        loan = createLoan();
    }

    @After
    public void tearDown() {
        helper.close();
    }

    private int createLoan() {
        int id = WalletDbCRUD.CreateDebtWallet(db, vasya, usd, "", null);
        assertTrue(id > 0);
        return id;
    }

    /** «Дать в долг»: кошелёк → заём, остаток займа +amount. */
    private long give(double amount) {
        return WalletDbCRUD.InsertOperation(db, DAY, cash, loan, amount, "");
    }

    private long giveBack(double amount) {
        return WalletDbCRUD.InsertOperation(db, DAY, loan, cash, amount, "");
    }

    @Test
    public void fullReturn_archivesLoan() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(100);
        assertEquals(1, before.finish(db));
        assertTrue(isArchived(loan));
    }

    @Test
    public void forgive_archivesLoan() {
        give(100);
        int category = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов");
        // ForgiveDebt сам оборачивает запись в begin/finish — снаружи ничего не нужно.
        assertEquals(WalletDbCRUD.WriteOffResult.written,
                WalletDbCRUD.ForgiveDebt(db, loan, category, "2026-11-01", 100, 2, ""));
        assertTrue(isArchived(loan));
    }

    @Test
    public void partialReturn_doesNotArchive() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(40);
        assertEquals(0, before.finish(db));
        assertFalse(isArchived(loan));
    }

    @Test
    public void overpayment_doesNotArchive() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(150); // остаток −50: я должен
        before.finish(db);
        assertFalse(isArchived(loan));
    }

    @Test
    public void deletingReturnOperation_keepsArchive() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        long ret = giveBack(100);
        before.finish(db);
        assertTrue(isArchived(loan));

        // Правка возвращает остаток +100 — авто-разархивирования нет.
        DebtAutoArchive.Snapshot afterArchive = DebtAutoArchive.begin(db);
        WalletDbCRUD.DeleteOperation(db, (int) ret);
        afterArchive.finish(db);
        assertTrue("остаётся в архиве", isArchived(loan));
    }

    @Test
    public void intermediateZeroInsideScenario_doesNotArchive() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(100); // промежуточный ноль...
        giveBack(30);  // ...но сценарий закончился остатком −30
        before.finish(db);
        assertFalse(isArchived(loan));
    }

    @Test
    public void zeroFromTheStart_doesNotArchive() {
        // Только что заведённый заём: остатка не было, и записанная «пустая» операция его не меняет.
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        assertTrue(before.isEmpty());
        assertEquals(0, before.finish(db));
        assertFalse(isArchived(loan));
    }

    @Test
    public void manualUnarchive_isNotOverridden_untilBalanceGoesThroughNonZero() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(100);
        before.finish(db);
        assertTrue(isArchived(loan));

        setArchived(loan, false); // пользователь снял архив вручную, остаток 0
        DebtAutoArchive.Snapshot idle = DebtAutoArchive.begin(db);
        assertEquals("ноль — не «открытый» заём", 0, idle.finish(db));
        assertFalse(isArchived(loan));

        // Снова выдача и полный возврат — архивируется как в первый раз.
        give(50);
        DebtAutoArchive.Snapshot again = DebtAutoArchive.begin(db);
        giveBack(50);
        again.finish(db);
        assertTrue(isArchived(loan));
    }

    @Test
    public void ordinaryWalletAtZero_isNotTouched() {
        // Обычный (недолговой) кошелёк в архив не уходит, сколько бы раз он ни проходил через ноль.
        WalletDbCRUD.InsertOperation(db, DAY, cash, loan, 10, "");
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        WalletDbCRUD.InsertOperation(db, DAY, loan, cash, 10, "");
        before.finish(db);
        assertFalse(isArchived(cash));
    }

    @Test
    public void archiveRaisesSyncDirtyFlag() {
        give(100);
        db.execSQL("UPDATE account SET dirty = 0");
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(100);
        before.finish(db);
        assertEquals("правка счёта уходит на сервер обычным синком", 1, intQuery(
                "SELECT dirty FROM account WHERE _id = " + loan));
    }

    @Test
    public void recurringRepayment_archivesLoanWhenItClosesIt() {
        give(100);
        com.example.wallettransactions.DataItems.Operations.RecurringOperation r =
                new com.example.wallettransactions.DataItems.Operations.RecurringOperation();
        r.accFromId = loan;
        r.accToId = cash;
        r.amount = 100;
        r.comment = "";
        r.frequency = com.example.wallettransactions.DataItems.Operations.RecurringOperation.Frequency.MONTHLY;
        r.startDate = java.time.LocalDateTime.of(2026, 11, 5, 10, 0);
        RecurringOperationsDb.insert(db, r);

        assertEquals(1, RecurringOperationsDb.generateDue(db, java.time.LocalDateTime.of(2026, 11, 20, 12, 0)));
        assertTrue(isArchived(loan));
    }

    @Test
    public void newGiveToSamePerson_createsNewWalletWithSuffix_archivedNotReused() {
        give(100);
        DebtAutoArchive.Snapshot before = DebtAutoArchive.begin(db);
        giveBack(100);
        before.finish(db);
        assertTrue(isArchived(loan));

        int second = createLoan();
        assertTrue(second != loan);
        assertEquals("Вася · USD", name(loan));
        assertEquals("Вася · USD · 2", name(second));
        assertTrue("архивный заём не тронут", isArchived(loan));
        assertFalse(isArchived(second));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private boolean isArchived(int id) {
        return intQuery("SELECT is_archived FROM account WHERE _id = " + id) == 1;
    }

    private void setArchived(int id, boolean archived) {
        db.execSQL("UPDATE account SET is_archived = " + (archived ? 1 : 0) + " WHERE _id = " + id);
    }

    private String name(int id) {
        Cursor c = db.rawQuery("SELECT name FROM account WHERE _id = " + id, null);
        try {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        } finally {
            c.close();
        }
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
