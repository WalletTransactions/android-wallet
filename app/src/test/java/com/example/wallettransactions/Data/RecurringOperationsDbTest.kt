package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.DataItems.Operations.RecurringOperation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Генератор повторяющихся операций ({@link RecurringOperationsDb#generateDue}) на настоящей
 * схеме БД: заводит наступившие повторы ровно один раз, двигает {@code last_date},
 * пропускает выключенные правила и правила на мягко удалённый счёт.
 */
@RunWith(RobolectricTestRunner::class)
class RecurringOperationsDbTest {

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var walletId: Int = 0
    private var categoryId: Int = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        helper = WalletDbHelper(context)
        db = helper.writableDatabase

        val iconId = insert(WalletContract.IconEntry.TABLE_NAME,
                WalletContract.IconEntry.COLUMN_NAME, "test_icon",
                WalletContract.IconEntry.COLUMN_IMAGE, byteArrayOf(0),
                WalletContract.IconEntry.COLUMN_ICON_TYPE, 1)
        val currencyId = insert(WalletContract.CurrencyEntry.TABLE_NAME,
                WalletContract.CurrencyEntry.COLUMN_NAME, "Test Euro",
                WalletContract.CurrencyEntry.COLUMN_ISO_NAME, "TST",
                WalletContract.CurrencyEntry.COLUMN_ICON_ID, iconId,
                WalletContract.CurrencyEntry.COLUMN_SYMBOL, "T")
        walletId = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test wallet",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId,
                WalletContract.AccountEntry.COLUMN_KIND, 1).toInt()
        insert(WalletContract.AccountOperatingEntry.TABLE_NAME,
                WalletContract.AccountOperatingEntry._ID, walletId,
                WalletContract.AccountOperatingEntry.COLUMN_CURRENCY_ID, currencyId)
        categoryId = insert(WalletContract.AccountEntry.TABLE_NAME,
                WalletContract.AccountEntry.COLUMN_NAME, "Test rent",
                WalletContract.AccountEntry.COLUMN_COMMENT, "",
                WalletContract.AccountEntry.COLUMN_ICON_ID, iconId).toInt()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun insert(table: String, vararg kv: Any): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            val v = kv[i + 1]
            if (v is ByteArray) cv.put(kv[i] as String, v)
            else if (v is Number) cv.put(kv[i] as String, v.toLong())
            else cv.put(kv[i] as String, v as String)
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }

    private fun monthlyRent(): RecurringOperation {
        val r = RecurringOperation()
        r.accFromId = walletId
        r.accToId = categoryId
        r.amount = 500.0
        r.comment = "Аренда"
        r.frequency = RecurringOperation.Frequency.MONTHLY
        r.startDate = LocalDateTime.of(2026, 1, 5, 10, 0)
        return r
    }

    private fun countOperations(): Int {
        val c = db.rawQuery("SELECT COUNT(*) FROM operation WHERE acc_fr_id = ? AND acc_to_id = ?",
                arrayOf(walletId.toString(), categoryId.toString()))
        try {
            c.moveToFirst()
            return c.getInt(0)
        } finally {
            c.close()
        }
    }

    @Test
    fun createsDueOccurrencesOnceAndAdvancesLastDate() {
        val r = monthlyRent()
        RecurringOperationsDb.insert(db, r)

        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        assertEquals(3, RecurringOperationsDb.generateDue(db, now))
        assertEquals(0, RecurringOperationsDb.generateDue(db, now))
        assertEquals(3, countOperations())

        val stored = RecurringOperationsDb.load(db, r.id)!!
        assertEquals(LocalDateTime.of(2026, 3, 5, 10, 0), stored.lastDate)

        assertEquals(1, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 4, 5, 10, 0)))
        assertEquals(4, countOperations())
    }

    @Test
    fun respectsEndDateAndInactiveRules() {
        val r = monthlyRent()
        r.endDate = LocalDate.of(2026, 2, 10)
        RecurringOperationsDb.insert(db, r)

        val off = monthlyRent()
        off.isActive = false
        RecurringOperationsDb.insert(db, off)

        assertEquals(2, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 12, 1, 0, 0)))
    }

    @Test
    fun stopsAfterEndDateEvenWhenRuleStaysActive() {
        val r = monthlyRent()
        r.frequency = RecurringOperation.Frequency.DAILY
        r.startDate = LocalDateTime.of(2026, 9, 1, 10, 0)
        r.endDate = LocalDate.of(2026, 9, 3)
        RecurringOperationsDb.insert(db, r)

        // Приложение открывают каждый день: и до, и после «даты по».
        for (day in 1..10) {
            RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 9, day, 12, 0))
        }
        assertEquals(3, countOperations())

        val stored = RecurringOperationsDb.load(db, r.id)!!
        assertEquals(LocalDateTime.of(2026, 9, 3, 10, 0), stored.lastDate)
        assertEquals(false, stored.isRunning(LocalDateTime.of(2026, 9, 10, 12, 0)))
    }

    @Test
    fun endedRuleStillCatchesUpOccurrencesBeforeEndDate() {
        val r = monthlyRent()
        r.endDate = LocalDate.of(2026, 3, 5)
        RecurringOperationsDb.insert(db, r)

        // Приложение не открывали с января — повторы до «даты по» досоздаются, после — нет.
        assertEquals(3, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 9, 30, 12, 0)))
        assertEquals(0, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 10, 30, 12, 0)))
    }

    @Test
    fun skipsRuleOnSoftDeletedAccount() {
        RecurringOperationsDb.insert(db, monthlyRent())
        db.execSQL("UPDATE account SET deleted_at = 1 WHERE _id = $categoryId")

        assertEquals(0, RecurringOperationsDb.generateDue(db, LocalDateTime.of(2026, 3, 20, 12, 0)))
    }

    @Test
    fun hardDeletedAccountCascadesToRule() {
        RecurringOperationsDb.insert(db, monthlyRent())
        db.execSQL("DELETE FROM account WHERE _id = $categoryId")

        assertEquals(0, RecurringOperationsDb.loadAll(db).size)
    }
}
