package com.example.wallettransactions.DataItems.Operations

class EquivalentResult(totalAmountEq: Double?, approxEq: Boolean, incomplete: Boolean) {
    var totalAmountEq: Double? = totalAmountEq
        private set
    var approxEq: Boolean = approxEq
        private set
    /** True when something could not be converted at all and was left out of the sum. */
    // Слагаемое без курса — это неполный итог, а не «вклад ноль»: сумма ниже
    // настоящей ровно на то, что не пересчиталось, и об этом надо сказать.
    var isIncomplete: Boolean = incomplete || totalAmountEq == null
        private set


    constructor(totalAmountEq: Double?, approxEq: Boolean) : this(totalAmountEq, approxEq, false)

    /**
     * Слагаемое без курса не просто пропускается: итог помечается неполным ({@code ~}).
     * Раньше такое слагаемое выпадало молча, и сумма дня тихо занижалась — причём
     * по-разному в зависимости от того, первой шла эта операция в группе или нет.
     *
     * Уже накопленную сумму оно не обнуляет: показать частичный итог с пометкой
     * полезнее, чем «?» на всю группу из-за одной операции.
     */
    fun addAmount(deltaAmount: Double?, nonCurrencyEq: Boolean) {
        val total = totalAmountEq
        if (deltaAmount == null)
            isIncomplete = true
        else if (total == null)
            totalAmountEq = deltaAmount   // первое слагаемое, которое удалось пересчитать
        else
            totalAmountEq = total + deltaAmount

        approxEq = approxEq || nonCurrencyEq
    }
}
