package com.example.wallettransactions.DataItems.Operations

import java.math.BigDecimal
import java.util.UUID

/**
 * Шаблон операций (openspec operation-templates): заголовок, упорядоченные шаги и
 * необязательное расписание. Пустое расписание — ручной шаблон; с расписанием — то,
 * что раньше называлось «повторяющейся операцией».
 *
 * Только локальная сущность: таблицы `operation_template` / `operation_template_step`
 * не синхронизируются, `uuid` заведён впрок под будущий синк (design D1).
 */
class OperationTemplate {

    /** Почему шаблон нельзя сохранить — см. [validate]. */
    enum class Problem {
        /** Нет ни одного шага. */
        NO_STEPS,
        /** Шагов «остаток» не ровно один. */
        REST_COUNT,
        /** «Фикс» не больше нуля или «%» вне (0; 100]. */
        BAD_VALUE,
        /** Комиссия ссылается не на основной шаг, списывающий с кошелька. */
        BAD_COMMISSION,
        /** У шага нет кошелька ни с одной стороны. */
        NO_WALLET,
        /** Кошельки шагов в разных валютах. */
        MIXED_CURRENCY,
    }

    @JvmField
    var id: Int = 0

    @JvmField
    var uuid: String = UUID.randomUUID().toString()

    @JvmField
    var name: String = ""

    @JvmField
    var comment: String = ""

    /** Общая сумма по умолчанию; обязательна у шаблона с расписанием. */
    @JvmField
    var totalAmount: BigDecimal? = null

    @JvmField
    val steps: MutableList<TemplateStep> = ArrayList()

    @JvmField
    var schedule: TemplateSchedule? = null

    val isScheduled: Boolean get() = schedule != null

    /** Шаг «остаток» — основной шаг шаблона; null только у неверного шаблона. */
    fun mainStep(): TemplateStep? = steps.firstOrNull { it.amountMode == TemplateStep.AmountMode.REST }

    fun stepByNum(num: Int): TemplateStep? = steps.firstOrNull { it.num == num }

    /** Комментарий проводки шага: свой, а если пуст — комментарий шаблона. */
    fun commentFor(step: TemplateStep): String =
        if (step.comment.isNotBlank()) step.comment else comment

    /**
     * Состав шаблона (спека «Состав шаблона», «Одна валюта на шаблон», «Комиссия как
     * связанная проводка»).
     *
     * @param currencyOf валюта кошелька по id счёта; null — счёт не кошелёк (категория).
     * @return первая найденная проблема или null — шаблон годен.
     */
    fun validate(currencyOf: (Int) -> Int?): Problem? {
        if (steps.isEmpty()) return Problem.NO_STEPS
        if (steps.count { it.amountMode == TemplateStep.AmountMode.REST } != 1) return Problem.REST_COUNT
        for (s in steps) {
            val v = s.amountValue
            when (s.amountMode) {
                TemplateStep.AmountMode.FIXED ->
                    if (v == null || v.signum() <= 0) return Problem.BAD_VALUE
                TemplateStep.AmountMode.PERCENT ->
                    if (v == null || v.signum() <= 0 || v > HUNDRED) return Problem.BAD_VALUE
                TemplateStep.AmountMode.REST -> {}
            }
        }
        for (s in steps) {
            val of = s.commissionOfNum ?: continue
            val main = stepByNum(of)
            if (main == null || main === s || main.isCommission) return Problem.BAD_COMMISSION
            if (currencyOf(main.accFromId) == null) return Problem.BAD_COMMISSION
        }
        var currency: Int? = null
        for (s in steps) {
            val from = currencyOf(s.accFromId)
            val to = currencyOf(s.accToId)
            if (from == null && to == null) return Problem.NO_WALLET
            for (c in listOfNotNull(from, to)) {
                if (currency == null) currency = c
                else if (currency != c) return Problem.MIXED_CURRENCY
            }
        }
        return null
    }

    companion object {
        private val HUNDRED = BigDecimal(100)
    }
}
