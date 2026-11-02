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
        List<Currency> inUse = Arrays.asList(rub, btc);
        assertEquals(Arrays.asList("RUB"), isos(EquivalentCurrencies.forHeader(inUse, all, rub)));
        assertEquals(Arrays.asList("RUB", "USD", "EUR"), isos(EquivalentCurrencies.filter(all, rub)));
    }

    /** «Текущая валюта скрыта»: BTC остаётся в пикере, пока выбрана; после смены пропадает. */
    @Test
    public void currentHiddenStaysUntilChanged() {
        List<Currency> inUse = Arrays.asList(rub, btc);
        assertEquals(Arrays.asList("RUB", "BTC"), isos(EquivalentCurrencies.forHeader(inUse, all, btc)));
        assertEquals(Arrays.asList("RUB", "USD", "BTC", "EUR"), isos(EquivalentCurrencies.filter(all, btc)));

        assertEquals(Arrays.asList("RUB"), isos(EquivalentCurrencies.forHeader(inUse, all, rub)));
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
        assertEquals(Arrays.asList("RUB", "BTC"), isos(EquivalentCurrencies.forHeader(hidden, hidden, null)));
    }

    /** Шапка: у живых кошельков с флагом нет ни одной — все валюты справочника с флагом. */
    @Test
    public void headerFallsBackToAllFlagged() {
        List<Currency> inUse = Arrays.asList(btc);
        assertEquals(Arrays.asList("RUB", "USD", "EUR"), isos(EquivalentCurrencies.forHeader(inUse, all, usd)));
    }

    /** Текущая не среди кошельков — всё равно показывается (в конце). */
    @Test
    public void currentOutsideCandidatesAppended() {
        List<Currency> inUse = Arrays.asList(rub, usd);
        assertEquals(Arrays.asList("RUB", "USD", "EUR"), isos(EquivalentCurrencies.forHeader(inUse, all, eur)));
    }
}
