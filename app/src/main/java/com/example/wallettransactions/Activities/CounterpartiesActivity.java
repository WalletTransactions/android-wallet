package com.example.wallettransactions.Activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.CounterpartyItem;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран «Контрагенты» (openspec debts, спека «Контрагент», «Связь контрагента с контактом
 * телефона»; design D7, D10): создание, переименование (дубль имени — отказ), архив и
 * разархив, удаление только без займов, выбор из контактов системным
 * {@code ACTION_PICK} — без разрешения {@code READ_CONTACTS}: система даёт доступ к одному
 * выбранному контакту. Привязка к контакту (lookup-key) — локальная, на другом устройстве
 * её нет.
 */
public class CounterpartiesActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_CONTACT = 420;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_counterparties);
        list = findViewById(R.id.counterpartiesList);
        render();
    }

    public void btnUp(View view) {
        finish();
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(this);
    }

    private void render() {
        list.removeAllViews();
        List<CounterpartyItem> all = WalletDbLoader.LoadCounterparties(db());
        List<CounterpartyItem> archived = new ArrayList<>();
        for (CounterpartyItem c : all) {
            if (c.isArchived) archived.add(c);
            else list.addView(row(c));
        }
        if (!archived.isEmpty()) {
            TextView header = text(getString(R.string.counterparties_archived), 12, R.color.colorTextSecondary, true);
            header.setAllCaps(true);
            header.setPadding(0, dp(18), 0, dp(4));
            list.addView(header);
            for (CounterpartyItem c : archived) list.addView(row(c));
        }
        findViewById(R.id.counterpartiesEmpty).setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View row(CounterpartyItem c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.addView(text(c.name, 16, c.isArchived ? R.color.colorTextSecondary : R.color.colorTextPrimary, true));
        int loans = WalletDbCRUD.CountLiveDebts(db(), c.uuid);
        String sub = loans > 0 ? getString(R.string.counterparty_loans, loans) : null;
        if (!c.comment.isEmpty()) sub = sub == null ? c.comment : sub + " · " + c.comment;
        if (sub != null) row.addView(text(sub, 12, R.color.colorTextSecondary, false));
        row.setOnClickListener(v -> showActions(c));
        return row;
    }

    private void showActions(CounterpartyItem c) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(getString(R.string.counterparty_rename));
        actions.add(() -> askName(c.name, name -> {
            if (!WalletDbCRUD.RenameCounterparty(db(), c.id, name)) {
                Toast.makeText(this, getString(R.string.counterparty_name_taken, name), Toast.LENGTH_LONG).show();
            }
            changed();
        }));
        items.add(getString(c.isArchived ? R.string.counterparty_unarchive : R.string.counterparty_archive));
        actions.add(() -> {
            WalletDbCRUD.ArchiveCounterparty(db(), c.id, !c.isArchived);
            changed();
        });
        String lookup = WalletDbCRUD.GetCounterpartyContact(db(), c.uuid);
        if (lookup != null) {
            items.add(getString(R.string.counterparty_open_contact));
            actions.add(() -> openContact(lookup));
        }
        items.add(getString(R.string.counterparty_delete));
        actions.add(() -> {
            // Спека «Удаление контрагента с долгом»: не удаляем, объясняем почему.
            if (WalletDbCRUD.DeleteCounterparty(db(), c.id) == WalletDbCRUD.DeleteCounterpartyResult.hasDebts) {
                Toast.makeText(this, getString(R.string.counterparty_delete_has_debts, c.name), Toast.LENGTH_LONG).show();
            }
            changed();
        });
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    public void onAddCounterparty(View view) {
        askName("", name -> create(name, null));
    }

    /** Системный выбор контакта — без разрешения; нет приложения контактов — ввод текстом. */
    public void onPickContact(View view) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI),
                    REQUEST_PICK_CONTACT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show();
            onAddCounterparty(view);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Отмена выбора — ничего не делаем (спека «Связь контрагента с контактом»).
        if (requestCode != REQUEST_PICK_CONTACT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String name = null, lookup = null;
        try (Cursor c = getContentResolver().query(data.getData(), new String[]{
                ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                lookup = c.getString(1);
            }
        } catch (SecurityException e) {
            name = null;
        }
        if (name == null || name.trim().isEmpty()) {
            onAddCounterparty(null);
            return;
        }
        String picked = lookup;
        askName(name, n -> create(n, picked));
    }

    private void create(String name, String lookupKey) {
        SQLiteDatabase db = db();
        int existing = WalletDbCRUD.GetCounterpartyIdByName(db, name);
        int id = existing > 0 ? existing : WalletDbCRUD.CreateCounterparty(db, name, "");
        if (existing > 0) {
            Toast.makeText(this, getString(R.string.debt_counterparty_exists, name), Toast.LENGTH_LONG).show();
        }
        if (id > 0 && lookupKey != null) {
            WalletDbCRUD.SetCounterpartyContact(db, WalletDbCRUD.CounterpartyUuid(db, id), lookupKey);
        }
        changed();
    }

    private void openContact(String lookupKey) {
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.counterparty_no_contacts_app, Toast.LENGTH_LONG).show();
        }
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void askName(String initial, NameCallback callback) {
        EditText input = new EditText(this);
        input.setHint(R.string.debt_counterparty_name_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(initial);
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.debt_counterparty_name_hint)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = WalletDbCRUD.NormalizeCounterpartyName(input.getText().toString());
                    if (!name.isEmpty()) callback.onName(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void changed() {
        PeriodFilterState.get(this).markDataChanged();
        render();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
