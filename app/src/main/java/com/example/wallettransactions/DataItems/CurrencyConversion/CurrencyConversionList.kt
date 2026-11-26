package com.example.wallettransactions.DataItems.CurrencyConversion

import android.util.Pair

import java.util.Date
import java.util.Hashtable

class CurrencyConversionList {

    private val conversionRates = Hashtable<Pair<Currency, Currency>, Hashtable<Date, Double>>()

    fun AddConversionRate(fromCur: Currency?, toCur: Currency?, date: Date?, rate: Double) {
        //   conversionRates.put(Pair<fromCur, toCur>, <date, rate>);
    }

}
