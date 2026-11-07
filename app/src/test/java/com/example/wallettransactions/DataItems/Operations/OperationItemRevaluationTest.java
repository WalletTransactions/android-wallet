package com.example.wallettransactions.DataItems.Operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;

import org.junit.Test;

/**
 * openspec capital-assets, задача 3.4: переоценка определяется по системной категории
 * сторон и не входит в итог дня ленты (по образцу {@link OperationItemMovementTest}).
 */
public class OperationItemRevaluationTest {

    private static int nextId = 1;
    private final Currency rub = new Currency(nextId++, "RUB", "RUB", null, "₽", 2);
    private final AccountOperatingItem card = wallet("Карта");
    private final AccountOperatingItem flat = wallet("Квартира");
    private final AccountItem reval = new AccountItem(nextId++, "Переоценка", "", null, false,
            AccountItem.SystemKind.REVALUATION, AccountItem.Kind.CATEGORY);
    private final AccountItem food = new AccountItem(nextId++, "Продукты", "", null, false,
            AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY);

    private AccountOperatingItem wallet(String name) {
        return new AccountOperatingItem(nextId++, name, "", null, false, rub,
                AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET);
    }

    private static OperationItem op(AccountItem from, AccountItem to, double amount) {
        return new OperationItem(nextId++, new java.util.Date(), from, to, amount, amount, "");
    }

    @Test
    public void revaluation_detectedBySystemKind_bothDirections() {
        OperationItem up = op(reval, flat, 2_000_000);
        OperationItem down = op(flat, reval, 150_000);
        assertTrue(up.isRevaluation());
        assertTrue(up.isRevaluationUp());
        assertTrue(down.isRevaluation());
        assertFalse(down.isRevaluationUp());
        assertFalse(op(card, food, 500).isRevaluation());
    }

    @Test
    public void dayTotal_withExpense500AndRevaluationUp_isMinus500() {
        EquivalentResult day = new EquivalentResult(op(card, food, 500).getMovementAmountEq(), false);
        day.addAmount(op(reval, flat, 2_000_000).getMovementAmountEq(), false);
        assertEquals(-500, day.getTotalAmountEq(), 0.0001);
    }
}
