package com.example.wallettransactions.DataItems;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;

import org.junit.Test;

/**
 * openspec similar-search / operations-filter: счета фильтра делятся на кошельки и
 * категории; внутри группы «ИЛИ», группы между собой — «И».
 */
public class DataFilterTest {

    private static final String[] FIELDS = {"acc_fr_id", "acc_to_id"};

    private static AccountItem wallet(int id) {
        return new AccountItem(id, "w" + id, "", null, false,
                AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET);
    }

    private static AccountItem category(int id) {
        return new AccountItem(id, "c" + id, "", null, false,
                AccountItem.SystemKind.NONE, AccountItem.Kind.CATEGORY);
    }

    /** Количество вхождений подстроки — по ним видно, сколько групп собралось. */
    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) ++n;
        return n;
    }

    @Test
    public void emptyFilter_hasNoAccountCondition() {
        assertEquals("", new DataFilter().getSQLFilter(FIELDS));
    }

    @Test
    public void categoryAndWallet_areTwoAndedGroups() {
        DataFilter filter = new DataFilter();
        filter.addAccount(category(5));
        filter.addAccount(wallet(2));

        String sql = filter.getSQLFilter(FIELDS);

        // Две группы подряд через AND: в каждой по OR между from и to.
        assertEquals(2, count(sql, " AND ("));
        assertTrue(sql.contains("(acc_fr_id in (2))"));
        assertTrue(sql.contains("(acc_to_id in (2))"));
        assertTrue(sql.contains("(acc_fr_id in (5))"));
        assertTrue(sql.contains("(acc_to_id in (5))"));
        assertEquals(2, count(sql, " OR "));
        // Кошелёк и категория не смешаны в один список.
        assertFalse(sql.contains("in (2,5)"));
        assertFalse(sql.contains("in (5,2)"));
    }

    @Test
    public void twoCategories_areOneOrGroup() {
        DataFilter filter = new DataFilter();
        filter.addAccount(category(5));
        filter.addAccount(category(7));

        String sql = filter.getSQLFilter(FIELDS);

        assertEquals(1, count(sql, " AND ("));
        assertTrue(sql.contains("(acc_fr_id in (5,7))"));
        assertTrue(sql.contains("(acc_to_id in (5,7))"));
    }

    @Test
    public void onlyWallets_areOneOrGroup() {
        DataFilter filter = new DataFilter();
        filter.addAccount(wallet(1));
        filter.addAccount(wallet(2));

        String sql = filter.getSQLFilter(FIELDS);

        assertEquals(1, count(sql, " AND ("));
        assertTrue(sql.contains("(acc_fr_id in (1,2))"));
        assertTrue(sql.contains("(acc_to_id in (1,2))"));
    }

    @Test
    public void accountsAreCombinedWithKindTextAndRanges() {
        DataFilter filter = new DataFilter();
        filter.addAccount(category(5));
        filter.addAccount(wallet(2));
        filter.setOperationKind(DataFilter.OperationKind.expense);
        filter.setSearchText("молоко");
        filter.setAmountRange(10.0, null);

        String sql = filter.getSQLFilter(FIELDS);

        assertTrue(sql.contains("acc_to_is_operating = 0 AND acc_from_is_operating = 1"));
        assertTrue(sql.contains("comment LIKE '%молоко%'"));
        assertTrue(sql.contains(" AND amount >= 10.0"));
        assertEquals(2, count(sql, "(acc_fr_id in ("));
        assertEquals(0, count(sql, "amount <="));
    }
}
