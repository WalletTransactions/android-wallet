package com.example.wallettransactions

import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Operations.EquivalentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка «Прочее» карточки итогов (openspec summary-other-section, спека period-summary):
 * сумма — остаток `Итог − (Доход − Расход)`, детали видны по данным.
 */
class SummaryOtherTest {

    private fun eq(v: Number): EquivalentResult = EquivalentResult(v.toDouble(), false, false)

    private fun zero(): EquivalentResult = eq(0)

    private fun capital(capital: Number, netWorth: Number): WalletDbLoader.CapitalResult =
        WalletDbLoader.CapitalResult(capital.toDouble() != 0.0, eq(capital), eq(netWorth))

    private fun noDebts(liquid: Number): WalletDbLoader.DebtsResult =
        WalletDbLoader.DebtsResult(false, zero(), zero(), eq(liquid))

    private fun debts(owed: Number, owe: Number, liquid: Number): WalletDbLoader.DebtsResult =
        WalletDbLoader.DebtsResult(
            true, eq(owed), eq(owe),
            eq(liquid.toDouble() + owed.toDouble() - owe.toDouble())
        )

    // ── Сценарий «Только доходы и расходы» ──────────────────────────────────

    @Test
    fun noAssetsNoDebts_noOtherRow() {
        val s = SummaryOther.compute(
            eq(1000), eq(1150), eq(200), eq(50),
            false, capital(0, 1150), zero(), noDebts(1150), zero()
        )

        assertEquals(0.0, s.other.value, DELTA)
        assertFalse("активов, долгов и разницы нет — строки «Прочее» нет", s.showOther)
        assertFalse(s.showCapital)
        assertFalse(s.showCapitalFlow)
        assertFalse(s.showDebts)
        assertFalse(s.showDebtFlow)
        assertFalse(s.showFxDiff)
        assertFalse(s.showTotal)
    }

    // ── Сценарий «Покупка актива» ───────────────────────────────────────────

    @Test
    fun assetPurchase_otherIsMinus400000() {
        // доход 200, расход 50, перевод 400 000 с карты на актив: Итог = −399 850
        val s = SummaryOther.compute(
            eq(500_000), eq(100_150), eq(200), eq(50),
            true, capital(400_000, 500_150), eq(-400_000), noDebts(100_150), zero()
        )

        assertEquals(-400_000.0, s.other.value, DELTA)
        assertTrue(s.showOther)
        assertTrue(s.showCapitalFlow)
        assertEquals(-400_000.0, s.capitalFlow.value, DELTA)
        assertTrue(s.showCapital)
        assertEquals(400_000.0, s.capital.value, DELTA)
        assertFalse("долгов нет — строк долгов нет", s.showDebts)
        assertFalse(s.showDebtFlow)
        assertFalse("поток капитала закрыл всё — курсовой разницы нет", s.showFxDiff)
        assertTrue(s.showTotal)
    }

    @Test
    fun assetsWithoutMovement_capitalShownFlowHidden() {
        val s = SummaryOther.compute(
            eq(1000), eq(1150), eq(200), eq(50),
            true, capital(0, 1150), zero(), noDebts(1150), zero()
        )

        assertTrue("актив есть — «Прочее» есть, даже если за период ноль", s.showOther)
        assertTrue(s.showCapital)
        assertFalse("перевода в актив не было — строки изменения нет", s.showCapitalFlow)
    }

    // ── Сценарий «Выдача займа» ─────────────────────────────────────────────

    @Test
    fun loanGiven_debtFlowAndOwedShown() {
        val s = SummaryOther.compute(
            eq(1000), eq(900), eq(0), eq(0),
            false, capital(0, 900), zero(), debts(100, 0, 900), eq(-100)
        )

        assertEquals(-100.0, s.other.value, DELTA)
        assertTrue(s.showOther)
        assertTrue(s.showDebtFlow)
        assertEquals(-100.0, s.debtFlow.value, DELTA)
        assertTrue(s.showDebts)
        assertEquals(100.0, s.owedToMe.value, DELTA)
        assertEquals(0.0, s.iOwe.value, DELTA)
        assertFalse(s.showCapital)
        assertFalse(s.showFxDiff)
    }

    @Test
    fun capitalAndDebts_allDetailRows() {
        // 400 000 в «Квартиру» и 100 в долг
        val s = SummaryOther.compute(
            eq(500_100), eq(100_000), eq(0), eq(0),
            true, capital(400_000, 500_000), eq(-400_000), debts(100, 0, 100_000), eq(-100)
        )

        assertEquals(-400_100.0, s.other.value, DELTA)
        assertTrue(s.showCapitalFlow)
        assertTrue(s.showCapital)
        assertTrue(s.showDebtFlow)
        assertTrue(s.showDebts)
        assertFalse(s.showFxDiff)
        assertTrue(s.showTotal)
        assertEquals(500_100.0, s.total.value, DELTA)
    }

    // ── Курсовая разница и неполные курсы ───────────────────────────────────

