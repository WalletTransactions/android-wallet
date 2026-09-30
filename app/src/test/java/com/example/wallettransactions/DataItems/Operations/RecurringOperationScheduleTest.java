package com.example.wallettransactions.DataItems.Operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Расписание правила повторяющейся операции — {@link RecurringOperation#occurrencesBetween}. */
public class RecurringOperationScheduleTest {

    private static RecurringOperation rule(RecurringOperation.Frequency f, String start) {
        RecurringOperation r = new RecurringOperation();
        r.frequency = f;
        r.startDate = LocalDateTime.parse(start);
        return r;
    }

    private static LocalDateTime at(String s) {
        return LocalDateTime.parse(s);
    }

    @Test
    public void dailyIncludesStartAndStopsAtUntil() {
        RecurringOperation r = rule(RecurringOperation.Frequency.DAILY, "2026-01-01T09:00");
        List<LocalDateTime> got = r.occurrencesBetween(null, at("2026-01-03T09:00"));
        assertEquals(3, got.size());
        assertEquals(at("2026-01-01T09:00"), got.get(0));
        assertEquals(at("2026-01-03T09:00"), got.get(2));
    }

    @Test
    public void dailyAfterLastDateIsExclusive() {
        RecurringOperation r = rule(RecurringOperation.Frequency.DAILY, "2026-01-01T09:00");
        List<LocalDateTime> got = r.occurrencesBetween(at("2026-01-02T09:00"), at("2026-01-04T08:59"));
        assertEquals(1, got.size());
        assertEquals(at("2026-01-03T09:00"), got.get(0));
    }

    @Test
    public void monthlyClampsToLastDayOfShortMonth() {
        RecurringOperation r = rule(RecurringOperation.Frequency.MONTHLY, "2026-01-31T12:00");
        List<LocalDateTime> got = r.occurrencesBetween(null, at("2026-04-30T23:00"));
        assertEquals(4, got.size());
        assertEquals(LocalDate.of(2026, 2, 28), got.get(1).toLocalDate());
        assertEquals(LocalDate.of(2026, 3, 31), got.get(2).toLocalDate());
        assertEquals(LocalDate.of(2026, 4, 30), got.get(3).toLocalDate());
    }

    @Test
    public void yearlyLeapDay() {
        RecurringOperation r = rule(RecurringOperation.Frequency.YEARLY, "2024-02-29T10:00");
        List<LocalDateTime> got = r.occurrencesBetween(null, at("2028-03-01T00:00"));
        assertEquals(5, got.size());
        assertEquals(LocalDate.of(2025, 2, 28), got.get(1).toLocalDate());
        assertEquals(LocalDate.of(2028, 2, 29), got.get(4).toLocalDate());
    }

    @Test
    public void weeklySelectedDaysEveryTwoWeeks() {
        // 2026-09-07 — понедельник.
        RecurringOperation r = rule(RecurringOperation.Frequency.WEEKLY, "2026-09-09T08:00"); // ср
        r.weekDays = RecurringOperation.dayBit(DayOfWeek.MONDAY) | RecurringOperation.dayBit(DayOfWeek.FRIDAY);
        r.interval = 2;
        List<LocalDateTime> got = r.occurrencesBetween(null, at("2026-09-30T23:00"));
        // Неделя старта: пн 7-го раньше даты начала — пропуск, пт 11-го; через одну — пн 21, пт 25.
        assertEquals(3, got.size());
        assertEquals(LocalDate.of(2026, 9, 11), got.get(0).toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 21), got.get(1).toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 25), got.get(2).toLocalDate());
    }

    @Test
    public void weeklyEmptyMaskUsesStartWeekday() {
        RecurringOperation r = rule(RecurringOperation.Frequency.WEEKLY, "2026-09-09T08:00");
        List<LocalDateTime> got = r.occurrencesBetween(null, at("2026-09-23T08:00"));
        assertEquals(3, got.size());
        for (LocalDateTime d : got) assertEquals(DayOfWeek.WEDNESDAY, d.getDayOfWeek());
    }

    @Test
    public void endDateIsInclusiveUpperBound() {
        RecurringOperation r = rule(RecurringOperation.Frequency.DAILY, "2026-01-01T23:30");
        r.endDate = LocalDate.of(2026, 1, 2);
        List<LocalDateTime> got = r.occurrencesBetween(null, at("2027-01-01T00:00"));
        assertEquals(2, got.size());
        assertNull(r.nextOccurrence(at("2026-01-03T00:00")));
    }

    @Test
    public void futureStartHasNoDueOccurrences() {
        RecurringOperation r = rule(RecurringOperation.Frequency.MONTHLY, "2030-05-15T12:00");
        assertTrue(r.occurrencesBetween(null, at("2026-09-24T12:00")).isEmpty());
        assertEquals(at("2030-05-15T12:00"), r.nextOccurrence(at("2026-09-24T12:00")));
    }
}
