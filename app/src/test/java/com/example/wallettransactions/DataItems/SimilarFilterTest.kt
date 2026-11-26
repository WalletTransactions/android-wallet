package com.example.wallettransactions.DataItems

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** openspec similar-search / «Найти подобные»: что из операции попадает в фильтр. */
class SimilarFilterTest {

    private fun wallet(id: Int): AccountItem = AccountItem(id, "w$id", "", null, false,
            AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET)

    private fun category(id: Int): AccountItem = AccountItem(id, "c$id", "", null, false,
            AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY)

    private fun revaluation(id: Int): AccountItem = AccountItem(id, "r$id", "", null, false,
            AccountItem.SystemKind.REVALUATION, AccountItem.Kind.CATEGORY)

    @Test
    fun expense_categoryWalletTextAndKind() {
        val f = SimilarFilter.of(wallet(2), category(5), "молоко")

        assertEquals(listOf(5, 2), f.accountIds)
        assertEquals(DataFilter.OperationKind.expense, f.kind)
        assertEquals("молоко", f.searchText)
    }

    @Test
    fun income_isFromCategoryToWallet() {
        val f = SimilarFilter.of(category(7), wallet(2), "аванс")

        assertEquals(listOf(7, 2), f.accountIds)
        assertEquals(DataFilter.OperationKind.income, f.kind)
    }

    @Test
    fun transfer_hasBothWalletsAndNoCategory() {
        val f = SimilarFilter.of(wallet(1), wallet(2), "")

        assertEquals(listOf(1, 2), f.accountIds)
        assertEquals(DataFilter.OperationKind.transfer, f.kind)
    }

    @Test
    fun emptyOrBlankComment_givesNoSearchText() {
        assertEquals("", SimilarFilter.of(wallet(2), category(5), "").searchText)
        assertEquals("", SimilarFilter.of(wallet(2), category(5), "   ").searchText)
        assertEquals("", SimilarFilter.of(wallet(2), category(5), null).searchText)
    }

    @Test
    fun commentIsTrimmed() {
        assertEquals("кофе", SimilarFilter.of(wallet(2), category(5), "  кофе \n").searchText)
    }

    @Test
    fun revaluation_hasNoKind() {
        val f = SimilarFilter.of(revaluation(9), wallet(2), "")

        assertEquals(DataFilter.OperationKind.all, f.kind)
        assertEquals(listOf(9, 2), f.accountIds)
    }

    @Test
    fun missingSide_isSkipped() {
        val f = SimilarFilter.of(wallet(2), null, "x")

        assertEquals(listOf(2), f.accountIds)
        assertEquals(DataFilter.OperationKind.all, f.kind)
    }
}