    @Test
    fun fxDiff_isTheRemainder() {
        // Итог 1000, доход − расход 900, потоков нет: 100 — курсовая разница
        val s = SummaryOther.compute(
            eq(0), eq(1000), eq(900), eq(0),
            false, capital(0, 1000), zero(), noDebts(1000), zero()
        )

        assertEquals(100.0, s.other.value, DELTA)
        assertTrue(s.showOther)
        assertTrue(s.showFxDiff)
        assertEquals(100.0, s.fxDiff.value, DELTA)
    }

    @Test
    fun incompleteRates_sumStillAddsUp_markStays() {
        val cl = EquivalentResult(1100.0, true, true)
        val income = EquivalentResult(300.0, false, false)
        val s = SummaryOther.compute(
            eq(1000), cl, income, eq(100),
            false, capital(0, 1100), zero(), noDebts(1100), zero()
        )

        // Итог 100, Доход − Расход 200 → Прочее −100 — ровно остаток, без «потерянных» слагаемых
        assertEquals(-100.0, s.other.value, DELTA)
        assertTrue("неполный эквивалент помечает сумму", s.other.incomplete)
        assertTrue("и ≈ тоже", s.other.approx)
        assertTrue(s.showOther)
        assertTrue(s.fxDiff.incomplete)
    }

    @Test
    fun emptyIncomeReport_isZero_andNotMarkedIncomplete() {
        val none = EquivalentResult(null as Double?, false, false)
        val s = SummaryOther.compute(
            eq(0), eq(0), none, none,
            false, capital(0, 0), zero(), noDebts(0), zero()
        )

        assertEquals(0.0, s.other.value, DELTA)
        assertFalse(s.showOther)
        assertFalse("пустой отчёт — не «нет курса»", s.other.incomplete)
        assertFalse(s.other.approx)
    }

    @Test
    fun incompleteIncome_marksOther() {
        // доход посчитан не по всем операциям (у части нет курса) — «Прочее» тоже неполное
        val income = EquivalentResult(300.0, true, true)
        val s = SummaryOther.compute(
            eq(1000), eq(1100), income, eq(100),
            false, capital(0, 1100), zero(), noDebts(1100), zero()
        )

        assertTrue(s.other.incomplete)
        assertTrue(s.other.approx)
        assertEquals("сумма всё равно остаток", -100.0, s.other.value, DELTA)
    }

    // ── Порог 0,5 ───────────────────────────────────────────────────────────

    @Test
    fun belowEpsilon_hidden() {
        val s = SummaryOther.compute(
            eq(0), eq(0.4), zero(), zero(),
            false, capital(0, 0.4), zero(), noDebts(0.4), zero()
        )

        assertFalse(s.showOther)
        assertFalse(s.showFxDiff)
    }

    @Test
    fun atEpsilon_shown() {
        val s = SummaryOther.compute(
            eq(0), eq(0.5), zero(), zero(),
            false, capital(0, 0.5), zero(), noDebts(0.5), zero()
        )

        assertTrue(s.showOther)
        assertTrue(s.showFxDiff)
    }

    @Test
    fun flowBelowEpsilon_hiddenButStillInOther() {
        val s = SummaryOther.compute(
            eq(1000), eq(999.7), zero(), zero(),
            true, capital(0, 999.7), eq(-0.3), noDebts(999.7), zero()
        )

        assertFalse(s.showCapitalFlow)
        assertEquals(-0.3, s.other.value, DELTA)
    }

    // ── «Всего с капиталом и долгами» ───────────────────────────────────────

    @Test
    fun total_withoutAssets_equalsOldTotalWithDebts() {
        val d = debts(300, 100, 1150)
        val s = SummaryOther.compute(
            eq(1000), eq(1150), eq(200), eq(50),
            false, capital(0, 1150), zero(), d, zero()
        )

        assertTrue(s.showTotal)
        assertEquals(
            "без активов — прежнее «Всего с долгами»",
            d.totalWithDebts.totalAmountEq!!, s.total.value, DELTA
        )
    }

    @Test
    fun total_withoutDebts_equalsOldNetWorth() {
        val c = capital(400_000, 500_150)
        val s = SummaryOther.compute(
            eq(1000), eq(100_150), eq(200), eq(50),
            true, c, eq(-400_000), noDebts(100_150), zero()
        )

        assertTrue(s.showTotal)
        assertEquals(
            "без долгов — прежнее «Всего с капиталом»",
            c.netWorth.totalAmountEq!!, s.total.value, DELTA
        )
    }

    @Test
    fun total_usesNetWorthNotLiquidOnly() {
        // актив 400 000 включён во «Всего с капиталом»; долги: должны мне 100, я должен 30
        val s = SummaryOther.compute(
            eq(0), eq(0), zero(), zero(),
            true, capital(400_000, 500_000), zero(), debts(100, 30, 100_000), zero()
        )

        assertEquals(500_070.0, s.total.value, DELTA)
    }

    @Test
    fun total_marksIncompleteFromAnyPart() {
        val d = WalletDbLoader.DebtsResult(
            true,
            EquivalentResult(100.0, true, true), zero(), eq(0)
        )
        val s = SummaryOther.compute(
            eq(0), eq(0), zero(), zero(),
            false, capital(0, 0), zero(), d, zero()
        )

        assertTrue(s.total.incomplete)
        assertTrue(s.total.approx)
    }

    companion object {
        private const val DELTA = 0.0001
    }
}
