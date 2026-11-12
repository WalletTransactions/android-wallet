package com.example.wallettransactions.DataItems;

import static org.junit.Assert.assertEquals;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** openspec similar-search / «Найти подобные»: что из операции попадает в фильтр. */
public class SimilarFilterTest {

    private static AccountItem wallet(int id) {
        return new AccountItem(id, "w" + id, "", null, false,
                AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET);
    }

    private static AccountItem category(int id) {
        return new AccountItem(id, "c" + id, "", null, false,
                AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY);
    }

    private static AccountItem revaluation(int id) {
        return new AccountItem(id, "r" + id, "", null, false,
                AccountItem.SystemKind.REVALUATION, AccountItem.Kind.CATEGORY);
    }

    @Test
    public void expense_categoryWalletTextAndKind() {
        SimilarFilter f = SimilarFilter.of(wallet(2), category(5), "молоко");

        assertEquals(Arrays.asList(5, 2), f.getAccountIds());
        assertEquals(DataFilter.OperationKind.expense, f.getKind());
        assertEquals("молоко", f.getSearchText());
    }

    @Test
    public void income_isFromCategoryToWallet() {
        SimilarFilter f = SimilarFilter.of(category(7), wallet(2), "аванс");

        assertEquals(Arrays.asList(7, 2), f.getAccountIds());
        assertEquals(DataFilter.OperationKind.income, f.getKind());
    }

    @Test
    public void transfer_hasBothWalletsAndNoCategory() {
        SimilarFilter f = SimilarFilter.of(wallet(1), wallet(2), "");

        assertEquals(Arrays.asList(1, 2), f.getAccountIds());
        assertEquals(DataFilter.OperationKind.transfer, f.getKind());
    }

    @Test
    public void emptyOrBlankComment_givesNoSearchText() {
        assertEquals("", SimilarFilter.of(wallet(2), category(5), "").getSearchText());
        assertEquals("", SimilarFilter.of(wallet(2), category(5), "   ").getSearchText());
        assertEquals("", SimilarFilter.of(wallet(2), category(5), null).getSearchText());
    }

    @Test
    public void commentIsTrimmed() {
        assertEquals("кофе", SimilarFilter.of(wallet(2), category(5), "  кофе \n").getSearchText());
    }

    @Test
    public void revaluation_hasNoKind() {
        SimilarFilter f = SimilarFilter.of(revaluation(9), wallet(2), "");

        assertEquals(DataFilter.OperationKind.all, f.getKind());
        assertEquals(Arrays.asList(9, 2), f.getAccountIds());
    }

    @Test
    public void missingSide_isSkipped() {
        SimilarFilter f = SimilarFilter.of(wallet(2), null, "x");

        assertEquals(Collections.singletonList(2), f.getAccountIds());
        assertEquals(DataFilter.OperationKind.all, f.getKind());
    }
}
