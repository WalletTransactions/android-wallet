package com.example.wallettransactions.DataItems.Accounts

import android.database.sqlite.SQLiteDatabase

import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.Icon

open class AccountOperatingItem(
    id: Int,
    name: String,
    comment: String?,
    icon: Icon?,
    isArchived: Boolean,
    currency: Currency?,
    systemKind: AccountItem.SystemKind,
    kind: AccountItem.Kind
) : AccountItem(id, name, comment, icon, isArchived, systemKind, kind) {

    val currency: Currency? = currency

    @JvmField
    var CurrentBalance: Double = 0.0

    /**
     * Признак капитального актива (openspec capital-assets, design п. 6): тип и «Учитывать во
     * „Всего с капиталом“» из живой строки {@code account_asset}. {@code null} — обычный
     * (ликвидный) кошелёк.
     */
    class AssetInfo(
        @JvmField val type: Int,
        @JvmField val includeInNetWorth: Boolean
    )

    var asset: AssetInfo? = null
        private set

    fun setAsset(asset: AssetInfo?) {
        this.asset = asset
    }

    val isAsset: Boolean
        get() = asset != null

    /**
     * Признак долгового кошелька (openspec debts, design D4) из живой строки
     * {@code account_debt}. {@code null} — не долговой. Знак баланса — направление:
     * «+» мне должны, «−» я должен.
     */
    class DebtInfo(
        @JvmField val counterpartyUuid: String?,
        comment: String?,
        /** Срок возврата {@code yyyy-MM-dd} или {@code null}. */
        @JvmField val dueDate: String?
    ) {
        @JvmField
        val comment: String = comment ?: ""
    }

    var debt: DebtInfo? = null
        private set

    fun setDebt(debt: DebtInfo?) {
        this.debt = debt
    }

    val isDebt: Boolean
        get() = debt != null

    /**
     * Balance converted to {@code currencyEq}, or {@code null} when no rate exists.
     * Never falls back to a 1:1 conversion, and returns a boxed value rather than a
     * negative sentinel because a wallet in the red has a legitimately negative
     * equivalent.
     */
    fun getBalanceEq(currencyEq: Currency?, db: SQLiteDatabase): Double? {
        if (currency == null || currencyEq == null) {
            return null
        }

        if (currency.id == currencyEq.id) {
            return CurrentBalance
        }

        // Query for conversion rate
        val cursor = db.rawQuery(
            "SELECT rate FROM v_currency_conversion_rate " +
                "WHERE from_currency_id = ? " +
                "  AND to_currency_id = ? " +
                "ORDER BY date DESC " +
                "LIMIT 1",
            arrayOf(
                currency.id.toString(),
                currencyEq.id.toString()
            )
        )

        try {
            if (cursor.moveToFirst()) {
                return CurrentBalance * cursor.getDouble(0)
            }
        } finally {
            cursor.close()
        }

        return null // Нет курса конвертации
    }

    /** True when showing the equivalent means converting, i.e. the marker is warranted. */
    fun isConverted(currencyEq: Currency?): Boolean {
        return currency == null || currencyEq == null
            || currency.id != currencyEq.id
    }
}
