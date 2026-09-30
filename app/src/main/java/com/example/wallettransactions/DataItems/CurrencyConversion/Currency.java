package com.example.wallettransactions.DataItems.CurrencyConversion;


import com.example.wallettransactions.DataItems.Icon;

public class Currency {
    private int id;
    private String iso;
    private String name;
    private Icon icon;
    private String symbol;
    private int precision;

    public int getId() { return id; }
    public String getIso() { return iso; }
    public String getName() { return name; }
    public Icon getIcon() { return icon; }
    public String getSymbol() { return symbol; }
    public int getPrecision() { return precision; }

    public Currency(int id, String iso, String name, Icon icon, String symbol, int precision) {
        this.id = id;
        this.iso = iso;
        this.name = name;
        this.icon = icon;
        this.symbol = symbol;
        this.precision = precision;
    }
}
