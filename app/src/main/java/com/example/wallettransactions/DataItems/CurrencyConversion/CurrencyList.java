package com.example.wallettransactions.DataItems.CurrencyConversion;

import com.example.wallettransactions.DataItems.Icon;

import java.util.ArrayList;
import java.util.Hashtable;

public class CurrencyList extends ArrayList<Currency> {

    private Hashtable<Integer, Currency> currencyList = new Hashtable<Integer, Currency>();

    public Currency addCurrency(int id, String iso, String name, Icon icon, String symbol, int precision) {
        return addCurrency(id, iso, name, icon, symbol, precision, true, false);
    }

    public Currency addCurrency(int id, String iso, String name, Icon icon, String symbol, int precision,
                                boolean showInEquivalent, boolean shared) {
        Currency cur = new Currency(id, iso, name, icon, symbol, precision, showInEquivalent, shared);
        this.add(cur);
        currencyList.put(id, cur);
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