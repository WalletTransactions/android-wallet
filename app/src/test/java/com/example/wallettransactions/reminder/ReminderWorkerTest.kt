package com.example.wallettransactions.reminder

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** [ReminderWorker]: когда следующее напоминание и когда оно лишнее. */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkerTest {

    companion object {
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private val DAY = LocalDate.of(2026, 10, 11)
    }

    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE operation (_id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, " +
                "acc_fr_id INTEGER, acc_to_id INTEGER, amount TEXT, comment TEXT, deleted_at INTEGER)"
        )
        db.execSQL("CREATE TABLE operation_link (main_id INTEGER, link_id INTEGER, type INTEGER, deleted_at INTEGER)")
        db.execSQL(
            "CREATE TABLE operation_template (_id INTEGER PRIMARY KEY, uuid TEXT, name TEXT, comment TEXT, " +
                "total_amount REAL, frequency INTEGER, interval INTEGER DEFAULT 1, week_days INTEGER DEFAULT 0, " +
                "start_date TEXT, end_date TEXT, is_active INTEGER DEFAULT 1, last_date TEXT)"
        )
        db.execSQL(
            "CREATE TABLE operation_template_step (_id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, " +
                "template_id INTEGER, num INTEGER, acc_fr_id INTEGER, acc_to_id INTEGER, amount_mode INTEGER, " +
                "amount_value REAL, comment TEXT DEFAULT '', commission_of_num INTEGER)"
        )
        db.execSQL("CREATE TABLE account_operating (_id INTEGER PRIMARY KEY, currency_id INTEGER)")
        db.execSQL("CREATE TABLE currency (_id INTEGER PRIMARY KEY, precision INTEGER)")
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Расписание ────────────────────────────────────────────────────────────

    @Test
    fun nextDue_todayIfAhead_tomorrowIfPassed() {
        val morning = LocalDateTime.of(2026, 10, 11, 9, 0).atZone(BERLIN)
        assertEquals(
            LocalDateTime.of(2026, 10, 11, 20, 0),
            ReminderWorker.nextDue(morning, 20, 0).toLocalDateTime()
        )

        val exactly = LocalDateTime.of(2026, 10, 11, 20, 0).atZone(BERLIN)
        assertEquals(
            "ровно в срок — уже следующий день",
            LocalDateTime.of(2026, 10, 12, 20, 0), ReminderWorker.nextDue(exactly, 20, 0).toLocalDateTime()
        )
    }

    /** 25 октября 2026 в Европе часы назад — сутки 25 часов, а время напоминания то же. */
    @Test
    fun nextDue_keepsWallClockAcrossDst() {
        val evening = LocalDateTime.of(2026, 10, 24, 21, 0).atZone(BERLIN)
        val due = ReminderWorker.nextDue(evening, 20, 0)
        assertEquals(LocalDateTime.of(2026, 10, 25, 20, 0), due.toLocalDateTime())
        assertEquals(24L, java.time.Duration.between(evening, due).toHours())
    }

    // ── Внесено ли сегодня ────────────────────────────────────────────────────

    @Test
    fun entered_onlyTodayAndNotDeleted() {
        insert(1, "2026-10-10 23:59", 1, 2, "50", null)
        insert(2, "2026-10-11 12:00", 1, 2, "50", 1L)
        assertFalse(ReminderWorker.hasEnteredToday(db, DAY))

        insert(3, "2026-10-11 00:00", 1, 2, "50", null)
        assertTrue(ReminderWorker.hasEnteredToday(db, DAY))
    }

    @Test
    fun recurringLookalike_doesNotCount() {
        template(1, 1200.0, scheduled = true)
        step(1, 1, 1, 5, mode = 2, value = null, commissionOf = null)
        insert(1, "2026-10-11 09:00", 1, 5, "1200", null)
        assertFalse(ReminderWorker.hasEnteredToday(db, DAY))

        insert(2, "2026-10-11 10:00", 1, 5, "300", null)
        assertTrue("та же пара счетов, другая сумма — внесено руками", ReminderWorker.hasEnteredToday(db, DAY))
    }

    /** Шаблон с комиссией по расписанию: обе проводки раскладки — «как у шаблона». */
    @Test
    fun scheduledTemplateSteps_doNotCount() {
        template(1, 1550.0, scheduled = true)
        step(1, 1, 1, 5, mode = 2, value = null, commissionOf = null)
        step(1, 2, 1, 7, mode = 0, value = 50.0, commissionOf = 1)
        insert(1, "2026-10-11 09:00", 1, 5, "1500", null)
        insert(2, "2026-10-11 09:00", 1, 7, "50", null)
        assertFalse(ReminderWorker.hasEnteredToday(db, DAY))
    }

    /** Та же операция, запущенная вручную шаблоном без расписания, — внесена человеком. */
    @Test
    fun manualTemplateRun_counts() {
        template(1, 1200.0, scheduled = false)
        step(1, 1, 1, 5, mode = 2, value = null, commissionOf = null)
        insert(1, "2026-10-11 09:00", 1, 5, "1200", null)
        assertTrue(ReminderWorker.hasEnteredToday(db, DAY))
    }

    private fun template(id: Int, total: Double, scheduled: Boolean) {
        db.execSQL(
            "INSERT INTO operation_template (_id, uuid, name, comment, total_amount, frequency, start_date) " +
                "VALUES (?, ?, 'T', '', ?, ?, ?)",
            arrayOf<Any?>(id, "t$id", total, if (scheduled) 2 else null, if (scheduled) "2026-01-11 09:00" else null)
        )
    }

    private fun step(templateId: Int, num: Int, from: Int, to: Int, mode: Int, value: Double?, commissionOf: Int?) {
        db.execSQL(
            "INSERT INTO operation_template_step (uuid, template_id, num, acc_fr_id, acc_to_id, amount_mode, " +
                "amount_value, commission_of_num) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("s$templateId-$num", templateId, num, from, to, mode, value, commissionOf)
        )
    }

    @Test
    fun linkedLeg_doesNotCount() {
        insert(1, "2026-10-11 09:00", 1, 2, "100", 1L)
        insert(2, "2026-10-11 09:00", 3, 4, "100", null)
        db.execSQL("INSERT INTO operation_link VALUES (1, 2, 1, NULL)")
        assertFalse(ReminderWorker.hasEnteredToday(db, DAY))
    }

    private fun insert(id: Int, date: String, from: Int, to: Int, amount: String, deletedAt: Long?) {
        db.execSQL(
            "INSERT INTO operation (_id, date, acc_fr_id, acc_to_id, amount, comment, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, '', ?)",
            arrayOf<Any?>(id, date, from, to, amount, deletedAt)
        )
    }
}
