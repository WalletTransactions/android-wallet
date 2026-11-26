package com.example.wallettransactions.DataItems.CurrencyConversion

import java.util.Date
import java.util.Hashtable

class CurrencyConversionItem(
    val fromCurrency: Currency?,
    val toCurrency: Currency?
) {
    private val conversionRates = Hashtable<Date, Double>()

    fun AddConversionRate(date: Date, rate: Double) {
        conversionRates[date] = rate
    }

}
