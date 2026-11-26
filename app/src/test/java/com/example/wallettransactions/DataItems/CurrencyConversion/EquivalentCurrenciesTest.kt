package com.example.wallettransactions.DataItems.CurrencyConversion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сценарии spec currency-directory «Пикеры валюты эквивалента учитывают флаг»
 * (openspec currency-ui-tweaks, design D5).
 */
class EquivalentCurrenciesTest {

    private fun cur(id: Int, iso: String, showInEquivalent: Boolean): Currency =
            Currency(id, iso, iso, null, iso, 2, showInEquivalent, false)

    private fun isos(currencies: List<Currency>): List<String> {
        val out = ArrayList<String>()
        for (c in currencies) out.add(c.iso)
        return out
    }

    private val rub = cur(1, "RUB", true)
    private val usd = cur(2, "USD", true)
    private val btc = cur(3, "BTC", false)
    private val eur = cur(4, "EUR", true)
    private val all = listOf(rub, usd, btc, eur)

    /** «Скрытая валюта не предлагается»: у BTC снят флаг, а кошелёк в BTC есть. */
    @Test
    fun hiddenCurrencyNotOffered() {
        assertEquals(listOf("RUB", "USD", "EUR"), isos(EquivalentCurrencies.filter(all, rub)))
    }

    /** «Текущая валюта скрыта»: BTC остаётся в пикере, пока выбрана; после смены пропадает. */
    @Test
    fun currentHiddenStaysUntilChanged() {
        assertEquals(listOf("RUB", "USD", "BTC", "EUR"), isos(EquivalentCurrencies.filter(all, btc)))

        assertEquals(listOf("RUB", "USD", "EUR"), isos(EquivalentCurrencies.filter(all, rub)))
    }

    /** Текущая сравнивается по id: после перечитывания списка экземпляр уже другой. */
    @Test
    fun currentMatchedById() {
        val staleBtc = cur(3, "BTC", false)
        assertEquals(listOf("RUB", "USD", "BTC", "EUR"), isos(EquivalentCurrencies.filter(all, staleBtc)))
    }

    /** «Все валюты скрыты»: показывается весь список, как до флага. */
    @Test
    fun allHiddenShowsEverything() {
        val hidden = listOf(cur(1, "RUB", false), cur(3, "BTC", false))
        assertEquals(listOf("RUB", "BTC"), isos(EquivalentCurrencies.filter(hidden, hidden[1])))
    }

    /**
     * Валюта с флагом без кошелька предлагается и в шапке (equivalent-picker-by-flag):
     * шапка зовёт тот же {@code filter} по всему справочнику, что и «Настройки».
     */
    @Test
    fun flaggedCurrencyWithoutWalletOffered() {
        val noWallet = cur(5, "ГГГГ", true)
        val directory = listOf(rub, btc, noWallet)
        assertEquals(listOf("RUB", "ГГГГ"), isos(EquivalentCurrencies.filter(directory, rub)))
    }

    /** Текущая, которой нет среди кандидатов, всё равно показывается (в конце). */
    @Test
    fun currentOutsideCandidatesAppended() {
        val gone = cur(9, "XXX", false)
        assertEquals(listOf("RUB", "USD", "EUR", "XXX"), isos(EquivalentCurrencies.filter(all, gone)))
    }

    /** Новая валюта: флаг выключен, кроме первой в справочнике. */
    @Test
    fun newCurrencyFlagOffExceptFirst() {
        assertEquals(true, EquivalentCurrencies.defaultForNew(ArrayList()))
        assertEquals(false, EquivalentCurrencies.defaultForNew(listOf(rub)))
        assertEquals(false, EquivalentCurrencies.defaultForNew(all))
    }
}
