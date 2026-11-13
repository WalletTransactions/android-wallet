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

    /**
     * Признак капитального актива (openspec capital-assets, design п. 6): тип и «Учитывать во
     * „Всего с капиталом“» из живой строки {@code account_asset}. {@code null} — обычный
     * (ликвидный) кошелёк.
     */
    public static final class AssetInfo {
        public final int type;
        public final boolean includeInNetWorth;

        public AssetInfo(int type, boolean includeInNetWorth) {
            this.type = type;
            this.includeInNetWorth = includeInNetWorth;
        }
    }

    private AssetInfo asset;

    public AssetInfo getAsset() {
        return asset;
    }

    public void setAsset(AssetInfo asset) {
        this.asset = asset;
    }

    public boolean isAsset() {
        return asset != null;
    }

    /**
     * Признак долгового кошелька (openspec debts, design D4) из живой строки
     * {@code account_debt}. {@code null} — не долговой. Знак баланса — направление:
     * «+» мне должны, «−» я должен.
     */
    public static final class DebtInfo {
        public final String counterpartyUuid;
        public final String comment;
        /** Срок возврата {@code yyyy-MM-dd} или {@code null}. */
        public final String dueDate;

        public DebtInfo(String counterpartyUuid, String comment, String dueDate) {
            this.counterpartyUuid = counterpartyUuid;
            this.comment = comment == null ? "" : comment;
            this.dueDate = dueDate;
        }
    }

    private DebtInfo debt;

    public DebtInfo getDebt() {
        return debt;
    }

    public void setDebt(DebtInfo debt) {
        this.debt = debt;
    }

    public boolean isDebt() {
        return debt != null;
    }

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
