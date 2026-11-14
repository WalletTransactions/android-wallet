package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.example.wallettransactions.Data.WalletSections.Section;

/** openspec wallets-filter-and-debt-autoarchive, спека wallets-tab: чипы секций и архив. */
public class WalletSectionsTest {

    private static final class Row {
        final String name;
        final Section section;
        final boolean archived;
        final double eq;

        Row(String name, Section section, boolean archived, double eq) {
            this.name = name;
            this.section = section;
            this.archived = archived;
            this.eq = eq;
        }
    }

    private static WalletSections.Layout<Row> layout(List<Row> rows, Set<Section> selected, boolean showArchived) {
        return WalletSections.layout(rows, r -> r.section, r -> r.archived, selected, showArchived);
    }

    private static double sum(List<Row> rows) {
        double s = 0;
        for (Row r : rows) s += r.eq;
        return s;
    }

    private final List<Row> ledger = Arrays.asList(
            new Row("Нал", Section.LIQUID, false, 100),
            new Row("Старая карта", Section.LIQUID, true, 100),
            new Row("Квартира", Section.CAPITAL, false, 5000),
            new Row("Вася · USD", Section.DEBTS, false, 30));

    @Test
    public void archivedHiddenByDefault_butSectionSumKeepsThem() {
        WalletSections.Layout<Row> l = layout(ledger, EnumSet.noneOf(Section.class), false);
        assertEquals(1, l.shown.get(Section.LIQUID).size());
        assertEquals("в списке одна строка, а сумма секции — 200", 200, sum(l.all.get(Section.LIQUID)), 1e-9);
    }

    @Test
    public void showArchived_listsThemLikeOrdinaryWallets() {
        WalletSections.Layout<Row> l = layout(ledger, EnumSet.noneOf(Section.class), true);
        assertEquals(2, l.shown.get(Section.LIQUID).size());
    }

    @Test
    public void noChipsSelected_allSectionsShown() {
        WalletSections.Layout<Row> l = layout(ledger, EnumSet.noneOf(Section.class), false);
        assertEquals(EnumSet.allOf(Section.class), l.visible);
        assertEquals(EnumSet.allOf(Section.class), l.available);
    }

    @Test
    public void onlyDebts() {
        WalletSections.Layout<Row> l = layout(ledger, EnumSet.of(Section.DEBTS), false);
        assertEquals(EnumSet.of(Section.DEBTS), l.visible);
    }

    @Test
    public void twoChips() {
        WalletSections.Layout<Row> l = layout(ledger, EnumSet.of(Section.CAPITAL, Section.DEBTS), false);
        assertEquals(EnumSet.of(Section.CAPITAL, Section.DEBTS), l.visible);
    }

    @Test
    public void chipsDoNotChangeSectionSums() {
        double all = sum(layout(ledger, EnumSet.noneOf(Section.class), false).all.get(Section.CAPITAL));
        double chosen = sum(layout(ledger, EnumSet.of(Section.CAPITAL), false).all.get(Section.CAPITAL));
        assertEquals(all, chosen, 1e-9);
    }

    @Test
    public void noAssets_noCapitalChip() {
        List<Row> noAssets = Arrays.asList(
                new Row("Нал", Section.LIQUID, false, 100),
                new Row("Вася · USD", Section.DEBTS, false, 30));
        WalletSections.Layout<Row> l = layout(noAssets, EnumSet.noneOf(Section.class), false);
        assertFalse(l.available.contains(Section.CAPITAL));
        assertTrue(l.available.contains(Section.DEBTS));
    }

    @Test
    public void staleChipOfVanishedSection_isIgnored_notEmptyingTheScreen() {
        List<Row> noDebts = Collections.singletonList(new Row("Нал", Section.LIQUID, false, 100));
        WalletSections.Layout<Row> l = layout(noDebts, EnumSet.of(Section.DEBTS), false);
        assertEquals("чип исчезнувшей секции не должен скрыть остальные", EnumSet.of(Section.LIQUID), l.visible);
    }

    @Test
    public void sectionWithOnlyHiddenArchived_notShown_untilToggle() {
        List<Row> onlyArchivedDebt = Arrays.asList(
                new Row("Нал", Section.LIQUID, false, 100),
                new Row("Вася · USD", Section.DEBTS, true, 30));
        assertEquals(EnumSet.of(Section.LIQUID),
                layout(onlyArchivedDebt, EnumSet.noneOf(Section.class), false).visible);
        assertEquals(EnumSet.of(Section.LIQUID, Section.DEBTS),
                layout(onlyArchivedDebt, EnumSet.noneOf(Section.class), true).visible);
    }
}
