package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Экран «Фильтр» операций. Открывается общей кнопкой шапки — см.
 * {@link BaseTabActivity#btnChangeFilter}, — и одинаково работает со всех табов.
 *
 * Период здесь не показывается: он уже есть в шапке самих «Операций»
 * ({@link com.example.wallettransactions.Activities.Fragments.ChooseDatePeriodFragment}),
 * дублировать его тут незачем.
 *
 * Контракт в обе стороны — {@link MainActivity#cFilterAccountList} (ID через запятую):
 * на входе уже выбранные счета (чтобы экран открывался в текущем состоянии фильтра, а не
 * пустым), на выходе — новые. Применяет их {@link BaseTabActivity#onActivityResult},
 * записывая в общее состояние {@link com.example.wallettransactions.PeriodFilterState}.
 *
 * Вид операции, текст поиска и границы сумм пишутся сразу в {@link PeriodFilterState} —
 * он один на процесс, поэтому передавать их через extra не нужно (как и период).
 */
public class FilterActivity extends AppCompatActivity {

    private static final int requestPickCategories = 1;
    private static final int requestPickWallets = 2;

    private final List<AccountItem> selectedCategories = new ArrayList<>();
    private final List<AccountItem> selectedWallets = new ArrayList<>();

    private TextView categorySummary;
    private TextView walletSummary;

    private EditText inputSearchText;
    private TextView chipAll;
    private ViewGroup chipExpense;
    private ViewGroup chipIncome;
    private ViewGroup chipTransfer;
    private DataFilter.OperationKind selectedKind = DataFilter.OperationKind.all;

    private EditText inputAmountMin;
    private EditText inputAmountMax;
    private EditText inputEqMin;
    private EditText inputEqMax;

    /** «Показать N» — N считает {@link #updateApplyButtonCount()} по ещё не применённому выбору. */
    private TextView btnApply;

    /** Крестики очистки по одному параметру (similar-search) — видны, только пока параметр задан. */
    private View clearSearch;
    private View clearKind;
    private View clearAmountMin;
    private View clearAmountMax;
    private View clearEqMin;
    private View clearEqMax;
    private View clearCategories;
    private View clearWallets;
    private View chevronCategories;
    private View chevronWallets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter);

        categorySummary = findViewById(R.id.categorySummary);
        walletSummary = findViewById(R.id.walletSummary);
        btnApply = findViewById(R.id.btnApply);

        setupSearchText();
        setupKind();
        setupAmountRanges();
        restoreSelection(getIntent().getStringExtra(MainActivity.cFilterAccountList));
        setupClearButtons();

        // Слушатели — после того, как поля заполнены исходным состоянием: иначе
        // восстановление сохранённого текста само по себе гоняло бы лишние запросы.
        attachCountWatchers(inputSearchText, inputAmountMin, inputAmountMax, inputEqMin, inputEqMax);
        updateApplyButtonCount();
    }

    // ── Кнопка «Показать N» ───────────────────────────────────────────────────

    private void attachCountWatchers(EditText... fields) {
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { updateApplyButtonCount(); }
        };
        for (EditText field : fields) field.addTextChangedListener(watcher);
    }

    /**
     * Период и валюта эквивалента берутся из общего состояния как есть — здесь их не
     * меняют (см. класс-javadoc), а остальное — из ещё не применённого выбора на экране,
     * поэтому строится временный {@link DataFilter}, а не {@code state().getDataFilter()}.
     */
    private void updateApplyButtonCount() {
        PeriodFilterState state = PeriodFilterState.get(this);
        int count = WalletDbLoader.CountOperations(Singleton.instance.getReadableDatabase(this),
                state.getDateFrom(), state.getDateTo(), state.getCurrencyEq(this), buildPreviewFilter());
        btnApply.setText(getString(R.string.filter_apply_count, count));
        refreshClearButtons();
    }

    // ── Крестики очистки по одному параметру ──────────────────────────────────

    private void setupClearButtons() {
        clearSearch = findViewById(R.id.btnClearSearch);
        clearKind = findViewById(R.id.btnClearKind);
        clearAmountMin = findViewById(R.id.btnClearAmountMin);
        clearAmountMax = findViewById(R.id.btnClearAmountMax);
        clearEqMin = findViewById(R.id.btnClearEqMin);
        clearEqMax = findViewById(R.id.btnClearEqMax);
        clearCategories = findViewById(R.id.btnClearCategories);
        clearWallets = findViewById(R.id.btnClearWallets);
        chevronCategories = findViewById(R.id.chevronCategories);
        chevronWallets = findViewById(R.id.chevronWallets);

        clearSearch.setOnClickListener(v -> inputSearchText.setText(""));
        clearAmountMin.setOnClickListener(v -> inputAmountMin.setText(""));
        clearAmountMax.setOnClickListener(v -> inputAmountMax.setText(""));
        clearEqMin.setOnClickListener(v -> inputEqMin.setText(""));
        clearEqMax.setOnClickListener(v -> inputEqMax.setText(""));
        clearKind.setOnClickListener(v -> pickKind(DataFilter.OperationKind.all));
        clearCategories.setOnClickListener(v -> {
            selectedCategories.clear();
            updateSummary(selectedCategories, categorySummary);
            updateApplyButtonCount();
        });
        clearWallets.setOnClickListener(v -> {
            selectedWallets.clear();
            updateSummary(selectedWallets, walletSummary);
            updateApplyButtonCount();
        });
    }

    /**
     * Крестик виден только у заданного параметра; в строках счетов он занимает место
     * шеврона — выбор уже сделан, и открыть пикер можно тапом по самой строке.
     */
    private void refreshClearButtons() {
        if (clearSearch == null) return;

        setVisible(clearSearch, inputSearchText.getText().length() > 0);
        setVisible(clearKind, selectedKind != DataFilter.OperationKind.all);
        setVisible(clearAmountMin, inputAmountMin.getText().length() > 0);
        setVisible(clearAmountMax, inputAmountMax.getText().length() > 0);
        setVisible(clearEqMin, inputEqMin.getText().length() > 0);
        setVisible(clearEqMax, inputEqMax.getText().length() > 0);

        setVisible(clearCategories, !selectedCategories.isEmpty());
        setVisible(chevronCategories, selectedCategories.isEmpty());
        setVisible(clearWallets, !selectedWallets.isEmpty());
        setVisible(chevronWallets, selectedWallets.isEmpty());
    }

    private static void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private DataFilter buildPreviewFilter() {
        DataFilter preview = new DataFilter();
        for (AccountItem item : selectedCategories) preview.addAccount(item);
        for (AccountItem item : selectedWallets) preview.addAccount(item);
        preview.setOperationKind(selectedKind);
        preview.setSearchText(inputSearchText.getText().toString());
        preview.setAmountRange(parseOptionalDouble(inputAmountMin), parseOptionalDouble(inputAmountMax));
        preview.setEqRange(parseOptionalDouble(inputEqMin), parseOptionalDouble(inputEqMax));
        return preview;
    }

    // ── Поиск по тексту ───────────────────────────────────────────────────────

    private void setupSearchText() {
        inputSearchText = findViewById(R.id.inputSearchText);
        inputSearchText.setText(PeriodFilterState.get(this).getSearchText());
    }

    // ── Тип операции ──────────────────────────────────────────────────────────

    private void setupKind() {
        chipAll = findViewById(R.id.chipAll);
        chipExpense = findViewById(R.id.chipExpense);
        chipIncome = findViewById(R.id.chipIncome);
        chipTransfer = findViewById(R.id.chipTransfer);

        selectedKind = PeriodFilterState.get(this).getOperationKind();
        paintKind();

        chipAll.setOnClickListener(v -> pickKind(DataFilter.OperationKind.all));
        chipExpense.setOnClickListener(v -> pickKind(DataFilter.OperationKind.expense));
        chipIncome.setOnClickListener(v -> pickKind(DataFilter.OperationKind.income));
        chipTransfer.setOnClickListener(v -> pickKind(DataFilter.OperationKind.transfer));
    }

    private void pickKind(DataFilter.OperationKind kind) {
        selectedKind = kind;
        paintKind();
        updateApplyButtonCount();
    }

    /** Красит все четыре чипа под текущий выбор — как чипы типа операции на экране операции. */
    private void paintKind() {
        paintPlainChip(chipAll, selectedKind == DataFilter.OperationKind.all);
        paintIconChip(chipExpense, selectedKind == DataFilter.OperationKind.expense);
        paintIconChip(chipIncome, selectedKind == DataFilter.OperationKind.income);
        paintIconChip(chipTransfer, selectedKind == DataFilter.OperationKind.transfer);
    }

    private void paintPlainChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(this,
                active ? R.color.colorSurface : R.color.colorTextSecondary));
    }

    /** Чип-контейнер из иконки (childAt 0) и подписи (childAt 1) — см. FilterChipIcon. */
    private void paintIconChip(ViewGroup chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);

        int color = ContextCompat.getColor(this,
                active ? R.color.colorSurface : R.color.colorTextSecondary);
        ((ImageView) chip.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
        ((TextView) chip.getChildAt(1)).setTextColor(color);
    }

    // ── Суммы ─────────────────────────────────────────────────────────────────

    private void setupAmountRanges() {
        inputAmountMin = findViewById(R.id.inputAmountMin);
        inputAmountMax = findViewById(R.id.inputAmountMax);
        inputEqMin = findViewById(R.id.inputEqMin);
        inputEqMax = findViewById(R.id.inputEqMax);

        PeriodFilterState state = PeriodFilterState.get(this);
        setOptionalText(inputAmountMin, state.getAmountMin());
        setOptionalText(inputAmountMax, state.getAmountMax());
        setOptionalText(inputEqMin, state.getEqMin());
        setOptionalText(inputEqMax, state.getEqMax());

        // Валюта эквивалента общая для всех табов и выбирается в шапке — тут не рубли
        // жёстко, а то, что выбрано сейчас (см. BaseTabActivity.onPickCurrencyEq).
        TextView labelEqRange = findViewById(R.id.labelEqRange);
        Currency currencyEq = state.getCurrencyEq(this);
        labelEqRange.setText(currencyEq != null
                ? getString(R.string.filter_eq_range_label_currency, currencyEq.getIso())
                : getString(R.string.filter_eq_range_label));
    }

    private static void setOptionalText(EditText field, Double value) {
        field.setText(value == null ? "" : formatCompact(value));
    }

    /** Без лишних нулей после запятой: 100.0 → «100», 100.5 → «100.5». */
    private static String formatCompact(double value) {
        return com.example.wallettransactions.AdapterUtils.plainNumber(value);
    }

    /** null — поле пустое или не число (запятую тоже принимаем как разделитель). */
    private static Double parseOptionalDouble(EditText field) {
        String text = field.getText().toString().trim().replace(",", ".");
        if (text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Счета ─────────────────────────────────────────────────────────────────

    /** Пришедшие ID раскладываются на кошельки и счета затрат — на экране это разные строки. */
    private void restoreSelection(String csvIds) {
        AccountList accounts = Singleton.instance.getAccounts(this);

        for (Integer id : PeriodFilterState.parseIds(csvIds)) {
            AccountItem item = accounts.getAccountById(id);
            if (item == null) continue;

            (item.isOperatingAccount() ? selectedWallets : selectedCategories).add(item);
        }

        updateSummary(selectedCategories, categorySummary);
        updateSummary(selectedWallets, walletSummary);
    }

    public void btnUp(View view) {
        finish();
    }

    public void onPickCategories(View view) {
        openPicker(getString(R.string.filter_picker_categories), MultiPickerActivity.cSourceExpanseAccounts,
                selectedCategories, requestPickCategories);
    }

    public void onPickWallets(View view) {
        openPicker(getString(R.string.filter_picker_wallets), MultiPickerActivity.cSourceOperationAccounts,
                selectedWallets, requestPickWallets);
    }

    private void openPicker(String title, int sourceType, List<AccountItem> selected, int requestCode) {
        Intent intent = new Intent(this, MultiPickerActivity.class);
        intent.putExtra(MultiPickerActivity.cTitle, title);
        intent.putExtra(MultiPickerActivity.cSourceType, sourceType);
        intent.putExtra(MultiPickerActivity.cSelectedIds, joinIds(selected));

        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        Set<Integer> ids = MultiPickerActivity.parseIds(
                data.getStringExtra(MultiPickerActivity.cSelectedIds));

        AccountList accounts = Singleton.instance.getAccounts(this);
        List<AccountItem> target = requestCode == requestPickCategories
                ? selectedCategories : selectedWallets;

        target.clear();
        for (Integer id : ids) {
            AccountItem item = accounts.getAccountById(id);
            if (item != null) target.add(item);
        }

        updateSummary(target, requestCode == requestPickCategories ? categorySummary : walletSummary);
        updateApplyButtonCount();
    }

    private void updateSummary(List<AccountItem> selected, TextView summary) {
        summary.setText(selected.isEmpty() ? getString(R.string.filter_all)
                : getResources().getQuantityString(R.plurals.filter_selected_count, selected.size(), selected.size()));
    }

    private static String joinIds(List<AccountItem> items) {
        StringBuilder ids = new StringBuilder();
        for (AccountItem item : items) {
            if (ids.length() > 0) ids.append(",");
            ids.append(item.getId());
        }
        return ids.toString();
    }

    public void onResetFilter(View view) {
        selectedCategories.clear();
        selectedWallets.clear();
        categorySummary.setText(R.string.filter_all);
        walletSummary.setText(R.string.filter_all);

        selectedKind = DataFilter.OperationKind.all;
        paintKind();

        inputSearchText.setText("");
        inputAmountMin.setText("");
        inputAmountMax.setText("");
        inputEqMin.setText("");
        inputEqMax.setText("");

        // Каждый setText выше уже дёргает watcher, но явный вызов не даёт положиться
        // на порядок срабатываний — последним всегда считаем по полностью сброшенному виду.
        updateApplyButtonCount();
    }

    public void onApplyFilter(View view) {
        PeriodFilterState state = PeriodFilterState.get(this);
        state.setOperationKind(this, selectedKind);
        state.setSearchText(this, inputSearchText.getText().toString());
        state.setAmountRange(this, parseOptionalDouble(inputAmountMin), parseOptionalDouble(inputAmountMax));
        state.setEqRange(this, parseOptionalDouble(inputEqMin), parseOptionalDouble(inputEqMax));

        List<AccountItem> allSelected = new ArrayList<>();
        allSelected.addAll(selectedCategories);
        allSelected.addAll(selectedWallets);

        Intent answerIntent = new Intent();
        if (!allSelected.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (AccountItem item : allSelected) {
                if (ids.length() > 0) ids.append(",");
                ids.append(item.getId());
            }
            answerIntent.putExtra(MainActivity.cFilterAccountList, ids.toString());
        }

        setResult(RESULT_OK, answerIntent);
        finish();
    }
}
