package com.example.wallettransactions.DataItems.Operations

import com.example.wallettransactions.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Правило повторяющейся операции (таблица `recurring_operation`): откуда, куда,
 * сколько и по какому расписанию заводить обычные операции.
 *
 * Счета здесь — только id: правило грузится и без справочника счетов (генератор в
 * `RecurringOperationsDb.generateDue`), а экраны берут сами счета из
 * `Singleton.getAccounts`.
 *
 * Расписание считается в [occurrencesBetween] без Android-зависимостей, поэтому
 * его проверяет обычный unit-тест.
 */
class RecurringOperation {

    /** Значения хранятся в `recurring_operation.frequency` — код существующего менять нельзя. */
    enum class Frequency(
        val value: Int,
        /** Ресурс подписи (openspec android-i18n): TextView.setText(int) и Menu.add принимают его. */
        val title: Int
    ) {
        DAILY(0, R.string.recurring_daily),
        WEEKLY(1, R.string.recurring_weekly),
        MONTHLY(2, R.string.recurring_monthly),
        YEARLY(3, R.string.recurring_yearly);

        companion object {
            /** Безопасный дефолт [MONTHLY] на незнакомое значение. */
            @JvmStatic
            fun fromValue(value: Int): Frequency {
                for (f in values()) if (f.value == value) return f
                return MONTHLY
            }
        }
    }

    @JvmField
    var id: Int = 0

    @JvmField
    var accFromId: Int = 0

    @JvmField
    var accToId: Int = 0

    @JvmField
    var amount: Double = 0.0

    @JvmField
    var comment: String? = ""

    @JvmField
    var frequency: Frequency = Frequency.MONTHLY

    /** «Каждые N недель» — только для [Frequency.WEEKLY], иначе 1. */
    @JvmField
    var interval: Int = 1

    /** Битовая маска дней недели: пн=1, вт=2, … вс=64 — см. [dayBit]. */
    @JvmField
    var weekDays: Int = 0

    /** С какой даты повторять; время — время создаваемых операций. */
    @JvmField
    var startDate: LocalDateTime? = null

    /** До какой даты включительно; null — «всегда». */
    @JvmField
    var endDate: LocalDate? = null

    @JvmField
    var isActive: Boolean = true

    /** Дата последней уже созданной операции; null — ещё ни одной. */
    @JvmField
    var lastDate: LocalDateTime? = null

    /**
     * Может ли правило заводить операции на момент `now`: оно включено и «дата по»
     * (включительно) ещё не прошла либо до неё остались не созданные повторы. Активность
     * и дата окончания — два равноправных условия, одного включённого флага мало.
     */
    fun isRunning(now: LocalDateTime): Boolean {
        if (!isActive || startDate == null) return false
        val end = endDate ?: return true
        if (!now.toLocalDate().isAfter(end)) return true
        val last = lastDate
        return last == null || last.toLocalDate().isBefore(end)
    }

    fun hasDay(day: DayOfWeek): Boolean {
        return (weekDays and dayBit(day)) != 0
    }

    /**
     * Дни недели, которые реально действуют: пустая маска означает день недели даты
     * начала — иначе еженедельное правило без отмеченных дней не срабатывало бы никогда.
     */
    private fun effectiveWeekDays(): Int {
        return if (weekDays != 0) weekDays else dayBit(startDate!!.dayOfWeek)
    }

    /**
     * Моменты повтора строго после `after` (null — с самого начала) и не позже
     * `until`, в порядке возрастания. Дата окончания правила ограничивает сверху.
     *
     * Ежемесячно — в тот же день месяца, что дата начала; в коротком месяце, где такого
     * дня нет (31-е, 30 февраля), — в последний день месяца. Ежегодно — так же, 29 февраля
     * в невисокосный год становится 28-м. Еженедельно — по отмеченным дням недели, неделя
     * отсчитывается от недели (пн–вс) даты начала: «каждые 2 недели» — это она, через одну
     * после неё и т.д.
     */
    fun occurrencesBetween(after: LocalDateTime?, until: LocalDateTime?): MutableList<LocalDateTime> {
        val result = ArrayList<LocalDateTime>()
        val start = startDate
        if (start == null || until == null) return result

        var limit: LocalDateTime = until
        val end = endDate
        if (end != null) {
            val endLimit = end.atTime(23, 59, 59)
            if (endLimit.isBefore(limit)) limit = endLimit
        }

        val step = maxOf(1, interval)
        val startDay = start.toLocalDate()
        var steps = 0

        when (frequency) {
            Frequency.DAILY -> {
                var d = startDay
                while (steps < MAX_OCCURRENCES) {
                    if (!addIfInRange(result, d, after, limit)) break
                    d = d.plusDays(1)
                    steps++
                }
            }
            Frequency.WEEKLY -> {
                val mask = effectiveWeekDays()
                val weekStart = startDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                var d = startDay
                while (steps < MAX_OCCURRENCES) {
                    val monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val weeks = ChronoUnit.WEEKS.between(weekStart, monday)
                    val matches = weeks % step == 0L && (mask and dayBit(d.dayOfWeek)) != 0
                    if (!matches) {
                        if (d.atTime(start.toLocalTime()).isAfter(limit)) break
                    } else if (!addIfInRange(result, d, after, limit)) {
                        break
                    }
                    d = d.plusDays(1)
                    steps++
                }
            }
            Frequency.MONTHLY -> {
                val day = startDay.dayOfMonth
                var m = 0
                while (steps < MAX_OCCURRENCES) {
                    val first = startDay.withDayOfMonth(1).plusMonths(m.toLong())
                    val d = first.withDayOfMonth(minOf(day, first.lengthOfMonth()))
                    if (!addIfInRange(result, d, after, limit)) break
                    m += step
                    steps++
                }
            }
            Frequency.YEARLY -> {
                var y = 0
                while (steps < MAX_OCCURRENCES) {
                    // plusYears сам сводит 29 февраля к 28-му в невисокосный год.
                    val d = startDay.plusYears(y.toLong())
                    if (!addIfInRange(result, d, after, limit)) break
                    y += step
                    steps++
                }
            }
        }
        return result
    }

