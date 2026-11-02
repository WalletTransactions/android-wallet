package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.wallettransactions.Adapters.CurrencyPairListAdapter;
import com.example.wallettransactions.Data.WalletDbCRUD;
import com.example.wallettransactions.Data.WalletDbHelper;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPairList;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Экран «Курсы валют» — список валютных пар. Тап по паре открывает
 * {@link CurrencyPairRateListActivity} (история курса пары).
 *
 * Фильтр один — поиск по валюте в шапке, он же всегда на виду. Раньше здесь была
 * сворачиваемая панель с чипами валют: два тапа до фильтра и ещё один список поверх
 * списка пар, хотя набрать «EUR» и так быстрее.
 */
public class CurrencyPairListActivity extends AppCompatActivity {

    private SQLiteDatabase db;
    private CurrencyPairListAdapter adapter;

    // "Новая валютная пара" sheet state — its rows open CurrencyPickerActivity for a result,
    // so the still-open dialog's view and the picked ISOs need to be reachable from
    // onActivityResult, not just closed-over locals in showAddPairDialog().
    private static final int REQUEST_PICK_BASE = 300;
    private static final int REQUEST_PICK_QUOTE = 301;
    private View newPairDlgView;
    private AlertDialog newPairDialog;
    private String selectedBaseIso;
    private String selectedQuoteIso;

