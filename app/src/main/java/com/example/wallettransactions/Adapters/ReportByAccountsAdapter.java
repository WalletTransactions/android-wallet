package com.example.wallettransactions.Adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.ReportItems.ReportByAccount;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Строки отчёта по категориям (report_byaccount_item_layout.xml).
 *
 * Сортировка по убыванию суммы и полоса доли — часть макета: без порядка
 * полосы не читаются. Ширина полосы берётся от самой большой категории
 * (у верхней строки полоса всегда полная), а процент — от общей суммы отчёта.
 */
public class ReportByAccountsAdapter  extends ArrayAdapter<AccountItem> {

    private final Context context;
    private final ReportByAccount report;
    private final IconList fIconList;
    private final Currency mCurrencyEq;
    private final int barColorRes;

    /** Сумма в эквиваленте по категории; null — эквивалент посчитать не удалось. */
    private final Map<AccountItem, Double> sums = new HashMap<>();
    /** Была ли конвертация — определяет знак ≈ перед суммой. */
    private final Map<AccountItem, Boolean> converted = new HashMap<>();

    private double maxAbs = 0.0;
    private double totalAbs = 0.0;

    public ReportByAccountsAdapter(@NonNull Context context, ReportByAccount report,
                                   IconList iconList, Currency currencyEq, int barColorRes) {

        super(context, R.layout.report_byaccount_item_layout, sortedAccounts(report));
        this.report = report;
        this.context = context;
        this.fIconList = iconList;
        this.mCurrencyEq = currencyEq;
        this.barColorRes = barColorRes;

        for (int i = 0; i < getCount(); i++) {
            AccountItem account = getItem(i);
            Totals t = totalsFor(report, account, currencyEq);
            sums.put(account, t.sumEq);
            converted.put(account, t.converted);

            if (t.sumEq != null) {
                double abs = Math.abs(t.sumEq);
                totalAbs += abs;
                if (abs > maxAbs) maxAbs = abs;
            }
        }
    }

    /** Категории по убыванию суммы — порядок HashMap для диаграммы не годится. */
    private static ArrayList<AccountItem> sortedAccounts(ReportByAccount report) {
        ArrayList<AccountItem> accounts = new ArrayList<>(report.ReportAccounts.keySet());

        final Map<AccountItem, Double> weight = new HashMap<>();
        for (AccountItem account : accounts) {
            Totals t = totalsFor(report, account, null);
            weight.put(account, t.sumEq == null ? -1.0 : Math.abs(t.sumEq));
        }

        Collections.sort(accounts, (a, b) -> Double.compare(weight.get(b), weight.get(a)));
        return accounts;
    }

    private static class Totals {
        Double sumEq;
        boolean converted;
    }

