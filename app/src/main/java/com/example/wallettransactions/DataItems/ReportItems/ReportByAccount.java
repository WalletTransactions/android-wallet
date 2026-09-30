package com.example.wallettransactions.DataItems.ReportItems;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;

import java.util.ArrayList;
import java.util.HashMap;

public class ReportByAccount {
    public class ReportByAccountItemCurrency {
        public Double amount;
        public Currency currency;
        public Double amountEq;

        public ReportByAccountItemCurrency(Double amount, Currency currency, Double amountEq)
        {
            this.currency = currency;
            this.amount = amount;
            this.amountEq = amountEq;
        }
    }

    public HashMap<AccountItem, ArrayList<ReportByAccountItemCurrency>> ReportAccounts = new HashMap<>();



    public EquivalentResult getTotal()
    {
        Currency lastCur= null;
        boolean isAprox = false;
        Double total;

        if (ReportAccounts.size() == 0)
            total = null;
        else
            total = 0.0;

        for (ArrayList<ReportByAccountItemCurrency> arr : ReportAccounts.values())
            for (ReportByAccountItemCurrency itm: arr)
        {
            if (lastCur == null)
                lastCur = itm.currency;

            isAprox = isAprox || (lastCur != itm.currency);

            if ((total!=null)&& (itm.amountEq != null))
                total += itm.amountEq;
            else
                total = null;
        }

        return  new EquivalentResult(total, isAprox);
    }

    public ReportByAccountItemCurrency AddSubOper(AccountItem account, Currency currency, Double amount, Double amountEq)
    {
        ArrayList<ReportByAccountItemCurrency> curlist = ReportAccounts.get(account);
        if (curlist == null)
        {
            curlist = new ArrayList<ReportByAccountItemCurrency>();
            ReportAccounts.put(account, curlist);
        }
        ReportByAccountItemCurrency res = new ReportByAccountItemCurrency(amount, currency, amountEq);

        curlist.add(res);
        return res;
    }
}
