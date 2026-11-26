package com.example.wallettransactions.DataItems.Accounts

/**
 * Контрагент долга (openspec debts, design D1/D4) — строка {@code counterparty}. Займы
 * ссылаются на него по {@link #uuid} ({@code account_debt.counterparty_uuid}), поэтому
 * переименование не трогает кошельки.
 */
class CounterpartyItem(
    @JvmField val id: Int,
    @JvmField val uuid: String,
    @JvmField val name: String,
    comment: String?,
    /** uuid иконки или {@code null}. */
    @JvmField val iconUuid: String?,
    @JvmField val isArchived: Boolean
) {
    @JvmField
    val comment: String = comment ?: ""

    override fun toString(): String {
        return name
    }
}
