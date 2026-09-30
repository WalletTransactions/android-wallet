package com.example.wallettransactions.DataItems.CurrencyConversion;

import java.util.Date;
import java.util.Hashtable;

public class CurrencyConversionItem {
    private Currency fromCurrency;
    private Currency toCurrency;

    private Hashtable<Date, Double> conversionRates = new Hashtable<Date, Double>();

    public Currency getFromCurrency() {return fromCurrency;}
    public Currency getToCurrency() {return toCurrency;}



    public CurrencyConversionItem(Currency fromCurrency, Currency toCurrency){
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
    }

    public void AddConversionRate(Date date, double rate)
    {
        conversionRates.put(date, rate);
    }

}
