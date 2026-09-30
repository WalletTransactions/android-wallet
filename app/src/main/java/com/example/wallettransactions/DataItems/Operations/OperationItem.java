package com.example.wallettransactions.DataItems.Operations;


import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class OperationItem {

    /**
     * Явный признак того, что за роль у связанной проводки в {@code operation_link}
     * (v77, колонка {@code operation_link.type}) — заменяет прежний вывод по
     * {@code system_kind} счёта-источника (а до него — по случайному совпадению id),
     * см. {@code TransactionActivity.loadLinkedParts}/{@code WalletDbLoader.AttachLinksTo}.
     * Значения зафиксированы на сервере (см. servers/), менять код существующего
     * значения нельзя. Новые виды связок (когда появятся) добавляются новым значением,
     * а не переиспользованием {@link #LEG}/{@link #COMMISSION}.
     */
    public enum LinkType {
        /** Вторая нога валютного перевода — с категории-моста «Transfer» на кошелёк-получатель. */
        LEG(0),
        /** Комиссия за перевод — отдельной проводкой на выбранную пользователем категорию. */
        COMMISSION(1);

        private final int value;

        LinkType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        /** Безопасный дефолт {@link #LEG} на любое незнакомое значение — тот же приём, что {@code AccountItem.Kind.fromValue}. */
        public static LinkType fromValue(int value) {
            return value == COMMISSION.value ? COMMISSION : LEG;
        }
    }

    public java.util.Date Date;

    public int IdTran;
    public double Amount;
    public Double AmountEq;
    public String Comment;

    public AccountItem AccFrom;
    public AccountItem AccTo;
    private Currency currency;

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
    public OperationItem LinkedLeg;
    public OperationItem LinkedCommission;

    /** Перевод, у которого стороны в разных валютах, поэтому в базе он двумя проводками. */
    public boolean isCrossCurrencyTransfer() {
        return LinkedLeg != null;
    }

    /** Кошелёк-получатель: у валютного перевода он на второй ноге, а не в этой проводке. */
    public AccountItem getDestinationAccount() {
        return LinkedLeg != null ? LinkedLeg.AccTo : AccTo;
    }

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
    public Double getMovementAmountEq() {
        Double total = AmountEq;
        total = addPart(total, LinkedLeg);
        total = addPart(total, LinkedCommission);
        return total;
    }

    private static Double addPart(Double total, OperationItem part) {
        if (part == null) return total;
        if (total == null || part.AmountEq == null) return null;
        return total + part.AmountEq;
    }

    /** true, если хоть одну часть операции пришлось пересчитывать в валюту эквивалента. */
    public boolean isApproxEq(Currency currencyEq) {
        return isApproxPart(this, currencyEq)
                || (LinkedLeg != null && isApproxPart(LinkedLeg, currencyEq))
                || (LinkedCommission != null && isApproxPart(LinkedCommission, currencyEq));
    }

    private static boolean isApproxPart(OperationItem op, Currency currencyEq) {
        Currency c = op.getCurrency();
        return c == null || !c.equals(currencyEq);
    }

    public Currency getCurrency() {
        return currency;
    }

    public OperationItem(int idTran, Date date, AccountItem accFrom, AccountItem accTo, double amount, Double amountEq, String comment) {
        IdTran = idTran;
        Date = date;
        AccFrom = accFrom;
        AccTo = accTo;
        Amount = amount;
        Comment = comment;
        AmountEq = setMovementAmountEq(amountEq);

        if (isOperatingAccountFrom())
            currency = ((AccountOperatingItem)AccFrom).getCurrency();
        else
        if (isOperatingAccountTo())
            currency = ((AccountOperatingItem)AccTo).getCurrency();
    }

    public boolean isOperatingAccountFrom() {
        return AccFrom.isOperatingAccount();
    }

    public boolean isOperatingAccountTo() {
        return AccTo.isOperatingAccount();
    }

    private Double setMovementAmountEq(Double amountEq) {
        if (amountEq == null)
            return null;

        if (this.isOperatingAccountFrom()) {
            if (this.isOperatingAccountTo())
                return 0.0;
            else
                return -1*amountEq;
        }

        if (this.isOperatingAccountTo()) {
            return amountEq;
        } else
            return 0.0;
    }

    public boolean isSameDay(Date date) {
        Instant instant1 = date.toInstant()
                .truncatedTo(ChronoUnit.DAYS);
        Instant instant2 = Date.toInstant()
                .truncatedTo(ChronoUnit.DAYS);
        return instant1.equals(instant2);
    }

}


