package com.example.wallettransactions.DataItems.CurrencyConversion;

import android.util.Pair;

import java.util.Date;
import java.util.Hashtable;

public class CurrencyConversionList {

    private Hashtable<Pair<Currency, Currency>, Hashtable<Date, Double>> conversionRates = new Hashtable<>();

    public void AddConversionRate(Currency fromCur, Currency toCur, Date date, double rate)
    {
     //   conversionRates.put(Pair<fromCur, toCur>, <date, rate>);
    }

}
