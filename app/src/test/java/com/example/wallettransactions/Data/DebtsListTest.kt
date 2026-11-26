package com.example.wallettransactions.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** openspec debts, спека «Экран „Долги“»: группировка, порядок, закрытые, просрочка, нет курса. */
class DebtsListTest {

    private val names: MutableMap<String, String> = HashMap()

    init {
        names[VASYA] = "Вася"
        names[PETYA] = "Петя"
    }

    @Test
    fun onePersonTwoCurrencies_shownOnceWithTwoLoans() {
        val g = DebtsList.group(
            listOf(
                loan(1, VASYA, 100.0, 90.0, null), // Вася · USD +100
                loan(2, VASYA, 50.0, 50.0, null)
            ), // Вася · EUR +50
            names, false
        )
        assertEquals(1, g.size)
        assertEquals("Вася", g[0].name)
        assertEquals(2, g[0].loans.size)
        assertEquals(140.0, g[0].totalEq, 1e-9)
        assertEquals("крупнейший заём первым", 1, g[0].loans[0].accountId)
    }

    @Test
    fun counterpartiesOrderedByAbsoluteEq() {
        val g = DebtsList.group(
            listOf(
                loan(1, VASYA, 10.0, 10.0, null),
                loan(2, PETYA, -500.0, -500.0, null)
            ), names, false
        )
        assertEquals("Петя", g[0].name)
        assertEquals("Вася", g[1].name)
    }

    @Test
    fun archivedLoan_hiddenUntilToggle_zeroWithoutArchiveStaysVisible() {
        val loans = listOf(archivedLoan(1, VASYA, 0.0, 0.0), loan(2, PETYA, 5.0, 5.0, null))
        assertEquals(1, DebtsList.group(loans, names, false).size)
        assertEquals(2, DebtsList.group(loans, names, true).size)
        // Нулевой заём без архива (только заведён / разархивирован вручную) виден без переключателя.
        val zero = listOf(loan(3, VASYA, 0.0, 0.0, null))
        assertEquals(1, DebtsList.group(zero, names, false).size)
        assertTrue(zero[0].isClosed())
        assertTrue("копейка ниже точности — закрыт", loan(3, VASYA, 0.004, 0.004, null).isClosed())
    }

    @Test
    fun overdue_onlyPastDueWithBalance() {
        assertTrue(loan(1, VASYA, 100.0, 100.0, "2026-11-01").isOverdue("2026-11-13"))
        assertFalse("сегодня — ещё не просрочен", loan(1, VASYA, 100.0, 100.0, "2026-11-13").isOverdue("2026-11-13"))
        assertFalse("закрытый не просрочен", loan(1, VASYA, 0.0, 0.0, "2026-11-01").isOverdue("2026-11-13"))
        assertFalse("без срока", loan(1, VASYA, 100.0, 100.0, null).isOverdue("2026-11-13"))
    }

    @Test
    fun approx_onlyWhenSomeLoanConverted() {
        val eur = DebtsList.Loan(1, "e", VASYA, 0.0, 0.0, "€", 2, null, "", false)
        assertFalse(DebtsList.group(listOf(eur), names, true)[0].approx)
        val rub = DebtsList.Loan(2, "r", VASYA, 1000.0, 11.2, "₽", 2, null, "", true)
        assertTrue(DebtsList.group(listOf(eur, rub), names, true)[0].approx)
    }

    @Test
    fun noRate_groupIncomplete_amountStillShownInLoanCurrency() {
        val g = DebtsList.group(
            listOf(
                loan(1, VASYA, 100.0, null, null), loan(2, VASYA, 20.0, 20.0, null)
            ), names, false
        )
        assertTrue(g[0].incomplete)
        assertEquals(20.0, g[0].totalEq, 1e-9)
        assertEquals(100.0, g[0].loans[0].balance, 1e-9)
    }

    // ── направление, поиск, «И» (спека debts «Фильтр направления», «Поиск по имени контрагента») ──

    private fun threeLoans(): List<DebtsList.Loan> {
        return listOf(
            loan(1, VASYA, 100.0, 100.0, null), // Вася · USD +100
            loan(2, VASYA, -50.0, -50.0, null), // Вася · EUR −50
            loan(3, PETYA, -30.0, -30.0, null) // Петя · USD −30
        )
    }

    @Test
    fun direction_owedToMe_keepsOnlyPositiveLoans() {
        val g = DebtsList.group(threeLoans(), names, false, DebtsList.Direction.OWED_TO_ME, "")
        assertEquals(1, g.size)
        assertEquals("Вася", g[0].name)
        assertEquals(1, g[0].loans.size)
        assertEquals(1, g[0].loans[0].accountId)
    }

