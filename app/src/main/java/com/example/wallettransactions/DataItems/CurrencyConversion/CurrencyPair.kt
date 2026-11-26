package com.example.wallettransactions.DataItems.CurrencyConversion

class CurrencyPair(
    val id: Int,
    val symbol: String,
    val baseCurrency: Currency?,
    val quoteCurrency: Currency?,
    val precision: Int,
    /** v85: пара из общего справочника сервера — удалить нельзя. */
    val isShared: Boolean
) {
    constructor(id: Int, symbol: String, baseCurrency: Currency?, quoteCurrency: Currency?, precision: Int) :
        this(id, symbol, baseCurrency, quoteCurrency, precision, false)
}
