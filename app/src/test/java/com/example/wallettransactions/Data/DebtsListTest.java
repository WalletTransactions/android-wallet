package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** openspec debts, спека «Экран „Долги“»: группировка, порядок, закрытые, просрочка, нет курса. */
public class DebtsListTest {

    private static final String VASYA = "cp-vasya";
    private static final String PETYA = "cp-petya";
    private final Map<String, String> names = new HashMap<>();

    {
        names.put(VASYA, "Вася");
        names.put(PETYA, "Петя");
    }

    private static DebtsList.Loan loan(int id, String cp, double balance, Double eq, String due) {
        return new DebtsList.Loan(id, "loan" + id, cp, balance, eq, "", 2, due, "");
    }

    @Test
    public void onePersonTwoCurrencies_shownOnceWithTwoLoans() {
        List<DebtsList.Group> g = DebtsList.group(Arrays.asList(
                loan(1, VASYA, 100, 90.0, null),     // Вася · USD +100
                loan(2, VASYA, 50, 50.0, null)),     // Вася · EUR +50
                names, false);
        assertEquals(1, g.size());
        assertEquals("Вася", g.get(0).name);
        assertEquals(2, g.get(0).loans.size());
        assertEquals(140, g.get(0).totalEq, 1e-9);
        assertEquals("крупнейший заём первым", 1, g.get(0).loans.get(0).accountId);
    }

    @Test
    public void counterpartiesOrderedByAbsoluteEq() {
        List<DebtsList.Group> g = DebtsList.group(Arrays.asList(
                loan(1, VASYA, 10, 10.0, null),
                loan(2, PETYA, -500, -500.0, null)), names, false);
        assertEquals("Петя", g.get(0).name);
        assertEquals("Вася", g.get(1).name);
    }

    private static DebtsList.Loan archivedLoan(int id, String cp, double balance, Double eq) {
        return new DebtsList.Loan(id, "loan" + id, cp, balance, eq, "", 2, null, "", false, true);
    }

    @Test
    public void archivedLoan_hiddenUntilToggle_zeroWithoutArchiveStaysVisible() {
        List<DebtsList.Loan> loans = Arrays.asList(archivedLoan(1, VASYA, 0, 0.0), loan(2, PETYA, 5, 5.0, null));
        assertEquals(1, DebtsList.group(loans, names, false).size());
        assertEquals(2, DebtsList.group(loans, names, true).size());
        // Нулевой заём без архива (только заведён / разархивирован вручную) виден без переключателя.
        List<DebtsList.Loan> zero = Arrays.asList(loan(3, VASYA, 0, 0.0, null));
        assertEquals(1, DebtsList.group(zero, names, false).size());
        assertTrue(zero.get(0).isClosed());
assertTrue("копейка ниже точности — закрыт", loan(3, VASYA, 0.004, 0.004, null).isClosed());
    }

    @Test
    public void overdue_onlyPastDueWithBalance() {
        assertTrue(loan(1, VASYA, 100, 100.0, "2026-11-01").isOverdue("2026-11-13"));
        assertFalse("сегодня — ещё не просрочен", loan(1, VASYA, 100, 100.0, "2026-11-13").isOverdue("2026-11-13"));
        assertFalse("закрытый не просрочен", loan(1, VASYA, 0, 0.0, "2026-11-01").isOverdue("2026-11-13"));
        assertFalse("без срока", loan(1, VASYA, 100, 100.0, null).isOverdue("2026-11-13"));
    }

    @Test
    public void approx_onlyWhenSomeLoanConverted() {
        DebtsList.Loan eur = new DebtsList.Loan(1, "e", VASYA, 0, 0.0, "€", 2, null, "", false);
        assertFalse(DebtsList.group(Arrays.asList(eur), names, true).get(0).approx);
        DebtsList.Loan rub = new DebtsList.Loan(2, "r", VASYA, 1000, 11.2, "₽", 2, null, "", true);
        assertTrue(DebtsList.group(Arrays.asList(eur, rub), names, true).get(0).approx);
    }

