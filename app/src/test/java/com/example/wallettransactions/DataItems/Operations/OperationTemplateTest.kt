package com.example.wallettransactions.DataItems.Operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** Состав шаблона — [OperationTemplate.validate] (спеки «Состав шаблона», «Одна валюта на шаблон»). */
class OperationTemplateTest {

    // Счета: 1, 2 — кошельки в валюте 10; 3 — кошелёк в валюте 20; 7, 8 — категории.
    private val currencyOf: (Int) -> Int? = { id ->
        when (id) {
            1, 2 -> 10
            3 -> 20
            else -> null
        }
    }

    private fun step(num: Int, from: Int, to: Int, mode: TemplateStep.AmountMode, value: String? = null,
                     commissionOf: Int? = null): TemplateStep {
        val s = TemplateStep()
        s.num = num
        s.accFromId = from
        s.accToId = to
        s.amountMode = mode
        s.amountValue = value?.let { BigDecimal(it) }
        s.commissionOfNum = commissionOf
        return s
    }

    private fun template(vararg steps: TemplateStep): OperationTemplate {
        val t = OperationTemplate()
        t.name = "T"
        t.steps.addAll(steps)
        return t
    }

    @Test
    fun cashWithdrawal_isValid() {
        val t = template(
            step(1, 1, 2, TemplateStep.AmountMode.REST),
            step(2, 1, 8, TemplateStep.AmountMode.FIXED, "50", commissionOf = 1)
        )
        assertNull(t.validate(currencyOf))
        assertEquals(t.steps[0], t.mainStep())
        assertEquals(false, t.isScheduled)
    }

    @Test
    fun noSteps() {
        assertEquals(OperationTemplate.Problem.NO_STEPS, template().validate(currencyOf))
    }

    @Test
    fun exactlyOneRest() {
        assertEquals(OperationTemplate.Problem.REST_COUNT,
            template(step(1, 1, 7, TemplateStep.AmountMode.FIXED, "5")).validate(currencyOf))
        assertEquals(OperationTemplate.Problem.REST_COUNT, template(
            step(1, 1, 7, TemplateStep.AmountMode.REST),
            step(2, 1, 8, TemplateStep.AmountMode.REST)
        ).validate(currencyOf))
    }

    @Test
    fun percentOutOfRange() {
        assertEquals(OperationTemplate.Problem.BAD_VALUE, template(
            step(1, 1, 7, TemplateStep.AmountMode.REST),
            step(2, 1, 8, TemplateStep.AmountMode.PERCENT, "101")
        ).validate(currencyOf))
        assertEquals(OperationTemplate.Problem.BAD_VALUE, template(
            step(1, 1, 7, TemplateStep.AmountMode.REST),
            step(2, 1, 8, TemplateStep.AmountMode.FIXED, "0")
        ).validate(currencyOf))
    }

    @Test
    fun commissionMustReferToDebitingMainStep() {
        assertEquals(OperationTemplate.Problem.BAD_COMMISSION, template(
            step(1, 1, 7, TemplateStep.AmountMode.REST),
            step(2, 1, 8, TemplateStep.AmountMode.FIXED, "5", commissionOf = 3)
        ).validate(currencyOf))
        // Основной шаг — доход (списывает с категории): комиссию к нему не привязать.
        assertEquals(OperationTemplate.Problem.BAD_COMMISSION, template(
            step(1, 7, 1, TemplateStep.AmountMode.REST),
            step(2, 1, 8, TemplateStep.AmountMode.FIXED, "5", commissionOf = 1)
        ).validate(currencyOf))
    }

    @Test
    fun mixedCurrencies() {
        assertEquals(OperationTemplate.Problem.MIXED_CURRENCY,
            template(step(1, 1, 3, TemplateStep.AmountMode.REST)).validate(currencyOf))
    }

    @Test
    fun stepWithoutWallet() {
        assertEquals(OperationTemplate.Problem.NO_WALLET,
            template(step(1, 7, 8, TemplateStep.AmountMode.REST)).validate(currencyOf))
    }

    @Test
    fun commentFallsBackToTemplateComment() {
        val t = template(step(1, 1, 7, TemplateStep.AmountMode.REST))
        t.comment = "Аренда"
        assertEquals("Аренда", t.commentFor(t.steps[0]))
        t.steps[0].comment = "Свет"
        assertEquals("Свет", t.commentFor(t.steps[0]))
    }
}
