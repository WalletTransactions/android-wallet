package com.example.wallettransactions.Activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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

import com.example.wallettransactions.Data.AccountNames;
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
 * телефона»; design D7, D10): создание и выбор из контактов системным
 * {@code ACTION_PICK} — без разрешения {@code READ_CONTACTS}: система даёт доступ к одному
 * выбранному контакту. Привязка к контакту (lookup-key) — локальная, на другом устройстве
 * её нет.
 */
public class CounterpartiesActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_CONTACT = 420;

    /** Режим выбора (карточка займа): тап по строке возвращает контрагента, а не открывает карточку. */
    public static final String EXTRA_PICK = "CounterpartiesActivity.pick";
    /** uuid уже выбранного контрагента: подсвечивается и остаётся в списке, даже если архивный. */
    public static final String EXTRA_SELECTED_UUID = "CounterpartiesActivity.selectedUuid";
    public static final String RESULT_UUID = "CounterpartiesActivity.resultUuid";
    public static final String RESULT_NAME = "CounterpartiesActivity.resultName";

    public static Intent buildPickIntent(android.content.Context context, String selectedUuid) {
        return new Intent(context, CounterpartiesActivity.class)
                .putExtra(EXTRA_PICK, true)
                .putExtra(EXTRA_SELECTED_UUID, selectedUuid);
    }

    private boolean pickMode;
    private String selectedUuid;

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_counterparties);
        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        selectedUuid = getIntent().getStringExtra(EXTRA_SELECTED_UUID);
        list = findViewById(R.id.counterpartiesList);
        EditText search = findViewById(R.id.counterpartiesSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                render();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из карточки контрагента (правка, архив, удаление) — список перечитываем.
        render();
    }

    /** Строка поиска (спека debts «Поиск по имени контрагента»); живёт на экране. */
    private String query = "";

    public void btnUp(View view) {
        finish();
    }

    private SQLiteDatabase db() {
        return Singleton.instance.getWritableDatabase(this);
    }

    private void render() {
        list.removeAllViews();
        List<CounterpartyItem> everyone = WalletDbLoader.LoadCounterparties(db());
        List<CounterpartyItem> all = new ArrayList<>();
        for (CounterpartyItem c : everyone) {
            // В выборе архивные не предлагаются (спека debts «Архивный контрагент»), кроме уже выбранного.
            if (pickMode && c.isArchived && !c.uuid.equals(selectedUuid)) continue;
            if (AccountNames.matchesSearch(c.name, query)) all.add(c);
        }
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
        TextView empty = findViewById(R.id.counterpartiesEmpty);
        // Пусто из-за поиска — «Ничего не найдено», пусто вообще — «Контрагентов пока нет».
        empty.setText(everyone.isEmpty() ? R.string.counterparties_empty : R.string.search_nothing_found);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View row(CounterpartyItem c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        boolean selected = pickMode && c.uuid.equals(selectedUuid);
        row.addView(text(c.name, 16, c.isArchived ? R.color.colorTextSecondary
                : selected ? R.color.colorPrimaryDark : R.color.colorTextPrimary, true));
        int loans = WalletDbCRUD.CountLiveDebts(db(), c.uuid);
        String sub = loans > 0 ? getString(R.string.counterparty_loans, loans) : null;
        if (!c.comment.isEmpty()) sub = sub == null ? c.comment : sub + " · " + c.comment;
        if (sub != null) row.addView(text(sub, 12, R.color.colorTextSecondary, false));
        if (pickMode) {
            // Тап — выбрать и вернуться; карточка (правка, архив, контакт) — долгим тапом.
            row.setOnClickListener(v -> deliver(c.uuid, c.name));
            row.setOnLongClickListener(v -> { openCard(c); return true; });
        } else {
            row.setOnClickListener(v -> openCard(c));
        }
        return row;
    }

    private void openCard(CounterpartyItem c) {
        startActivity(new Intent(this, CounterpartyCardActivity.class)
                .putExtra(CounterpartyCardActivity.ARG_COUNTERPARTY_ID, c.id));
    }

    private void deliver(String uuid, String name) {
        setResult(RESULT_OK, new Intent().putExtra(RESULT_UUID, uuid).putExtra(RESULT_NAME, name));
        finish();
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
        // В режиме выбора только что созданный (или найденный по имени) контрагент и есть выбор.
        if (pickMode && id > 0) {
            changed();
            deliver(WalletDbCRUD.CounterpartyUuid(db, id), name);
            return;
        }
        changed();
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
