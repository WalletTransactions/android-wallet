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

    @Test
    public void closedLoan_hiddenUntilToggle() {
        List<DebtsList.Loan> loans = Arrays.asList(loan(1, VASYA, 0, 0.0, null), loan(2, PETYA, 5, 5.0, null));
        assertEquals(1, DebtsList.group(loans, names, false).size());
        assertEquals(2, DebtsList.group(loans, names, true).size());
        assertTrue(loans.get(0).isClosed());
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
}
