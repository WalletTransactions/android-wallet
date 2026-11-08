package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Лист «Списать остаток?» при архивации кошелька (openspec archive-wallet-writeoff; по образцу
 * {@link RevaluationDialog}). Сам ничего не пишет: решение отдаёт {@link Listener}, а экран
 * «Кошелёк» сохраняет правки и проводку одной транзакцией. Категорию выбирает
 * {@link AccountPickerActivity} — его результат приходит в {@code onActivityResult} экрана,
 * тот передаёт его сюда через {@link #setCategory}.
 */
public class ArchiveWriteOffDialog {

    public interface Listener {
        /** «Списать и архивировать»: категория выбрана, {@code day} — {@code yyyy-MM-dd}. */
        void onWriteOff(int categoryId, String day);

        /** «Архивировать без списания». */
        void onArchiveWithoutWriteOff();

        /** Тап по полю категории: открыть выбор, {@code currentId} — 0, если не выбрана. */
        void onPickCategory(int currentId);
    }

    /**
     * Показывать ли лист (design D4, D5): кошелёк, не актив ни до, ни после сохранения,
     * архив ставится сейчас, остаток (уже округлённый до точности валюты) не 0.
     */
    public static boolean isOffered(boolean isWallet, boolean wasArchived, boolean archiveNow,
                                    boolean assetBefore, boolean assetAfter, double roundedBalance) {
        return isWallet && !wasArchived && archiveNow && !assetBefore && !assetAfter && roundedBalance != 0;
    }

    private static final SimpleDateFormat DISPLAY_FORMAT = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

    private final Context context;
    private final String message;
    private final Listener listener;
    private final Calendar pickedDate = Calendar.getInstance();
    private AlertDialog dialog;
    private EditText edtCategory;
    private int categoryId;

    /** @param message готовая строка «На кошельке «…» осталось …» */
    public ArchiveWriteOffDialog(Context context, String message, Listener listener) {
        this.context = context;
        this.message = message;
        this.listener = listener;
    }

    public void show() {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_archive_writeoff, null);
        ((TextView) view.findViewById(R.id.textMessage)).setText(message);
        edtCategory = view.findViewById(R.id.inputCategory);
        EditText edtDate = view.findViewById(R.id.inputDate);

        drawDate(edtDate);
        edtDate.setOnClickListener(v -> CalendarPicker.show(context, pickedDate, (year, month, day) -> {
            pickedDate.set(year, month, day);
            drawDate(edtDate);
        }));
        edtCategory.setOnClickListener(v -> listener.onPickCategory(categoryId));

        dialog = new AlertDialog.Builder(context).setView(view).create();
        view.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnSkip).setOnClickListener(v -> listener.onArchiveWithoutWriteOff());
        view.findViewById(R.id.btnWriteOff).setOnClickListener(v -> {
            if (categoryId <= 0) {
                edtCategory.setError(context.getString(R.string.archive_writeoff_category_hint));
                return;
            }
            listener.onWriteOff(categoryId, DateUtils.DateToDateStr(pickedDate.getTime()));
        });
        dialog.show();
    }

    /** Выбор из {@link AccountPickerActivity}. */
    public void setCategory(AccountItem category) {
        if (category == null || edtCategory == null) return;
        categoryId = category.getId();
        edtCategory.setText(category.getDisplayName());
        edtCategory.setError(null);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        if (isShowing()) dialog.dismiss();
    }

    private void drawDate(EditText field) {
        field.setText(DISPLAY_FORMAT.format(pickedDate.getTime()));
    }
}