    /** @return false — дата уже за пределом, дальше перебирать незачем. */
    private fun addIfInRange(
        result: MutableList<LocalDateTime>, day: LocalDate,
        after: LocalDateTime?, limit: LocalDateTime
    ): Boolean {
        val at = day.atTime(startDate!!.toLocalTime())
        if (at.isAfter(limit)) return false
        if (after == null || at.isAfter(after)) result.add(at)
        return true
    }

    /** Ближайший повтор после `now`, null — повторов больше не будет. */
    fun nextOccurrence(now: LocalDateTime): LocalDateTime? {
        val start = startDate ?: return null
        val last = lastDate
        val from = if (last != null && last.isAfter(now)) last else now
        // Горизонт — от даты начала, если она ещё впереди, и с запасом на «каждые N»:
        // правило без конца всё равно найдёт повтор в пределах пары шагов.
        val base = if (start.isAfter(from)) start else from
        val next = occurrencesBetween(from, base.plusYears((maxOf(1, interval) + 1).toLong()))
        return if (next.isEmpty()) null else next[0]
    }

    /** Человекочитаемое описание расписания: «Каждые 2 недели: пн, ср», «Ежемесячно, 15-го». */
    fun describeSchedule(c: android.content.Context): String {
        val start = startDate
        return when (frequency) {
            Frequency.DAILY -> c.getString(R.string.recurring_daily)
            Frequency.WEEKLY -> {
                val days = StringBuilder()
                val mask = if (start != null) effectiveWeekDays() else weekDays
                for (d in DayOfWeek.values()) {
                    if ((mask and dayBit(d)) == 0) continue
                    if (days.isNotEmpty()) days.append(", ")
                    days.append(shortDayName(c, d))
                }
                val head = if (interval <= 1) c.getString(R.string.recurring_weekly)
                else c.resources.getQuantityString(R.plurals.recurring_every_weeks, interval, interval)
                if (days.isNotEmpty()) "$head: $days" else head
            }
            Frequency.MONTHLY ->
                if (start != null) c.getString(R.string.recurring_monthly_on, start.dayOfMonth)
                else c.getString(R.string.recurring_monthly)
            Frequency.YEARLY ->
                if (start != null) c.getString(R.string.recurring_yearly_on, start.dayOfMonth, start.monthValue)
                else c.getString(R.string.recurring_yearly)
        }
    }

    companion object {
        /** Предел шагов, которые генератор делает за один раз, — защита от бесконечного цикла. */
        private const val MAX_OCCURRENCES = 10000

        @JvmStatic
        fun dayBit(day: DayOfWeek): Int {
            return 1 shl (day.value - 1)
        }

        @JvmStatic
        fun shortDayName(c: android.content.Context, day: DayOfWeek): String {
            return when (day) {
                DayOfWeek.MONDAY -> c.getString(R.string.day_short_mon)
                DayOfWeek.TUESDAY -> c.getString(R.string.day_short_tue)
                DayOfWeek.WEDNESDAY -> c.getString(R.string.day_short_wed)
                DayOfWeek.THURSDAY -> c.getString(R.string.day_short_thu)
                DayOfWeek.FRIDAY -> c.getString(R.string.day_short_fri)
                DayOfWeek.SATURDAY -> c.getString(R.string.day_short_sat)
                else -> c.getString(R.string.day_short_sun)
            }
        }
    }
}
