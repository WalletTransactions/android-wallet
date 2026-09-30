package com.example.wallettransactions.DataItems.CurrencyConversion;

public class CurrencyPair {
        private int fId;
        private String fSymbol;
        private Currency fBaseCurrency;
        private Currency fQuoteCurrency;
        private int fPrecision;

        public int getId() { return fId; }
        public String getSymbol() { return fSymbol; }
        public Currency getBaseCurrency() { return fBaseCurrency; }
        public Currency getQuoteCurrency() { return fQuoteCurrency; }
        public int getPrecision() { return fPrecision; }

        public CurrencyPair(int id, String symbol, Currency baseCurrency, Currency quoteCurrency, int precision) {
            fId = id;
            fSymbol = symbol;
            fBaseCurrency = baseCurrency;
            fQuoteCurrency = quoteCurrency;
            fPrecision = precision;
        }
}
