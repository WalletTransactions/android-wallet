package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Составные курсы (openspec currency-conversion-routes, spec currency-routes): чистая функция
 * {@link DerivedRates#compute} без БД. Те же сценарии, что на сервере (wallet-api, {@code rate_on}).
 */
public class DerivedRatesTest {

    private static final long BTC = 1, EUR = 2, RUB = 3, USD = 4, GBP = 5;

    private DerivedRates.Input in;

    @Before
    public void setUp() {
        in = new DerivedRates.Input();
        in.currencies.put(BTC, "BTC");
        in.currencies.put(EUR, "EUR");
        in.currencies.put(RUB, "RUB");
        in.currencies.put(USD, "USD");
        in.currencies.put(GBP, "GBP");
        in.equivalents.add(RUB); // эквивалент — рубли
    }

    private void pair(long id, long base, long quote) {
        in.pairs.add(new DerivedRates.Pair(id, base, quote));
    }

    private void rate(long pairId, String date, double rate) {
        in.rates.add(new DerivedRates.PairRate(pairId, date, rate));
    }

    private void step(long from, long to, int num, long pairId, boolean reverse) {
        in.steps.add(new DerivedRates.Step(from, to, num, pairId, reverse));
    }

    /** Последний курс from→to не позже даты; null — строк нет. */
    private Double rateOn(List<DerivedRates.Row> rows, long from, long to, String date) {
        String bestDate = null;
        Double best = null;
        for (DerivedRates.Row r : rows) {
            if (r.fromId == from && r.toId == to && r.date.compareTo(date) <= 0
                    && (bestDate == null || r.date.compareTo(bestDate) > 0)) {
                bestDate = r.date;
                best = r.rate;
            }
        }
        return best;
    }

    private static void near(double expected, Double actual) {
        assertNotNull("нет курса", actual);
        assertEquals(expected, actual, Math.abs(expected) * 1e-9);
    }

    @Test
    public void explicitRoute_twoSteps_productAndReverseDirection() {
        pair(11, BTC, EUR); pair(12, EUR, RUB);
        rate(11, "2026-11-01", 90000); rate(12, "2026-11-01", 100);
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false);
        List<DerivedRates.Row> rows = DerivedRates.compute(in);
        near(9000000, rateOn(rows, BTC, RUB, "2026-11-05"));
        near(1.0 / 9000000, rateOn(rows, RUB, BTC, "2026-11-05"));
    }

    @Test
    public void explicitRoute_stepOverReversedPair_usesInverseRate() {
        pair(11, BTC, EUR); pair(16, RUB, EUR); // RUBEUR: шаг EUR→RUB по обратной паре
        rate(11, "2026-11-01", 90000); rate(16, "2026-11-01", 0.01);
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 16, true);
        near(9000000, rateOn(DerivedRates.compute(in), BTC, RUB, "2026-11-05"));
    }

    @Test
    public void explicitRoute_threeSteps() {
        pair(13, BTC, USD); pair(17, USD, EUR); pair(12, EUR, RUB);
        rate(13, "2026-11-01", 60000); rate(17, "2026-11-01", 0.9); rate(12, "2026-11-01", 100);
        step(BTC, RUB, 1, 13, false); step(BTC, RUB, 2, 17, false); step(BTC, RUB, 3, 12, false);
        near(5400000, rateOn(DerivedRates.compute(in), BTC, RUB, "2026-11-05"));
    }

    @Test
    public void explicitRoute_stepsTakeLatestRateIndependentlyByDate() {
        pair(11, BTC, EUR); pair(12, EUR, RUB);
        rate(11, "2026-11-01", 90000); rate(12, "2026-11-10", 100);
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false);
        List<DerivedRates.Row> rows = DerivedRates.compute(in);
        assertNull("у шага 2 ещё нет курса", rateOn(rows, BTC, RUB, "2026-11-05"));
        near(9000000, rateOn(rows, BTC, RUB, "2026-11-12"));
        rate(11, "2026-11-15", 100000);
        rows = DerivedRates.compute(in);
        near(10000000, rateOn(rows, BTC, RUB, "2026-11-15"));
        near(9000000, rateOn(rows, BTC, RUB, "2026-11-14"));
    }

    @Test
    public void explicitRoute_unfinishedOrDisjointChain_givesNoRate() {
        pair(11, BTC, EUR); pair(12, EUR, RUB); pair(18, GBP, RUB);
        rate(11, "2026-11-01", 2); rate(12, "2026-11-01", 2); rate(18, "2026-11-01", 2);
        step(BTC, RUB, 1, 11, false); // недостроенная: шага 2 нет
        assertTrue(DerivedRates.compute(in).isEmpty());
        step(BTC, RUB, 2, 18, false); // шаг 2 не стыкуется (начало GBP, а не EUR)
        assertTrue(DerivedRates.compute(in).isEmpty());
    }

    @Test
    public void explicitRoute_brokenByDeletedPair_givesNoRate() {
        pair(11, BTC, EUR); // пары EURRUB (12) нет вовсе — удалена
        rate(11, "2026-11-01", 90000);
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false);
        assertTrue(DerivedRates.compute(in).isEmpty());
    }

    @Test
    public void directPair_beatsRouteAtAnyDate() {
        pair(11, BTC, EUR); pair(12, EUR, RUB); pair(15, BTC, RUB);
        rate(11, "2026-09-01", 90000); rate(12, "2026-09-01", 100);
        rate(15, "2026-11-01", 8000000); // прямой курс появился позже
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false);
        assertTrue("прямой курс есть — составного нет на любую дату", DerivedRates.compute(in).isEmpty());
    }

    @Test
    public void usdCross_bothDirections_andIndependentDates() {
        pair(13, BTC, USD); pair(14, USD, RUB);
        rate(13, "2026-11-01", 60000); rate(14, "2026-11-10", 90);
        List<DerivedRates.Row> rows = DerivedRates.compute(in);
        assertNull(rateOn(rows, BTC, RUB, "2026-11-05"));
        near(5400000, rateOn(rows, BTC, RUB, "2026-11-12"));
        near(1.0 / 5400000, rateOn(rows, RUB, BTC, "2026-11-12"));
        rate(13, "2026-11-15", 70000);
        near(6300000, rateOn(DerivedRates.compute(in), BTC, RUB, "2026-11-15"));
    }

    @Test
    public void usdCross_stepsOverReversedPairs() {
        pair(19, EUR, USD); pair(20, RUB, USD); // EURUSD 1.1, RUBUSD 0.011
        rate(19, "2026-11-01", 1.1); rate(20, "2026-11-01", 0.011);
        near(100, rateOn(DerivedRates.compute(in), EUR, RUB, "2026-11-05"));
    }

    @Test
    public void usdCross_needsBothLegs_andNotForUsdItself() {
        pair(13, BTC, USD);
        rate(13, "2026-11-01", 60000);
        assertTrue("нет USD→RUB", DerivedRates.compute(in).isEmpty());
        in.pairs.clear(); in.rates.clear();
        pair(14, USD, RUB); rate(14, "2026-11-01", 90);
        List<DerivedRates.Row> rows = DerivedRates.compute(in);
        assertNull("BTC→USD прямой пары нет, USD сам не кросс-валюта", rateOn(rows, BTC, USD, "2026-11-05"));
    }

    @Test
    public void usdCross_blockedByValidRouteWithoutRate_butRouteWinsWhenPriced() {
        pair(11, BTC, EUR); pair(12, EUR, RUB); pair(13, BTC, USD); pair(14, USD, RUB);
        rate(11, "2026-11-01", 90000);          // у шага 2 курса нет
        rate(13, "2026-11-01", 60000); rate(14, "2026-11-01", 90);
        step(BTC, RUB, 1, 11, false); step(BTC, RUB, 2, 12, false);
        assertNull("валидный маршрут без курса блокирует USD", rateOn(DerivedRates.compute(in), BTC, RUB, "2026-11-05"));
        rate(12, "2026-11-01", 100);
        near(9000000, rateOn(DerivedRates.compute(in), BTC, RUB, "2026-11-05")); // через EUR, не через USD
    }

    @Test
    public void rowsOnlyForEquivalentCurrencies() {
        pair(13, BTC, USD); pair(14, USD, RUB); pair(21, USD, EUR);
        rate(13, "2026-11-01", 60000); rate(14, "2026-11-01", 90); rate(21, "2026-11-01", 0.9);
        List<DerivedRates.Row> rows = DerivedRates.compute(in);
        // EUR не эквивалент: курса BTC→EUR в кэше нет, только в/из рублей
        assertNull(rateOn(rows, BTC, EUR, "2026-11-05"));
        assertNotNull(rateOn(rows, BTC, RUB, "2026-11-05"));
        in.equivalents.add(EUR);
        assertNotNull(rateOn(DerivedRates.compute(in), BTC, EUR, "2026-11-05"));
    }

    @Test
    public void rowDatesAreCalendarDays_evenIfRateCarriesTime() {
        pair(13, BTC, USD); pair(14, USD, RUB);
        rate(13, "2026-11-01 10:30", 60000); rate(14, "2026-11-01", 90);
        List<DerivedRates.Row> rows = DerivedRates.compute(in);
        for (DerivedRates.Row r : rows) assertEquals(10, r.date.length());
    }
}
