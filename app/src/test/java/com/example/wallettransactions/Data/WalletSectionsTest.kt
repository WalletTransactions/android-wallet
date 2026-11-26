package com.example.wallettransactions.Data

import com.example.wallettransactions.Data.WalletSections.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** openspec wallets-filter-and-debt-autoarchive, спека wallets-tab: чипы секций и архив. */
class WalletSectionsTest {

    private class Row(
            val name: String,
            val section: Section,
            val archived: Boolean,
            val eq: Double
    )

    private fun layout(rows: List<Row>, selected: Set<Section>, showArchived: Boolean): WalletSections.Layout<Row> {
        return WalletSections.layout(rows, { r -> r.section }, { r -> r.archived }, selected, showArchived)
    }

    private fun sum(rows: List<Row>): Double {
        var s = 0.0
        for (r in rows) s += r.eq
        return s
    }

    private val ledger = listOf(
            Row("Нал", Section.LIQUID, false, 100.0),
            Row("Старая карта", Section.LIQUID, true, 100.0),
            Row("Квартира", Section.CAPITAL, false, 5000.0),
            Row("Вася · USD", Section.DEBTS, false, 30.0))

    @Test
    fun archivedHiddenByDefault_butSectionSumKeepsThem() {
        val l = layout(ledger, EnumSet.noneOf(Section::class.java), false)
        assertEquals(1, l.shown[Section.LIQUID]!!.size)
        assertEquals("в списке одна строка, а сумма секции — 200", 200.0, sum(l.all[Section.LIQUID]!!), 1e-9)
    }

    @Test
    fun showArchived_listsThemLikeOrdinaryWallets() {
        val l = layout(ledger, EnumSet.noneOf(Section::class.java), true)
        assertEquals(2, l.shown[Section.LIQUID]!!.size)
    }

    @Test
    fun noChipsSelected_allSectionsShown() {
        val l = layout(ledger, EnumSet.noneOf(Section::class.java), false)
        assertEquals(EnumSet.allOf(Section::class.java), l.visible)
        assertEquals(EnumSet.allOf(Section::class.java), l.available)
    }

    @Test
    fun onlyDebts() {
        val l = layout(ledger, EnumSet.of(Section.DEBTS), false)
        assertEquals(EnumSet.of(Section.DEBTS), l.visible)
    }

    @Test
    fun twoChips() {
        val l = layout(ledger, EnumSet.of(Section.CAPITAL, Section.DEBTS), false)
        assertEquals(EnumSet.of(Section.CAPITAL, Section.DEBTS), l.visible)
    }

    @Test
    fun chipsDoNotChangeSectionSums() {
        val all = sum(layout(ledger, EnumSet.noneOf(Section::class.java), false).all[Section.CAPITAL]!!)
        val chosen = sum(layout(ledger, EnumSet.of(Section.CAPITAL), false).all[Section.CAPITAL]!!)
        assertEquals(all, chosen, 1e-9)
    }

    @Test
    fun noAssets_noCapitalChip() {
        val noAssets = listOf(
                Row("Нал", Section.LIQUID, false, 100.0),
                Row("Вася · USD", Section.DEBTS, false, 30.0))
        val l = layout(noAssets, EnumSet.noneOf(Section::class.java), false)
        assertFalse(l.available.contains(Section.CAPITAL))
        assertTrue(l.available.contains(Section.DEBTS))
    }

    @Test
    fun staleChipOfVanishedSection_isIgnored_notEmptyingTheScreen() {
        val noDebts = listOf(Row("Нал", Section.LIQUID, false, 100.0))
        val l = layout(noDebts, EnumSet.of(Section.DEBTS), false)
        assertEquals("чип исчезнувшей секции не должен скрыть остальные", EnumSet.of(Section.LIQUID), l.visible)
    }

    @Test
    fun sectionWithOnlyHiddenArchived_notShown_untilToggle() {
        val onlyArchivedDebt = listOf(
                Row("Нал", Section.LIQUID, false, 100.0),
                Row("Вася · USD", Section.DEBTS, true, 30.0))
        assertEquals(EnumSet.of(Section.LIQUID),
                layout(onlyArchivedDebt, EnumSet.noneOf(Section::class.java), false).visible)
        assertEquals(EnumSet.of(Section.LIQUID, Section.DEBTS),
                layout(onlyArchivedDebt, EnumSet.noneOf(Section::class.java), true).visible)
    }
}
