package com.example.wallettransactions.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Составные курсы (openspec currency-conversion-routes, spec currency-routes): чистая функция
 * {@link DerivedRates#compute} без БД. Те же сценарии, что на сервере (wallet-api, {@code rate_on}).
 */
class DerivedRatesTest {

    private lateinit var input: DerivedRates.Input

    @Before
    fun setUp() {
        input = DerivedRates.Input()
        input.currencies[BTC] = "BTC"
        input.currencies[EUR] = "EUR"
        input.currencies[RUB] = "RUB"
        input.currencies[USD] = "USD"
        input.currencies[GBP] = "GBP"
        input.equivalents.add(RUB) // эквивалент — рубли
    }

    private fun pair(id: Long, base: Long, quote: Long) {
        input.pairs.add(DerivedRates.Pair(id, base, quote))
    }

    private fun rate(pairId: Long, date: String, rate: Double) {
        input.rates.add(DerivedRates.PairRate(pairId, date, rate))
    }

    private fun step(from: Long, to: Long, num: Int, pairId: Long, reverse: Boolean) {
        input.steps.add(DerivedRates.Step(from, to, num, pairId, reverse))
    }

    /** Последний курс from→to не позже даты; null — строк нет. */
    private fun rateOn(rows: List<DerivedRates.Row>, from: Long, to: Long, date: String): Double? {
        var bestDate: String? = null
        var best: Double? = null
        for (r in rows) {
            if (r.fromId == from && r.toId == to && r.date.compareTo(date) <= 0
                && (bestDate == null || r.date.compareTo(bestDate) > 0)
            ) {
                bestDate = r.date
                best = r.rate
            }
        }
        return best
    }

    @Test
    fun explicitRoute_twoSteps_productAndReverseDirection() {
        pair(11, BTC, EUR); pair(12, EUR, RUB)
        rate(11, "2026-11-01", 90000.0); rate(12, "2026-11-01", 100.0)
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false)
        val rows = DerivedRates.compute(input)
        near(9000000.0, rateOn(rows, BTC, RUB, "2026-11-05"))
        near(1.0 / 9000000, rateOn(rows, RUB, BTC, "2026-11-05"))
    }

    @Test
    fun explicitRoute_stepOverReversedPair_usesInverseRate() {
        pair(11, BTC, EUR); pair(16, RUB, EUR) // RUBEUR: шаг EUR→RUB по обратной паре
        rate(11, "2026-11-01", 90000.0); rate(16, "2026-11-01", 0.01)
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 16, true)
        near(9000000.0, rateOn(DerivedRates.compute(input), BTC, RUB, "2026-11-05"))
    }

    @Test
    fun explicitRoute_threeSteps() {
        pair(13, BTC, USD); pair(17, USD, EUR); pair(12, EUR, RUB)
        rate(13, "2026-11-01", 60000.0); rate(17, "2026-11-01", 0.9); rate(12, "2026-11-01", 100.0)
        step(BTC, RUB, 1, 13, false); step(BTC, RUB, 2, 17, false); step(BTC, RUB, 3, 12, false)
        near(5400000.0, rateOn(DerivedRates.compute(input), BTC, RUB, "2026-11-05"))
    }

    @Test
    fun explicitRoute_stepsTakeLatestRateIndependentlyByDate() {
        pair(11, BTC, EUR); pair(12, EUR, RUB)
        rate(11, "2026-11-01", 90000.0); rate(12, "2026-11-10", 100.0)
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false)
        var rows = DerivedRates.compute(input)
        assertNull("у шага 2 ещё нет курса", rateOn(rows, BTC, RUB, "2026-11-05"))
        near(9000000.0, rateOn(rows, BTC, RUB, "2026-11-12"))
        rate(11, "2026-11-15", 100000.0)
        rows = DerivedRates.compute(input)
        near(10000000.0, rateOn(rows, BTC, RUB, "2026-11-15"))
        near(9000000.0, rateOn(rows, BTC, RUB, "2026-11-14"))
    }

    @Test
    fun explicitRoute_unfinishedOrDisjointChain_givesNoRate() {
        pair(11, BTC, EUR); pair(12, EUR, RUB); pair(18, GBP, RUB)
        rate(11, "2026-11-01", 2.0); rate(12, "2026-11-01", 2.0); rate(18, "2026-11-01", 2.0)
        step(BTC, RUB, 1, 11, false) // недостроенная: шага 2 нет
        assertTrue(DerivedRates.compute(input).isEmpty())
        step(BTC, RUB, 2, 18, false) // шаг 2 не стыкуется (начало GBP, а не EUR)
        assertTrue(DerivedRates.compute(input).isEmpty())
    }

    @Test
    fun explicitRoute_brokenByDeletedPair_givesNoRate() {
        pair(11, BTC, EUR) // пары EURRUB (12) нет вовсе — удалена
        rate(11, "2026-11-01", 90000.0)
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false)
        assertTrue(DerivedRates.compute(input).isEmpty())
    }

    @Test
    fun directPair_beatsRouteAtAnyDate() {
        pair(11, BTC, EUR); pair(12, EUR, RUB); pair(15, BTC, RUB)
        rate(11, "2026-09-01", 90000.0); rate(12, "2026-09-01", 100.0)
        rate(15, "2026-11-01", 8000000.0) // прямой курс появился позже
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false)
        assertTrue("прямой курс есть — составного нет на любую дату", DerivedRates.compute(input).isEmpty())
    }

    @Test
    fun usdCross_bothDirections_andIndependentDates() {
        pair(13, BTC, USD); pair(14, USD, RUB)
        rate(13, "2026-11-01", 60000.0); rate(14, "2026-11-10", 90.0)
        val rows = DerivedRates.compute(input)
        assertNull(rateOn(rows, BTC, RUB, "2026-11-05"))
        near(5400000.0, rateOn(rows, BTC, RUB, "2026-11-12"))
        near(1.0 / 5400000, rateOn(rows, RUB, BTC, "2026-11-12"))
        rate(13, "2026-11-15", 70000.0)
        near(6300000.0, rateOn(DerivedRates.compute(input), BTC, RUB, "2026-11-15"))
    }

    @Test
    fun usdCross_stepsOverReversedPairs() {
        pair(19, EUR, USD); pair(20, RUB, USD) // EURUSD 1.1, RUBUSD 0.011
        rate(19, "2026-11-01", 1.1); rate(20, "2026-11-01", 0.011)
        near(100.0, rateOn(DerivedRates.compute(input), EUR, RUB, "2026-11-05"))
    }

    @Test
    fun usdCross_needsBothLegs_andNotForUsdItself() {
        pair(13, BTC, USD)
        rate(13, "2026-11-01", 60000.0)
        assertTrue("нет USD→RUB", DerivedRates.compute(input).isEmpty())
        input.pairs.clear(); input.rates.clear()
        pair(14, USD, RUB); rate(14, "2026-11-01", 90.0)
        val rows = DerivedRates.compute(input)
        assertNull("BTC→USD прямой пары нет, USD сам не кросс-валюта", rateOn(rows, BTC, USD, "2026-11-05"))
    }

    @Test
    fun usdCross_blockedByValidRouteWithoutRate_butRouteWinsWhenPriced() {
        pair(11, BTC, EUR); pair(12, EUR, RUB); pair(13, BTC, USD); pair(14, USD, RUB)
        rate(11, "2026-11-01", 90000.0) // у шага 2 курса нет
        rate(13, "2026-11-01", 60000.0); rate(14, "2026-11-01", 90.0)
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false)
        assertNull("валидный маршрут без курса блокирует USD", rateOn(DerivedRates.compute(input), BTC, RUB, "2026-11-05"))
        rate(12, "2026-11-01", 100.0)
        near(9000000.0, rateOn(DerivedRates.compute(input), BTC, RUB, "2026-11-05")) // через EUR, не через USD
    }

    @Test
    fun rowsOnlyForEquivalentCurrencies() {
        pair(13, BTC, USD); pair(14, USD, RUB); pair(21, USD, EUR)
        rate(13, "2026-11-01", 60000.0); rate(14, "2026-11-01", 90.0); rate(21, "2026-11-01", 0.9)
        val rows = DerivedRates.compute(input)
        // EUR не эквивалент: курса BTC→EUR в кэше нет, только в/из рублей
        assertNull(rateOn(rows, BTC, EUR, "2026-11-05"))
        assertNotNull(rateOn(rows, BTC, RUB, "2026-11-05"))
        input.equivalents.add(EUR)
        assertNotNull(rateOn(DerivedRates.compute(input), BTC, EUR, "2026-11-05"))
    }

    @Test
    fun rowDatesAreCalendarDays_evenIfRateCarriesTime() {
        pair(13, BTC, USD); pair(14, USD, RUB)
        rate(13, "2026-11-01 10:30", 60000.0); rate(14, "2026-11-01", 90.0)
        val rows = DerivedRates.compute(input)
        for (r in rows) assertEquals(10, r.date.length)
    }

    companion object {
        private const val BTC = 1L
        private const val EUR = 2L
        private const val RUB = 3L
        private const val USD = 4L
        private const val GBP = 5L

        private fun near(expected: Double, actual: Double?) {
            assertNotNull("нет курса", actual)
            assertEquals(expected, actual!!, Math.abs(expected) * 1e-9)
        }
    }
}
