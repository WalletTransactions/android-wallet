package com.example.wallettransactions.DataItems.CurrencyConversion

import java.util.Hashtable

class CurrencyPairList {
    private val mCurrencyPairArray: MutableList<CurrencyPair> = ArrayList()
    private val mCurrencyPairs = Hashtable<Int, CurrencyPair>()

    fun AddCurrencyPair(id: Int, symbol: String, baseCurrency: Currency?, quoteCurrency: Currency?, precision: Int): CurrencyPair {
        return AddCurrencyPair(id, symbol, baseCurrency, quoteCurrency, precision, false)
    }

    fun AddCurrencyPair(
        id: Int, symbol: String, baseCurrency: Currency?, quoteCurrency: Currency?,
        precision: Int, shared: Boolean
    ): CurrencyPair {
        val currencyPair = CurrencyPair(id, symbol, baseCurrency, quoteCurrency, precision, shared)
        mCurrencyPairs[id] = currencyPair
        mCurrencyPairArray.add(currencyPair)
        return currencyPair
    }

    fun clear() {
        mCurrencyPairArray.clear()
        mCurrencyPairs.clear()
    }

    fun getItem(indx: Int): CurrencyPair {
        return mCurrencyPairArray[indx]
    }

    fun GetItemById(id: Int): CurrencyPair? {
        return mCurrencyPairs[id]
    }

    fun getItemBySymbol(symbol: String?): CurrencyPair? {
        for (cp in mCurrencyPairArray) {
            if (cp.symbol == symbol) return cp
        }
        return null
    }


    fun size(): Int {
        return mCurrencyPairs.size
    }
}
