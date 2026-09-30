package com.example.wallettransactions.DataItems.CurrencyConversion;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class CurrencyPairList {
    private List<CurrencyPair> mCurrencyPairArray = new ArrayList<CurrencyPair>();
    private Hashtable<Integer, CurrencyPair> mCurrencyPairs = new Hashtable<Integer, CurrencyPair>();

    public CurrencyPair AddCurrencyPair(int id, String symbol, Currency baseCurrency, Currency quoteCurrency, int precision) {
        CurrencyPair currencyPair = new CurrencyPair(id, symbol, baseCurrency, quoteCurrency, precision);
        mCurrencyPairs.put(id, currencyPair);
        mCurrencyPairArray.add(currencyPair);
        return currencyPair;
    }

    public void clear() {
        mCurrencyPairArray.clear();
        mCurrencyPairs.clear();
    }

    public CurrencyPair getItem(int indx)
    {
         return mCurrencyPairArray.get(indx);
    }

    public CurrencyPair GetItemById(int id){
        return mCurrencyPairs.get(id);
    }

    public CurrencyPair getItemBySymbol(String symbol) {
        for (CurrencyPair cp : mCurrencyPairArray) {
            if (cp.getSymbol().equals(symbol)) return cp;
        }
        return null;
    }


    public int size() {
        return mCurrencyPairs.size();
    }
}
