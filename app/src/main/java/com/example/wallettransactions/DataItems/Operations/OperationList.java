package com.example.wallettransactions.DataItems.Operations;

import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;

import java.util.*;


public class OperationList extends ArrayList<OperationItem> {

    public List<ListChanged> onListChanged = new ArrayList<ListChanged>();

    public void notifyObservers()
    {
        for (ListChanged itm: onListChanged)
        {
            itm.notifyDataSetChanged();
        }
    }


}
