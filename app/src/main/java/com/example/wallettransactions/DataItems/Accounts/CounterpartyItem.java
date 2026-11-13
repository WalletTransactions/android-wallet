package com.example.wallettransactions.DataItems.Accounts;

/**
 * Контрагент долга (openspec debts, design D1/D4) — строка {@code counterparty}. Займы
 * ссылаются на него по {@link #uuid} ({@code account_debt.counterparty_uuid}), поэтому
 * переименование не трогает кошельки.
 */
public final class CounterpartyItem {
    public final int id;
    public final String uuid;
    public final String name;
    public final String comment;
    /** uuid иконки или {@code null}. */
    public final String iconUuid;
    public final boolean isArchived;

    public CounterpartyItem(int id, String uuid, String name, String comment, String iconUuid, boolean isArchived) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.comment = comment == null ? "" : comment;
        this.iconUuid = iconUuid;
        this.isArchived = isArchived;
    }

    @Override
    public String toString() {
        return name;
    }
}
