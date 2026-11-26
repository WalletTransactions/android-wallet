package com.example.wallettransactions.DataItems.Operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Расписание шаблона операций — {@link TemplateSchedule#occurrencesBetween} (логика прежнего правила без изменений). */
class TemplateScheduleTest {

    private fun rule(f: TemplateSchedule.Frequency, start: String): TemplateSchedule {
        val r = TemplateSchedule()
        r.frequency = f
        r.startDate = LocalDateTime.parse(start)
        return r
    }

    private fun at(s: String): LocalDateTime = LocalDateTime.parse(s)

    @Test
    fun dailyIncludesStartAndStopsAtUntil() {
        val r = rule(TemplateSchedule.Frequency.DAILY, "2026-01-01T09:00")
        val got = r.occurrencesBetween(null, at("2026-01-03T09:00"))
        assertEquals(3, got.size)
        assertEquals(at("2026-01-01T09:00"), got[0])
        assertEquals(at("2026-01-03T09:00"), got[2])
    }

    @Test
    fun dailyAfterLastDateIsExclusive() {
        val r = rule(TemplateSchedule.Frequency.DAILY, "2026-01-01T09:00")
        val got = r.occurrencesBetween(at("2026-01-02T09:00"), at("2026-01-04T08:59"))
        assertEquals(1, got.size)
        assertEquals(at("2026-01-03T09:00"), got[0])
    }

    @Test
    fun monthlyClampsToLastDayOfShortMonth() {
        val r = rule(TemplateSchedule.Frequency.MONTHLY, "2026-01-31T12:00")
        val got = r.occurrencesBetween(null, at("2026-04-30T23:00"))
        assertEquals(4, got.size)
        assertEquals(LocalDate.of(2026, 2, 28), got[1].toLocalDate())
        assertEquals(LocalDate.of(2026, 3, 31), got[2].toLocalDate())
        assertEquals(LocalDate.of(2026, 4, 30), got[3].toLocalDate())
    }

    @Test
    fun yearlyLeapDay() {
        val r = rule(TemplateSchedule.Frequency.YEARLY, "2024-02-29T10:00")
        val got = r.occurrencesBetween(null, at("2028-03-01T00:00"))
        assertEquals(5, got.size)
        assertEquals(LocalDate.of(2025, 2, 28), got[1].toLocalDate())
        assertEquals(LocalDate.of(2028, 2, 29), got[4].toLocalDate())
    }

    @Test
    fun weeklySelectedDaysEveryTwoWeeks() {
        // 2026-09-07 — понедельник.
        val r = rule(TemplateSchedule.Frequency.WEEKLY, "2026-09-09T08:00") // ср
        r.weekDays = TemplateSchedule.dayBit(DayOfWeek.MONDAY) or TemplateSchedule.dayBit(DayOfWeek.FRIDAY)
        r.interval = 2
        val got = r.occurrencesBetween(null, at("2026-09-30T23:00"))
        // Неделя старта: пн 7-го раньше даты начала — пропуск, пт 11-го; через одну — пн 21, пт 25.
        assertEquals(3, got.size)
        assertEquals(LocalDate.of(2026, 9, 11), got[0].toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 21), got[1].toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 25), got[2].toLocalDate())
    }

    @Test
    fun weeklyEmptyMaskUsesStartWeekday() {
        val r = rule(TemplateSchedule.Frequency.WEEKLY, "2026-09-09T08:00")
        val got = r.occurrencesBetween(null, at("2026-09-23T08:00"))
        assertEquals(3, got.size)
        for (d in got) assertEquals(DayOfWeek.WEDNESDAY, d.dayOfWeek)
    }

    @Test
    fun endDateIsInclusiveUpperBound() {
        val r = rule(TemplateSchedule.Frequency.DAILY, "2026-01-01T23:30")
        r.endDate = LocalDate.of(2026, 1, 2)
        val got = r.occurrencesBetween(null, at("2027-01-01T00:00"))
        assertEquals(2, got.size)
        assertNull(r.nextOccurrence(at("2026-01-03T00:00")))
    }

    @Test
    fun futureStartHasNoDueOccurrences() {
        val r = rule(TemplateSchedule.Frequency.MONTHLY, "2030-05-15T12:00")
        assertTrue(r.occurrencesBetween(null, at("2026-09-24T12:00")).isEmpty())
        assertEquals(at("2030-05-15T12:00"), r.nextOccurrence(at("2026-09-24T12:00")))
    }
}
