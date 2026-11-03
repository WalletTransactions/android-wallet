package com.example.wallettransactions;

import android.icu.text.DecimalFormat;
import androidx.core.content.ContextCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.example.wallettransactions.Adapters.TransactionArrayAdapter;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;

import java.util.HashMap;
import java.util.Map;

import static java.lang.StrictMath.abs;

public class AdapterUtils {
    private static final Map<Integer, DecimalFormat> formatCache = new HashMap<>();

    public static DecimalFormat getFormat(int precision) {
        return formatCache.computeIfAbsent(precision, p -> {
            StringBuilder pattern = new StringBuilder("#,##0");
            if (p > 0) {
                pattern.append(".");
                for (int i = 0; i < p; i++) pattern.append("0");
            }
            return new DecimalFormat(pattern.toString());
        });
    }

    public static String formatAmount(double amount, Currency currency) {
        return getFormat(currency != null ? currency.getPrecision() : 2).format(amount);
    }

    public static void setListViewHeightBasedOnChildren(ListView listView) {
        ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter == null) {
            // pre-condition
            return;
        }

        int totalHeight = listView.getPaddingTop() + listView.getPaddingBottom();

        for (int i = 0; i < listAdapter.getCount(); i++) {
            View listItem = listAdapter.getView(i, null, listView);
            if (listItem instanceof ViewGroup) {
                listItem.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            listItem.measure(0, 0);
            totalHeight += listItem.getMeasuredHeight();
        }

        ViewGroup.LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight + (listView.getDividerHeight() * (listAdapter.getCount() - 1));
        listView.setLayoutParams(params);
    }

    public static void setListViewHeightBasedOnChildren2(ListView listView) {
        ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter == null) {
            // pre-condition
            return;
        }

        int totalHeight = 0;
        int desiredWidth = View.MeasureSpec.makeMeasureSpec(listView.getWidth(), View.MeasureSpec.AT_MOST);
        for (int i = 0; i < listAdapter.getCount(); i++) {
            View listItem = listAdapter.getView(i, null, listView);
            listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED);
            totalHeight += listItem.getMeasuredHeight();
        }

        ViewGroup.LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight + (listView.getDividerHeight() * (listAdapter.getCount() - 1));
        listView.setLayoutParams(params);
        listView.requestLayout();
    }

    public enum Operation  {expanse, deposit, transfer}


    public static void setAmountText(TextView textView, EquivalentResult eqAmount,  Currency currency) {
        setAmountText(textView, eqAmount.getTotalAmountEq(), currency,
                deriveOperation(eqAmount.getTotalAmountEq()),
                eqAmount.getApproxEq(), eqAmount.isIncomplete());
    }

    public static void setAmountText(TextView textView, EquivalentResult eqAmount, Currency currency,
                                     Operation operation) {
        setAmountText(textView, eqAmount.getTotalAmountEq(), currency, operation,
                eqAmount.getApproxEq(), eqAmount.isIncomplete());
    }

    private static Operation deriveOperation(Double amount) {
        if (amount == null) return Operation.transfer;
        return amount > 0 ? Operation.deposit : amount < 0 ? Operation.expanse : Operation.transfer;
    }

    public static void setAmountExpanseText(TextView textView, Double amount, Currency currency) {
        setAmountText(textView, amount, currency, Operation.expanse, false);
    }

    public static void setAmountDepositText(TextView textView, Double amount, Currency currency) {
        setAmountText(textView, amount, currency, Operation.deposit, false);
    }

    public static void setAmountTransferText(TextView textView, Double amount, Currency currency) {
        setAmountText(textView, amount, currency, Operation.transfer, false);
    }

    public static void setAmountText(TextView textView, Double amount, Currency currency, boolean isAprox) {
        setAmountText(textView, amount, currency, deriveOperation(amount), isAprox, false);
    }

    /** Colours by the sign of {@code amount}, so keep this for figures that can go negative. */
    public static void setAmountText(TextView textView, Double amount, Currency currency,
                                     boolean isAprox, boolean isIncomplete) {
        setAmountText(textView, amount, currency, deriveOperation(amount), isAprox, isIncomplete);
    }

    public static void setAmountText(TextView textView, Double amount, Currency currency, Operation operation, boolean isAprox) {
        setAmountText(textView, amount, currency, operation, isAprox, false);
    }

    /**
     * @param isAprox      a conversion was applied — marked with {@code ≈}
     * @param isIncomplete something could not be converted at all — marked with {@code ~},
     *                     which wins over {@code ≈} because the figure is not just rounded
     *                     but missing a part
     */
    public static void setAmountText(TextView textView, Double amount, Currency currency,
                                     Operation operation, boolean isAprox, boolean isIncomplete) {
        DecimalFormat fmt = getFormat(currency != null ? currency.getPrecision() : 2);
        String amText;
        if (amount == null)
            amText = "?";
        else
            if (operation == Operation.expanse)
                amText = fmt.format(-1 * abs(amount));
            else
                amText = fmt.format(amount);



        String aproxSign = isIncomplete ? "~" : isAprox ? "≈" : "";
        // Валюты может не быть: кошелёк без строки account_operating (см.
        // AccountList.getOperationAccounts) — сумму показываем без символа.
        String symbol = currency != null ? currency.getSymbol() : "";
        switch (operation) {
            case expanse:

                textView.setTextColor(ContextCompat.getColor(textView.getContext(), R.color.colorexpanse));
                textView.setText(aproxSign + amText + symbol);
                return;

            case deposit:
                textView.setTextColor(ContextCompat.getColor(textView.getContext(), R.color.colorprofit));
                textView.setText(aproxSign + amText + symbol);
                return;

            case transfer:
                textView.setTextColor(ContextCompat.getColor(textView.getContext(), R.color.colortransfer));
                textView.setText(aproxSign + amText + symbol);
                return;

        }
    }
}
