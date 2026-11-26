package com.example.wallettransactions.DataItems

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * openspec similar-search / operations-filter: счета фильтра делятся на кошельки и
 * категории; внутри группы «ИЛИ», группы между собой — «И».
 */
class DataFilterTest {

    private val FIELDS = arrayOf("acc_fr_id", "acc_to_id")

    private fun wallet(id: Int): AccountItem = AccountItem(id, "w$id", "", null, false,
            AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET)

    private fun category(id: Int): AccountItem = AccountItem(id, "c$id", "", null, false,
            AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY)

    /** Количество вхождений подстроки — по ним видно, сколько групп собралось. */
    private fun count(text: String, part: String): Int {
        var n = 0
        var i = text.indexOf(part)
        while (i >= 0) {
            ++n
            i = text.indexOf(part, i + 1)
        }
        return n
    }

    @Test
    fun emptyFilter_hasNoAccountCondition() {
        assertEquals("", DataFilter().getSQLFilter(FIELDS))
    }

    @Test
    fun categoryAndWallet_areTwoAndedGroups() {
        val filter = DataFilter()
        filter.addAccount(category(5))
        filter.addAccount(wallet(2))

        val sql = filter.getSQLFilter(FIELDS)

        // Две группы подряд через AND: в каждой по OR между from и to.
        assertEquals(2, count(sql, " AND ("))
        assertTrue(sql.contains("(acc_fr_id in (2))"))
        assertTrue(sql.contains("(acc_to_id in (2))"))
        assertTrue(sql.contains("(acc_fr_id in (5))"))
        assertTrue(sql.contains("(acc_to_id in (5))"))
        assertEquals(2, count(sql, " OR "))
        // Кошелёк и категория не смешаны в один список.
        assertFalse(sql.contains("in (2,5)"))
        assertFalse(sql.contains("in (5,2)"))
    }

    @Test
    fun twoCategories_areOneOrGroup() {
        val filter = DataFilter()
        filter.addAccount(category(5))
        filter.addAccount(category(7))

        val sql = filter.getSQLFilter(FIELDS)

        assertEquals(1, count(sql, " AND ("))
        assertTrue(sql.contains("(acc_fr_id in (5,7))"))
        assertTrue(sql.contains("(acc_to_id in (5,7))"))
    }

    @Test
    fun onlyWallets_areOneOrGroup() {
        val filter = DataFilter()
        filter.addAccount(wallet(1))
        filter.addAccount(wallet(2))

        val sql = filter.getSQLFilter(FIELDS)

        assertEquals(1, count(sql, " AND ("))
        assertTrue(sql.contains("(acc_fr_id in (1,2))"))
        assertTrue(sql.contains("(acc_to_id in (1,2))"))
    }

    @Test
    fun accountsAreCombinedWithKindTextAndRanges() {
        val filter = DataFilter()
        filter.addAccount(category(5))
        filter.addAccount(wallet(2))
        filter.setOperationKind(DataFilter.OperationKind.expense)
        filter.setSearchText("молоко")
        filter.setAmountRange(10.0, null)

        val sql = filter.getSQLFilter(FIELDS)

        assertTrue(sql.contains("acc_to_is_operating = 0 AND acc_from_is_operating = 1"))
        assertTrue(sql.contains("comment LIKE '%молоко%'"))
        assertTrue(sql.contains(" AND amount >= 10.0"))
        assertEquals(2, count(sql, "(acc_fr_id in ("))
        assertEquals(0, count(sql, "amount <="))
    }
}
