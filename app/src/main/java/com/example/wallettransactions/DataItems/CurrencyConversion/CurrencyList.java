package com.example.wallettransactions.DataItems.CurrencyConversion;

import com.example.wallettransactions.DataItems.Icon;

import java.util.ArrayList;
import java.util.Hashtable;

public class CurrencyList extends ArrayList<Currency> {

    private Hashtable<Integer, Currency> currencyList = new Hashtable<Integer, Currency>();

    public Currency addCurrency(int id, String iso, String name, Icon icon, String symbol, int precision) {
        return addCurrency(id, iso, name, icon, symbol, precision, true, false);
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
    public Currency addCurrency(int id, String iso, String name, Icon icon, String symbol, int precision,
                                boolean showInEquivalent, boolean shared) {
        Currency cur = currencyList.get(id);
        if (cur == null) {
            cur = new Currency(id, iso, name, icon, symbol, precision, showInEquivalent, shared);
            currencyList.put(id, cur);
        } else {
            cur.update(iso, name, icon, symbol, precision, showInEquivalent, shared);
        }
        if (!this.contains(cur)) this.add(cur); // без clear() перед загрузкой — без дубля
        return cur;
    }

    public Currency getCurrencyById(int id)
    {
        return currencyList.get(id);
    }


    public Currency getCurrencyByISO(String iso)
    {
        for (Currency itm: this)
        {
            if (itm.getIso().equals(iso))
            return itm;

        }
        return null;
    }
}