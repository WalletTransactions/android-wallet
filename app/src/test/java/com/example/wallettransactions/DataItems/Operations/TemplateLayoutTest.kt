package com.example.wallettransactions.DataItems.Operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Раскладка общей суммы по шагам — [TemplateLayout] (openspec operation-templates, «Режимы суммы шага»). */
class TemplateLayoutTest {

    private fun step(mode: TemplateStep.AmountMode, value: String? = null): TemplateStep {
        val s = TemplateStep()
        s.amountMode = mode
        s.amountValue = value?.let { BigDecimal(it) }
        return s
    }

    private val rest = { step(TemplateStep.AmountMode.REST) }

    private fun amounts(r: TemplateLayout.Result): List<String> {
        assertTrue("ожидалась раскладка, а не $r", r is TemplateLayout.Result.Ok)
        return (r as TemplateLayout.Result.Ok).amounts.map { it.toPlainString() }
    }

    @Test
    fun fixedAndRest() {
        val r = TemplateLayout.expand(listOf(rest(), step(TemplateStep.AmountMode.FIXED, "50")), BigDecimal("1550"), 2)
        assertEquals(listOf("1500.00", "50.00"), amounts(r))
    }

    @Test
    fun percentOfTotal() {
        val r = TemplateLayout.expand(listOf(rest(), step(TemplateStep.AmountMode.PERCENT, "3")), BigDecimal("1550"), 2)
        assertEquals(listOf("1503.50", "46.50"), amounts(r))
    }

    @Test
    fun restTakesRoundingDifference() {
        val r = TemplateLayout.expand(listOf(
            rest(),
            step(TemplateStep.AmountMode.PERCENT, "33.333"),
            step(TemplateStep.AmountMode.PERCENT, "33.333")
        ), BigDecimal("100.00"), 2)
        assertEquals(listOf("33.34", "33.33", "33.33"), amounts(r))
        val sum = (r as TemplateLayout.Result.Ok).amounts.fold(BigDecimal.ZERO, BigDecimal::add)
        assertEquals(0, BigDecimal("100.00").compareTo(sum))
    }

    @Test
    fun halfUpRounding() {
        // 0,5 % от 1 = 0,005 → 0,01
        val r = TemplateLayout.expand(listOf(rest(), step(TemplateStep.AmountMode.PERCENT, "0.5")), BigDecimal("1"), 2)
        assertEquals(listOf("0.99", "0.01"), amounts(r))
    }

    @Test
    fun commissionGreaterThanTotal_isOverflow() {
        val r = TemplateLayout.expand(listOf(rest(), step(TemplateStep.AmountMode.FIXED, "50")), BigDecimal("40"), 2)
        assertSame(TemplateLayout.Result.Overflow, r)
    }

    @Test
    fun restZero_isOverflow() {
        val r = TemplateLayout.expand(listOf(rest(), step(TemplateStep.AmountMode.FIXED, "50")), BigDecimal("50"), 2)
        assertSame(TemplateLayout.Result.Overflow, r)
    }

    @Test
    fun exactlyOneRest() {
        assertSame(TemplateLayout.Result.Invalid,
            TemplateLayout.expand(listOf(step(TemplateStep.AmountMode.FIXED, "5")), BigDecimal("10"), 2))
        assertSame(TemplateLayout.Result.Invalid,
            TemplateLayout.expand(listOf(rest(), rest()), BigDecimal("10"), 2))
    }

    @Test
    fun zeroPrecisionCurrency() {
        val r = TemplateLayout.expand(listOf(rest(), step(TemplateStep.AmountMode.PERCENT, "1")), BigDecimal("1550"), 0)
        assertEquals(listOf("1534", "16"), amounts(r))
    }

    // ── Правка раскладки при запуске ──────────────────────────────────────────

    @Test
    fun editCommission_recalculatesRestKeepingTotal() {
        val (a, total) = TemplateLayout.edit(
            listOf(BigDecimal("1500.00"), BigDecimal("50.00")), 0, BigDecimal("1550"), 1, BigDecimal("60"))
        assertEquals(0, BigDecimal("1490").compareTo(a[0]))
        assertEquals(0, BigDecimal("1550").compareTo(total))
    }

    @Test
    fun editRest_changesTotal() {
        val (a, total) = TemplateLayout.edit(
            listOf(BigDecimal("1500.00"), BigDecimal("50.00")), 0, BigDecimal("1550"), 0, BigDecimal("2000"))
        assertEquals(0, BigDecimal("50").compareTo(a[1]))
        assertEquals(0, BigDecimal("2050").compareTo(total))
    }
}