    private static Totals totalsFor(ReportByAccount report, AccountItem account, Currency currencyEq) {
        Totals result = new Totals();
        List<ReportByAccount.ReportByAccountItemCurrency> curList = report.ReportAccounts.get(account);
        if (curList == null) return result;

        double sum = 0.0;
        for (ReportByAccount.ReportByAccountItemCurrency cur : curList) {
            if (currencyEq != null && !cur.currency.equals(currencyEq)) result.converted = true;

            if (cur.amountEq == null) return result; // sumEq остаётся null
            sum += cur.amountEq;
        }
        result.sumEq = sum;
        return result;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View listItem = convertView;

        Holder holder;

        if (listItem == null) {
            listItem = LayoutInflater.from(context).inflate(R.layout.report_byaccount_item_layout, parent, false);

            holder = new Holder(listItem.findViewById(R.id.textName),
                    listItem.findViewById(R.id.textBalance),
                    listItem.findViewById(R.id.textShare),
                    listItem.findViewById(R.id.imageView),
                    listItem.findViewById(R.id.ListViewFc),
                    listItem.findViewById(R.id.barFill),
                    listItem.findViewById(R.id.barRest));
            listItem.setTag(holder);

        } else {
            holder = (Holder) convertView.getTag();
        }

        AccountItem сhargeItem = getItem(position);

        ArrayList<ReportByAccount.ReportByAccountItemCurrency> curList = report.ReportAccounts.get(сhargeItem);

        holder.ListViewItem.removeAllViews();
        for (ReportByAccount.ReportByAccountItemCurrency cur : curList) {
            if (cur.currency.equals(mCurrencyEq) && (curList.size() == 1))
                break;

            View listSubItems = LayoutInflater.from(context).inflate(R.layout.accountoperation_value_currency_item_layout, null);

            ImageView imgSb = listSubItems.findViewById(R.id.imageAccountView);
            TextView txSb = listSubItems.findViewById(R.id.textAmount);
            TextView curSb = listSubItems.findViewById(R.id.textCurr);

            imgSb.setImageBitmap(cur.currency.getIcon().getBitmap());
            txSb.setText(cur.amount.toString());
            curSb.setText(cur.currency.getSymbol());
            holder.ListViewItem.addView(listSubItems);
        }

        Double sumEq = sums.get(сhargeItem);
        boolean wasConverted = Boolean.TRUE.equals(converted.get(сhargeItem));

        // ~ means the figure could not be produced, ≈ that it was converted
        String aproxSign = sumEq == null ? "~" : wasConverted ? "≈" : "";

        if (sumEq == null) {
            holder.TextBalance.setText(aproxSign + "?");
            holder.TextBalance.setTextColor(ContextCompat.getColor(context, R.color.colortransfer));
        }
        else {
            holder.TextBalance.setText(aproxSign + com.example.wallettransactions.AdapterUtils
                    .getFormat(mCurrencyEq != null ? mCurrencyEq.getPrecision() : 2)
                    .format(sumEq));

            if ((сhargeItem.isOperatingAccount() && sumEq > 0) || (!сhargeItem.isOperatingAccount() && sumEq < 0)) {
                holder.TextBalance.setTextColor(ContextCompat.getColor(context, R.color.colorprofit));
            } else if ((сhargeItem.isOperatingAccount() && sumEq < 0) || (!сhargeItem.isOperatingAccount() && sumEq > 0)) {
                holder.TextBalance.setTextColor(ContextCompat.getColor(context, R.color.colorexpanse));
            } else
                holder.TextBalance.setTextColor(ContextCompat.getColor(context, R.color.colortransfer));
        }

        bindShare(holder, sumEq);

        holder.TvName.setText(сhargeItem.getName());
        holder.Img.setImageBitmap(сhargeItem.getIcon().getBitmap());

        return listItem;
    }

    private void bindShare(Holder holder, Double sumEq) {
        double abs = sumEq == null ? 0.0 : Math.abs(sumEq);

        double share = maxAbs > 0 ? abs / maxAbs : 0.0;
        setWeight(holder.BarFill, (float) share);
        setWeight(holder.BarRest, (float) (1.0 - share));
        holder.BarFill.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(context, barColorRes)));

        if (sumEq == null || totalAbs <= 0) {
            holder.TextShare.setText("");
        } else {
            holder.TextShare.setText(Math.round(abs / totalAbs * 100) + "%");
        }
    }

    private static void setWeight(View view, float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.weight = weight;
        view.setLayoutParams(params);
    }

    static class Holder
    {
        public TextView TvName;
        public TextView TextBalance;
        public TextView TextShare;
        public ImageView Img;
        public LinearLayout ListViewItem;
        public View BarFill;
        public View BarRest;

        public Holder (TextView tvName, TextView textBalance, TextView textShare, ImageView img,
                       LinearLayout listViewItem, View barFill, View barRest)
        {
            TvName = tvName;
            TextBalance = textBalance;
            TextShare = textShare;
            Img = img;
            ListViewItem = listViewItem;
            BarFill = barFill;
            BarRest = barRest;
        }
    }
}
