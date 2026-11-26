package com.example.wallettransactions.Data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.AppSettings
import com.example.wallettransactions.DataItems.Operations.TemplateSchedule
import com.example.wallettransactions.DataItems.Operations.TemplateStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Миграция v94 (openspec operation-templates, «Перенос правил повторяющихся операций»):
 * каждое правило `recurring_operation` становится шаблоном с расписанием и одним шагом
 * «остаток» без потери полей; генератор после переноса ничего не создаёт заново.
 */
@RunWith(RobolectricTestRunner::class)
class OperationTemplatesMigrationTest {

    private lateinit var helper: WalletDbHelper
    private lateinit var db: SQLiteDatabase
    private var iconId = 0L
    private var card = 0
    private var cash = 0
    private var phone = 0
    private var salary = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("wallet.db")
        AppSettings.setLedgerRereadPending(context, false)
        helper = WalletDbHelper(context)
        db = helper.writableDatabase
        // Сид не нужен: свои счета и валюта ниже (имена счетов уникальны).
        for (t in arrayOf("operation_link", "operation", "operation_template", "account_asset", "account_debt",
                "account_operating", "account", "currency_conversion", "currency_pair_rate", "currency_pair",
                "currency", "icon")) {
            db.execSQL("DELETE FROM $t")
        }
        iconId = insert("icon", "name", "test_icon", "image", byteArrayOf(0), "icon_type", 1)
        val rub = insert("currency", "name", "Тест-рубль", "iso_code", "TSR", "icon_id", iconId,
                "symbol", "₽", "precision", 2)
        card = wallet("Тест-карта", rub)
        cash = wallet("Тест-наличные", rub)
        phone = category("Связь")
        salary = category("Тест-зарплата")

        // Схема v93: таблица правил, как её создавал шаг v79.
        db.execSQL("CREATE TABLE recurring_operation (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "acc_fr_id INTEGER NOT NULL REFERENCES account (_id) ON DELETE CASCADE, " +
                "acc_to_id INTEGER NOT NULL REFERENCES account (_id) ON DELETE CASCADE, " +
                "amount DECIMAL(15,2) NOT NULL, comment TEXT NOT NULL DEFAULT '', " +
                "frequency INTEGER NOT NULL, interval INTEGER NOT NULL DEFAULT 1, " +
                "week_days INTEGER NOT NULL DEFAULT 0, start_date TEXT NOT NULL, end_date TEXT, " +
                "is_active INTEGER NOT NULL DEFAULT 1, last_date TEXT)")
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun insert(table: String, vararg kv: Any?): Long {
        val cv = ContentValues()
        var i = 0
        while (i < kv.size) {
            when (val v = kv[i + 1]) {
                null -> cv.putNull(kv[i] as String)
                is ByteArray -> cv.put(kv[i] as String, v)
                is Double -> cv.put(kv[i] as String, v)
                is Number -> cv.put(kv[i] as String, v.toLong())
                else -> cv.put(kv[i] as String, v as String)
            }
            i += 2
        }
        return db.insertOrThrow(table, null, cv)
    }

    private fun wallet(name: String, currency: Long): Int {
        val id = insert("account", "name", name, "comment", "", "icon_id", iconId, "kind", 1).toInt()
        insert("account_operating", "_id", id, "currency_id", currency)
        return id
    }

    private fun category(name: String): Int =
        insert("account", "name", name, "comment", "", "icon_id", iconId).toInt()

    private fun rule(from: Int, to: Int, amount: Double, comment: String, frequency: Int, start: String,
                     end: String? = null, active: Int = 1, last: String? = null,
                     interval: Int = 1, weekDays: Int = 0): Long =
        insert("recurring_operation", "acc_fr_id", from, "acc_to_id", to, "amount", amount,
                "comment", comment, "frequency", frequency, "interval", interval, "week_days", weekDays,
                "start_date", start, "end_date", end, "is_active", active, "last_date", last)

    private fun upgrade() = helper.onUpgrade(db, 93, 94)

    private fun tableExists(name: String): Boolean = WalletDbHelper.tableExists(db, name)

