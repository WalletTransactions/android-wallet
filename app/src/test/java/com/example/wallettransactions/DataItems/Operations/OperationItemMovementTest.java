package com.example.wallettransactions.DataItems.Operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;

import org.junit.Test;

/**
 * Юнит-тесты {@link OperationItem#getMovementAmountEq()} — «во что обошёлся» разновалютный
 * перевод через мост «Transfer» (см. Android/TODO_transfers.md, п.3 и обсуждение
 * 2026-09-09). Чистая Java-логика, без БД/Android-фреймворка: главная проводка, её
 * {@link OperationItem#LinkedLeg} и {@link OperationItem#LinkedCommission} собираются
 * руками — так, как их собрал бы {@code WalletDbLoader.AttachLinksTo}.
 *
 * Точность (double) — плюс-минус 0.0001, достаточно для сумм с копейками.
 */
public class OperationItemMovementTest {

    private static final double EPS = 0.0001;

    private static int nextId = 1;

    private static AccountOperatingItem wallet(String name, Currency currency) {
        return new AccountOperatingItem(nextId++, name, "", null, false, currency,
                AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET);
    }

    private static AccountItem category(String name, AccountItem.SystemKind systemKind) {
        return new AccountItem(nextId++, name, "", null, false, systemKind, AccountItem.Kind.CATEGORY);
    }

    private static Currency currency(String iso, int precision) {
        return new Currency(nextId++, iso, iso, null, iso, precision);
    }

    /** amountEqRaw — положительная величина, как её отдаёт v_operation_eq (round(rate*amount)); знак навешивает сам конструктор по ролям счетов. */
    private static OperationItem op(AccountItem from, AccountItem to, double amount, Double amountEqRaw) {
        return new OperationItem(nextId++, new java.util.Date(), from, to, amount, amountEqRaw, "");
    }

    // ── Без комиссии — только курсовая разница ──────────────────────────────────

    @Test
    public void crossCurrencyTransfer_noCommission_lossIsRateSpreadOnly() {
        Currency eur = currency("EUR", 2);
        Currency gbp = currency("GBP", 2);
        AccountOperatingItem walletA = wallet("Wallet A", eur);
        AccountOperatingItem walletB = wallet("Wallet B", gbp);
        AccountItem transfer = category("Transfer", AccountItem.SystemKind.TRANSFER);

        // 100 EUR ушло (~108.00 в эквиваленте), 85 GBP пришло (~107.20) — курс был хуже
        // ожидаемого, перевод обошёлся в 0.80.
        OperationItem main = op(walletA, transfer, 100.00, 108.00);
        OperationItem leg  = op(transfer, walletB, 85.00, 107.20);
        main.LinkedLeg = leg;

        Double movement = main.getMovementAmountEq();
        assertEquals(-0.80, movement, EPS);
    }

    // ── С комиссией — убыток обязан вырасти на её сумму ─────────────────────────

    @Test
    public void crossCurrencyTransfer_commissionFromSourceWallet_lossIncludesCommission() {
        Currency eur = currency("EUR", 2);
        Currency gbp = currency("GBP", 2);
        AccountOperatingItem walletA = wallet("Wallet A", eur);
        AccountOperatingItem walletB = wallet("Wallet B", gbp);
        AccountItem transfer = category("Transfer", AccountItem.SystemKind.TRANSFER);
        AccountItem commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION);

        OperationItem main = op(walletA, transfer, 100.00, 108.00);
        OperationItem leg  = op(transfer, walletB, 85.00, 107.20);
        // Комиссия списана с исходного кошелька (EUR), сумма в эквиваленте — 5.60:
        // валюта комиссии (EUR) отличается от валюты эквивалента (эта же, что и у
        // операции, — GenerateReportGetBalanceToDate ведёт себя аналогично).
        OperationItem commission = op(walletA, commissionCat, 5.00, 5.60);
        main.LinkedLeg = leg;
        main.LinkedCommission = commission;

