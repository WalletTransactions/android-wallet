package com.example.wallettransactions.Data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** openspec debts: направление перевода по действию, переплата, имя контрагента по кошельку. */
public class DebtDirectionTest {

    @Test
    public void give_walletToLoan_take_loanToWallet() {
        assertEquals(DebtDirection.WALLET_TO_LOAN, DebtDirection.of(DebtDirection.Action.GIVE, 0));
        assertEquals(DebtDirection.WALLET_TO_LOAN, DebtDirection.of(DebtDirection.Action.GIVE, -50));
        assertEquals(DebtDirection.LOAN_TO_WALLET, DebtDirection.of(DebtDirection.Action.TAKE, 100));
    }

    @Test
    public void return_bySignOfBalance_noneAtZero() {
        // «Вернуть при положительном остатке»: мне должны — деньги приходят ко мне.
        assertEquals(DebtDirection.LOAN_TO_WALLET, DebtDirection.of(DebtDirection.Action.RETURN, 100));
        // «Вернуть при отрицательном остатке»: я должен — деньги уходят на заём.
        assertEquals(DebtDirection.WALLET_TO_LOAN, DebtDirection.of(DebtDirection.Action.RETURN, -100));
        // «Нулевой остаток»: не предлагается.
        assertEquals(DebtDirection.NONE, DebtDirection.of(DebtDirection.Action.RETURN, 0));
    }

    @Test
    public void overpay_onlyWhenCrossingZero() {
        // «Вернули больше»: остаток +100, вернули 150.
        assertTrue(DebtDirection.isOverpay(100, false, 150));
        assertFalse(DebtDirection.isOverpay(100, false, 100));
        assertFalse(DebtDirection.isOverpay(100, false, 40));
        // Я должен 100, отдаю 120.
        assertTrue(DebtDirection.isOverpay(-100, true, 120));
        assertFalse(DebtDirection.isOverpay(-100, true, 100));
        // Новая выдача в ту же сторону — не переплата.
        assertFalse(DebtDirection.isOverpay(100, true, 500));
        assertFalse(DebtDirection.isOverpay(0, true, 500));
        assertFalse(DebtDirection.isOverpay(-100, false, 500));
    }

    @Test
    public void suggestCounterpartyName_stripsLoanSuffix() {
        assertEquals("Александ Кулигин", WalletDbCRUD.SuggestCounterpartyName("Александ Кулигин · EUR"));
        assertEquals("Алексей Петров (веб дизайнер)", WalletDbCRUD.SuggestCounterpartyName("Алексей Петров (веб дизайнер) · EUR"));
        assertEquals("Вася", WalletDbCRUD.SuggestCounterpartyName("Вася · USD · 2"));
        assertEquals("Наташе няне", WalletDbCRUD.SuggestCounterpartyName("Наташе няне · EUR"));
        assertEquals("Company Debt", WalletDbCRUD.SuggestCounterpartyName("Company Debt"));
        assertEquals("Баба О", WalletDbCRUD.SuggestCounterpartyName("  Баба О "));
        assertEquals("", WalletDbCRUD.SuggestCounterpartyName(null));
    }
}
