package com.example.wallettransactions.DataItems.Operations

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * openspec capital-assets, задача 3.4: переоценка определяется по системной категории
 * сторон и не входит в итог дня ленты (по образцу {@link OperationItemMovementTest}).
 */
class OperationItemRevaluationTest {

    companion object {
        private var nextId = 1

        private fun op(from: AccountItem, to: AccountItem, amount: Double): OperationItem =
                OperationItem(nextId++, java.util.Date(), from, to, amount, amount, "")
    }

    private val rub = Currency(nextId++, "RUB", "RUB", null, "₽", 2)
    private val card = wallet("Карта")
    private val flat = wallet("Квартира")
    private val reval = AccountItem(nextId++, "Переоценка", "", null, false,
            AccountItem.SystemKind.REVALUATION, AccountItem.Kind.CATEGORY)
    private val food = AccountItem(nextId++, "Продукты", "", null, false,
            AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY)

    private fun wallet(name: String): AccountOperatingItem =
            AccountOperatingItem(nextId++, name, "", null, false, rub,
                    AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET)

    @Test
    fun revaluation_detectedBySystemKind_bothDirections() {
        val up = op(reval, flat, 2_000_000.0)
        val down = op(flat, reval, 150_000.0)
        assertTrue(up.isRevaluation)
        assertTrue(up.isRevaluationUp)
        assertTrue(down.isRevaluation)
        assertFalse(down.isRevaluationUp)
        assertFalse(op(card, food, 500.0).isRevaluation)
    }

    @Test
    fun dayTotal_withExpense500AndRevaluationUp_isMinus500() {
        val day = EquivalentResult(op(card, food, 500.0).movementAmountEq, false)
        day.addAmount(op(reval, flat, 2_000_000.0).movementAmountEq, false)
        assertEquals(-500.0, day.totalAmountEq!!, 0.0001)
    }
}
