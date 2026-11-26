package com.example.wallettransactions.Data

import org.junit.Assert.assertEquals
import org.junit.Test

/** openspec debts, спека «Подпись операции с долговым кошельком». */
class DebtLabelsTest {

    private fun p(id: Int, date: String, delta: Double): DebtLabels.Posting {
        return DebtLabels.Posting(id, date, 7, delta)
    }

    @Test
    fun lendThenRepaid_gaveThenReturned() {
        val m = DebtLabels.compute(
            listOf(
                p(1, "2026-01-10 10:00", 100.0), p(2, "2026-01-20 10:00", -40.0)
            )
        )
        assertEquals(DebtLabels.Label.GAVE, m[1])
        assertEquals(DebtLabels.Label.RETURNED, m[2])
    }

    @Test
    fun borrowFromZero_tookThenRepaid() {
        val m = DebtLabels.compute(
            listOf(
                p(1, "2026-01-10 10:00", -300.0), p(2, "2026-02-10 10:00", 100.0)
            )
        )
        assertEquals("первый «взял» — с нуля", DebtLabels.Label.TOOK, m[1])
        assertEquals(DebtLabels.Label.REPAID, m[2])
    }

    @Test
    fun backdatedEdit_relabelsByNewOrder() {
        // Выдачу перенесли позже возврата: теперь первым идёт перевод с займа при нуле.
        val m = DebtLabels.compute(
            listOf(
                p(1, "2026-01-25 10:00", 100.0), p(2, "2026-01-20 10:00", -40.0)
            )
        )
        assertEquals(DebtLabels.Label.TOOK, m[2])
        assertEquals(DebtLabels.Label.REPAID, m[1])
    }

    @Test
    fun loansCountedSeparately() {
        val m = DebtLabels.compute(
            listOf(
                DebtLabels.Posting(1, "2026-01-10", 7, 100.0),
                DebtLabels.Posting(2, "2026-01-11", 8, -50.0)
            )
        )
        assertEquals(DebtLabels.Label.GAVE, m[1])
        assertEquals("у другого займа свой остаток", DebtLabels.Label.TOOK, m[2])
    }

    @Test
    fun sameDate_orderedById() {
        val m = DebtLabels.compute(
            listOf(
                p(5, "2026-01-10 10:00", -100.0), p(4, "2026-01-10 10:00", 100.0)
            )
        )
        assertEquals(DebtLabels.Label.GAVE, m[4])
        assertEquals(DebtLabels.Label.RETURNED, m[5])
    }
}
