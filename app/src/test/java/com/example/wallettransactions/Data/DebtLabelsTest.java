package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

/** openspec debts, спека «Подпись операции с долговым кошельком». */
public class DebtLabelsTest {

    private static DebtLabels.Posting p(int id, String date, double delta) {
        return new DebtLabels.Posting(id, date, 7, delta);
    }

    @Test
    public void lendThenRepaid_gaveThenReturned() {
        Map<Integer, DebtLabels.Label> m = DebtLabels.compute(Arrays.asList(
                p(1, "2026-01-10 10:00", 100), p(2, "2026-01-20 10:00", -40)));
        assertEquals(DebtLabels.Label.GAVE, m.get(1));
        assertEquals(DebtLabels.Label.RETURNED, m.get(2));
    }

    @Test
    public void borrowFromZero_tookThenRepaid() {
        Map<Integer, DebtLabels.Label> m = DebtLabels.compute(Arrays.asList(
                p(1, "2026-01-10 10:00", -300), p(2, "2026-02-10 10:00", 100)));
        assertEquals("первый «взял» — с нуля", DebtLabels.Label.TOOK, m.get(1));
        assertEquals(DebtLabels.Label.REPAID, m.get(2));
    }

    @Test
    public void backdatedEdit_relabelsByNewOrder() {
        // Выдачу перенесли позже возврата: теперь первым идёт перевод с займа при нуле.
        Map<Integer, DebtLabels.Label> m = DebtLabels.compute(Arrays.asList(
                p(1, "2026-01-25 10:00", 100), p(2, "2026-01-20 10:00", -40)));
        assertEquals(DebtLabels.Label.TOOK, m.get(2));
        assertEquals(DebtLabels.Label.REPAID, m.get(1));
    }

    @Test
    public void loansCountedSeparately() {
        Map<Integer, DebtLabels.Label> m = DebtLabels.compute(Arrays.asList(
                new DebtLabels.Posting(1, "2026-01-10", 7, 100),
                new DebtLabels.Posting(2, "2026-01-11", 8, -50)));
        assertEquals(DebtLabels.Label.GAVE, m.get(1));
        assertEquals("у другого займа свой остаток", DebtLabels.Label.TOOK, m.get(2));
    }

    @Test
    public void sameDate_orderedById() {
        Map<Integer, DebtLabels.Label> m = DebtLabels.compute(Arrays.asList(
                p(5, "2026-01-10 10:00", -100), p(4, "2026-01-10 10:00", 100)));
        assertEquals(DebtLabels.Label.GAVE, m.get(4));
        assertEquals(DebtLabels.Label.RETURNED, m.get(5));
    }
}
