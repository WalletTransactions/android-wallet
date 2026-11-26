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
            "CREATE TABLE recurring_operation (_id INTEGER PRIMARY KEY, acc_fr_id INTEGER, " +
                "acc_to_id INTEGER, amount REAL)"
        )
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
        db.execSQL("INSERT INTO recurring_operation VALUES (1, 1, 5, 1200.0)")
        insert(1, "2026-10-11 09:00", 1, 5, "1200", null)
        assertFalse(ReminderWorker.hasEnteredToday(db, DAY))

        insert(2, "2026-10-11 10:00", 1, 5, "300", null)
        assertTrue("та же пара счетов, другая сумма — внесено руками", ReminderWorker.hasEnteredToday(db, DAY))
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
