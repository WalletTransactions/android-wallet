package com.example.wallettransactions.DataItems.Operations


import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency

import java.time.temporal.ChronoUnit

class OperationItem(
    idTran: Int,
    date: java.util.Date,
    accFrom: AccountItem,
    accTo: AccountItem,
    amount: Double,
    amountEq: Double?,
    comment: String?
) {

    /**
     * Явный признак того, что за роль у связанной проводки в {@code operation_link}
     * (v77, колонка {@code operation_link.type}) — заменяет прежний вывод по
     * {@code system_kind} счёта-источника (а до него — по случайному совпадению id),
     * см. {@code TransactionActivity.loadLinkedParts}/{@code WalletDbLoader.AttachLinksTo}.
     * Значения зафиксированы на сервере (см. servers/), менять код существующего
     * значения нельзя. Новые виды связок (когда появятся) добавляются новым значением,
     * а не переиспользованием {@link #LEG}/{@link #COMMISSION}.
     */
    enum class LinkType(val value: Int) {
        /** Вторая нога валютного перевода — с категории-моста «Transfer» на кошелёк-получатель. */
        LEG(0),
        /** Комиссия за перевод — отдельной проводкой на выбранную пользователем категорию. */
        COMMISSION(1);

        companion object {
            /** Безопасный дефолт {@link #LEG} на любое незнакомое значение — тот же приём, что {@code AccountItem.Kind.fromValue}. */
            @JvmStatic
            fun fromValue(value: Int): LinkType {
                return if (value == COMMISSION.value) COMMISSION else LEG
            }
        }
    }

    @JvmField
    var Date: java.util.Date = date

    @JvmField
    var IdTran: Int = idTran
    @JvmField
    var Amount: Double = amount
    @JvmField
    var AmountEq: Double? = null
    @JvmField
    var Comment: String? = comment
    /**
     * Подпись перевода с займом (openspec debts, design D6) — вычисляется при загрузке
     * ленты ({@code WalletDbLoader.AttachDebtLabels}), в базе не хранится; null — не заём.
     */
    @JvmField
    var DebtLabel: com.example.wallettransactions.Data.DebtLabels.Label? = null
    /** Имя контрагента займа для подписи; null — не заём. */
    @JvmField
    var DebtCounterparty: String? = null

    @JvmField
    var AccFrom: AccountItem = accFrom
    @JvmField
    var AccTo: AccountItem = accTo
    var currency: Currency? = null
        private set

    /**
     * Проводки, которые пользователь завёл этой же операцией (таблица {@code operation_link}).
     * В ленте они не отдельные строки: их показывает та же карточка, что и главную проводку.
     *
     * {@link #LinkedLeg} — вторая нога валютного перевода: с категории-моста «Transfer» на
     * кошелёк-получатель. Её {@code Amount} — сумма в валюте получателя, и она не равна
     * сумме списания: у операции одна колонка суммы, а валют две.
     *
     * {@link #LinkedCommission} — комиссия за перевод, отдельной проводкой на свою категорию.
     */
    @JvmField
    var LinkedLeg: OperationItem? = null
    @JvmField
    var LinkedCommission: OperationItem? = null

    /** Перевод, у которого стороны в разных валютах, поэтому в базе он двумя проводками. */
    val isCrossCurrencyTransfer: Boolean
        get() = LinkedLeg != null

    /**
     * Переоценка капитального актива (openspec capital-assets): одна из сторон — системная
     * категория «Переоценка». Тот же признак, что колонка {@code is_revaluation} вьюх.
     */
    val isRevaluation: Boolean
        get() = AccFrom.isSystemRevaluation || AccTo.isSystemRevaluation

    /** Переоценка вверх — зачисление на актив (категория → актив). */
    val isRevaluationUp: Boolean
        get() = isRevaluation && AccFrom.isSystemRevaluation

    /** Кошелёк-получатель: у валютного перевода он на второй ноге, а не в этой проводке. */
    val destinationAccount: AccountItem
        get() = LinkedLeg?.AccTo ?: AccTo

    /**
     * Движение денег по кошелькам в валюте эквивалента — за всю операцию, вместе со
     * связанными проводками. Именно это, а не {@link #AmountEq} одной проводки, идёт в
     * итог дня: связанные проводки своей строки в ленте не имеют, и их движение иначе
     * просто пропало бы из суммы.
     *
     * У валютного перевода ноги гасят друг друга (списание минусом, зачисление плюсом),
     * так что от операции остаётся разница курсов и комиссия — то есть ровно то, во
     * что перевод обошёлся.
     *
     * {@code null} — если хоть для одной части не нашлось курса: итог тогда неизвестен,
     * а не «на эту часть меньше».
     */
    val movementAmountEq: Double?
        get() {
            // Переоценка актива (capital-assets) — не движение денег: в итог дня не входит.
            if (isRevaluation) return 0.0
            var total: Double? = AmountEq
            total = addPart(total, LinkedLeg)
            total = addPart(total, LinkedCommission)
            return total
        }

    private fun addPart(total: Double?, part: OperationItem?): Double? {
        if (part == null) return total
        if (total == null || part.AmountEq == null) return null
        return total + part.AmountEq!!
    }

    /** true, если хоть одну часть операции пришлось пересчитывать в валюту эквивалента. */
    fun isApproxEq(currencyEq: Currency?): Boolean {
        return isApproxPart(this, currencyEq)
            || (LinkedLeg != null && isApproxPart(LinkedLeg!!, currencyEq))
            || (LinkedCommission != null && isApproxPart(LinkedCommission!!, currencyEq))
    }

    private fun isApproxPart(op: OperationItem, currencyEq: Currency?): Boolean {
        val c = op.currency
        return c == null || c != currencyEq
    }

    init {
        AmountEq = setMovementAmountEq(amountEq)

        if (isOperatingAccountFrom)
            currency = (AccFrom as AccountOperatingItem).currency
        else
            if (isOperatingAccountTo)
                currency = (AccTo as AccountOperatingItem).currency
    }

    val isOperatingAccountFrom: Boolean
        get() = AccFrom.isOperatingAccount

    val isOperatingAccountTo: Boolean
        get() = AccTo.isOperatingAccount

    private fun setMovementAmountEq(amountEq: Double?): Double? {
        if (amountEq == null)
            return null

        if (this.isOperatingAccountFrom) {
            if (this.isOperatingAccountTo)
                return 0.0
            else
                return -1 * amountEq
        }

        if (this.isOperatingAccountTo) {
            return amountEq
        } else
            return 0.0
    }

    fun isSameDay(date: java.util.Date): Boolean {
        val instant1 = date.toInstant()
            .truncatedTo(ChronoUnit.DAYS)
        val instant2 = Date.toInstant()
            .truncatedTo(ChronoUnit.DAYS)
        return instant1 == instant2
    }

}
