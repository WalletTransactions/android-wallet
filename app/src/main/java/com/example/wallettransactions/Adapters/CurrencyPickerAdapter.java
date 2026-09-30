package com.example.wallettransactions.Adapters;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rows for CurrencyPickerActivity's list (currency_picker_item_layout.xml) — filters by
 * ISO code or name, marks the currently-selected currency (if any) with its radio dot. */
public class CurrencyPickerAdapter extends BaseAdapter implements Filterable {

    /** Тап по ✎ в строке — правка валюты, отдельно от выбора строки. */
    public interface OnEditCurrencyListener {
        void onEditCurrency(Currency currency);
    }

    private final Context context;
    private final List<Currency> all;
    private List<Currency> shown;
    private final String selectedIso;
    private OnEditCurrencyListener editListener;

    public CurrencyPickerAdapter(Context context, List<Currency> all, @Nullable String selectedIso) {
        this.context = context;
        this.all = all;
        this.shown = new ArrayList<>(all);
        this.selectedIso = selectedIso;
    }

    public void setOnEditCurrencyListener(OnEditCurrencyListener listener) {
        this.editListener = listener;
    }

    /** Список валют живёт в синглтоне и мог измениться в форме — перечитываем. */
    public void replaceAll(List<Currency> currencies) {
        all.clear();
        all.addAll(currencies);
        shown = new ArrayList<>(all);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return shown.size();
    }

    @Override
    public Currency getItem(int position) {
        return shown.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        if (view == null)
            view = LayoutInflater.from(context).inflate(R.layout.currency_picker_item_layout, parent, false);

        Currency c = getItem(position);
        ((TextView) view.findViewById(R.id.currencyCode)).setText(c.getIso());
        ((TextView) view.findViewById(R.id.currencyName)).setText(c.getName());
        ((TextView) view.findViewById(R.id.currencySymbol)).setText(c.getSymbol());
        view.findViewById(R.id.selectMark)
                .setVisibility(c.getIso().equals(selectedIso) ? View.VISIBLE : View.GONE);

        ImageView icon = view.findViewById(R.id.currencyIcon);
        if (c.getIcon() != null) icon.setImageBitmap(c.getIcon().getBitmap());
        else icon.setImageDrawable(null);

        View edit = view.findViewById(R.id.btnEditCurrency);
        edit.setOnClickListener(v -> {
            if (editListener != null) editListener.onEditCurrency(c);
        });

        return view;
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String q = constraint == null ? "" : constraint.toString().trim().toUpperCase(Locale.ROOT);
                List<Currency> filtered = new ArrayList<>();
                for (Currency c : all) {
                    if (q.isEmpty()
                            || c.getIso().toUpperCase(Locale.ROOT).contains(q)
                            || (c.getName() != null && c.getName().toUpperCase(Locale.ROOT).contains(q))) {
                        filtered.add(c);
                    }
                }
                FilterResults results = new FilterResults();
                results.values = filtered;
                results.count = filtered.size();
                return results;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                shown = (List<Currency>) results.values;
                notifyDataSetChanged();
            }
        };
    }
}
