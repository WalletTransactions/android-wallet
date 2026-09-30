package com.example.wallettransactions.DataItems.Operations;

public final class EquivalentResult {
    private Double totalAmountEq;
    private boolean approxEq;
    /** True when something could not be converted at all and was left out of the sum. */
    private boolean incomplete;


    public EquivalentResult(Double totalAmountEq, boolean approxEq) {
        this(totalAmountEq, approxEq, false);
    }

    public EquivalentResult(Double totalAmountEq, boolean approxEq, boolean incomplete) {
        this.totalAmountEq = totalAmountEq;
        this.approxEq = approxEq;
        // Слагаемое без курса — это неполный итог, а не «вклад ноль»: сумма ниже
        // настоящей ровно на то, что не пересчиталось, и об этом надо сказать.
        this.incomplete = incomplete || totalAmountEq == null;
    }

    public Double getTotalAmountEq() {
        return totalAmountEq;
    }

    public boolean getApproxEq() {
        return approxEq;
    }

    public boolean isIncomplete() {
        return incomplete;
    }

    /**
     * Слагаемое без курса не просто пропускается: итог помечается неполным ({@code ~}).
     * Раньше такое слагаемое выпадало молча, и сумма дня тихо занижалась — причём
     * по-разному в зависимости от того, первой шла эта операция в группе или нет.
     *
     * Уже накопленную сумму оно не обнуляет: показать частичный итог с пометкой
     * полезнее, чем «?» на всю группу из-за одной операции.
     */
    public void addAmount(Double deltaAmount, boolean nonCurrencyEq){
        if (deltaAmount == null)
            incomplete = true;
        else if (totalAmountEq == null)
            totalAmountEq = deltaAmount;   // первое слагаемое, которое удалось пересчитать
        else
            totalAmountEq += deltaAmount;

        approxEq = approxEq || nonCurrencyEq;
    }
}
