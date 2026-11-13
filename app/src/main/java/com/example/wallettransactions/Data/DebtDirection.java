package com.example.wallettransactions.Data;

/**
 * Направление перевода по действию с займом и проверка переплаты (openspec debts,
 * спека «Направление перевода по действию», «Переплата»; design D6, D11). Без БД и
 * Android — только арифметика, ради юнит-тестов.
 *
 * <p>Остаток займа: «+» — мне должны, «−» — я должен.
 */
public final class DebtDirection {
    private DebtDirection() {}

    public enum Action { GIVE, TAKE, RETURN }

    /** Нет перевода: «Вернуть» при нулевом остатке не предлагается. */
    public static final int NONE = 0;
    /** Деньги с кошелька пользователя на заём: «Дать в долг», возврат своего долга. */
    public static final int WALLET_TO_LOAN = 1;
    /** Деньги с займа на кошелёк пользователя: «Взять в долг», «Мне вернули». */
    public static final int LOAN_TO_WALLET = 2;

    /**
     * «Дать в долг» — с кошелька на заём, «Взять в долг» — с займа на кошелёк,
     * «Вернуть» — в сторону, противоположную знаку остатка: при «+» деньги приходят ко мне
     * (заём → кошелёк), при «−» уходят (кошелёк → заём), при нуле — {@link #NONE}.
     */
    public static int of(Action action, double loanBalance) {
        switch (action) {
            case GIVE: return WALLET_TO_LOAN;
            case TAKE: return LOAN_TO_WALLET;
            default:
                if (loanBalance > 0) return LOAN_TO_WALLET;
                if (loanBalance < 0) return WALLET_TO_LOAN;
                return NONE;
        }
    }

    /**
     * Переплата: перевод гасит остаток займа и переходит через ноль. Только для переводов,
     * которые остаток уменьшают по модулю, — новая выдача в ту же сторону не переплата.
     *
     * @param loanBalance остаток займа до перевода
     * @param loanIsTarget заём — получатель перевода (кошелёк → заём)
     * @param amount сумма перевода в валюте займа, &gt; 0
     */
    public static boolean isOverpay(double loanBalance, boolean loanIsTarget, double amount) {
        if (loanIsTarget) return loanBalance < 0 && amount > -loanBalance + 1e-9;
        return loanBalance > 0 && amount > loanBalance + 1e-9;
    }
}
