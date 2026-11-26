package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * openspec wallets-filter-and-debt-autoarchive, спека debts «Авто-архив погашенного займа»
 * (design D2, D3): архив ставится при переходе остатка «≠ 0 → 0» между {@code begin} и
 * {@code finish}, а не при каждом нуле.
 */
@RunWith(RobolectricTestRunner::class)
class DebtAutoArchiveTest {

    private lateinit var context: Context
    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId: Long = 0
    private var usd = 0
    private var cash = 0
    private var vasya = 0
    private var loan = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        for (t in arrayOf(
            "operation_link", "operation", "recurring_operation", "account_asset",
            "account_debt", "counterparty", "account_operating", "account", "currency_conversion",
            "currency_pair_rate", "currency_pair", "currency", "icon"
        )) {
            db.execSQL("DELETE FROM " + t)
        }
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        insert("icon", "name", "unknown", "image", byteArrayOf(0), "icon_type", 1)
        usd = insert("currency", "name", "Доллар", "iso_code", "USD", "icon_id", iconId, "symbol", "$").toInt()
        cash = WalletDbCRUD.InsertOperatingAccount(db, "Наличные", "", iconId.toInt(), usd).toInt()
        vasya = WalletDbCRUD.CreateCounterparty(db, "Вася", "")
        loan = createLoan()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun createLoan(): Int {
        val id = WalletDbCRUD.CreateDebtWallet(db, vasya, usd, "", null)
        assertTrue(id > 0)
        return id
    }

    /** «Дать в долг»: кошелёк → заём, остаток займа +amount. */
    private fun give(amount: Double): Long {
        return WalletDbCRUD.InsertOperation(db, DAY, cash, loan, amount, "")
    }

    private fun giveBack(amount: Double): Long {
        return WalletDbCRUD.InsertOperation(db, DAY, loan, cash, amount, "")
    }

