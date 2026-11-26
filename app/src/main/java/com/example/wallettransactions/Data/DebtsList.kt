package com.example.wallettransactions.Data

import kotlin.math.abs
import kotlin.math.pow

/**
 * Группировка экрана «Долги» (openspec debts, спека «Экран „Долги“», design D10): займы по
 * контрагентам, контрагенты — по модулю остатка в эквиваленте, архивные займы скрыты за
 * переключателем «Показывать архивные» (openspec wallets-filter-and-debt-autoarchive, D1: «Закрытые»
 * заменены архивом; нулевой заём без архива виден), направление «Мне должны» / «Я должен» — по
 * знаку остатка (D7), поиск по имени контрагента (D6), просрочка — срок раньше сегодня при
 * ненулевом остатке. Без БД и Android — для юнит-тестов.
 */
object DebtsList {

    /**
     * Один заём — один кошелёк.
     *
     * [balance] — остаток в валюте займа: «+» мне должны, «−» я должен; [balanceEq] — остаток в
     * эквиваленте (null — нет курса); [dueDate] — `yyyy-MM-dd` или null; [converted] — остаток
     * пересчитан по курсу (валюта займа не валюта эквивалента); [archived] — флаг архива
     * кошелька займа, влияет только на показ в списке.
     */
    class Loan @JvmOverloads constructor(
        @JvmField val accountId: Int,
        @JvmField val name: String?,
        @JvmField val counterpartyUuid: String?,
        @JvmField val balance: Double,
        @JvmField val balanceEq: Double?,
        @JvmField val currencySymbol: String?,
        @JvmField val precision: Int,
        @JvmField val dueDate: String?,
        comment: String?,
        @JvmField val converted: Boolean = false,
        @JvmField val archived: Boolean = false
    ) {
        @JvmField
        val comment: String = comment ?: ""

        /** Нулевой остаток с точностью валюты займа. */
        fun isClosed(): Boolean {
            return abs(balance) < 10.0.pow(-precision) / 2
        }

        /** Спека «Просрочка»: срок раньше сегодня, остаток не нулевой. */
        fun isOverdue(today: String): Boolean {
            return dueDate != null && !isClosed() && dueDate < today
        }
    }

    /** Контрагент со своими займами. */
    class Group internal constructor(
        @JvmField val counterpartyUuid: String?,
        @JvmField val name: String
    ) {
        @JvmField
        val loans: MutableList<Loan> = ArrayList()

        /** Сумма остатков в эквиваленте по займам с курсом. */
        @JvmField
        var totalEq: Double = 0.0

        /** Хоть у одного займа нет курса — [totalEq] неполная. */
        @JvmField
        var incomplete: Boolean = false

        /** Хоть один заём пересчитан по курсу — сумма приблизительная («≈»). */
        @JvmField
        var approx: Boolean = false
    }

    /**
     * Займы одного контрагента для его карточки (openspec counterparty-card): те же группировка и
     * итог, что у экрана «Долги», но архивные займы остаются в списке (с пометкой) и входят в итог.
     *
     * @return группа контрагента или null, если у него нет займов
     */
    @JvmStatic
    fun groupFor(loans: List<Loan>, names: Map<String, String>, counterpartyUuid: String?): Group? {
        for (g in group(loans, names, true)) {
            if (g.counterpartyUuid == counterpartyUuid) return g
        }
        return null
    }

    /** Фильтр направления: по знаку остатка займа; нулевой остаток не проходит ни в одно из двух. */
    enum class Direction { ALL, OWED_TO_ME, I_OWE }

    /** Без поиска и направления — только переключатель архива. */
    @JvmStatic
    fun group(loans: List<Loan>, names: Map<String, String>, showArchived: Boolean): List<Group> {
        return group(loans, names, showArchived, Direction.ALL, "")
    }

    /**
     * Условия складываются через «И»; контрагент без подходящих займов в результат не попадает.
     *
     * @param names uuid контрагента → имя; займ неизвестного контрагента идёт под его uuid
     * @param showArchived переключатель «Показывать архивные»
     * @param query строка поиска по имени контрагента ([AccountNames.matchesSearch])
     */
    @JvmStatic
    fun group(
        loans: List<Loan>, names: Map<String, String>, showArchived: Boolean,
        direction: Direction, query: String?
    ): List<Group> {
        val byCp = LinkedHashMap<String?, Group>()
        for (loan in loans) {
            if (!showArchived && loan.archived) continue
            if (direction == Direction.OWED_TO_ME && (loan.isClosed() || loan.balance < 0)) continue
            if (direction == Direction.I_OWE && (loan.isClosed() || loan.balance > 0)) continue
            var g = byCp[loan.counterpartyUuid]
            if (g == null) {
                val name = names[loan.counterpartyUuid]
                val shown = name ?: loan.counterpartyUuid!!
                if (!AccountNames.matchesSearch(shown, query)) continue
                g = Group(loan.counterpartyUuid, shown)
                byCp[loan.counterpartyUuid] = g
            }
            g.loans.add(loan)
            if (loan.balanceEq != null) g.totalEq += loan.balanceEq
            // Нет курса — итог неполный («~»); пересчёт по курсу — приблизительный («≈»); заём в
            // валюте эквивалента не требует ни того, ни другого.
            if (loan.isClosed()) continue
            if (loan.balanceEq == null) g.incomplete = true
            else if (loan.converted) g.approx = true
        }
        val out = ArrayList(byCp.values)
        for (g in out) {
            g.loans.sortWith { a, b -> java.lang.Double.compare(abs(b.balanceEq, b.balance), abs(a.balanceEq, a.balance)) }
        }
        out.sortWith { a, b ->
            val c = java.lang.Double.compare(abs(b.totalEq), abs(a.totalEq))
            if (c != 0) c else a.name.compareTo(b.name, ignoreCase = true)
        }
        return out
    }

    private fun abs(eq: Double?, balance: Double): Double {
        return abs(eq ?: balance)
    }
}
