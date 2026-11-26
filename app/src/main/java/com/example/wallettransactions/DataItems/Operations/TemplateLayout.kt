package com.example.wallettransactions.DataItems.Operations

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Раскладка общей суммы запуска по шагам шаблона (openspec operation-templates,
 * design D2). Одна функция на ручной запуск, генератор по расписанию и
 * `ReminderWorker` — чтобы результаты не расходились.
 *
 * Арифметика — [BigDecimal]: «остаток = общая − Σ» на `double` теряет копейки.
 */
object TemplateLayout {

    private val HUNDRED = BigDecimal(100)

    /** Результат раскладки: суммы по шагам в порядке входного списка или ошибка. */
    sealed class Result {
        class Ok(@JvmField val amounts: List<BigDecimal>) : Result()

        /** Фикс и проценты вместе не меньше общей суммы — остатку ничего не остаётся. */
        object Overflow : Result()

        /** Шаблон неверен: шагов «остаток» не ровно один или нет числа у фикса/процента. */
        object Invalid : Result()
    }

    /**
     * 1. Фикс — как есть (с округлением до `precision`).
     * 2. Процент — `total × p / 100`, `HALF_UP` до `precision`.
     * 3. Остаток — `total − Σ(остальные)`; строго больше нуля, иначе [Result.Overflow].
     */
    @JvmStatic
    fun expand(steps: List<TemplateStep>, total: BigDecimal, precision: Int): Result {
        if (steps.count { it.amountMode == TemplateStep.AmountMode.REST } != 1) return Result.Invalid
        val amounts = arrayOfNulls<BigDecimal>(steps.size)
        var sum = BigDecimal.ZERO
        var restIndex = -1
        for ((i, s) in steps.withIndex()) {
            val v = s.amountValue
            val a = when (s.amountMode) {
                TemplateStep.AmountMode.REST -> {
                    restIndex = i
                    continue
                }
                TemplateStep.AmountMode.FIXED -> v ?: return Result.Invalid
                TemplateStep.AmountMode.PERCENT ->
                    (v ?: return Result.Invalid).multiply(total).divide(HUNDRED)
            }.setScale(precision, RoundingMode.HALF_UP)
            amounts[i] = a
            sum = sum.add(a)
        }
        val rest = total.setScale(precision, RoundingMode.HALF_UP).subtract(sum)
        if (rest.signum() <= 0) return Result.Overflow
        amounts[restIndex] = rest
        return Result.Ok(amounts.map { it!! })
    }

    /**
     * Правка суммы одной проводки на экране запуска (спека «Правка раскладки при запуске»):
     * правка не-остатка пересчитывает остаток так, чтобы общая сумма сохранилась; правка
     * самого остатка меняет общую сумму.
     *
     * @return новые суммы и новая общая сумма; остаток может стать ≤ 0 — это проверяет вызывающий.
     */
    @JvmStatic
    fun edit(
        amounts: List<BigDecimal>, restIndex: Int, total: BigDecimal,
        editedIndex: Int, value: BigDecimal
    ): Pair<List<BigDecimal>, BigDecimal> {
        val result = amounts.toMutableList()
        result[editedIndex] = value
        if (editedIndex == restIndex) {
            var newTotal = BigDecimal.ZERO
            for (a in result) newTotal = newTotal.add(a)
            return Pair(result, newTotal)
        }
        var others = BigDecimal.ZERO
        for ((i, a) in result.withIndex()) if (i != restIndex) others = others.add(a)
        result[restIndex] = total.subtract(others)
        return Pair(result, total)
    }
}
