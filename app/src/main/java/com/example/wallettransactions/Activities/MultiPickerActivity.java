package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Множественный выбор счетов (activity_multi_picker.xml, прототип 2a — блок isPick).
 *
 * Заменяет системный AlertDialog с галочками, который стоял в «Фильтре».
 * Контракт: на вход — какой набор счетов показывать ({@link #cSourceType}) и уже
 * отмеченные id через запятую ({@link #cSelectedIds}); на выход — отмеченные id
 * в том же виде, тем же ключом.
 *
 * Сверху — поиск по названию (contains, без учёта регистра), как в
 * {@link AccountPickerActivity}: фильтрует только видимый список, отметки при
 * этом не теряются даже у строк, ушедших из выдачи.
 */
public class MultiPickerActivity extends AppCompatActivity {

    public final static String cTitle = "MultiPickerActivity.Title";
    public final static String cSourceType = "MultiPickerActivity.SourceType";
    public final static String cSelectedIds = "MultiPickerActivity.SelectedIds";

    /** Статьи затрат — всё, что не кошелёк. */
    public final static int cSourceExpanseAccounts = 1;
    /** Кошельки. */
    public final static int cSourceOperationAccounts = 2;

    private final List<AccountItem> mAllItems = new ArrayList<>();
    private final List<AccountItem> mVisibleItems = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();

    private TextView pickerCount;
    private TextView btnToggleAll;
    private EditText mSearchInput;
    private View mEmptyState;
    private PickerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_picker);

        pickerCount = findViewById(R.id.pickerCount);
        btnToggleAll = findViewById(R.id.btnToggleAll);
        mSearchInput = findViewById(R.id.searchInput);
        mEmptyState = findViewById(R.id.emptyState);

        String title = getIntent().getStringExtra(cTitle);
        if (title != null) ((TextView) findViewById(R.id.pickerTitle)).setText(title);

        mAllItems.addAll(loadItems(getIntent().getIntExtra(cSourceType, cSourceOperationAccounts)));
        selected.addAll(parseIds(getIntent().getStringExtra(cSelectedIds)));

        adapter = new PickerAdapter(this, mVisibleItems);
        ListView list = findViewById(R.id.listItems);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AccountItem item = mVisibleItems.get(position);
            if (!selected.remove(item.getId())) selected.add(item.getId());
            adapter.notifyDataSetChanged();
            updateHeader();
        });

        mSearchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { applyFilter(); }
        });

        applyFilter();
    }

    /** Именно contains, а не регулярка: символ вроде скобки в поиске не должен ронять экран. */
    private void applyFilter() {
        String query = mSearchInput.getText().toString().trim().toLowerCase();

        mVisibleItems.clear();
        for (AccountItem item : mAllItems) {
            if (query.isEmpty() || item.getName().toLowerCase().contains(query))
                mVisibleItems.add(item);
        }

        adapter.notifyDataSetChanged();
        mEmptyState.setVisibility(mVisibleItems.isEmpty() ? View.VISIBLE : View.GONE);
        updateHeader();
    }

    private List<AccountItem> loadItems(int sourceType) {
        AccountList accounts = Singleton.instance.getAccounts(this);
        return sourceType == cSourceExpanseAccounts
                ? new ArrayList<>(accounts.getExpanseAccounts())
                : new ArrayList<>(accounts.getOperationAccounts());
    }

    static Set<Integer> parseIds(String csv) {
        Set<Integer> result = new HashSet<>();
        if (csv == null || csv.isEmpty()) return result;

        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // чужой id в extra — просто пропускаем, экран не должен падать
            }
        }
        return result;
    }

    private void updateHeader() {
        boolean allSelected = selected.size() == mAllItems.size() && !mAllItems.isEmpty();

        pickerCount.setText(selected.isEmpty()
                ? "Все"
                : selected.size() + " из " + mAllItems.size());

        btnToggleAll.setText(allSelected ? "Снять всё" : "Выбрать все");
    }

    public void onToggleAll(View view) {
        if (selected.size() == mAllItems.size()) {
            selected.clear();
        } else {
            for (AccountItem item : mAllItems) selected.add(item.getId());
        }
        adapter.notifyDataSetChanged();
        updateHeader();
    }

    public void btnUp(View view) {
        finish();
    }

    public void onDone(View view) {
        StringBuilder ids = new StringBuilder();
        // Идём по mAllItems, а не по set — порядок остаётся тем же, что на экране.
        for (AccountItem item : mAllItems) {
            if (!selected.contains(item.getId())) continue;
            if (ids.length() > 0) ids.append(",");
            ids.append(item.getId());
        }

        Intent result = new Intent();
        result.putExtra(cSelectedIds, ids.toString());
        setResult(RESULT_OK, result);
        finish();
    }

    private class PickerAdapter extends ArrayAdapter<AccountItem> {

        PickerAdapter(Context context, List<AccountItem> items) {
            super(context, R.layout.multi_picker_item_layout, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(getContext())
                        .inflate(R.layout.multi_picker_item_layout, parent, false);
            }

            AccountItem item = getItem(position);
            boolean isChecked = selected.contains(item.getId());

            ((ImageView) row.findViewById(R.id.itemIcon)).setImageBitmap(item.getIcon().getBitmap());
            ((TextView) row.findViewById(R.id.itemLabel)).setText(item.getName());

            TextView check = row.findViewById(R.id.itemCheck);
            check.setBackgroundResource(isChecked ? R.drawable.bg_check_on : R.drawable.bg_check_off);
            check.setText(isChecked ? "✓" : "");

            return row;
        }
    }
}
