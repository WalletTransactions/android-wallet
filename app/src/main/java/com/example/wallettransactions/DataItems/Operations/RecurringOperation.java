package com.example.wallettransactions.DataItems.Operations;

import com.example.wallettransactions.R;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Правило повторяющейся операции (таблица {@code recurring_operation}): откуда, куда,
 * сколько и по какому расписанию заводить обычные операции.
 * <p>
 * Счета здесь — только id: правило грузится и без справочника счетов (генератор в
 * {@code RecurringOperationsDb.generateDue}), а экраны берут сами счета из
 * {@code Singleton.getAccounts}.
 * <p>
 * Расписание считается в {@link #occurrencesBetween} без Android-зависимостей, поэтому
 * его проверяет обычный unit-тест.
 */
public class RecurringOperation {

    /** Значения хранятся в {@code recurring_operation.frequency} — код существующего менять нельзя. */
    public enum Frequency {
        DAILY(0, R.string.recurring_daily),
        WEEKLY(1, R.string.recurring_weekly),
        MONTHLY(2, R.string.recurring_monthly),
        YEARLY(3, R.string.recurring_yearly);

        private final int value;
        /** Ресурс подписи (openspec android-i18n): TextView.setText(int) и Menu.add принимают его. */
        private final int title;

        Frequency(int value, int title) {
            this.value = value;
            this.title = title;
        }

        public int getValue() {
            return value;
        }

        public int getTitle() {
            return title;
        }

        /** Безопасный дефолт {@link #MONTHLY} на незнакомое значение. */
        public static Frequency fromValue(int value) {
            for (Frequency f : values()) if (f.value == value) return f;
            return MONTHLY;
        }
    }

    /** Предел шагов, которые генератор делает за один раз, — защита от бесконечного цикла. */
    private static final int MAX_OCCURRENCES = 10000;

    public int id;
    public int accFromId;
    public int accToId;
    public double amount;
    public String comment = "";
    public Frequency frequency = Frequency.MONTHLY;
    /** «Каждые N недель» — только для {@link Frequency#WEEKLY}, иначе 1. */
    public int interval = 1;
    /** Битовая маска дней недели: пн=1, вт=2, … вс=64 — см. {@link #dayBit}. */
    public int weekDays;
    /** С какой даты повторять; время — время создаваемых операций. */
    public LocalDateTime startDate;
    /** До какой даты включительно; null — «всегда». */
    public LocalDate endDate;
    public boolean isActive = true;
    /** Дата последней уже созданной операции; null — ещё ни одной. */
    public LocalDateTime lastDate;

    /**
     * Может ли правило заводить операции на момент {@code now}: оно включено и «дата по»
     * (включительно) ещё не прошла либо до неё остались не созданные повторы. Активность
     * и дата окончания — два равноправных условия, одного включённого флага мало.
     */
    public boolean isRunning(LocalDateTime now) {
        if (!isActive || startDate == null) return false;
        if (endDate == null) return true;
        if (!now.toLocalDate().isAfter(endDate)) return true;
        return lastDate == null || lastDate.toLocalDate().isBefore(endDate);
    }

    public static int dayBit(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }

    public boolean hasDay(DayOfWeek day) {
        return (weekDays & dayBit(day)) != 0;
    }

    /**
     * Дни недели, которые реально действуют: пустая маска означает день недели даты
     * начала — иначе еженедельное правило без отмеченных дней не срабатывало бы никогда.
     */
    private int effectiveWeekDays() {
        return weekDays != 0 ? weekDays : dayBit(startDate.getDayOfWeek());
    }

    /**
     * Моменты повтора строго после {@code after} (null — с самого начала) и не позже
     * {@code until}, в порядке возрастания. Дата окончания правила ограничивает сверху.
     * <p>
     * Ежемесячно — в тот же день месяца, что дата начала; в коротком месяце, где такого
     * дня нет (31-е, 30 февраля), — в последний день месяца. Ежегодно — так же, 29 февраля
     * в невисокосный год становится 28-м. Еженедельно — по отмеченным дням недели, неделя
     * отсчитывается от недели (пн–вс) даты начала: «каждые 2 недели» — это она, через одну
     * после неё и т.д.
     */
    public List<LocalDateTime> occurrencesBetween(LocalDateTime after, LocalDateTime until) {
        List<LocalDateTime> result = new ArrayList<>();
        if (startDate == null || until == null) return result;

        LocalDateTime limit = until;
        if (endDate != null) {
            LocalDateTime endLimit = endDate.atTime(23, 59, 59);
            if (endLimit.isBefore(limit)) limit = endLimit;
        }

        int step = Math.max(1, interval);
        LocalDate startDay = startDate.toLocalDate();
        int steps = 0;

        switch (frequency) {
            case DAILY: {
                for (LocalDate d = startDay; steps < MAX_OCCURRENCES; d = d.plusDays(1), steps++) {
                    if (!addIfInRange(result, d, after, limit)) break;
                }
                break;
            }
            case WEEKLY: {
                int mask = effectiveWeekDays();
                LocalDate weekStart = startDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDate d = startDay; steps < MAX_OCCURRENCES; d = d.plusDays(1), steps++) {
                    LocalDate monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    long weeks = ChronoUnit.WEEKS.between(weekStart, monday);
                    boolean matches = weeks % step == 0 && (mask & dayBit(d.getDayOfWeek())) != 0;
                    if (!matches) {
                        if (d.atTime(startDate.toLocalTime()).isAfter(limit)) break;
                        continue;
                    }
                    if (!addIfInRange(result, d, after, limit)) break;
                }
                break;
            }
            case MONTHLY: {
                int day = startDay.getDayOfMonth();
                for (int m = 0; steps < MAX_OCCURRENCES; m += step, steps++) {
                    LocalDate first = startDay.withDayOfMonth(1).plusMonths(m);
                    LocalDate d = first.withDayOfMonth(Math.min(day, first.lengthOfMonth()));
                    if (!addIfInRange(result, d, after, limit)) break;
                }
                break;
            }
            case YEARLY: {
                for (int y = 0; steps < MAX_OCCURRENCES; y += step, steps++) {
                    // plusYears сам сводит 29 февраля к 28-му в невисокосный год.
                    LocalDate d = startDay.plusYears(y);
                    if (!addIfInRange(result, d, after, limit)) break;
                }
                break;
            }
        }
        return result;
    }

    /** @return false — дата уже за пределом, дальше перебирать незачем. */
    private boolean addIfInRange(List<LocalDateTime> result, LocalDate day,
                                 LocalDateTime after, LocalDateTime limit) {
        LocalDateTime at = day.atTime(startDate.toLocalTime());
        if (at.isAfter(limit)) return false;
        if (after == null || at.isAfter(after)) result.add(at);
        return true;
    }

    /** Ближайший повтор после {@code now}, null — повторов больше не будет. */
    public LocalDateTime nextOccurrence(LocalDateTime now) {
        if (startDate == null) return null;
        LocalDateTime from = lastDate != null && lastDate.isAfter(now) ? lastDate : now;
        // Горизонт — от даты начала, если она ещё впереди, и с запасом на «каждые N»:
        // правило без конца всё равно найдёт повтор в пределах пары шагов.
        LocalDateTime base = startDate.isAfter(from) ? startDate : from;
        List<LocalDateTime> next = occurrencesBetween(from, base.plusYears(Math.max(1, interval) + 1));
        return next.isEmpty() ? null : next.get(0);
    }

    /** Человекочитаемое описание расписания: «Каждые 2 недели: пн, ср», «Ежемесячно, 15-го». */
    public String describeSchedule(android.content.Context c) {
        switch (frequency) {
            case DAILY:
                return c.getString(R.string.recurring_daily);
            case WEEKLY: {
                StringBuilder days = new StringBuilder();
                int mask = startDate != null ? effectiveWeekDays() : weekDays;
                for (DayOfWeek d : DayOfWeek.values()) {
                    if ((mask & dayBit(d)) == 0) continue;
                    if (days.length() > 0) days.append(", ");
                    days.append(shortDayName(c, d));
                }
                String head = interval <= 1 ? c.getString(R.string.recurring_weekly)
                        : c.getResources().getQuantityString(R.plurals.recurring_every_weeks, interval, interval);
                return days.length() > 0 ? head + ": " + days : head;
            }
            case MONTHLY:
                return startDate != null
                        ? c.getString(R.string.recurring_monthly_on, startDate.getDayOfMonth())
                        : c.getString(R.string.recurring_monthly);
            case YEARLY:
            default:
                return startDate != null
                        ? c.getString(R.string.recurring_yearly_on, startDate.getDayOfMonth(), startDate.getMonthValue())
                        : c.getString(R.string.recurring_yearly);
        }
    }

    public static String shortDayName(android.content.Context c, DayOfWeek day) {
        switch (day) {
            case MONDAY:    return c.getString(R.string.day_short_mon);
            case TUESDAY:   return c.getString(R.string.day_short_tue);
            case WEDNESDAY: return c.getString(R.string.day_short_wed);
            case THURSDAY:  return c.getString(R.string.day_short_thu);
            case FRIDAY:    return c.getString(R.string.day_short_fri);
            case SATURDAY:  return c.getString(R.string.day_short_sat);
            default:        return c.getString(R.string.day_short_sun);
        }
    }
}
