package com.example.wallettransactions.DataItems.Accounts;

import com.example.wallettransactions.DataItems.Operations.ListChanged;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class AccountList extends Hashtable<Integer, AccountItem> {

    public List<ListChanged> onListChanged = new ArrayList<ListChanged>();

    public void notifyObservers()
    {
        for (ListChanged itm: onListChanged)
        {
            itm.notifyDataSetChanged();
        }
    }

    public AccountItem getAccountById(Integer id) {
        return get(id);
    }


    public AccountItem getAccountByName(String name) {

        for (Map.Entry<Integer, AccountItem> acc: this.entrySet())
        {
            if (acc.getValue().getName().equals(name))
                return acc.getValue();
        }
        return null;
    }

    /**
     * Системная категория по {@code system_kind} — версия {@link #getAccountByName} для
     * поиска, который переживает переименование категории (v75, см. {@link AccountItem.SystemKind}).
     */
    public AccountItem getAccountBySystemKind(AccountItem.SystemKind systemKind) {
        for (Map.Entry<Integer, AccountItem> acc : this.entrySet())
        {
            if (acc.getValue().getSystemKind() == systemKind)
                return acc.getValue();
        }
        return null;
    }

    public ArrayList<AccountItem> getAllAccounts() {
        return new ArrayList<AccountItem>(this.values());
    }

    public ArrayList<AccountItem> getIncomeAccounts() {
        return new ArrayList<AccountItem>(this.values());
    }


    public ArrayList<AccountItem> getExpanseAccounts() {
        ArrayList<AccountItem> res = new ArrayList<AccountItem>();

        for (AccountItem itm : values()) {
            if (!(itm.isOperatingAccount())) {
                res.add(itm);
            }
        }
        return res;
    }

    /**
     * Кошельки с валютой. Кошелёк ({@code kind == WALLET}) без строки
     * {@code account_operating} загружается простым {@link AccountItem} (см.
     * {@code WalletDbLoader.LoadAccounts}) — без валюты ему здесь не место, а
     * приведение типа роняло бы пикер эквивалента. Такие кошельки оставлял
     * pull, пока не научился откладывать строки (sync.PullDeferredRows).
     */
    public ArrayList<AccountOperatingItem> getOperationAccounts() {
        ArrayList<AccountOperatingItem> res = new ArrayList<AccountOperatingItem>();

        for (AccountItem itm : values()) {
            if (itm.isOperatingAccount() && itm instanceof AccountOperatingItem) {
                res.add((AccountOperatingItem)itm);
            }
        }
        return res;
    }

    public ArrayList<AccountItem> getFilteredOperationAccounts() {
        ArrayList<AccountItem> res = new ArrayList<AccountItem>();

        for (AccountItem itm : values()) {
            if (itm.isOperatingAccount()) {
                res.add(itm);
            }
        }
        return res;
    }
}

