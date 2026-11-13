package com.example.wallettransactions.Activities;

import android.app.Activity;
import android.database.sqlite.SQLiteDatabase;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.wallettransactions.CalendarPicker;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Поля признака займа ({@code view_debt_fields.xml}, openspec debts): «Заём», контрагент,
 * срок возврата, комментарий. Нужна, чтобы уже заведённые кошельки-люди («Кулигин · EUR»)
 * стали займами со всей своей историей — запись {@code WalletDbCRUD.SetAccountDebt} /
 * {@code ClearAccountDebt}. С признаком актива взаимоисключающий: пока отмечено одно,
 * флажок другого скрыт (спека, «Кошелёк уже актив»).
 *
 * <p>Новый контрагент в базу не пишется до «Сохранить»: экран можно закрыть без записи.
 * Его имя держится в {@link #newCounterpartyName}, создаёт его {@link #save}.
 */
final class DebtFields {

    private final Activity activity;
    private final CheckBox checkIsDebt;
    private final View details;
    private final TextView counterpartyView;
    private final TextView dueView;
    private final EditText commentView;
    private final View assetFields;
    private final CheckBox checkIsAsset;
    private final Runnable onChange;
    private final String walletName;

    private final AccountOperatingItem.DebtInfo original;
    private String counterpartyUuid;
    private String counterpartyName;
    /** Не пусто — выбран «Новый контрагент», в базе его ещё нет. */
    private String newCounterpartyName;
    private String dueDate;

    DebtFields(Activity activity, AccountOperatingItem.DebtInfo initial, String walletName, Runnable onChange) {
        this.activity = activity;
        this.original = initial;
        this.onChange = onChange;
        this.walletName = walletName;
        checkIsDebt = activity.findViewById(R.id.checkIsDebt);
        details = activity.findViewById(R.id.debtDetails);
        counterpartyView = activity.findViewById(R.id.debtCounterparty);
        dueView = activity.findViewById(R.id.debtDueDate);
        commentView = activity.findViewById(R.id.debtComment);
        assetFields = activity.findViewById(R.id.assetFields);
        checkIsAsset = activity.findViewById(R.id.checkIsAsset);

        if (initial != null) {
            checkIsDebt.setChecked(true);
            counterpartyUuid = initial.counterpartyUuid;
            counterpartyName = nameOf(initial.counterpartyUuid);
            dueDate = initial.dueDate;
            commentView.setText(initial.comment);
        }
        refresh();

        checkIsDebt.setOnCheckedChangeListener((b, checked) -> {
            if (checked && counterpartyUuid == null && newCounterpartyName == null) suggestCounterparty();
            refresh();
            changed();
        });
        counterpartyView.setOnClickListener(v -> pickCounterparty());
        dueView.setOnClickListener(v -> pickDueDate());
        commentView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { changed(); }
        });
    }

    boolean isDebt() {
        return checkIsDebt.isChecked();
    }

    /** Отличается ли выбор от исходного признака займа. */
    boolean differs() {
        if (original == null) return isDebt();
        if (!isDebt()) return true;
        return newCounterpartyName != null
                || !eq(counterpartyUuid, original.counterpartyUuid)
                || !eq(dueDate, original.dueDate)
                || !comment().equals(original.comment);
    }

    /** Можно ли сохранить: заём без контрагента не пишется. */
    boolean validate() {
        if (!isDebt() || counterpartyUuid != null || newCounterpartyName != null) return true;
        Toast.makeText(activity, R.string.debt_error_no_counterparty, Toast.LENGTH_LONG).show();
        return false;
    }

    /**
     * Запись признака — в транзакции вызывающего. Новый контрагент создаётся здесь же;
     * если имя тем временем занял другой (синк), берётся существующий.
     *
     * @return false — не записалось (контрагент не создан или кошелёк не подходит)
     */
    boolean save(SQLiteDatabase db, int accountId) {
        if (!differs()) return true;
        if (!isDebt()) {
            WalletDbCRUD.ClearAccountDebt(db, accountId);
            return true;
        }
        String uuid = counterpartyUuid;
        if (newCounterpartyName != null) {
            int id = WalletDbCRUD.GetCounterpartyIdByName(db, newCounterpartyName);
            if (id <= 0) id = WalletDbCRUD.CreateCounterparty(db, newCounterpartyName, "");
            if (id <= 0) return false;
            uuid = WalletDbCRUD.CounterpartyUuid(db, id);
        }
        return uuid != null && WalletDbCRUD.SetAccountDebt(db, accountId, uuid, comment(), dueDate);
    }

    // ── выбор ──────────────────────────────────────────────────────────────

    /**
     * При первом включении — контрагент по имени кошелька («Кулигин · EUR» → «Кулигин»):
     * есть такой — он, нет — новый с этим именем (в базу — только по «Сохранить»).
     */
    private void suggestCounterparty() {
        String suggested = WalletDbCRUD.SuggestCounterpartyName(walletName);
        if (suggested.isEmpty()) return;
        SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
        int id = WalletDbCRUD.GetCounterpartyIdByName(db, suggested);
        if (id > 0) {
            for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) {
                if (c.id == id) { selectExisting(c); return; }
            }
        }
        newCounterpartyName = suggested;
        counterpartyName = suggested;
    }

    private void pickCounterparty() {
        SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
        List<CounterpartyItem> live = new ArrayList<>();
        for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) {
            // Архивный — не в выборе (спека, «Архивный контрагент»), кроме уже выбранного.
            if (!c.isArchived || c.uuid.equals(counterpartyUuid)) live.add(c);
        }
        String[] items = new String[live.size() + 1];
        items[0] = activity.getString(R.string.debt_new_counterparty);
        for (int i = 0; i < live.size(); i++) items[i + 1] = live.get(i).name;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.debt_pick_counterparty)
                .setItems(items, (d, which) -> {
                    if (which == 0) askNewCounterparty();
                    else {
                        selectExisting(live.get(which - 1));
                        refresh();
                        changed();
                    }
                })
                .show();
    }

    private void askNewCounterparty() {
        EditText input = new EditText(activity);
        input.setHint(R.string.debt_counterparty_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(newCounterpartyName != null ? newCounterpartyName : WalletDbCRUD.SuggestCounterpartyName(walletName));
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.debt_new_counterparty_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = WalletDbCRUD.NormalizeCounterpartyName(input.getText().toString());
                    if (name.isEmpty()) return;
                    SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
                    int existing = WalletDbCRUD.GetCounterpartyIdByName(db, name);
                    if (existing > 0) {
                        // Спека «Дубль имени»: второго не заводим — выбираем существующего.
                        for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) {
                            if (c.id == existing) selectExisting(c);
                        }
                        Toast.makeText(activity, activity.getString(R.string.debt_counterparty_exists,
                                counterpartyName), Toast.LENGTH_LONG).show();
                    } else {
                        counterpartyUuid = null;
                        newCounterpartyName = name;
                        counterpartyName = name;
                    }
                    refresh();
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickDueDate() {
        Calendar start = Calendar.getInstance();
        if (dueDate != null) {
            String[] p = dueDate.split("-");
            start.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        }
        if (dueDate != null) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.debt_due_label)
                    .setItems(new String[]{activity.getString(R.string.debt_due_label),
                            activity.getString(R.string.debt_due_clear)}, (d, which) -> {
                        if (which == 1) { dueDate = null; refresh(); changed(); }
                        else showCalendar(start);
                    })
                    .show();
        } else {
            showCalendar(start);
        }
    }

    private void showCalendar(Calendar start) {
        CalendarPicker.show(activity, start, (y, m, d) -> {
            dueDate = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
            refresh();
            changed();
        });
    }

    // ── вид ─────────────────────────────────────────────────────────────────

    /** Перерисовать; зовёт и экран при смене флажка актива (его слушает AssetFields). */
    void refresh() {
        boolean debt = checkIsDebt.isChecked();
        details.setVisibility(debt ? View.VISIBLE : View.GONE);
        // Актив и заём — взаимоисключающие: пока отмечено одно, другого не предлагаем.
        if (assetFields != null) assetFields.setVisibility(debt ? View.GONE : View.VISIBLE);
        boolean asset = checkIsAsset != null && checkIsAsset.isChecked();
        checkIsDebt.setVisibility(asset ? View.GONE : View.VISIBLE);
        counterpartyView.setText(counterpartyName != null ? counterpartyName
                : activity.getString(R.string.debt_counterparty_none));
        dueView.setText(dueDate != null ? dueDate : activity.getString(R.string.debt_due_none));
    }

    private void selectExisting(CounterpartyItem c) {
        counterpartyUuid = c.uuid;
        counterpartyName = c.name;
        newCounterpartyName = null;
    }

    private String nameOf(String uuid) {
        SQLiteDatabase db = new WalletDbHelper(activity).getReadableDatabase();
        for (CounterpartyItem c : WalletDbLoader.LoadCounterparties(db)) {
            if (c.uuid.equals(uuid)) return c.name;
        }
        return null;
    }

    private String comment() {
        return commentView.getText().toString().trim();
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