    @Test
    fun direction_iOwe_keepsOnlyNegativeLoans() {
        val g = DebtsList.group(threeLoans(), names, false, DebtsList.Direction.I_OWE, "")
        assertEquals(2, g.size)
        // Вася −50 (заём 2) и Петя −30 (заём 3); положительный заём 1 отсечён.
        assertEquals("Вася", g[0].name)
        assertEquals(2, g[0].loans[0].accountId)
        assertEquals(1, g[0].loans.size)
        assertEquals("Петя", g[1].name)
        assertEquals(3, g[1].loans[0].accountId)
    }

    @Test
    fun direction_zeroBalanceMatchesNeitherDirection_butShownInAll() {
        val loans = listOf(loan(1, VASYA, 0.0, 0.0, null))
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.OWED_TO_ME, "").size)
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.I_OWE, "").size)
        assertEquals(1, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "").size)
    }

    @Test
    fun search_filtersByCounterpartyName_notLoanName() {
        assertEquals(1, DebtsList.group(threeLoans(), names, false, DebtsList.Direction.ALL, "ас").size)
        assertEquals(
            "loan1 имя займа не ищется", 0,
            DebtsList.group(threeLoans(), names, false, DebtsList.Direction.ALL, "loan").size
        )
    }

    @Test
    fun search_yoAndSpecialCharacters() {
        names["cp-alena"] = "Алёна"
        val loans = listOf(loan(9, "cp-alena", 10.0, 10.0, null))
        assertEquals(1, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "алена").size)
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "(").size)
    }

    @Test
    fun searchDirectionArchive_combineWithAnd_counterpartyWithoutMatchingLoansHidden() {
        val loans = listOf(
            archivedLoan(1, VASYA, 100.0, 100.0), // архивный, мне должны
            loan(2, PETYA, -30.0, -30.0, null)
        )
        // Архив выключен, поиск «вас»: все займы Васи в архиве — контрагент не показан.
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "вас").size)
        assertEquals(1, DebtsList.group(loans, names, true, DebtsList.Direction.ALL, "вас").size)
        // Архив включён, но направление «Я должен» Васю отсекает.
        assertEquals(0, DebtsList.group(loans, names, true, DebtsList.Direction.I_OWE, "вас").size)
    }

    // ── карточка контрагента (openspec counterparty-card) ─────────────────────

    @Test
    fun groupFor_includesArchivedLoansAndTotal() {
        val archived = DebtsList.Loan(2, "loan2", VASYA, 50.0, 50.0, "", 2, null, "", false, true)
        val g = DebtsList.groupFor(
            listOf(
                loan(1, VASYA, 100.0, 90.0, null), archived, loan(3, PETYA, 10.0, 10.0, null)
            ), names, VASYA
        )!!
        assertEquals("Вася", g.name)
        assertEquals("архивный заём остаётся в списке карточки", 2, g.loans.size)
        assertEquals("и входит в итог", 140.0, g.totalEq, 1e-9)
    }

    @Test
    fun groupFor_counterpartyWithoutLoans_isNull() {
        assertEquals(null, DebtsList.groupFor(listOf(loan(1, VASYA, 100.0, 90.0, null)), names, PETYA))
    }

    @Test
    fun groupFor_totalMatchesDebtsScreen() {
        val loans = listOf(loan(1, VASYA, 100.0, 90.0, null), loan(2, VASYA, -30.0, -27.0, null))
        assertEquals(
            DebtsList.group(loans, names, false)[0].totalEq,
            DebtsList.groupFor(loans, names, VASYA)!!.totalEq, 1e-9
        )
    }

    @Test
    fun sameCurrencyAsEquivalent_isNeitherApproxNorIncomplete() {
        val eur = DebtsList.Loan(1, "e", VASYA, 5000.0, 5000.0, "€", 2, null, "", false)
        val g = DebtsList.group(listOf(eur), names, true)[0]
        assertFalse(g.approx)
        assertFalse("заём в валюте эквивалента — курс не нужен", g.incomplete)
    }

    @Test
    fun convertedWithoutRate_isIncompleteNotApprox() {
        val rub = DebtsList.Loan(1, "r", VASYA, 1000.0, null, "₽", 2, null, "", true)
        val g = DebtsList.group(listOf(rub), names, true)[0]
        assertTrue("нет курса — итог неполный", g.incomplete)
        assertFalse(g.approx)
    }

    companion object {
        private const val VASYA = "cp-vasya"
        private const val PETYA = "cp-petya"

        private fun loan(id: Int, cp: String, balance: Double, eq: Double?, due: String?): DebtsList.Loan {
            return DebtsList.Loan(id, "loan" + id, cp, balance, eq, "", 2, due, "")
        }

        private fun archivedLoan(id: Int, cp: String, balance: Double, eq: Double?): DebtsList.Loan {
            return DebtsList.Loan(id, "loan" + id, cp, balance, eq, "", 2, null, "", false, true)
        }
    }
}
