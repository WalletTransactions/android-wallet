package com.example.wallettransactions.Adapters;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair;
import com.example.wallettransactions.R;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;

/**
 * Row adapter for the "Курсы валют" pair list ({@code pair_item_layout.xml}) — flags, symbol,
 * plus the pair's latest known rate/date and the % change against the previous known rate.
 */
public class CurrencyPairListAdapter extends ArrayAdapter<CurrencyPair> {

    private final Context context;
    private final SQLiteDatabase db;
    private final String dateFormat = "%1$td %1$tb %1$tY";

    public CurrencyPairListAdapter(@NonNull Context context, @NonNull List<CurrencyPair> items, SQLiteDatabase db) {
        super(context, R.layout.pair_item_layout, items);
        this.context = context;
        this.db = db;
    }

    /** Swaps in a new (e.g. filtered) list and redraws. */
    public void setItems(List<CurrencyPair> items) {
        clear();
        addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        Holder holder;

        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.pair_item_layout, parent, false);
            holder = new Holder();
            holder.flagA = view.findViewById(R.id.flagA);
            holder.flagB = view.findViewById(R.id.flagB);
            holder.pairName = view.findViewById(R.id.pairName);
            holder.pairDate = view.findViewById(R.id.pairDate);
            holder.pairRate = view.findViewById(R.id.pairRate);
            holder.pairDelta = view.findViewById(R.id.pairDelta);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }

        CurrencyPair cp = getItem(position);
        if (cp == null) return view;

        holder.flagA.setImageBitmap(cp.getBaseCurrency().getIcon().getBitmap());
        holder.flagB.setImageBitmap(cp.getQuoteCurrency().getIcon().getBitmap());
        holder.pairName.setText(cp.getSymbol());

        // Full history, sorted DESC by date (see WalletDbLoader.LoadCurrencyPairRateList) — the
        // first two rows are the latest rate and the one right before it.
        List<Pair<Date, Double>> rates = WalletDbLoader.LoadCurrencyPairRateList(db, cp.getSymbol());

        if (rates.isEmpty()) {
            holder.pairDate.setText("Нет курса");
            holder.pairRate.setText("—");
            holder.pairDelta.setText("");
        } else {
            Pair<Date, Double> latest = rates.get(0);
            holder.pairDate.setText(String.format(dateFormat, latest.first));
            holder.pairRate.setText(AdapterUtils.getFormat(cp.getPrecision()).format(latest.second));

            if (rates.size() > 1 && rates.get(1).second != 0) {
                double prev = rates.get(1).second;
                double pct = (latest.second - prev) / prev * 100.0;
                DecimalFormat pctFmt = new DecimalFormat("0.00");
                String sign = pct >= 0 ? "+" : "-";
                holder.pairDelta.setText(sign + pctFmt.format(Math.abs(pct)) + "%");
                holder.pairDelta.setTextColor(context.getResources().getColor(
                        pct >= 0 ? R.color.colorprofit : R.color.colorexpanse));
            } else {
                holder.pairDelta.setText("");
            }
        }

        return view;
    }

    private static class Holder {
        ImageView flagA, flagB;
        TextView pairName, pairDate, pairRate, pairDelta;
    }
}
