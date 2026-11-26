package com.example.wallettransactions.DataItems.CurrencyConversion

import com.example.wallettransactions.DataItems.Icon

import java.util.Hashtable

class CurrencyList : ArrayList<Currency>() {

    private val currencyList = Hashtable<Int, Currency>()

    fun addCurrency(id: Int, iso: String, name: String, icon: Icon?, symbol: String, precision: Int): Currency {
        return addCurrency(id, iso, name, icon, symbol, precision, true, false)
    }

    /**
     * Валюта с уже знакомым id не заводится заново, а обновляется на месте.
     * <p>
     * Справочник перечитывают так: {@code clear()} (чистит только сам список — индекс по id
     * остаётся), затем {@code WalletDbLoader.LoadCurrencyList}. Раньше каждый проход заводил
     * новые объекты {@link Currency}, а счета, пары и {@code PeriodFilterState.currencyEq}
     * держали старые — с прежними флагом «В эквиваленте», символом и названием до
     * перезапуска приложения (android-wallet#8). {@code Currency} не переопределяет
     * {@code equals}, так что старый и новый объект одной валюты к тому же не равны.
     */
    fun addCurrency(
        id: Int, iso: String, name: String, icon: Icon?, symbol: String, precision: Int,
        showInEquivalent: Boolean, shared: Boolean
    ): Currency {
        var cur = currencyList[id]
        if (cur == null) {
            cur = Currency(id, iso, name, icon, symbol, precision, showInEquivalent, shared)
            currencyList[id] = cur
        } else {
            cur.update(iso, name, icon, symbol, precision, showInEquivalent, shared)
        }
        if (!this.contains(cur)) this.add(cur) // без clear() перед загрузкой — без дубля
        return cur
    }

    fun getCurrencyById(id: Int): Currency? {
        return currencyList[id]
    }


    fun getCurrencyByISO(iso: String?): Currency? {
        for (itm in this) {
            if (itm.iso == iso)
                return itm

        }
        return null
    }
}