    @Test
    fun freshInstall_hasTemplateTablesAndNoRules() {
        val fresh = ApplicationProvider.getApplicationContext<Context>()
        helper.close()
        fresh.deleteDatabase("wallet.db")
        helper = WalletDbHelper(fresh)
        db = helper.writableDatabase
        assertTrue(tableExists("operation_template"))
        assertTrue(tableExists("operation_template_step"))
        assertFalse(tableExists("recurring_operation"))
    }

    @Test
    fun noRules_noTemplates() {
        upgrade()
        assertFalse(tableExists("recurring_operation"))
        assertEquals(0, OperationTemplatesDb.loadAll(db).size)
    }

    @Test
    fun monthlyExpense_becomesScheduledTemplateNamedAfterCategory() {
        rule(card, phone, 500.0, "", 2, "2026-01-15 10:00", last = "2026-10-15 10:00")
        upgrade()

        val all = OperationTemplatesDb.loadAll(db)
        assertEquals(1, all.size)
        val t = all[0]
        assertEquals("Связь", t.name)
        assertEquals(0, BigDecimal("500").compareTo(t.totalAmount))
        assertEquals(1, t.steps.size)
        val step = t.steps[0]
        assertEquals(1, step.num)
        assertEquals(card, step.accFromId)
        assertEquals(phone, step.accToId)
        assertEquals(TemplateStep.AmountMode.REST, step.amountMode)
        assertNull(step.commissionOfNum)
        val s = t.schedule!!
        assertEquals(TemplateSchedule.Frequency.MONTHLY, s.frequency)
        assertEquals(LocalDateTime.of(2026, 1, 15, 10, 0), s.startDate)
        assertEquals(LocalDateTime.of(2026, 10, 15, 10, 0), s.lastDate)
        assertTrue(s.isActive)

        // Повтор за 15 октября уже был — после обновления он не создаётся заново.
        assertEquals(0, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 10, 20, 12, 0)))
        assertEquals(1, OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 11, 15, 12, 0)))
    }

    @Test
    fun ruleWithComment_isNamedByIt_andOperationsKeepComment() {
        rule(card, phone, 30000.0, "Аренда", 2, "2026-11-01 09:00")
        upgrade()

        val t = OperationTemplatesDb.loadAll(db)[0]
        assertEquals("Аренда", t.name)
        assertEquals("Аренда", t.steps[0].comment)
        OperationTemplatesDb.generateDue(db, LocalDateTime.of(2026, 11, 2, 0, 0))
        db.rawQuery("SELECT comment FROM operation", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Аренда", c.getString(0))
        }
    }

    @Test
    fun disabledEndedWeeklyIncomeAndTransfer_keepAllFields() {
        rule(card, phone, 10.0, "", 1, "2026-01-05 08:00", end = "2026-06-30", active = 0,
                interval = 2, weekDays = 1 or 4)
        rule(salary, card, 100000.0, "", 2, "2026-01-10 12:00")
        rule(card, cash, 5000.0, "", 0, "2026-01-01 12:00")
        upgrade()

        val all = OperationTemplatesDb.loadAll(db)
        assertEquals(3, all.size)
        val weekly = all[0].schedule!!
        assertEquals(TemplateSchedule.Frequency.WEEKLY, weekly.frequency)
        assertEquals(2, weekly.interval)
        assertEquals(1 or 4, weekly.weekDays)
        assertEquals(LocalDate.of(2026, 6, 30), weekly.endDate)
        assertFalse(weekly.isActive)
        assertEquals("доход — по источнику", "Тест-зарплата", all[1].name)
        assertEquals("перевод — по источнику", "Тест-карта", all[2].name)
    }

    @Test
    fun missingAccountName_fallsBackToDefaultName() {
        db.execSQL("PRAGMA foreign_keys = OFF")
        rule(card, 999_999, 5.0, "", 2, "2026-01-01 12:00")
        db.execSQL("PRAGMA foreign_keys = ON")
        // Без FK на время переноса, как в onUpgrade (SQLiteOpenHelper включает их позже).
        db.execSQL("PRAGMA foreign_keys = OFF")
        upgrade()
        db.execSQL("PRAGMA foreign_keys = ON")

        db.rawQuery("SELECT name FROM operation_template", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.getString(0) == "Повторяющаяся операция" || c.getString(0) == "Recurring operation")
        }
    }
}