    /** Строка поиска в верхнем регистре; пустая — показываем все пары. */
    private String filterQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_currencypair_list);

        db = new WalletDbHelper(this).getReadableDatabase();

        ListView listPairs = findViewById(R.id.listPairs);
        List<CurrencyPair> items = new ArrayList<>();
        adapter = new CurrencyPairListAdapter(this, items, db);
        listPairs.setAdapter(adapter);
        listPairs.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            CurrencyPair item = adapter.getItem(position);
            if (item == null) return;
            Intent intent = new Intent(this, CurrencyPairRateListActivity.class);
            intent.putExtra(CurrencyPairRateListActivity.ARG_ITEM_SYMBOL, item.getSymbol());
            startActivity(intent);
        });
        listPairs.setOnItemLongClickListener((parent, view, position, id) -> {
            CurrencyPair item = adapter.getItem(position);
            if (item == null) return false;
            if (item.isShared()) {
                Toast.makeText(this, "Общая пара — удалить нельзя", Toast.LENGTH_SHORT).show();
            } else {
                confirmDeletePair(this, item, this::applyFilter);
            }
            return true;
        });

        EditText currencyFilterSearch = findViewById(R.id.currencyFilterSearch);
        currencyFilterSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterQuery = s.toString().trim().toUpperCase(Locale.ROOT);
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        applyFilter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null && db.isOpen()) db.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rows show each pair's latest rate/date/% change (see CurrencyPairListAdapter) — refresh
        // on every return to this screen, since a rate can be added/edited/deleted/downloaded on
        // CurrencyPairRateListActivity and we're not notified of that via onActivityResult (it's
        // opened with a plain startActivity).
        if (adapter != null) applyFilter();
    }

    public void btnUp(View view) {
        finish();
    }

    public void onAddPair(View view) {
        showAddPairDialog();
    }

    /** Кнопка «Валюты» в тулбаре — список валют в режиме просмотра. */
    public void onOpenCurrencies(View view) {
        startActivity(CurrencyPickerActivity.buildBrowseIntent(this));
    }

    /**
     * Подтверждение и удаление своей пары вместе с курсами (spec currency-pairs) — общее
     * для долгого нажатия здесь и кнопки в тулбаре CurrencyPairRateListActivity. Пишет
     * только локально; на сервер пара и курсы уйдут tombstone'ами со следующим push.
     */
    static void confirmDeletePair(android.app.Activity activity, CurrencyPair pair, Runnable onDeleted) {
        new AlertDialog.Builder(activity)
                .setMessage("Удалить пару " + pair.getSymbol() + "? Вся история её курса тоже будет удалена.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    long rates = liveRateCount(activity, pair.getId());
                    if (rates == 0) {
                        deletePair(activity, pair, onDeleted);
                    } else {
                        confirmWithRateCount(activity, pair, rates, onDeleted);
                    }
                })
                .show();
    }

    /**
     * Второй шаг для пары с историей: курсы вводились руками и восстановить их нечем,
     * поэтому перед удалением переспрашиваем, называя, сколько курсов уйдёт. Пара без
     * курсов сюда не попадает (spec currency-pairs).
     */
    private static void confirmWithRateCount(android.app.Activity activity, CurrencyPair pair, long rates,
                                             Runnable onDeleted) {
        new AlertDialog.Builder(activity)
                .setMessage("У пары " + pair.getSymbol() + " курсов: " + rates
                        + ". Удалить пару вместе со всеми курсами?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> deletePair(activity, pair, onDeleted))
                .show();
    }

    private static long liveRateCount(android.app.Activity activity, int pairId) {
        return android.database.DatabaseUtils.queryNumEntries(
                Singleton.instance.getReadableDatabase(activity),
                com.example.wallettransactions.Data.WalletContract.CurrencyPairRateEntry.TABLE_NAME,
                com.example.wallettransactions.Data.WalletContract.CurrencyPairRateEntry.COLUMN_CURRENCY_PAIR_RATE + "=? AND "
                        + com.example.wallettransactions.Data.WalletContract.CurrencyPairRateEntry.COLUMN_DELETED_AT + " IS NULL",
                new String[]{Integer.toString(pairId)});
    }

    private static void deletePair(android.app.Activity activity, CurrencyPair pair, Runnable onDeleted) {
        SQLiteDatabase writable = new WalletDbHelper(activity).getWritableDatabase();
        int deleted = WalletDbCRUD.DeleteCurrencyPair(writable, pair.getId());
        writable.close();
        if (deleted == 0) {
            Toast.makeText(activity, "Общая пара — удалить нельзя", Toast.LENGTH_SHORT).show();
            return;
        }
        Singleton.instance.reloadCurrencyPairList(activity);
        onDeleted.run();
    }

    /**
     * Пара проходит, если запрос пуст или совпадает с символом пары либо с ISO или
     * названием любой из двух её валют: «EUR», «eurusd» и «евро» одинаково находят пару.
     */
    private boolean matchesFilter(CurrencyPair cp) {
        if (filterQuery.isEmpty()) return true;

        return contains(cp.getSymbol())
                || matchesCurrency(cp.getBaseCurrency())
                || matchesCurrency(cp.getQuoteCurrency());
    }

    private boolean matchesCurrency(Currency currency) {
        return currency != null && (contains(currency.getIso()) || contains(currency.getName()));
    }

    private boolean contains(String value) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(filterQuery);
    }

    private void applyFilter() {
        CurrencyPairList all = Singleton.instance.getCurrencyPairList(this);
        List<CurrencyPair> filtered = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            CurrencyPair cp = all.getItem(i);
            if (matchesFilter(cp)) filtered.add(cp);
        }
        adapter.setItems(filtered);
    }

    private void showAddPairDialog() {
        selectedBaseIso = null;
        selectedQuoteIso = null;

        newPairDlgView = LayoutInflater.from(this).inflate(R.layout.dialog_new_pair, null);
        ((TextView) newPairDlgView.findViewById(R.id.baseValue)).setText("Выбрать");
        ((TextView) newPairDlgView.findViewById(R.id.quoteValue)).setText("Выбрать");

        newPairDialog = new AlertDialog.Builder(this).setView(newPairDlgView).create();
        newPairDialog.show();
    }

    /** rowBase tap target (dialog_new_pair.xml android:onClick). */
    public void onPickBase(View view) {
        startActivityForResult(
                CurrencyPickerActivity.buildIntent(this, "Базовая валюта", selectedBaseIso),
                REQUEST_PICK_BASE);
    }

    /** rowQuote tap target (dialog_new_pair.xml android:onClick). */
    public void onPickQuote(View view) {
        startActivityForResult(
                CurrencyPickerActivity.buildIntent(this, "Котируемая валюта", selectedQuoteIso),
                REQUEST_PICK_QUOTE);
    }

    /** btnCancel tap target (dialog_new_pair.xml android:onClick). */
    public void onCancelNewPair(View view) {
        if (newPairDialog != null) newPairDialog.dismiss();
    }

    /** btnCreate tap target (dialog_new_pair.xml android:onClick). */
    public void onCreatePair(View view) {
        if (selectedBaseIso == null || selectedQuoteIso == null) {
            Toast.makeText(this, "Выберите обе валюты", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedBaseIso.equals(selectedQuoteIso)) {
            Toast.makeText(this, "Валюты должны быть разными", Toast.LENGTH_SHORT).show();
            return;
        }

        int precision = 5;
        try {
            precision = Integer.parseInt(((EditText) newPairDlgView.findViewById(R.id.inputPrecision))
                    .getText().toString().trim());
        } catch (NumberFormatException ignored) {}

        SQLiteDatabase writable = new WalletDbHelper(this).getWritableDatabase();
        long newId = WalletDbCRUD.InsertCurrencyPair(writable, selectedBaseIso, selectedQuoteIso, precision);
        writable.close();
        if (newId < 0) {
            Toast.makeText(this, "Ошибка добавления пары", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPairDialog != null) newPairDialog.dismiss();
        // Reload pair list into singleton and refresh adapter (respecting the
        // currently active currency filters, if any)
        Singleton.instance.getCurrencyPairList(this).clear();
        Singleton.instance.getCurrencyPairList(this);
        applyFilter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || newPairDlgView == null) return;

        String iso = data.getStringExtra(CurrencyPickerActivity.RESULT_ISO);
        if (iso == null) return;

        if (requestCode == REQUEST_PICK_BASE) {
            selectedBaseIso = iso;
            ((TextView) newPairDlgView.findViewById(R.id.baseValue)).setText(iso);
        } else if (requestCode == REQUEST_PICK_QUOTE) {
            selectedQuoteIso = iso;
            ((TextView) newPairDlgView.findViewById(R.id.quoteValue)).setText(iso);
        }
    }
}
