package com.example.wallettransactions.Adapters;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.icu.text.DecimalFormat;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.IFilterIsLike;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.Collections;

public class AccountsAdapter extends ArrayAdapter<AccountItem> {

    private ArrayList<AccountItem> accounts;
    private IconList fIconList;
    private ColorMatrix grayColorMatrix;
    private Currency currencyEq;
    private Context context;
    private Filter filter;

    private DecimalFormat decimalFormat = new DecimalFormat("#,##0.00");

    public void setAccounts(ArrayList<AccountItem> accounts) {
        this.accounts = accounts;
    }

    @Override
    public Filter getFilter() {
        if (filter == null)
            filter = new AccountFilter<AccountItem>(accounts);
        return filter;
    }



    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View listItem = convertView;

        if(listItem == null)
            listItem = LayoutInflater.from(context).inflate(R.layout.account_item_layout,parent,false);

        TextView tvName =  listItem.findViewById(R.id.textName);
        TextView tvDescr = listItem.findViewById(R.id.textDescription);
        TextView textBalance = listItem.findViewById(R.id.textBalance);
        TextView textCurrencySymbol = listItem.findViewById(R.id.textCurrencySymbol);
        TextView textEquivalent = listItem.findViewById(R.id.textEquivalent);
        ImageView img = listItem.findViewById(R.id.imageView);
        ImageView imgCur = listItem.findViewById(R.id.imageCurencyView);

        AccountItem account = accounts.get(position);

        if (account.isOperatingAccount()) {
            AccountOperatingItem accountOp  = (AccountOperatingItem) account;

            imgCur.setVisibility(View.VISIBLE);
            textBalance.setVisibility(View.VISIBLE);
            textCurrencySymbol.setVisibility(View.VISIBLE);
            textBalance.setText(com.example.wallettransactions.AdapterUtils
                    .getFormat(accountOp.getCurrency().getPrecision())
                    .format(accountOp.CurrentBalance));
            textCurrencySymbol.setText(accountOp.getCurrency().getSymbol());
            imgCur.setImageBitmap(accountOp.getCurrency().getIcon().getBitmap());

            // Show equivalent if available
            if (currencyEq != null) {
                textEquivalent.setVisibility(View.VISIBLE);
                SQLiteDatabase db = new WalletDbHelper(context).getReadableDatabase();
                try {
                    Double equivalent = accountOp.getBalanceEq(currencyEq, db);
                    if (equivalent == null) {
                        textEquivalent.setText("~");
                    } else {
                        textEquivalent.setText(
                                (accountOp.isConverted(currencyEq) ? "≈ " : "")
                                + com.example.wallettransactions.AdapterUtils
                                        .getFormat(currencyEq.getPrecision()).format(equivalent)
                                + " " + currencyEq.getSymbol());
                    }
                } finally {
                    db.close();
                }
            } else {
                textEquivalent.setVisibility(View.GONE);
            }
        } else
        {
            imgCur.setVisibility(View.GONE);
            textBalance.setVisibility(View.GONE);
            textCurrencySymbol.setVisibility(View.GONE);
            textEquivalent.setVisibility(View.GONE);
        }

        tvName.setText(account.getName());
        tvDescr.setText(account.getComment());
        img.setImageBitmap(account.getIcon().getBitmap());

        if (account.getIsArchived())
            img.setColorFilter(new ColorMatrixColorFilter(grayColorMatrix));
        else
            img.setColorFilter(null);

        return listItem;
    }

    public AccountsAdapter(@NonNull Context context, ArrayList<AccountItem> accounts, IconList iconList) {
        this(context, accounts, iconList, null);
    }

    public AccountsAdapter(@NonNull Context context, ArrayList<AccountItem> accounts,
                           IconList iconList, Currency currencyEq) {
        super(context, R.layout.account_item_layout, accounts);
        this.accounts = accounts;
        this.context = context;
        this.fIconList = iconList;
        this.currencyEq = currencyEq;

        grayColorMatrix = new ColorMatrix();
        grayColorMatrix.setSaturation(0);
    }

    private class AccountFilter<T extends IFilterIsLike> extends Filter {
        private ArrayList<T> sourceObjects;

        public AccountFilter(ArrayList<T> accounts) {
            sourceObjects = new ArrayList<T>();
            synchronized (this) {
                sourceObjects.addAll(accounts);
            }
        }

        @Override
        protected FilterResults performFiltering(CharSequence chars) {
            String filterSeq = chars.toString().toLowerCase();
            FilterResults result = new FilterResults();
            if (filterSeq != null && filterSeq.length() > 0) {
                ArrayList<T> filter = new ArrayList<T>();

                for (T object : sourceObjects) {
                    // the filtering itself:
                    if (object.isLike(filterSeq))
                        filter.add(object);
                }
                result.count = filter.size();
                result.values = filter;
            } else {
                // add all objects
                synchronized (this) {
                    result.values = sourceObjects;
                    result.count = sourceObjects.size();
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void publishResults(CharSequence constraint,
                                      FilterResults results) {
            // NOTE: this function is *always* called from the UI thread.
            ArrayList<T> filtered = (ArrayList<T>) results.values;
            notifyDataSetChanged();
            clear();
            for (int i = 0, l = filtered.size(); i < l; i++)
                add((AccountItem) filtered.get(i));
            notifyDataSetInvalidated();
        }
    }
}
