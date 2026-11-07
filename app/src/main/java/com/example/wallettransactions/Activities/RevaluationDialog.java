package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.sync.SyncRunner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Диалог «Переоценка» актива (openspec capital-assets, design п. 8; по образцу
 * {@link RateEditDialog}). Δ = новая оценка − баланс актива по всем его проводкам до конца
 * выбранного дня; запись — {@link WalletDbCRUD#WriteRevaluation} (одна проводка через
 * системную «Переоценку», на конец дня, комментарий «Оценка: …»). После записи —
 * {@code markDataChanged()} и синк в фоне, как «Записать» на экране «ИИ».
 */
public class RevaluationDialog {

    public interface OnSaved {
        void onRevaluationSaved();
    }

    private static final SimpleDateFormat DISPLAY_FORMAT = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

    private final Context context;
    private final AccountOperatingItem asset;
    private final OnSaved listener;
    private final Calendar pickedDate = Calendar.getInstance();
    private AlertDialog dialog;

    public RevaluationDialog(Context context, AccountOperatingItem asset, OnSaved listener) {
        this.context = context;
        this.asset = asset;
        this.listener = listener;
    }

    public void show() {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_revaluation, null);
        EditText edtDate = view.findViewById(R.id.inputDate);
        EditText edtValue = view.findViewById(R.id.inputValue);
        TextView tvDelta = view.findViewById(R.id.textCurrentDelta);

        drawDate(edtDate);
        Runnable refresh = () -> tvDelta.setText(deltaLine(edtValue.getText().toString()));
        edtDate.setOnClickListener(v -> CalendarPicker.show(context, pickedDate, (year, month, day) -> {
            pickedDate.set(year, month, day);
            drawDate(edtDate);
            refresh.run();
        }));
        edtValue.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh.run(); }
        });
        refresh.run();

        dialog = new AlertDialog.Builder(context).setView(view).create();
        view.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnSave).setOnClickListener(v -> save(edtValue));
        dialog.show();
    }

    private void drawDate(EditText field) {
        field.setText(DISPLAY_FORMAT.format(pickedDate.getTime()));
    }

    private String dayStr() {
        return DateUtils.DateToDateStr(pickedDate.getTime());
    }

    private double currentBalance() {
        return WalletDbLoader.LoadAccountBalanceAt(db(), asset.getId(), dayStr() + " 23:59");
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(context);
    }

    private int precision() {
        Currency c = asset.getCurrency();
        return c != null ? c.getPrecision() : 2;
    }

    private String money(double value) {
        Currency c = asset.getCurrency();
        return AdapterUtils.getFormat(precision()).format(value) + " " + (c != null ? c.getSymbol() : "");
    }

    /** «Сейчас: 10 000 000 ₽ · Разница: +2 000 000 ₽». */
    String deltaLine(String input) {
        double current = currentBalance();
        Double value = parse(input);
        if (value == null) return context.getString(R.string.revaluation_current, money(current));
        double delta = round(value - current);
        String sign = delta > 0 ? "+" : delta < 0 ? "−" : "";
        return context.getString(R.string.revaluation_current_delta, money(current), sign + money(Math.abs(delta)));
    }

    private void save(EditText edtValue) {
        Double value = parse(edtValue.getText().toString());
        if (value == null) {
            edtValue.setError(context.getString(R.string.revaluation_enter_value));
            return;
        }
        if (value < 0) {
            edtValue.setError(context.getString(R.string.revaluation_negative));
            return;
        }
        if (BigDecimal.valueOf(value).stripTrailingZeros().scale() > precision()) {
            edtValue.setError(context.getString(R.string.revaluation_too_precise, precision()));
            return;
        }
        WalletDbCRUD.RevaluationResult result = WalletDbCRUD.WriteRevaluation(db(), asset.getId(), dayStr(),
                value, precision(), context.getString(R.string.revaluation_comment, money(value)));
        switch (result) {
            case unchanged:
                Toast.makeText(context, R.string.revaluation_unchanged, Toast.LENGTH_SHORT).show();
                return;
            case negative:
                edtValue.setError(context.getString(R.string.revaluation_negative));
                return;
            case failed:
                Toast.makeText(context, R.string.revaluation_failed, Toast.LENGTH_LONG).show();
                return;
            default:
                break;
        }
        Singleton.instance.reloadAccounts(context);
        PeriodFilterState.get(context).markDataChanged();
        Context app = context.getApplicationContext();
        new Thread(() -> SyncRunner.runOnce(app)).start();
        dialog.dismiss();
        if (listener != null) listener.onRevaluationSaved();
    }

    private double round(double v) {
        return BigDecimal.valueOf(v).setScale(precision(), RoundingMode.HALF_UP).doubleValue();
    }

    private static Double parse(String text) {
        String s = text.trim().replace(',', '.').replace(" ", "");
        if (s.isEmpty() || s.equals("-")) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
