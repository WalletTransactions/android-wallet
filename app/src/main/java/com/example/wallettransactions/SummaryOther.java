package com.example.wallettransactions;

import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;

/**
 * Расчёт строки «Прочее» карточки итогов периода (openspec summary-other-section):
 * всё, что сводит «Итог» с «Доход − Расход» — потоки в капитал и долги и курсовая
 * разница, — плюс остатки капитала и долгов на конец периода для раскрытых деталей.
 * <p>
 * Класс чистый — без Android и SQLite: балансы и потоки приходят готовыми, поэтому его
 * покрывают юнит-тесты, а {@code BaseTabActivity} только рисует результат.
 * <p>
 * Сумма «Прочего» — остаток {@code Итог − (Доход − Расход)}, а не сумма слагаемых:
 * при неполных курсах слагаемые теряют то, что не пересчиталось, а остаток сходится
 * всегда; неполнота и «≈» при этом переносятся пометками.
 */
public final class SummaryOther {

    /** Меньше этого (в валюте эквивалента) сумма — округление, строку не показываем. */
    public static final double EPSILON = 0.5;

    /** Число с пометками «≈» (был пересчёт) и неполноты (часть слагаемых без курса). */
    public static final class Amount {
        public final double value;
        public final boolean approx;
        public final boolean incomplete;

        Amount(double value, boolean approx, boolean incomplete) {
            this.value = value;
            this.approx = approx;
            this.incomplete = incomplete;
        }

        static Amount of(EquivalentResult r) {
            return new Amount(orZero(r), r.getApproxEq(), r.isIncomplete());
        }
    }

    /** «Прочее» целиком: {@code Итог − (Доход − Расход)}. */
    public final Amount other;
    /** Строка «Прочее» вообще есть в карточке. */
    public final boolean showOther;

    /** «Капитал: изменение» — «В капитал / из капитала» за период. */
    public final Amount capitalFlow;
    public final boolean showCapitalFlow;

    /** «Капитал» — сумма активов на конец периода. */
    public final Amount capital;
    public final boolean showCapital;

    /** «Долги: изменение» — «В долг / из долга» за период. */
    public final Amount debtFlow;
    public final boolean showDebtFlow;

    /** «Мне должны» и «Я должен» на конец периода; показываются вместе. */
    public final Amount owedToMe;
    public final Amount iOwe;
    public final boolean showDebts;

    /** «Курсовая разница» — остаток после потоков капитала и долгов. */
    public final Amount fxDiff;
    public final boolean showFxDiff;

    /** «Всего с капиталом и долгами» = {@code netWorth + owedToMe − iOwe}. */
    public final Amount total;
    public final boolean showTotal;

    /**
     * @param opBalance  баланс ликвидных кошельков на начало периода
     * @param clBalance  то же на конец периода
     * @param income     «Доход» за период; null или пустая сумма — ноль
     * @param expense    «Расход» за период (положительное число)
     * @param hasAssets  есть ли хоть один актив — в том числе без операций
     * @param capital    капитал и «всего с капиталом» на конец; без активов
     *                   {@code netWorth} — это просто ликвидные деньги
     * @param capitalFlow «В капитал / из капитала» за период
     * @param debts      долги на конец периода
     * @param debtFlow   «В долг / из долга» за период
     */
    public static SummaryOther compute(EquivalentResult opBalance, EquivalentResult clBalance,
                                       EquivalentResult income, EquivalentResult expense,
                                       boolean hasAssets,
                                       WalletDbLoader.CapitalResult capital,
                                       EquivalentResult capitalFlow,
                                       WalletDbLoader.DebtsResult debts,
                                       EquivalentResult debtFlow) {
        return new SummaryOther(opBalance, clBalance, income, expense, hasAssets, capital,
                capitalFlow, debts, debtFlow);
    }

    private SummaryOther(EquivalentResult opBalance, EquivalentResult clBalance,
                         EquivalentResult income, EquivalentResult expense,
                         boolean hasAssets,
                         WalletDbLoader.CapitalResult capitalResult,
                         EquivalentResult capitalFlowResult,
                         WalletDbLoader.DebtsResult debtsResult,
                         EquivalentResult debtFlowResult) {
        boolean hasDebts = debtsResult.hasDebts;

        double total = orZero(clBalance) - orZero(opBalance);
        double incomeMinusExpense = orZero(income) - orZero(expense);
        double otherValue = total - incomeMinusExpense;
        // «Доход» и «Расход» входят в остаток так же, как балансы: если у части операций нет
        // курса, сумма «Прочего» считана от неполных данных — и об этом надо сказать.
        boolean approx = clBalance.getApproxEq() || opBalance.getApproxEq()
                || flagApprox(income) || flagApprox(expense);
        boolean incomplete = clBalance.isIncomplete() || opBalance.isIncomplete()
                || flagIncomplete(income) || flagIncomplete(expense);
        this.other = new Amount(otherValue, approx, incomplete);
        this.showOther = hasAssets || hasDebts || Math.abs(otherValue) >= EPSILON;

        // Потоки показываем по данным, а не только по наличию активов и займов: скрытая
        // ненулевая строка в деталях не дала бы сложить «Прочее» глазами.
        double flowEq = orZero(capitalFlowResult);
        double debtEq = orZero(debtFlowResult);
        this.capitalFlow = Amount.of(capitalFlowResult);
        this.showCapitalFlow = Math.abs(flowEq) >= EPSILON;
        this.debtFlow = Amount.of(debtFlowResult);
        this.showDebtFlow = Math.abs(debtEq) >= EPSILON;

        this.capital = Amount.of(capitalResult.capital);
        this.showCapital = hasAssets;

        this.owedToMe = Amount.of(debtsResult.owedToMe);
        this.iOwe = Amount.of(debtsResult.iOwe);
        this.showDebts = hasDebts;

        // Курсовая разница — остаток, поэтому равенство сходится даже при неполных курсах.
        double fx = otherValue - flowEq - debtEq;
        this.fxDiff = new Amount(fx,
                approx || capitalFlowResult.getApproxEq() || debtFlowResult.getApproxEq(),
                incomplete || capitalFlowResult.isIncomplete() || debtFlowResult.isIncomplete());
        this.showFxDiff = Math.abs(fx) >= EPSILON;

        // netWorth — ликвидные плюс активы с включённым «Учитывать…» (не totalWithDebts:
        // оно считается от ликвидных без капитала).
        EquivalentResult nw = capitalResult.netWorth;
        this.total = new Amount(
                orZero(nw) + orZero(debtsResult.owedToMe) - orZero(debtsResult.iOwe),
                nw.getApproxEq() || debtsResult.owedToMe.getApproxEq() || debtsResult.iOwe.getApproxEq(),
                nw.isIncomplete() || debtsResult.owedToMe.isIncomplete() || debtsResult.iOwe.isIncomplete());
        this.showTotal = hasAssets || hasDebts;
    }

    // Отчёт без строк даёт сумму null, а конструктор EquivalentResult помечает такую сумму
    // неполной — это «за период ничего не было», а не «не хватило курса»; такой отчёт
    // пометок не добавляет.
    private static boolean flagApprox(EquivalentResult r) {
        return r != null && r.getTotalAmountEq() != null && r.getApproxEq();
    }

    private static boolean flagIncomplete(EquivalentResult r) {
        return r != null && r.getTotalAmountEq() != null && r.isIncomplete();
    }

    /** У отчёта без строк сумма null — для сверки это ноль. */
    private static double orZero(EquivalentResult r) {
        return r == null || r.getTotalAmountEq() == null ? 0 : r.getTotalAmountEq();
    }
}
