package com.example.wallettransactions.DataItems.Accounts;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.Icon;

public class AccountOperatingItem extends AccountItem {
    private Currency currency;

    public Currency getCurrency(){
        return currency;
    }

    public double CurrentBalance;

    public AccountOperatingItem(Integer id, String name, String comment, Icon icon, boolean isArchived,
                                 Currency currency, AccountItem.SystemKind systemKind, AccountItem.Kind kind)
    {
        super(id, name, comment, icon, isArchived, systemKind, kind);
        this.currency = currency;
        this.CurrentBalance=0;
    }

    /**
     * Balance converted to {@code currencyEq}, or {@code null} when no rate exists.
     * Never falls back to a 1:1 conversion, and returns a boxed value rather than a
     * negative sentinel because a wallet in the red has a legitimately negative
     * equivalent.
     */
    public Double getBalanceEq(Currency currencyEq, SQLiteDatabase db) {
        if (currency == null || currencyEq == null) {
            return null;
        }

        if (currency.getId() == currencyEq.getId()) {
            return CurrentBalance;
        }

        // Query for conversion rate
        Cursor cursor = db.rawQuery(
            "SELECT rate FROM v_currency_conversion_rate " +
            "WHERE from_currency_id = ? " +
            "  AND to_currency_id = ? " +
            "ORDER BY date DESC " +
            "LIMIT 1",
            new String[]{String.valueOf(currency.getId()),
                       String.valueOf(currencyEq.getId())});

        try {
            if (cursor.moveToFirst()) {
                return CurrentBalance * cursor.getDouble(0);
            }
        } finally {
            cursor.close();
        }

        return null; // Нет курса конвертации
    }

    /** True when showing the equivalent means converting, i.e. the marker is warranted. */
    public boolean isConverted(Currency currencyEq) {
        return currency == null || currencyEq == null
                || currency.getId() != currencyEq.getId();
    }
}
