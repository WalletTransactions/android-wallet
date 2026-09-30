package com.example.wallettransactions.Adapters;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.R;

import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CurrencyPairRateAdapter extends ArrayAdapter<Pair<Date, Double>> {

    private Context context;
    private List<Pair<Date, Double>> currencyPairRate;
    private int precision;
    private int currencyPairId;
    private OnRateClickListener listener;

    private String dateFormat = "%1$td %1$tb %1$tY";

    public interface OnRateClickListener {
        void onRateClick(Date rateDate, double rateValue);
    }

    /**
     * Изменение курса в процентах. Пустая строка, если сравнивать не с чем —
     * у самой старой записи предыдущего курса нет.
     */
    public static String formatDelta(Double previous, double current) {
        if (previous == null || previous == 0) return "";
        return String.format(Locale.ROOT, "%+.2f%%", (current - previous) / previous * 100);
    }

    public static int deltaColorRes(Double previous, double current) {
        if (previous == null || previous == 0 || current == previous) return R.color.colortransfer;
        return current > previous ? R.color.colorprofit : R.color.colorexpanse;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View listItem = convertView;
        Holder holder;

        if (listItem == null) {
            listItem = LayoutInflater.from(context).inflate(R.layout.currencypair_rate_list_content, parent, false);

            holder = new CurrencyPairRateAdapter.Holder(listItem.findViewById(R.id.textDate),
                    listItem.findViewById(R.id.textRate),
                    listItem.findViewById(R.id.textDelta));
            listItem.setTag(holder);

        } else {
            holder = (CurrencyPairRateAdapter.Holder) convertView.getTag();
        }

        Pair<Date, Double> rate = getItem(position);

        holder.TextRate.setText(AdapterUtils.getFormat(precision).format(rate.second));
        holder.TvDate.setText(String.format(dateFormat, rate.first));

        // Список отсортирован от новых к старым, поэтому предыдущий курс — следующий по списку.
        Double previous = position + 1 < getCount() ? getItem(position + 1).second : null;
        holder.TextDelta.setText(formatDelta(previous, rate.second));
        holder.TextDelta.setTextColor(
                ContextCompat.getColor(context, deltaColorRes(previous, rate.second)));

        if (listener != null) {
            listItem.setOnClickListener(v -> listener.onRateClick(rate.first, rate.second));
        }

        return listItem;
    }

    public void setOnRateClickListener(OnRateClickListener listener) {
        this.listener = listener;
    }

    public void setCurrencyPairId(int id) {
        this.currencyPairId = id;
    }

    public int getPrecision() {
        return precision;
    }

    static class Holder {
        public TextView TvDate;
        public TextView TextRate;
        public TextView TextDelta;

        public Holder(TextView tvDate, TextView textRate, TextView textDelta) {
            TvDate = tvDate;
            TextRate = textRate;
            TextDelta = textDelta;
        }
    }

    public CurrencyPairRateAdapter(@NonNull Context context, List<Pair<Date, Double>> currencyPairRate, int precision) {
        super(context, R.layout.currencypair_rate_list_content, currencyPairRate);
        this.currencyPairRate = currencyPairRate;
        this.context = context;
        this.precision = precision;
    }

}
