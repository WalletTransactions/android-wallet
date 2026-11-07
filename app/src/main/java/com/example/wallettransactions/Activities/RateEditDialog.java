package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * "Добавить/Редактировать курс" — one sheet (dialog_edit_rate.xml) for both, per its own
 * design comment: dialogTitle and btnDelete's visibility just adapt to the mode.
 */
public class RateEditDialog {

    private final Context context;
    private final int currencyPairId;
    private final Date rateDate;
    private final double rateValue;
    private final boolean isNew;
    private final OnRateSavedListener listener;
    private AlertDialog dialog;

    /** Выбранная дата целиком — не разбираем её обратно из текста поля. */
    private final Calendar pickedDate = Calendar.getInstance();
    private static final SimpleDateFormat DISPLAY_FORMAT =
            new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

    public interface OnRateSavedListener {
        void onRateSaved();
        void onRateDeleted();
    }

    /** Edit an existing rate. */
    public RateEditDialog(Context context, int currencyPairId, Date rateDate, double rateValue, OnRateSavedListener listener) {
        this.context = context;
        this.currencyPairId = currencyPairId;
        this.rateDate = rateDate;
        this.rateValue = rateValue;
        this.isNew = false;
        this.listener = listener;
    }

    /** Add a new rate — date defaults to today, value starts blank. */
    public static RateEditDialog newRate(Context context, int currencyPairId, OnRateSavedListener listener) {
        return new RateEditDialog(context, currencyPairId, listener);
    }

    private RateEditDialog(Context context, int currencyPairId, OnRateSavedListener listener) {
        this.context = context;
        this.currencyPairId = currencyPairId;
        this.rateDate = new Date();
        this.rateValue = 0;
        this.isNew = true;
        this.listener = listener;
    }

    public void show() {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_edit_rate, null);

        EditText edtDate = view.findViewById(R.id.inputDate);
        EditText edtValue = view.findViewById(R.id.inputRate);
        View btnDelete = view.findViewById(R.id.btnDelete);

        ((TextView) view.findViewById(R.id.dialogTitle))
                .setText(isNew ? R.string.rate_dialog_add : R.string.rate_dialog_edit);

        pickedDate.setTime(rateDate);
        drawDate(edtDate);
        edtDate.setOnClickListener(v -> CalendarPicker.show(context, pickedDate, (year, month, day) -> {
            pickedDate.set(year, month, day);
            drawDate(edtDate);
        }));

        if (isNew) {
            edtValue.setText("");
            btnDelete.setVisibility(View.GONE);
        } else {
            edtValue.setText(String.format("%.5f", rateValue));
            btnDelete.setVisibility(View.VISIBLE);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(view);
        dialog = builder.create();

        btnDelete.setOnClickListener(v -> deleteRate());
        view.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnSave).setOnClickListener(v -> saveRate(edtValue));

