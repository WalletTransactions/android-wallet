package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.Data.RecurringOperationsDb;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.Operations.RecurringOperation;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.sync.RecurringWorker;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Ещё» → «Повторяющиеся операции»: список правил ({@code recurring_operation}).
 * Тап — правка ({@link RecurringOperationActivity}), долгое нажатие — удаление,
 * переключатель справа — включить/выключить повтор без захода в правило.
 */
public class RecurringOperationListActivity extends AppCompatActivity {

    private static final int requestCodeEdit = 1;

    private static final DateTimeFormatter DATE_UI = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("ru"));

    private final List<RecurringOperation> mItems = new ArrayList<>();
    private RecurringAdapter mAdapter;
    private TextView mEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recurring_operation_list);

        ((TextView) findViewById(R.id.screenTitle)).setText("Повторяющиеся операции");

        ListView list = findViewById(R.id.listRecurring);
        mEmpty = findViewById(R.id.emptyRecurring);
        mAdapter = new RecurringAdapter(this, mItems);
        list.setAdapter(mAdapter);

        reload();
    }

    /** Строка-«+» под списком (activity_recurring_operation_list.xml). */
    public void onAddRecurring(View view) {
        startActivityForResult(RecurringOperationActivity.buildIntent(this, 0), requestCodeEdit);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == requestCodeEdit) reload();
    }

    public void btnUp(View view) {
        finish();
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(this);
    }

    private void reload() {
        mItems.clear();
        mItems.addAll(RecurringOperationsDb.loadAll(db()));
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openEdit(RecurringOperation r) {
        startActivityForResult(RecurringOperationActivity.buildIntent(this, r.id), requestCodeEdit);
    }

    private void confirmDelete(RecurringOperation r) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить повтор?")
                .setMessage("Уже созданные операции останутся, новые создаваться не будут.")
                .setPositiveButton("Удалить", (d, w) -> {
                    RecurringOperationsDb.delete(db(), r.id);
                    RecurringWorker.kick(this);
                    reload();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Включение — пауза снимается без досоздания пропущенного за время паузы
     * (см. {@link RecurringOperationsDb#setActive}); операции, наступающие с этого
     * момента, заведёт {@link RecurringWorker} (или открытие таба, что раньше).
     */
    private void toggleActive(RecurringOperation r) {
        RecurringOperationsDb.setActive(db(), r, !r.isActive);
        RecurringWorker.kick(this);
        mAdapter.notifyDataSetChanged();
    }

    // ── Адаптер ───────────────────────────────────────────────────────────────

    private class RecurringAdapter extends ArrayAdapter<RecurringOperation> {

        RecurringAdapter(Context ctx, List<RecurringOperation> items) {
            super(ctx, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.recurring_operation_item_layout, parent, false);

            RecurringOperation r = getItem(position);
            AccountList accounts = Singleton.instance.getAccounts(getContext());
            AccountItem from = accounts.getAccountById(r.accFromId);
            AccountItem to = accounts.getAccountById(r.accToId);

            boolean fromWallet = from != null && from.isOperatingAccount();
            boolean toWallet = to != null && to.isOperatingAccount();
            // «Другая сторона» — как на экране операции: статья у расхода, источник у дохода/перевода.
            AccountItem top = fromWallet && !toWallet ? to : from;

            ImageView icon = v.findViewById(R.id.recIcon);
            if (top != null) icon.setImageBitmap(top.getIcon().getBitmap());
            else icon.setImageBitmap(Singleton.instance.getIconList(getContext()).getUnknownIcon().getBitmap());

            String title = r.comment != null && !r.comment.trim().isEmpty()
                    ? r.comment.trim()
                    : (top != null ? top.getDisplayName() : "Счёт удалён");
            ((TextView) v.findViewById(R.id.recTitle)).setText(title);
            ((TextView) v.findViewById(R.id.recAccounts)).setText(nameOf(from) + " → " + nameOf(to));

            StringBuilder schedule = new StringBuilder(r.describeSchedule());
            if (r.endDate != null) schedule.append(" · до ").append(r.endDate.format(DATE_UI));
            if (r.isActive) {
                LocalDateTime next = r.nextOccurrence(LocalDateTime.now());
                schedule.append(next != null ? "\nСледующая: " + next.format(DATE_UI) : "\nЗавершено");
            } else {
                schedule.append("\nВыключено");
            }
            ((TextView) v.findViewById(R.id.recSchedule)).setText(schedule);

            TextView amount = v.findViewById(R.id.recAmount);
            AccountOperatingItem wallet = fromWallet ? (AccountOperatingItem) from
                    : toWallet ? (AccountOperatingItem) to : null;
            int precision = wallet != null ? wallet.getCurrency().getPrecision() : 2;
            String sign = fromWallet && !toWallet ? "−" : (!fromWallet && toWallet ? "+" : "");
            amount.setText(sign + String.format(Locale.US, "%,." + precision + "f", r.amount)
                    + (wallet != null ? " " + wallet.getCurrency().getSymbol() : ""));
            int color = fromWallet && !toWallet ? R.color.colorexpanse
                    : (!fromWallet && toWallet ? R.color.colorprofit : R.color.colortransfer);
            amount.setTextColor(ContextCompat.getColor(getContext(), color));

            FrameLayout sw = v.findViewById(R.id.recSwitch);
            applySwitchState(sw, r.isActive);
            sw.setOnClickListener(x -> toggleActive(r));

            View content = v.findViewById(R.id.recContent);
            content.setAlpha(r.isActive ? 1f : 0.5f);
            content.setOnClickListener(x -> openEdit(r));
            content.setOnLongClickListener(x -> {
                confirmDelete(r);
                return true;
            });
            return v;
        }

        private String nameOf(AccountItem item) {
            return item != null ? item.getDisplayName() : "—";
        }

        private void applySwitchState(FrameLayout switchView, boolean on) {
            switchView.setBackgroundResource(on ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
            View thumb = switchView.getChildAt(0);
            if (thumb == null) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
            lp.gravity = on ? android.view.Gravity.END : android.view.Gravity.START;
            thumb.setLayoutParams(lp);
        }
    }
}
