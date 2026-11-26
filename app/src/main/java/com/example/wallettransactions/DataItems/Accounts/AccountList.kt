package com.example.wallettransactions.DataItems.Accounts

import com.example.wallettransactions.DataItems.Operations.ListChanged

import java.util.Hashtable

class AccountList : Hashtable<Int, AccountItem>() {

    @JvmField
    var onListChanged: MutableList<ListChanged> = ArrayList()

    fun notifyObservers() {
        for (itm in onListChanged) {
            itm.notifyDataSetChanged()
        }
    }

    fun getAccountById(id: Int): AccountItem? {
        return get(id)
    }


    fun getAccountByName(name: String?): AccountItem? {

        for (acc in this.entries) {
            if (acc.value.name == name)
                return acc.value
        }
        return null
    }

    /**
     * Системная категория по {@code system_kind} — версия {@link #getAccountByName} для
     * поиска, который переживает переименование категории (v75, см. {@link AccountItem.SystemKind}).
     */
    fun getAccountBySystemKind(systemKind: AccountItem.SystemKind?): AccountItem? {
        for (acc in this.entries) {
            if (acc.value.systemKind == systemKind)
                return acc.value
        }
        return null
    }

    val allAccounts: ArrayList<AccountItem>
        get() = ArrayList<AccountItem>(this.values)

    val incomeAccounts: ArrayList<AccountItem>
        get() = ArrayList<AccountItem>(this.values)


    val expanseAccounts: ArrayList<AccountItem>
        get() {
            val res = ArrayList<AccountItem>()

            for (itm in values) {
                if (!(itm.isOperatingAccount)) {
                    res.add(itm)
                }
            }
            return res
        }

    /**
     * Кошельки с валютой. Кошелёк ({@code kind == WALLET}) без строки
     * {@code account_operating} загружается простым {@link AccountItem} (см.
     * {@code WalletDbLoader.LoadAccounts}) — без валюты ему здесь не место, а
     * приведение типа роняло бы пикер эквивалента. Такие кошельки оставлял
     * pull, пока не научился откладывать строки (sync.PullDeferredRows).
     */
    val operationAccounts: ArrayList<AccountOperatingItem>
        get() {
            val res = ArrayList<AccountOperatingItem>()

            for (itm in values) {
                if (itm.isOperatingAccount && itm is AccountOperatingItem) {
                    res.add(itm)
                }
            }
            return res
        }

    val filteredOperationAccounts: ArrayList<AccountItem>
        get() {
            val res = ArrayList<AccountItem>()

            for (itm in values) {
                if (itm.isOperatingAccount) {
                    res.add(itm)
                }
            }
            return res
        }
}