        dialog.show();
    }

    private void drawDate(EditText field) {
        field.setText(DISPLAY_FORMAT.format(pickedDate.getTime()));
    }

    private void saveRate(EditText edtValue) {
        String valueStr = edtValue.getText().toString().trim().replace(',', '.');

        if (valueStr.isEmpty()) {
            Toast.makeText(context, R.string.rate_error_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // Дата приходит из календаря, а не из текста поля — разбирать нечего.
            Date newDate = pickedDate.getTime();
            double newValue = Double.parseDouble(valueStr);

            SQLiteDatabase db = new WalletDbHelper(context).getWritableDatabase();
            try {
                if (isNew) {
                    // v72 (org/docs/TODO_mobile_sync.md, Фаза 3): раньше — CONFLICT_REPLACE,
                    // а для SQLite это "удалить старую строку целиком и вставить новую" —
                    // при случайном совпадении дат уже существующий uuid терялся бы и в
                    // потоке синка появлялся бы дубль вместо одного обновления. Тот же
                    // приём, что теперь и в WalletDbCRUD.InsertOrReplaceRate: сперва пробуем
                    // обновить (uuid сохраняется, dirty форсит триггер), и только если
                    // строки ещё нет — вставляем с новым uuid.
                    String dateStr = DateUtils.DateToDateStr(newDate);
                    ContentValues updateValues = new ContentValues();
                    updateValues.put("rate", newValue);
                    // deleted_at IS NULL — реальный баг, найденный на живом
                    // устройстве (2026-09-11, WalletFillData.InsertCurrencyPairRate,
                    // тот же класс здесь): без этого условия matcher "оживлял" мягко
                    // удалённую строку (rate обновлялся, deleted_at оставался
                    // проставленным) — на сервере та же строка тоже мягко удалена и
                    // потому не покрыта частичным индексом uq_currency_pair_rate_ledger
                    // (WHERE deleted_at IS NULL), ON CONFLICT по нему не находил
                    // "конфликта" и падал на currency_pair_rate_pkey голым INSERT'ом
                    // с уже занятым uuid ("duplicate_key"). С фильтром мёртвая строка
                    // для матчинга не существует — INSERT ниже заведёт новую с
                    // свежим uuid, старая тень остаётся как есть.
                    int updated = db.update("currency_pair_rate", updateValues,
                            "currency_pair_id = ? AND date = ? AND deleted_at IS NULL",
                            new String[]{String.valueOf(currencyPairId), dateStr});

                    long rowId;
                    if (updated > 0) {
                        rowId = 1; // строка уже была — обновили, а не вставили
                    } else {
                        ContentValues values = new ContentValues();
                        values.put("currency_pair_id", currencyPairId);
                        values.put("date", dateStr);
                        values.put("rate", newValue);
                        values.put("uuid", java.util.UUID.randomUUID().toString());
                        rowId = db.insert("currency_pair_rate", null, values);
                    }

                    if (rowId >= 0) {
                        listener.onRateSaved();
                        if (dialog != null) dialog.dismiss();
                        Toast.makeText(context, R.string.rate_added, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.rate_error_add, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    ContentValues values = new ContentValues();
                    values.put("date", DateUtils.DateToDateStr(newDate));
                    values.put("rate", newValue);

                    // Match on the date part only: rates are stored as plain 'yyyy-MM-dd', so
                    // comparing against a full 'yyyy-MM-dd HH:mm' string never matched any row
                    // and the edit silently no-op'd.
                    int rows = db.update("currency_pair_rate", values,
                            "currency_pair_id = ? AND substr(date, 1, 10) = ?",
                            new String[]{String.valueOf(currencyPairId), DateUtils.DateToDateStr(rateDate)});

                    if (rows > 0) {
                        listener.onRateSaved();
                        if (dialog != null) dialog.dismiss();
                        Toast.makeText(context, R.string.rate_updated, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.rate_not_found, Toast.LENGTH_SHORT).show();
                    }
                }
            } finally {
                db.close();
            }
        } catch (Exception e) {
            Toast.makeText(context, context.getString(R.string.error_with_reason, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteRate() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.rate_delete_question)
                .setMessage(DateUtils.DateToStr(rateDate) + " - " + String.format("%.5f", rateValue))
                .setPositiveButton(R.string.action_delete, (d, w) -> doDeleteRate())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Мягкое удаление (см. javadoc WalletDbCRUD.DeleteOperation, тот же довод) —
     * {@code deleted_at}, dirty=1 форсит триггер, доедет до сервера/других устройств
     * следующим синком.
     */
    private void doDeleteRate() {
        try {
            SQLiteDatabase db = new WalletDbHelper(context).getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("deleted_at", System.currentTimeMillis());
            int rows = db.update("currency_pair_rate", values,
                    "currency_pair_id = ? AND substr(date, 1, 10) = ?",
                    new String[]{String.valueOf(currencyPairId), DateUtils.DateToDateStr(rateDate)});
            db.close();

            if (rows > 0) {
                listener.onRateDeleted();
                if (dialog != null) dialog.dismiss();
                Toast.makeText(context, R.string.rate_deleted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, R.string.rate_not_found, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, context.getString(R.string.rate_error_delete, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
}
