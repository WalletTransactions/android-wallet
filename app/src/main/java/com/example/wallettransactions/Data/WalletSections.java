package com.example.wallettransactions.Data;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Отбор строк таба «Кошельки» (openspec wallets-filter-and-debt-autoarchive, спека wallets-tab
 * «Чипы секций», «Архивные кошельки на табе», design D5): какие секции есть, какие из них выбраны
 * чипами и какие строки показаны. Без БД и Android — для юнит-тестов.
 *
 * <p>Главное правило: скрытие — только для показа. Суммы секций считаются по {@link
 * Layout#all} (все строки секции, включая скрытые архивные), а на экран идёт {@link
 * Layout#shown}.
 */
public final class WalletSections {
    private WalletSections() {}

    /** Секции таба в порядке показа: ликвидные, капитал, долги. */
    public enum Section { LIQUID, CAPITAL, DEBTS }

    public static final class Layout<T> {
        /** Секции, в которых есть хоть одна строка (с архивными) — для чипов. */
        public final Set<Section> available = EnumSet.noneOf(Section.class);
        /** Секции, которые показаны (с учётом чипов). Порядок — как в {@link Section}. */
        public final Set<Section> visible = EnumSet.noneOf(Section.class);
        /** Все строки секции — по ним считается сумма, скрытые архивные входят. */
        public final Map<Section, List<T>> all = new EnumMap<>(Section.class);
        /** Строки секции, которые идут на экран (без скрытых архивных). */
        public final Map<Section, List<T>> shown = new EnumMap<>(Section.class);
    }

    /**
     * @param selected выбранные чипы; пусто — показаны все секции. Выбранный чип секции, которой
     *                 нет, ни на что не влияет: он остаётся «выбранным» только в состоянии экрана.
     * @param showArchived переключатель «Показывать архивные»
     */
    public static <T> Layout<T> layout(List<T> rows, Function<T, Section> sectionOf,
                                       Predicate<T> archivedOf, Set<Section> selected,
                                       boolean showArchived) {
        Layout<T> out = new Layout<>();
        for (Section s : Section.values()) {
            out.all.put(s, new ArrayList<>());
            out.shown.put(s, new ArrayList<>());
        }
        for (T r : rows) {
            Section s = sectionOf.apply(r);
            out.all.get(s).add(r);
            if (showArchived || !archivedOf.test(r)) out.shown.get(s).add(r);
        }
        // Чипы — только по секциям, которые существуют; выбранный чип исчезнувшей секции игнорируем.
        Set<Section> effective = EnumSet.noneOf(Section.class);
        for (Section s : Section.values()) {
            if (!out.all.get(s).isEmpty()) out.available.add(s);
            if (selected.contains(s) && !out.all.get(s).isEmpty()) effective.add(s);
        }
        // Секция, у которой все строки скрыты архивом, на экран не идёт: заголовок без строк
        // только сбивал бы; деньги в ней учтены в карточке периода и появятся с переключателем.
        for (Section s : out.available) {
            boolean chosen = effective.isEmpty() || effective.contains(s);
            if (chosen && !out.shown.get(s).isEmpty()) out.visible.add(s);
        }
        return out;
    }
}
