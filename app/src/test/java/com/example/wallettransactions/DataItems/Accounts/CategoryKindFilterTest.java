package com.example.wallettransactions.DataItems.Accounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.wallettransactions.DataItems.Accounts.AccountItem.CategoryKind;

import org.junit.Test;

/** openspec category-kind-income-expense, задача 3.1: правило выбора статьи по виду операции. */
public class CategoryKindFilterTest {

    @Test
    public void expenseSlot_showsExpenseAndBoth_hidesIncome() {
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.EXPENSE, CategoryKind.EXPENSE, false));
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.BOTH, CategoryKind.EXPENSE, false));
        assertFalse(CategoryKindFilter.isVisible(CategoryKind.INCOME, CategoryKind.EXPENSE, false));
    }

    @Test
    public void incomeSlot_showsIncomeAndBoth_hidesExpense() {
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.INCOME, CategoryKind.INCOME, false));
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.BOTH, CategoryKind.INCOME, false));
        assertFalse(CategoryKindFilter.isVisible(CategoryKind.EXPENSE, CategoryKind.INCOME, false));
    }

    @Test
    public void selectedOrPriorityCategory_staysEvenWithWrongKind() {
        // Правка расхода по «Зарплате» (вид «Доход») или кандидат ИИ — остаются в списке.
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.INCOME, CategoryKind.EXPENSE, true));
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.EXPENSE, CategoryKind.INCOME, true));
    }

    @Test
    public void noOperationKind_meansNoFilter() {
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.EXPENSE, null, false));
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.INCOME, null, false));
        assertTrue(CategoryKindFilter.isVisible(CategoryKind.INCOME, CategoryKind.BOTH, false));
    }

    @Test
    public void nullCategoryKind_isTreatedAsBoth() {
        assertTrue(CategoryKindFilter.isVisible(null, CategoryKind.EXPENSE, false));
        assertTrue(CategoryKindFilter.isVisible(null, CategoryKind.INCOME, false));
    }

    @Test
    public void operationKindFromExtra() {
        assertEquals(CategoryKind.EXPENSE, CategoryKindFilter.operationKindFromExtra(0));
        assertEquals(CategoryKind.INCOME, CategoryKindFilter.operationKindFromExtra(1));
        assertNull(CategoryKindFilter.operationKindFromExtra(CategoryKindFilter.NO_FILTER));
        assertNull(CategoryKindFilter.operationKindFromExtra(2));
        assertNull(CategoryKindFilter.operationKindFromExtra(9));
    }
}
