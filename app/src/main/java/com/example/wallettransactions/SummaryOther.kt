package com.example.wallettransactions

import com.example.wallettransactions.Data.WalletDbLoader
import com.example.wallettransactions.DataItems.Operations.EquivalentResult
import kotlin.math.abs

/**
 * Расчёт строки «Прочее» карточки итогов периода (openspec summary-other-section):
 * всё, что сводит «Итог» с «Доход − Расход» — потоки в капитал и долги и курсовая
 * разница, — плюс остатки капитала и долгов на конец периода для раскрытых деталей.
 *
 * Класс чистый — без Android и SQLite: балансы и потоки приходят готовыми, поэтому его
 * покрывают юнит-тесты, а `BaseTabActivity` только рисует результат.
 *
 * Сумма «Прочего» — остаток `Итог − (Доход − Расход)`, а не сумма слагаемых:
 * при неполных курсах слагаемые теряют то, что не пересчиталось, а остаток сходится
 * всегда; неполнота и «≈» при этом переносятся пометками.
 */
class SummaryOther private constructor(
    opBalance: EquivalentResult, clBalance: EquivalentResult,
    income: EquivalentResult?, expense: EquivalentResult?,
    hasAssets: Boolean,
    capitalResult: WalletDbLoader.CapitalResult,
    capitalFlowResult: EquivalentResult,
    debtsResult: WalletDbLoader.DebtsResult,
    debtFlowResult: EquivalentResult
) {

    /** Число с пометками «≈» (был пересчёт) и неполноты (часть слагаемых без курса). */
    class Amount internal constructor(
        @JvmField val value: Double,
        @JvmField val approx: Boolean,
        @JvmField val incomplete: Boolean
    ) {
        companion object {
            internal fun of(r: EquivalentResult): Amount =
                Amount(orZero(r), r.approxEq, r.isIncomplete)
        }
    }

    /** «Прочее» целиком: `Итог − (Доход − Расход)`. */
    @JvmField
    val other: Amount

    /** Строка «Прочее» вообще есть в карточке. */
    @JvmField
    val showOther: Boolean

    /** «Капитал: изменение» — «В капитал / из капитала» за период. */
    @JvmField
    val capitalFlow: Amount
    @JvmField
    val showCapitalFlow: Boolean

    /** «Капитал» — сумма активов на конец периода. */
    @JvmField
    val capital: Amount
    @JvmField
    val showCapital: Boolean

    /** «Долги: изменение» — «В долг / из долга» за период. */
    @JvmField
    val debtFlow: Amount
    @JvmField
    val showDebtFlow: Boolean

    /** «Мне должны» и «Я должен» на конец периода; показываются вместе. */
    @JvmField
    val owedToMe: Amount
    @JvmField
    val iOwe: Amount
    @JvmField
    val showDebts: Boolean

    /** «Курсовая разница» — остаток после потоков капитала и долгов. */
    @JvmField
    val fxDiff: Amount
    @JvmField
    val showFxDiff: Boolean

    /** «Всего с капиталом и долгами» = `netWorth + owedToMe − iOwe`. */
    @JvmField
    val total: Amount
    @JvmField
    val showTotal: Boolean

    init {
        val hasDebts = debtsResult.hasDebts

        val totalDelta = orZero(clBalance) - orZero(opBalance)
        val incomeMinusExpense = orZero(income) - orZero(expense)
        val otherValue = totalDelta - incomeMinusExpense
        // «Доход» и «Расход» входят в остаток так же, как балансы: если у части операций нет
        // курса, сумма «Прочего» считана от неполных данных — и об этом надо сказать.
        val approx = clBalance.approxEq || opBalance.approxEq
                || flagApprox(income) || flagApprox(expense)
        val incomplete = clBalance.isIncomplete || opBalance.isIncomplete
                || flagIncomplete(income) || flagIncomplete(expense)
        other = Amount(otherValue, approx, incomplete)
        showOther = hasAssets || hasDebts || abs(otherValue) >= EPSILON

        // Потоки показываем по данным, а не только по наличию активов и займов: скрытая
        // ненулевая строка в деталях не дала бы сложить «Прочее» глазами.
        val flowEq = orZero(capitalFlowResult)
        val debtEq = orZero(debtFlowResult)
        capitalFlow = Amount.of(capitalFlowResult)
        showCapitalFlow = abs(flowEq) >= EPSILON
        debtFlow = Amount.of(debtFlowResult)
        showDebtFlow = abs(debtEq) >= EPSILON

        capital = Amount.of(capitalResult.capital)
        showCapital = hasAssets

        owedToMe = Amount.of(debtsResult.owedToMe)
        iOwe = Amount.of(debtsResult.iOwe)
        showDebts = hasDebts

        // Курсовая разница — остаток, поэтому равенство сходится даже при неполных курсах.
        val fx = otherValue - flowEq - debtEq
        fxDiff = Amount(
            fx,
            approx || capitalFlowResult.approxEq || debtFlowResult.approxEq,
            incomplete || capitalFlowResult.isIncomplete || debtFlowResult.isIncomplete
        )
        showFxDiff = abs(fx) >= EPSILON

        // netWorth — ликвидные плюс активы с включённым «Учитывать…» (не totalWithDebts:
        // оно считается от ликвидных без капитала).
        val nw = capitalResult.netWorth
        total = Amount(
            orZero(nw) + orZero(debtsResult.owedToMe) - orZero(debtsResult.iOwe),
            nw.approxEq || debtsResult.owedToMe.approxEq || debtsResult.iOwe.approxEq,
            nw.isIncomplete || debtsResult.owedToMe.isIncomplete || debtsResult.iOwe.isIncomplete
        )
        showTotal = hasAssets || hasDebts
    }

    companion object {
        /** Меньше этого (в валюте эквивалента) сумма — округление, строку не показываем. */
        const val EPSILON = 0.5

        /**
         * @param opBalance  баланс ликвидных кошельков на начало периода
         * @param clBalance  то же на конец периода
         * @param income     «Доход» за период; null или пустая сумма — ноль
         * @param expense    «Расход» за период (положительное число)
         * @param hasAssets  есть ли хоть один актив — в том числе без операций
         * @param capital    капитал и «всего с капиталом» на конец; без активов
         *                   `netWorth` — это просто ликвидные деньги
         * @param capitalFlow «В капитал / из капитала» за период
         * @param debts      долги на конец периода
         * @param debtFlow   «В долг / из долга» за период
         */
        @JvmStatic
        fun compute(
            opBalance: EquivalentResult, clBalance: EquivalentResult,
            income: EquivalentResult?, expense: EquivalentResult?,
            hasAssets: Boolean,
            capital: WalletDbLoader.CapitalResult,
            capitalFlow: EquivalentResult,
            debts: WalletDbLoader.DebtsResult,
            debtFlow: EquivalentResult
        ): SummaryOther = SummaryOther(
            opBalance, clBalance, income, expense, hasAssets, capital,
            capitalFlow, debts, debtFlow
        )

        // Отчёт без строк даёт сумму null, а конструктор EquivalentResult помечает такую сумму
        // неполной — это «за период ничего не было», а не «не хватило курса»; такой отчёт
        // пометок не добавляет.
        private fun flagApprox(r: EquivalentResult?): Boolean =
            r != null && r.totalAmountEq != null && r.approxEq

        private fun flagIncomplete(r: EquivalentResult?): Boolean =
            r != null && r.totalAmountEq != null && r.isIncomplete

        /** У отчёта без строк сумма null — для сверки это ноль. */
        private fun orZero(r: EquivalentResult?): Double = r?.totalAmountEq ?: 0.0
    }
}