    @Test
    public void noRate_groupIncomplete_amountStillShownInLoanCurrency() {
        List<DebtsList.Group> g = DebtsList.group(Arrays.asList(
                loan(1, VASYA, 100, null, null), loan(2, VASYA, 20, 20.0, null)), names, false);
        assertTrue(g.get(0).incomplete);
        assertEquals(20, g.get(0).totalEq, 1e-9);
        assertEquals(100, g.get(0).loans.get(0).balance, 1e-9);
    }

    // ── направление, поиск, «И» (спека debts «Фильтр направления», «Поиск по имени контрагента») ──

    private List<DebtsList.Loan> threeLoans() {
        return Arrays.asList(
                loan(1, VASYA, 100, 100.0, null),   // Вася · USD +100
                loan(2, VASYA, -50, -50.0, null),   // Вася · EUR −50
                loan(3, PETYA, -30, -30.0, null));  // Петя · USD −30
    }

    @Test
    public void direction_owedToMe_keepsOnlyPositiveLoans() {
        List<DebtsList.Group> g = DebtsList.group(threeLoans(), names, false, DebtsList.Direction.OWED_TO_ME, "");
        assertEquals(1, g.size());
        assertEquals("Вася", g.get(0).name);
        assertEquals(1, g.get(0).loans.size());
        assertEquals(1, g.get(0).loans.get(0).accountId);
    }

    @Test
    public void direction_iOwe_keepsOnlyNegativeLoans() {
        List<DebtsList.Group> g = DebtsList.group(threeLoans(), names, false, DebtsList.Direction.I_OWE, "");
        assertEquals(2, g.size());
        // Вася −50 (заём 2) и Петя −30 (заём 3); положительный заём 1 отсечён.
        assertEquals("Вася", g.get(0).name);
        assertEquals(2, g.get(0).loans.get(0).accountId);
        assertEquals(1, g.get(0).loans.size());
        assertEquals("Петя", g.get(1).name);
        assertEquals(3, g.get(1).loans.get(0).accountId);
    }

    @Test
    public void direction_zeroBalanceMatchesNeitherDirection_butShownInAll() {
        List<DebtsList.Loan> loans = Arrays.asList(loan(1, VASYA, 0, 0.0, null));
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.OWED_TO_ME, "").size());
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.I_OWE, "").size());
        assertEquals(1, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "").size());
    }

    @Test
    public void search_filtersByCounterpartyName_notLoanName() {
        assertEquals(1, DebtsList.group(threeLoans(), names, false, DebtsList.Direction.ALL, "ас").size());
        assertEquals("loan1 имя займа не ищется", 0,
                DebtsList.group(threeLoans(), names, false, DebtsList.Direction.ALL, "loan").size());
    }

    @Test
    public void search_yoAndSpecialCharacters() {
        names.put("cp-alena", "Алёна");
        List<DebtsList.Loan> loans = Arrays.asList(loan(9, "cp-alena", 10, 10.0, null));
        assertEquals(1, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "алена").size());
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "(").size());
    }

    @Test
    public void searchDirectionArchive_combineWithAnd_counterpartyWithoutMatchingLoansHidden() {
        List<DebtsList.Loan> loans = Arrays.asList(
                archivedLoan(1, VASYA, 100, 100.0),    // архивный, мне должны
                loan(2, PETYA, -30, -30.0, null));
        // Архив выключен, поиск «вас»: все займы Васи в архиве — контрагент не показан.
        assertEquals(0, DebtsList.group(loans, names, false, DebtsList.Direction.ALL, "вас").size());
        assertEquals(1, DebtsList.group(loans, names, true, DebtsList.Direction.ALL, "вас").size());
        // Архив включён, но направление «Я должен» Васю отсекает.
        assertEquals(0, DebtsList.group(loans, names, true, DebtsList.Direction.I_OWE, "вас").size());
    }
}
