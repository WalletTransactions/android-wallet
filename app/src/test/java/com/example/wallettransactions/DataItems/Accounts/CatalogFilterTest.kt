package com.example.wallettransactions.DataItems.Accounts

import com.example.wallettransactions.DataItems.Accounts.AccountItem.CategoryKind
import com.example.wallettransactions.DataItems.Accounts.CatalogFilter.WalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** openspec catalog-filters-panel, задача 1.1: правила фильтров справочника. */
class CatalogFilterTest {

    @Test
    fun noKindSelected_showsEverything_includingSystem() {
        val f = CatalogFilter()
        assertTrue(f.matchesCategory(CategoryKind.EXPENSE, false))
        assertTrue(f.matchesCategory(CategoryKind.INCOME, false))
        assertTrue(f.matchesCategory(CategoryKind.BOTH, false))
        assertTrue(f.matchesCategory(CategoryKind.BOTH, true))
    }

    @Test
    fun oneKind_isExact() {
        val f = CatalogFilter()
        f.toggleKind(CategoryKind.INCOME)
        assertTrue(f.matchesCategory(CategoryKind.INCOME, false))
        assertFalse(f.matchesCategory(CategoryKind.EXPENSE, false))
        assertFalse(f.matchesCategory(CategoryKind.BOTH, false))
    }

    @Test
    fun expenseDoesNotIncludeBoth() {
        // «Подарки» вида «Оба» не попадают под чип «Расход» (в отличие от слота операции).
        val f = CatalogFilter()
        f.toggleKind(CategoryKind.EXPENSE)
        assertFalse(f.matchesCategory(CategoryKind.BOTH, false))
        assertTrue(f.matchesCategory(CategoryKind.EXPENSE, false))
    }

    @Test
    fun twoKinds_areOr() {
        val f = CatalogFilter()
        f.toggleKind(CategoryKind.INCOME)
        f.toggleKind(CategoryKind.BOTH)
        assertTrue(f.matchesCategory(CategoryKind.INCOME, false))
        assertTrue(f.matchesCategory(CategoryKind.BOTH, false))
        assertFalse(f.matchesCategory(CategoryKind.EXPENSE, false))
    }

    @Test
    fun systemCategories_hiddenWhenAnyKindSelected_shownWhenNone() {
        val f = CatalogFilter()
        assertTrue(f.matchesCategory(CategoryKind.BOTH, true))
        f.toggleKind(CategoryKind.BOTH)
        assertFalse(f.matchesCategory(CategoryKind.BOTH, true))
        // Снятие единственного чипа возвращает системные категории.
        f.toggleKind(CategoryKind.BOTH)
        assertTrue(f.matchesCategory(CategoryKind.BOTH, true))
    }

    @Test
    fun toggleKind_reportsNewState() {
        val f = CatalogFilter()
        assertTrue(f.toggleKind(CategoryKind.EXPENSE))
        assertTrue(f.hasKind(CategoryKind.EXPENSE))
        assertFalse(f.toggleKind(CategoryKind.EXPENSE))
        assertFalse(f.hasKind(CategoryKind.EXPENSE))
    }

    @Test
    fun walletType_regularMeansNeitherAssetNorDebt() {
        val f = CatalogFilter()
        assertTrue(f.matchesWallet(isAsset = true, isDebt = false))
        f.walletType = WalletType.REGULAR
        assertTrue(f.matchesWallet(isAsset = false, isDebt = false))
        assertFalse(f.matchesWallet(isAsset = true, isDebt = false))
        assertFalse(f.matchesWallet(isAsset = false, isDebt = true))
        f.walletType = WalletType.ASSET
        assertTrue(f.matchesWallet(isAsset = true, isDebt = false))
        assertFalse(f.matchesWallet(isAsset = false, isDebt = false))
        f.walletType = WalletType.DEBT
        assertTrue(f.matchesWallet(isAsset = false, isDebt = true))
        assertFalse(f.matchesWallet(isAsset = true, isDebt = false))
    }

    @Test
    fun activeCount_walletsTab_archiveAndType() {
        val f = CatalogFilter()
        assertEquals(0, f.activeCount(true))
        f.walletType = WalletType.ASSET
        f.showArchived = true
        assertEquals(2, f.activeCount(true))
    }

    @Test
    fun activeCount_kindsGroupIsOneCondition() {
        val f = CatalogFilter()
        f.toggleKind(CategoryKind.EXPENSE)
        f.toggleKind(CategoryKind.INCOME)
        assertEquals(1, f.activeCount(false))
    }

    @Test
    fun activeCount_hiddenTabConditionsNotCounted() {
        val f = CatalogFilter()
        f.toggleKind(CategoryKind.INCOME)
        f.walletType = WalletType.DEBT
        // На «Кошельках» виды не считаются, на «Категориях» — тип кошелька.
        assertEquals(1, f.activeCount(true))
        assertEquals(1, f.activeCount(false))
        f.walletType = WalletType.ALL
        assertEquals(0, f.activeCount(true))
    }

    @Test
    fun activeCount_archiveIsSharedBetweenTabs() {
        val f = CatalogFilter()
        f.showArchived = true
        assertEquals(1, f.activeCount(true))
        assertEquals(1, f.activeCount(false))
    }

    @Test
    fun reset_restoresDefaults() {
        val f = CatalogFilter()
        f.showArchived = true
        f.walletType = WalletType.ASSET
        f.toggleKind(CategoryKind.INCOME)
        f.reset()
        assertFalse(f.showArchived)
        assertEquals(WalletType.ALL, f.walletType)
        assertFalse(f.hasKind(CategoryKind.INCOME))
        assertEquals(0, f.activeCount(true))
        assertEquals(0, f.activeCount(false))
    }
}
