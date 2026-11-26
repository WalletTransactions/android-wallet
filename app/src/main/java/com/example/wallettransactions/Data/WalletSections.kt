package com.example.wallettransactions.Data

import java.util.EnumMap
import java.util.EnumSet
import java.util.function.Function
import java.util.function.Predicate

/**
 * Отбор строк таба «Кошельки» (openspec wallets-filter-and-debt-autoarchive, спека wallets-tab
 * «Чипы секций», «Архивные кошельки на табе», design D5): какие секции есть, какие из них выбраны
 * чипами и какие строки показаны. Без БД и Android — для юнит-тестов.
 *
 * Главное правило: скрытие — только для показа. Суммы секций считаются по [Layout.all]
 * (все строки секции, включая скрытые архивные), а на экран идёт [Layout.shown].
 */
object WalletSections {
    /** Секции таба в порядке показа: ликвидные, капитал, долги. */
    enum class Section { LIQUID, CAPITAL, DEBTS }

    class Layout<T> {
        /** Секции, в которых есть хоть одна строка (с архивными) — для чипов. */
        @JvmField
        val available: MutableSet<Section> = EnumSet.noneOf(Section::class.java)

        /** Секции, которые показаны (с учётом чипов). Порядок — как в [Section]. */
        @JvmField
        val visible: MutableSet<Section> = EnumSet.noneOf(Section::class.java)

        /** Все строки секции — по ним считается сумма, скрытые архивные входят. */
        @JvmField
        val all: MutableMap<Section, MutableList<T>> = EnumMap(Section::class.java)

        /** Строки секции, которые идут на экран (без скрытых архивных). */
        @JvmField
        val shown: MutableMap<Section, MutableList<T>> = EnumMap(Section::class.java)
    }

    /**
     * @param selected выбранные чипы; пусто — показаны все секции. Выбранный чип секции, которой
     *                 нет, ни на что не влияет: он остаётся «выбранным» только в состоянии экрана.
     * @param showArchived переключатель «Показывать архивные»
     */
    @JvmStatic
    fun <T> layout(
        rows: List<T>, sectionOf: Function<T, Section>,
        archivedOf: Predicate<T>, selected: Set<Section>,
        showArchived: Boolean
    ): Layout<T> {
        val out = Layout<T>()
        for (s in Section.values()) {
            out.all[s] = ArrayList()
            out.shown[s] = ArrayList()
        }
        for (r in rows) {
            val s = sectionOf.apply(r)
            out.all[s]!!.add(r)
            if (showArchived || !archivedOf.test(r)) out.shown[s]!!.add(r)
        }
        // Чипы — только по секциям, которые существуют; выбранный чип исчезнувшей секции игнорируем.
        val effective: MutableSet<Section> = EnumSet.noneOf(Section::class.java)
        for (s in Section.values()) {
            if (out.all[s]!!.isNotEmpty()) out.available.add(s)
            if (selected.contains(s) && out.all[s]!!.isNotEmpty()) effective.add(s)
        }
        // Секция, у которой все строки скрыты архивом, на экран не идёт: заголовок без строк
        // только сбивал бы; деньги в ней учтены в карточке периода и появятся с переключателем.
        for (s in out.available) {
            val chosen = effective.isEmpty() || effective.contains(s)
            if (chosen && out.shown[s]!!.isNotEmpty()) out.visible.add(s)
        }
        return out
    }
}
