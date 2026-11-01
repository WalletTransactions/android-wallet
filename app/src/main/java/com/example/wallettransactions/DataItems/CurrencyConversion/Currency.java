package com.example.wallettransactions.DataItems.CurrencyConversion;


import com.example.wallettransactions.DataItems.Icon;

public class Currency {
    private int id;
    private String iso;
    private String name;
    private Icon icon;
    private String symbol;
    private int precision;
    /** v85: предлагать ли валюту в пикерах эквивалента (см. EquivalentCurrencies). */
    private boolean showInEquivalent;
    /** v85: строка из общего справочника сервера — форма только для чтения. */
    private boolean shared;

    public int getId() { return id; }
    public String getIso() { return iso; }
    public String getName() { return name; }
    public Icon getIcon() { return icon; }
    public String getSymbol() { return symbol; }
    public int getPrecision() { return precision; }
    public boolean isShowInEquivalent() { return showInEquivalent; }
    public boolean isShared() { return shared; }

    public Currency(int id, String iso, String name, Icon icon, String symbol, int precision) {
        this(id, iso, name, icon, symbol, precision, true, false);
    }

    public Currency(int id, String iso, String name, Icon icon, String symbol, int precision,
                    boolean showInEquivalent, boolean shared) {
        this.id = id;
        this.iso = iso;
        this.name = name;
        this.icon = icon;
        this.symbol = symbol;
        this.precision = precision;
        this.showInEquivalent = showInEquivalent;
        this.shared = shared;
    }
}
