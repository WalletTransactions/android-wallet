package com.example.wallettransactions.Activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.example.wallettransactions.Adapters.CurrencyPickerAdapter;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * «Список валют» — общий однократный выбор одной валюты (activity_currency_picker.xml):
 * тап по строке сразу возвращает результат. Используется by dialog_new_pair.xml's
 * Базовая/Котируемая валюта. С {@link #ARG_BROWSE} (кнопка «Валюты» на «Курсы валют») —
 * режим просмотра: тап открывает форму валюты и ничего не возвращает. The currency source is the same {@link CurrencyList} the
 * rest of the app already uses (MainActivity's switcher, SettingsActivity, filter chips),
 * per the design's own "не дублируйте список по экранам" note — no separate
 * currencies.json is loaded here.
 */
public class CurrencyPickerActivity extends AppCompatActivity {

    public static final String ARG_TITLE = "CurrencyPicker.Title";
    public static final String ARG_CURRENT_ISO = "CurrencyPicker.CurrentIso";
    /** Режим просмотра (кнопка «Валюты» на «Курсы валют», design D4): тап открывает форму, ничего не возвращает. */
    public static final String ARG_BROWSE = "CurrencyPicker.Browse";
    public static final String RESULT_ISO = "CurrencyPicker.ResultIso";

    public static Intent buildIntent(Context ctx, String title, String currentIso) {
        Intent intent = new Intent(ctx, CurrencyPickerActivity.class);
        intent.putExtra(ARG_TITLE, title);
        if (currentIso != null) intent.putExtra(ARG_CURRENT_ISO, currentIso);
        return intent;
    }

    /** Список «Валюты» в режиме просмотра. */
    public static Intent buildBrowseIntent(Context ctx) {
        Intent intent = buildIntent(ctx, ctx.getString(R.string.currency_list_title), null);
        intent.putExtra(ARG_BROWSE, true);
        return intent;
    }

    private static final int REQUEST_EDIT_CURRENCY = 1;

    private CurrencyPickerAdapter adapter;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currency_picker);

        String title = getIntent().getStringExtra(ARG_TITLE);
        if (title != null) ((TextView) findViewById(R.id.pickerTitle)).setText(title);
        String currentIso = getIntent().getStringExtra(ARG_CURRENT_ISO);
        boolean browse = getIntent().getBooleanExtra(ARG_BROWSE, false);

        adapter = new CurrencyPickerAdapter(this, loadCurrencies(), currentIso);
        adapter.setOnEditCurrencyListener(this::openForm);

        ListView listCurrencies = findViewById(R.id.listCurrencies);
        listCurrencies.setAdapter(adapter);
        listCurrencies.setOnItemClickListener((parent, view, position, id) -> {
            Currency chosen = adapter.getItem(position);
            if (browse) {
                openForm(chosen);
                return;
            }
            Intent result = new Intent();
            result.putExtra(RESULT_ISO, chosen.getIso());
            setResult(RESULT_OK, result);
            finish();
        });

        searchInput = findViewById(R.id.searchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    public void btnUp(View view) {
        finish();
    }

    public void onCreateCurrency(View view) {
        startActivityForResult(CurrencyFormActivity.buildIntent(this, -1), REQUEST_EDIT_CURRENCY);
    }

    private void openForm(Currency currency) {
        startActivityForResult(CurrencyFormActivity.buildIntent(this, currency.getId()), REQUEST_EDIT_CURRENCY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT_CURRENCY && resultCode == RESULT_OK) refresh();
    }

    /**
     * Синк перечитывает валюты в синглтоне (Singleton.reloadCurrencyList) — правки с
     * веба (design D9) видны при возврате на экран, а не только после перезапуска.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) refresh();
    }

    /** Перечитать из синглтона, не сбрасывая набранный поиск. */
    private void refresh() {
        adapter.replaceAll(loadCurrencies());
        CharSequence q = searchInput.getText();
        if (q.length() > 0) adapter.getFilter().filter(q);
    }

    private List<Currency> loadCurrencies() {
        CurrencyList currencyList = Singleton.instance.getCurrencyList(this);
        List<Currency> all = new ArrayList<>();
        for (int i = 0; i < currencyList.size(); i++) all.add(currencyList.get(i));
        return all;
    }
}
