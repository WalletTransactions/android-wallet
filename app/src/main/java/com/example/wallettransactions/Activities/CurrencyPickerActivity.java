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
 * Базовая/Котируемая валюта; the currency source is the same {@link CurrencyList} the
 * rest of the app already uses (MainActivity's switcher, SettingsActivity, filter chips),
 * per the design's own "не дублируйте список по экранам" note — no separate
 * currencies.json is loaded here.
 */
public class CurrencyPickerActivity extends AppCompatActivity {

    public static final String ARG_TITLE = "CurrencyPicker.Title";
    public static final String ARG_CURRENT_ISO = "CurrencyPicker.CurrentIso";
    public static final String RESULT_ISO = "CurrencyPicker.ResultIso";

    public static Intent buildIntent(Context ctx, String title, String currentIso) {
        Intent intent = new Intent(ctx, CurrencyPickerActivity.class);
        intent.putExtra(ARG_TITLE, title);
        if (currentIso != null) intent.putExtra(ARG_CURRENT_ISO, currentIso);
        return intent;
    }

    private static final int REQUEST_EDIT_CURRENCY = 1;

    private CurrencyPickerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currency_picker);

        String title = getIntent().getStringExtra(ARG_TITLE);
        if (title != null) ((TextView) findViewById(R.id.pickerTitle)).setText(title);
        String currentIso = getIntent().getStringExtra(ARG_CURRENT_ISO);

        adapter = new CurrencyPickerAdapter(this, loadCurrencies(), currentIso);
        adapter.setOnEditCurrencyListener(currency ->
                startActivityForResult(CurrencyFormActivity.buildIntent(this, currency.getId()),
                        REQUEST_EDIT_CURRENCY));

        ListView listCurrencies = findViewById(R.id.listCurrencies);
        listCurrencies.setAdapter(adapter);
        listCurrencies.setOnItemClickListener((parent, view, position, id) -> {
            Currency chosen = adapter.getItem(position);
            Intent result = new Intent();
            result.putExtra(RESULT_ISO, chosen.getIso());
            setResult(RESULT_OK, result);
            finish();
        });

        EditText searchInput = findViewById(R.id.searchInput);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT_CURRENCY && resultCode == RESULT_OK) {
            adapter.replaceAll(loadCurrencies());
        }
    }

    private List<Currency> loadCurrencies() {
        CurrencyList currencyList = Singleton.instance.getCurrencyList(this);
        List<Currency> all = new ArrayList<>();
        for (int i = 0; i < currencyList.size(); i++) all.add(currencyList.get(i));
        return all;
    }
}
