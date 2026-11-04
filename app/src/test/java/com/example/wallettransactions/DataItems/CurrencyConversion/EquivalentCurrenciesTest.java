package com.example.wallettransactions.DataItems.CurrencyConversion;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Сценарии spec currency-directory «Пикеры валюты эквивалента учитывают флаг»
 * (openspec currency-ui-tweaks, design D5).
 */
public class EquivalentCurrenciesTest {

    private static Currency cur(int id, String iso, boolean showInEquivalent) {
        return new Currency(id, iso, iso, null, iso, 2, showInEquivalent, false);
    }

    private static List<String> isos(List<Currency> currencies) {
        List<String> out = new ArrayList<>();
        for (Currency c : currencies) out.add(c.getIso());
        return out;
    }

    private final Currency rub = cur(1, "RUB", true);
    private final Currency usd = cur(2, "USD", true);
    private final Currency btc = cur(3, "BTC", false);
    private final Currency eur = cur(4, "EUR", true);
    private final List<Currency> all = Arrays.asList(rub, usd, btc, eur);

    /** «Скрытая валюта не предлагается»: у BTC снят флаг, а кошелёк в BTC есть. */
    @Test
    public void hiddenCurrencyNotOffered() {
        assertEquals(Arrays.asList("RUB", "USD", "EUR"), isos(EquivalentCurrencies.filter(all, rub)));
    }

    /** «Текущая валюта скрыта»: BTC остаётся в пикере, пока выбрана; после смены пропадает. */
    @Test
    public void currentHiddenStaysUntilChanged() {
        assertEquals(Arrays.asList("RUB", "USD", "BTC", "EUR"), isos(EquivalentCurrencies.filter(all, btc)));

        assertEquals(Arrays.asList("RUB", "USD", "EUR"), isos(EquivalentCurrencies.filter(all, rub)));
    }

    /** Текущая сравнивается по id: после перечитывания списка экземпляр уже другой. */
    @Test
    public void currentMatchedById() {
        Currency staleBtc = cur(3, "BTC", false);
        assertEquals(Arrays.asList("RUB", "USD", "BTC", "EUR"), isos(EquivalentCurrencies.filter(all, staleBtc)));
    }

    /** «Все валюты скрыты»: показывается весь список, как до флага. */
    @Test
    public void allHiddenShowsEverything() {
        List<Currency> hidden = Arrays.asList(cur(1, "RUB", false), cur(3, "BTC", false));
        assertEquals(Arrays.asList("RUB", "BTC"), isos(EquivalentCurrencies.filter(hidden, hidden.get(1))));
    }

    /**
     * Валюта с флагом без кошелька предлагается и в шапке (equivalent-picker-by-flag):
     * шапка зовёт тот же {@code filter} по всему справочнику, что и «Настройки».
     */
    @Test
    public void flaggedCurrencyWithoutWalletOffered() {
        Currency noWallet = cur(5, "ГГГГ", true);
        List<Currency> directory = Arrays.asList(rub, btc, noWallet);
        assertEquals(Arrays.asList("RUB", "ГГГГ"), isos(EquivalentCurrencies.filter(directory, rub)));
    }

    /** Текущая, которой нет среди кандидатов, всё равно показывается (в конце). */
    @Test
    public void currentOutsideCandidatesAppended() {
        Currency gone = cur(9, "XXX", false);
        assertEquals(Arrays.asList("RUB", "USD", "EUR", "XXX"), isos(EquivalentCurrencies.filter(all, gone)));
    }

    /** Новая валюта: флаг выключен, кроме первой в справочнике. */
    @Test
    public void newCurrencyFlagOffExceptFirst() {
        assertEquals(true, EquivalentCurrencies.defaultForNew(new ArrayList<>()));
        assertEquals(false, EquivalentCurrencies.defaultForNew(Arrays.asList(rub)));
        assertEquals(false, EquivalentCurrencies.defaultForNew(all));
    }
}
