package com.example.wallettransactions.DataItems.CurrencyConversion;

public class CurrencyPair {
        private int fId;
        private String fSymbol;
        private Currency fBaseCurrency;
        private Currency fQuoteCurrency;
        private int fPrecision;
        /** v85: пара из общего справочника сервера — удалить нельзя. */
        private boolean fShared;

        public int getId() { return fId; }
        public String getSymbol() { return fSymbol; }
        public Currency getBaseCurrency() { return fBaseCurrency; }
        public Currency getQuoteCurrency() { return fQuoteCurrency; }
        public int getPrecision() { return fPrecision; }
        public boolean isShared() { return fShared; }

        public CurrencyPair(int id, String symbol, Currency baseCurrency, Currency quoteCurrency, int precision) {
            this(id, symbol, baseCurrency, quoteCurrency, precision, false);
        }

        public CurrencyPair(int id, String symbol, Currency baseCurrency, Currency quoteCurrency, int precision,
                            boolean shared) {
            fId = id;
            fSymbol = symbol;
            fBaseCurrency = baseCurrency;
            fQuoteCurrency = quoteCurrency;
            fPrecision = precision;
            fShared = shared;
        }
}