        // -108.00 (списание) + 107.20 (зачисление) - 5.60 (комиссия) = -6.40 —
        // РОВНО на комиссию больше, чем без неё (-0.80).
        Double movement = main.getMovementAmountEq();
        assertEquals(-6.40, movement, EPS);
    }

    @Test
    public void crossCurrencyTransfer_commissionFromDestinationWallet_lossIncludesCommission() {
        Currency eur = currency("EUR", 2);
        Currency gbp = currency("GBP", 2);
        AccountOperatingItem walletA = wallet("Wallet A", eur);
        AccountOperatingItem walletB = wallet("Wallet B", gbp);
        AccountItem transfer = category("Transfer", AccountItem.SystemKind.TRANSFER);
        AccountItem commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION);

        OperationItem main = op(walletA, transfer, 100.00, 108.00);
        OperationItem leg  = op(transfer, walletB, 85.00, 107.20);
        // На этот раз комиссию списали со стороны получателя (GBP) — третья
        // валюта в игре (EUR перевода, GBP перевода/комиссии, USD эквивалента).
        OperationItem commission = op(walletB, commissionCat, 3.00, 3.90);
        main.LinkedLeg = leg;
        main.LinkedCommission = commission;

        assertEquals(-0.80 - 3.90, main.getMovementAmountEq(), EPS);
    }

    // ── Одновалютный перевод: без спреда, есть только комиссия ──────────────────

    @Test
    public void sameCurrencyTransfer_withCommission_lossEqualsCommissionOnly() {
        Currency eur = currency("EUR", 2);
        AccountOperatingItem walletA = wallet("Wallet A", eur);
        AccountOperatingItem walletB = wallet("Wallet B", eur);
        AccountItem commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION);

        // Одновалютный перевод — одна проводка кошелёк-кошелёк, moста Transfer нет,
        // LinkedLeg отсутствует.
        OperationItem main = op(walletA, walletB, 100.00, 100.00);
        OperationItem commission = op(walletA, commissionCat, 2.00, 2.20);
        main.LinkedCommission = commission;

        // Списание/зачисление гасят друг друга (0.0, см. setMovementAmountEq) —
        // остаётся только комиссия.
        assertEquals(-2.20, main.getMovementAmountEq(), EPS);
    }

    // ── Курса не нашлось хотя бы для одной части — итог неизвестен, а не заниженный ──

    @Test
    public void crossCurrencyTransfer_commissionRateMissing_movementIsNullNotUnderstated() {
        Currency eur = currency("EUR", 2);
        Currency gbp = currency("GBP", 2);
        AccountOperatingItem walletA = wallet("Wallet A", eur);
        AccountOperatingItem walletB = wallet("Wallet B", gbp);
        AccountItem transfer = category("Transfer", AccountItem.SystemKind.TRANSFER);
        AccountItem commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION);

        OperationItem main = op(walletA, transfer, 100.00, 108.00);
        OperationItem leg  = op(transfer, walletB, 85.00, 107.20);
        // amountEqRaw = null — курса для валюты комиссии не нашлось (v_operation_eq
        // отдаёт NULL amount_eq, если пути пересчёта нет).
        OperationItem commission = op(walletA, commissionCat, 5.00, null);
        main.LinkedLeg = leg;
        main.LinkedCommission = commission;

        // NULL — это "неизвестно", а не "0" (иначе -0.80 выглядело бы как готовый
        // и заниженный итог, хотя на деле комиссия просто не пересчиталась).
        assertNull(main.getMovementAmountEq());
    }

    // ── isApproxEq — приблизительность помечается по каждой части отдельно ─────

    @Test
    public void isApproxEq_trueWhenAnyLinkedPartIsInDifferentCurrency() {
        Currency eur = currency("EUR", 2);
        Currency gbp = currency("GBP", 2);
        Currency usd = currency("USD", 2); // валюта эквивалента
        AccountOperatingItem walletA = wallet("Wallet A", eur);
        AccountOperatingItem walletB = wallet("Wallet B", gbp);
        AccountItem transfer = category("Transfer", AccountItem.SystemKind.TRANSFER);

        OperationItem main = op(walletA, transfer, 100.00, 108.00);
        OperationItem leg  = op(transfer, walletB, 85.00, 107.20);
        main.LinkedLeg = leg;

        // Ни EUR, ни GBP не совпадают с USD — обе части приблизительные.
        assertTrue(main.isApproxEq(usd));
    }
}
