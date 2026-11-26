package com.example.wallettransactions.DataItems.Operations

import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты {@link OperationItem#getMovementAmountEq()} — «во что обошёлся» разновалютный
 * перевод через мост «Transfer» (см. docs/TODO_transfers.md, п.3 и обсуждение
 * 2026-09-09). Чистая Java-логика, без БД/Android-фреймворка: главная проводка, её
 * {@link OperationItem#LinkedLeg} и {@link OperationItem#LinkedCommission} собираются
 * руками — так, как их собрал бы {@code WalletDbLoader.AttachLinksTo}.
 *
 * Точность (double) — плюс-минус 0.0001, достаточно для сумм с копейками.
 */
class OperationItemMovementTest {

    companion object {
        private const val EPS = 0.0001

        private var nextId = 1

        private fun wallet(name: String, currency: Currency): AccountOperatingItem =
                AccountOperatingItem(nextId++, name, "", null, false, currency,
                        AccountItem.SystemKind.NONE, AccountItem.Kind.WALLET)

        private fun category(name: String, systemKind: AccountItem.SystemKind): AccountItem =
                AccountItem(nextId++, name, "", null, false, systemKind, AccountItem.Kind.CATEGORY)

        private fun currency(iso: String, precision: Int): Currency =
                Currency(nextId++, iso, iso, null, iso, precision)

        /** amountEqRaw — положительная величина, как её отдаёт v_operation_eq (round(rate*amount)); знак навешивает сам конструктор по ролям счетов. */
        private fun op(from: AccountItem, to: AccountItem, amount: Double, amountEqRaw: Double?): OperationItem =
                OperationItem(nextId++, java.util.Date(), from, to, amount, amountEqRaw, "")
    }

    // ── Без комиссии — только курсовая разница ──────────────────────────────────

    @Test
    fun crossCurrencyTransfer_noCommission_lossIsRateSpreadOnly() {
        val eur = currency("EUR", 2)
        val gbp = currency("GBP", 2)
        val walletA = wallet("Wallet A", eur)
        val walletB = wallet("Wallet B", gbp)
        val transfer = category("Transfer", AccountItem.SystemKind.TRANSFER)

        // 100 EUR ушло (~108.00 в эквиваленте), 85 GBP пришло (~107.20) — курс был хуже
        // ожидаемого, перевод обошёлся в 0.80.
        val main = op(walletA, transfer, 100.00, 108.00)
        val leg = op(transfer, walletB, 85.00, 107.20)
        main.LinkedLeg = leg

        val movement: Double? = main.movementAmountEq
        assertEquals(-0.80, movement!!, EPS)
    }

    // ── С комиссией — убыток обязан вырасти на её сумму ─────────────────────────

    @Test
    fun crossCurrencyTransfer_commissionFromSourceWallet_lossIncludesCommission() {
        val eur = currency("EUR", 2)
        val gbp = currency("GBP", 2)
        val walletA = wallet("Wallet A", eur)
        val walletB = wallet("Wallet B", gbp)
        val transfer = category("Transfer", AccountItem.SystemKind.TRANSFER)
        val commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION)

        val main = op(walletA, transfer, 100.00, 108.00)
        val leg = op(transfer, walletB, 85.00, 107.20)
        // Комиссия списана с исходного кошелька (EUR), сумма в эквиваленте — 5.60:
        // валюта комиссии (EUR) отличается от валюты эквивалента (эта же, что и у
        // операции, — GenerateReportGetBalanceToDate ведёт себя аналогично).
        val commission = op(walletA, commissionCat, 5.00, 5.60)
        main.LinkedLeg = leg
        main.LinkedCommission = commission

        // -108.00 (списание) + 107.20 (зачисление) - 5.60 (комиссия) = -6.40 —
        // РОВНО на комиссию больше, чем без неё (-0.80).
        val movement: Double? = main.movementAmountEq
        assertEquals(-6.40, movement!!, EPS)
    }

    @Test
    fun crossCurrencyTransfer_commissionFromDestinationWallet_lossIncludesCommission() {
        val eur = currency("EUR", 2)
        val gbp = currency("GBP", 2)
        val walletA = wallet("Wallet A", eur)
        val walletB = wallet("Wallet B", gbp)
        val transfer = category("Transfer", AccountItem.SystemKind.TRANSFER)
        val commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION)

        val main = op(walletA, transfer, 100.00, 108.00)
        val leg = op(transfer, walletB, 85.00, 107.20)
        // На этот раз комиссию списали со стороны получателя (GBP) — третья
        // валюта в игре (EUR перевода, GBP перевода/комиссии, USD эквивалента).
        val commission = op(walletB, commissionCat, 3.00, 3.90)
        main.LinkedLeg = leg
        main.LinkedCommission = commission

        assertEquals(-0.80 - 3.90, main.movementAmountEq!!, EPS)
    }

    // ── Одновалютный перевод: без спреда, есть только комиссия ──────────────────

    @Test
    fun sameCurrencyTransfer_withCommission_lossEqualsCommissionOnly() {
        val eur = currency("EUR", 2)
        val walletA = wallet("Wallet A", eur)
        val walletB = wallet("Wallet B", eur)
        val commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION)

        // Одновалютный перевод — одна проводка кошелёк-кошелёк, moста Transfer нет,
        // LinkedLeg отсутствует.
        val main = op(walletA, walletB, 100.00, 100.00)
        val commission = op(walletA, commissionCat, 2.00, 2.20)
        main.LinkedCommission = commission

        // Списание/зачисление гасят друг друга (0.0, см. setMovementAmountEq) —
        // остаётся только комиссия.
        assertEquals(-2.20, main.movementAmountEq!!, EPS)
    }

    // ── Курса не нашлось хотя бы для одной части — итог неизвестен, а не заниженный ──

    @Test
    fun crossCurrencyTransfer_commissionRateMissing_movementIsNullNotUnderstated() {
        val eur = currency("EUR", 2)
        val gbp = currency("GBP", 2)
        val walletA = wallet("Wallet A", eur)
        val walletB = wallet("Wallet B", gbp)
        val transfer = category("Transfer", AccountItem.SystemKind.TRANSFER)
        val commissionCat = category("Transfer commission", AccountItem.SystemKind.TRANSFER_COMMISSION)

        val main = op(walletA, transfer, 100.00, 108.00)
        val leg = op(transfer, walletB, 85.00, 107.20)
        // amountEqRaw = null — курса для валюты комиссии не нашлось (v_operation_eq
        // отдаёт NULL amount_eq, если пути пересчёта нет).
        val commission = op(walletA, commissionCat, 5.00, null)
        main.LinkedLeg = leg
        main.LinkedCommission = commission

        // NULL — это "неизвестно", а не "0" (иначе -0.80 выглядело бы как готовый
        // и заниженный итог, хотя на деле комиссия просто не пересчиталась).
        assertNull(main.movementAmountEq)
    }

    // ── isApproxEq — приблизительность помечается по каждой части отдельно ─────

    @Test
    fun isApproxEq_trueWhenAnyLinkedPartIsInDifferentCurrency() {
        val eur = currency("EUR", 2)
        val gbp = currency("GBP", 2)
        val usd = currency("USD", 2) // валюта эквивалента
        val walletA = wallet("Wallet A", eur)
        val walletB = wallet("Wallet B", gbp)
        val transfer = category("Transfer", AccountItem.SystemKind.TRANSFER)

        val main = op(walletA, transfer, 100.00, 108.00)
        val leg = op(transfer, walletB, 85.00, 107.20)
        main.LinkedLeg = leg

        // Ни EUR, ни GBP не совпадают с USD — обе части приблизительные.
        assertTrue(main.isApproxEq(usd))
    }
}