    @Test
    fun fullReturn_archivesLoan() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        giveBack(100.0)
        assertEquals(1, before.finish(db))
        assertTrue(isArchived(loan))
    }

    @Test
    fun forgive_archivesLoan() {
        give(100.0)
        val category = WalletDbCRUD.GetOrCreateDebtWriteOffCategory(db, "Списание долгов")
        // ForgiveDebt сам оборачивает запись в begin/finish — снаружи ничего не нужно.
        assertEquals(
            WalletDbCRUD.WriteOffResult.written,
            WalletDbCRUD.ForgiveDebt(db, loan, category, "2026-11-01", 100.0, 2, "")
        )
        assertTrue(isArchived(loan))
    }

    @Test
    fun partialReturn_doesNotArchive() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        giveBack(40.0)
        assertEquals(0, before.finish(db))
        assertFalse(isArchived(loan))
    }

    @Test
    fun overpayment_doesNotArchive() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        giveBack(150.0) // остаток −50: я должен
        before.finish(db)
        assertFalse(isArchived(loan))
    }

    @Test
    fun deletingReturnOperation_keepsArchive() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        val ret = giveBack(100.0)
        before.finish(db)
        assertTrue(isArchived(loan))

        // Правка возвращает остаток +100 — авто-разархивирования нет.
        val afterArchive = DebtAutoArchive.begin(db)
        WalletDbCRUD.DeleteOperation(db, ret.toInt())
        afterArchive.finish(db)
        assertTrue("остаётся в архиве", isArchived(loan))
    }

    @Test
    fun intermediateZeroInsideScenario_doesNotArchive() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        giveBack(100.0) // промежуточный ноль...
        giveBack(30.0) // ...но сценарий закончился остатком −30
        before.finish(db)
        assertFalse(isArchived(loan))
    }

    @Test
    fun zeroFromTheStart_doesNotArchive() {
        // Только что заведённый заём: остатка не было, и записанная «пустая» операция его не меняет.
        val before = DebtAutoArchive.begin(db)
        assertTrue(before.isEmpty())
        assertEquals(0, before.finish(db))
        assertFalse(isArchived(loan))
    }

    @Test
    fun manualUnarchive_isNotOverridden_untilBalanceGoesThroughNonZero() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        giveBack(100.0)
        before.finish(db)
        assertTrue(isArchived(loan))

        setArchived(loan, false) // пользователь снял архив вручную, остаток 0
        val idle = DebtAutoArchive.begin(db)
        assertEquals("ноль — не «открытый» заём", 0, idle.finish(db))
        assertFalse(isArchived(loan))

        // Снова выдача и полный возврат — архивируется как в первый раз.
        give(50.0)
        val again = DebtAutoArchive.begin(db)
        giveBack(50.0)
        again.finish(db)
        assertTrue(isArchived(loan))
    }

    @Test
    fun ordinaryWalletAtZero_isNotTouched() {
        // Обычный (недолговой) кошелёк в архив не уходит, сколько бы раз он ни проходил через ноль.
        WalletDbCRUD.InsertOperation(db, DAY, cash, loan, 10.0, "")
        val before = DebtAutoArchive.begin(db)
        WalletDbCRUD.InsertOperation(db, DAY, loan, cash, 10.0, "")
        before.finish(db)
        assertFalse(isArchived(cash))
    }

    @Test
    fun archiveRaisesSyncDirtyFlag() {
        give(100.0)
        db.execSQL("UPDATE account SET dirty = 0")
        val before = DebtAutoArchive.begin(db)
        giveBack(100.0)
        before.finish(db)
        assertEquals(
            "правка счёта уходит на сервер обычным синком", 1, intQuery(
                "SELECT dirty FROM account WHERE _id = " + loan
            )
        )
    }

    @Test
    fun recurringRepayment_archivesLoanWhenItClosesIt() {
        give(100.0)
        val r = com.example.wallettransactions.DataItems.Operations.RecurringOperation()
        r.accFromId = loan
        r.accToId = cash
        r.amount = 100.0
        r.comment = ""
        r.frequency = com.example.wallettransactions.DataItems.Operations.RecurringOperation.Frequency.MONTHLY
        r.startDate = java.time.LocalDateTime.of(2026, 11, 5, 10, 0)
        RecurringOperationsDb.insert(db, r)

        assertEquals(1, RecurringOperationsDb.generateDue(db, java.time.LocalDateTime.of(2026, 11, 20, 12, 0)))
        assertTrue(isArchived(loan))
    }

    @Test
    fun newGiveToSamePerson_createsNewWalletWithSuffix_archivedNotReused() {
        give(100.0)
        val before = DebtAutoArchive.begin(db)
        giveBack(100.0)
        before.finish(db)
        assertTrue(isArchived(loan))

        val second = createLoan()
        assertTrue(second != loan)
        assertEquals("Вася · USD", name(loan))
        assertEquals("Вася · USD · 2", name(second))
        assertTrue("архивный заём не тронут", isArchived(loan))
        assertFalse(isArchived(second))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun isArchived(id: Int): Boolean {
        return intQuery("SELECT is_archived FROM account WHERE _id = " + id) == 1
    }

    private fun setArchived(id: Int, archived: Boolean) {
        db.execSQL("UPDATE account SET is_archived = " + (if (archived) 1 else 0) + " WHERE _id = " + id)
    }

    private fun name(id: Int): String {
        val c = db.rawQuery("SELECT name FROM account WHERE _id = " + id, null)
        try {
            assertTrue(c.moveToFirst())
            return c.getString(0)
        } finally {
            c.close()
        }
    }

    private fun intQuery(sql: String): Int {
        val c = db.rawQuery(sql, null)
        try {
            assertTrue(sql, c.moveToFirst())
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val key = kv[i] as String
            val value = kv[i + 1]
            when (value) {
                is Int -> cv.put(key, value)
                is Long -> cv.put(key, value)
                is ByteArray -> cv.put(key, value)
                else -> cv.put(key, value as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }

    companion object {
        private const val DAY = "2026-11-01 12:00"
    }
}
