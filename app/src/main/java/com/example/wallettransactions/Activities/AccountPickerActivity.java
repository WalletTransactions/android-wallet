package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.core.content.ContextCompat;
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

import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Экран со списком счетов. Работает в двух режимах, различает их {@link #cCatalogMode}:
 *
 * <ul>
 *   <li><b>выбор счёта для операции</b> — тап по строке возвращает её id и закрывает
 *   экран. Пришёл на смену списку-диалогу внутри {@link AccountActivity}, где он
 *   мешался с дашбордом «Кошельки»;</li>
 *
 *   <li><b>справочник</b> (пункт «Кошельки и категории» на табе «Ещё») — тот же
 *   список, но тап открывает счёт на редактирование ({@link AccountItemActivity}),
 *   в шапке есть «+», и видны архивные счета: снять архив больше негде.</li>
 * </ul>
 *
 * Строка списка — строка кошелька из отчёта «Кошельки» без остатка на начало
 * периода и без изменения за период: при вводе операции важно только, сколько на
 * счёте сейчас. Остаток берётся на конец общего периода — ровно то же число, что
 * показывает таб «Кошельки», чтобы экраны не расходились.
 *
 * Тип счёта задаёт {@link #cAccountSelectedType}:
 * <ul>
 *   <li>{@link #cSelectedTypeALL} — и категории, и кошельки, сверху переключатель;</li>
 *   <li>{@link #cSelectedTypeChargeAccounts} — только категории трат,</li>
 *   <li>{@link #cSelectedTypeOperationAccounts} — только кошельки.</li>
 * </ul>
 * В двух последних случаях переключатель скрыт: слот операции уже знает свой тип,
 * и подменять его выбором из другого списка нельзя.
 */
public class AccountPickerActivity extends AppCompatActivity {

    public final static int cSelectedTypeALL = 0;
    public final static int cSelectedTypeOperationAccounts = 1;
    public final static int cSelectedTypeChargeAccounts = 2;

    public final static String cAccountSelectedType = "AccountPicker.SelectedType";
    /** На входе — выбранный сейчас счёт (0, если не выбран), на выходе — выбранный. */
    public final static String cAccountId = "AccountPicker.AccountId";
    public final static String cTitle = "AccountPicker.Title";
    public final static String cCatalogMode = "AccountPicker.CatalogMode";

    private static final int REQUEST_EDIT_ACCOUNT = 300;
    private static final int REQUEST_ADD_WALLET   = 301;
    private static final int REQUEST_ADD_CATEGORY = 302;

    public static Intent buildIntent(Context ctx, String title, int selectedType, int currentAccountId) {
        Intent intent = new Intent(ctx, AccountPickerActivity.class);
        intent.putExtra(cTitle, title);
        intent.putExtra(cAccountSelectedType, selectedType);
        intent.putExtra(cAccountId, currentAccountId);
        return intent;
    }

    /** Справочник «Кошельки и категории»: оба списка, переключатель, правка по тапу. */
    public static Intent buildCatalogIntent(Context ctx) {
        Intent intent = new Intent(ctx, AccountPickerActivity.class);
        intent.putExtra(cTitle, "Кошельки и категории");
        intent.putExtra(cAccountSelectedType, cSelectedTypeALL);
        intent.putExtra(cCatalogMode, true);
        return intent;
    }

    private final List<Row> mWalletRows = new ArrayList<>();
    private final List<Row> mCategoryRows = new ArrayList<>();

    private final List<Row> mVisibleRows = new ArrayList<>();
    private AccountPickerAdapter mAdapter;

    private TextView mKindCategories;
    private TextView mKindWallets;
    private EditText mSearchInput;
    private View mEmptyState;

    private boolean mShowWallets;
    private boolean mCatalogMode;
    private int mCurrentAccountId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_picker);

        mKindCategories = findViewById(R.id.kindCategories);
        mKindWallets    = findViewById(R.id.kindWallets);
        mSearchInput    = findViewById(R.id.searchInput);
        mEmptyState     = findViewById(R.id.emptyState);

        String title = getIntent().getStringExtra(cTitle);
        if (title != null) ((TextView) findViewById(R.id.pickerTitle)).setText(title);

        mCatalogMode      = getIntent().getBooleanExtra(cCatalogMode, false);
        mCurrentAccountId = getIntent().getIntExtra(cAccountId, 0);
        int selectedType  = getIntent().getIntExtra(cAccountSelectedType, cSelectedTypeALL);

        findViewById(R.id.btnAddAccountRow).setVisibility(mCatalogMode ? View.VISIBLE : View.GONE);

        buildRows();

        // Тип задан слотом операции — переключать его нельзя, поэтому и показывать нечего.
        boolean typeIsFixed = selectedType != cSelectedTypeALL;
        findViewById(R.id.rowKindFilter).setVisibility(typeIsFixed ? View.GONE : View.VISIBLE);

        if (typeIsFixed) {
            mShowWallets = selectedType == cSelectedTypeOperationAccounts;
        } else {
            // Открываемся на той вкладке, где лежит уже выбранный счёт.
            AccountItem current = mCurrentAccountId > 0
                    ? Singleton.instance.getAccounts(this).getAccountById(mCurrentAccountId) : null;
            mShowWallets = current == null || current.isOperatingAccount();
        }

        mAdapter = new AccountPickerAdapter(this, mVisibleRows, currencyEq(), mCurrentAccountId);

        ListView list = findViewById(R.id.listAccounts);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int accountId = mVisibleRows.get(position).account.getId();

            if (mCatalogMode) {
                Intent edit = new Intent(this, AccountItemActivity.class);
                edit.putExtra(AccountItemActivity.ARG_ACCOUNT_ID, accountId);
                startActivityForResult(edit, REQUEST_EDIT_ACCOUNT);
                return;
            }

            Intent result = new Intent();
            result.putExtra(cAccountId, accountId);
            setResult(RESULT_OK, result);
            finish();
        });

        mSearchInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { applyFilters(); }
        });

        paintKindChips();
        applyFilters();
    }

    public void btnUp(View view) {
        finish();
    }

    /** Тот же выбор, что на табе «Кошельки»: справочник — второе место, где счета заводят. */
    public void onAddAccount(View view) {
        new AlertDialog.Builder(this)
                .setTitle("Добавить")
                .setItems(new String[]{"Кошелёк", "Категория"}, (d, which) -> {
                    if (which == 0) {
                        startActivityForResult(new Intent(this, AddWalletActivity.class), REQUEST_ADD_WALLET);
                    } else {
                        startActivityForResult(new Intent(this, AddCategoryActivity.class), REQUEST_ADD_CATEGORY);
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Счёт могли переименовать, заархивировать или удалить — список пересобираем.
        switch (requestCode) {
            case REQUEST_EDIT_ACCOUNT:
            case REQUEST_ADD_WALLET:
            case REQUEST_ADD_CATEGORY:
                if (resultCode == RESULT_OK) {
                    buildRows();
                    applyFilters();
                }
                break;
        }
    }

    public void onKindFilterClick(View view) {
        boolean pickedWallets = view.getId() == R.id.kindWallets;
        if (pickedWallets == mShowWallets) return;

        mShowWallets = pickedWallets;
        paintKindChips();
        applyFilters();
    }

    private void paintKindChips() {
        paintChip(mKindCategories, !mShowWallets);
        paintChip(mKindWallets, mShowWallets);
    }

    /** Тот же способ подсветки чипа, что на экране операции. */
    private void paintChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(this,
                active ? R.color.colorSurface : R.color.colorTextSecondary));
    }

    private void applyFilters() {
        // Именно contains, а не AccountItem.isLike: тот принимает строку как регулярное
        // выражение, и одна скобка, набранная в поиске, уронила бы экран.
        String query = mSearchInput.getText().toString().trim().toLowerCase();

        mVisibleRows.clear();
        for (Row row : (mShowWallets ? mWalletRows : mCategoryRows)) {
            if (query.isEmpty() || row.account.getName().toLowerCase().contains(query))
                mVisibleRows.add(row);
        }

        mAdapter.notifyDataSetChanged();
        mEmptyState.setVisibility(mVisibleRows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private Currency currencyEq() {
        return PeriodFilterState.get(this).getCurrencyEq(this);
    }

    /**
     * Кошельки с остатком на конец периода + категории трат. Остатки приходят только
     * по счетам, где были операции, поэтому список строится от самих счетов — иначе
     * только что заведённый кошелёк выбрать было бы нельзя.
     */
    private void buildRows() {
        AccountList accounts = Singleton.instance.getAccounts(this);
        Currency currencyEq = currencyEq();

        mWalletRows.clear();
        mCategoryRows.clear();

        Map<Integer, WalletDbLoader.WalletBalanceRow> balances = new HashMap<>();
        if (currencyEq != null) {
            SQLiteDatabase db = Singleton.instance.getReadableDatabase(this);
            java.util.Date dateTo = PeriodFilterState.get(this).getDateTo();
            for (WalletDbLoader.WalletBalanceRow r
                    : WalletDbLoader.LoadBalancesPerWallet(db, dateTo, dateTo, currencyEq)) {
                balances.put(r.accountId, r);
            }
        }

        for (AccountItem account : accounts.getAllAccounts()) {
            // Архивный счёт на то и архивный, чтобы не попадаться при вводе операции.
            // В справочнике он, наоборот, нужен: снять архив больше негде. Прячется он
            // только здесь, в выборе, — в расчётах и отчётах остаётся (AccountItem.getIsArchived).
            if (!mCatalogMode && Boolean.TRUE.equals(account.getIsArchived())) continue;

            if (account.isOperatingAccount()) {
                mWalletRows.add(new Row(account, balances.get(account.getId())));
            } else {
                mCategoryRows.add(new Row(account, null));
            }
        }

        // Кошельки — как в отчёте: крупные остатки сверху, счета без курса к валюте
        // эквивалента опускаются под те, что можно сравнить.
        mWalletRows.sort(Comparator
                .<Row>comparingInt(r -> r.hasEq ? 1 : 0).reversed()
                .thenComparing((a, b) -> Double.compare(
                        b.hasEq ? b.balanceEq : b.balance,
                        a.hasEq ? a.balanceEq : a.balance)));

        // У категорий сравнивать нечего — только имя.
        mCategoryRows.sort((a, b) -> a.account.getName().compareToIgnoreCase(b.account.getName()));
    }

    /** Счёт для строки списка: у кошелька есть остаток, у категории — нет. */
    private static class Row {
        final AccountItem account;
        final String currencySymbol;
        final double balance;
        final double balanceEq;
        final boolean hasBalance;
        final boolean hasEq;
        final boolean approx;

        Row(AccountItem account, WalletDbLoader.WalletBalanceRow balanceRow) {
            this.account = account;

            AccountOperatingItem wallet = account instanceof AccountOperatingItem
                    ? (AccountOperatingItem) account : null;

            // Кошелёк без операций в LoadBalancesPerWallet не попадает — у него ровно ноль.
            this.hasBalance     = wallet != null;
            this.currencySymbol = wallet != null && wallet.getCurrency() != null
                    ? wallet.getCurrency().getSymbol() : "";
            this.balance   = balanceRow != null ? balanceRow.balance : 0;
            this.balanceEq = balanceRow != null && balanceRow.hasEq ? balanceRow.balanceEq : 0;
            this.hasEq     = balanceRow == null || balanceRow.hasEq;
            this.approx    = balanceRow != null && balanceRow.approx;
        }
    }

    // ── Адаптер (account_picker_item_layout.xml) ──────────────────────────────

    private static class AccountPickerAdapter extends ArrayAdapter<Row> {

        private final Currency mCurrencyEq;
        private final int mCurrentAccountId;

        AccountPickerAdapter(Context ctx, List<Row> rows, Currency currencyEq, int currentAccountId) {
            super(ctx, R.layout.account_picker_item_layout, rows);
            mCurrencyEq = currencyEq;
            mCurrentAccountId = currentAccountId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.account_picker_item_layout, parent, false);

            Row row = getItem(position);

            ImageView imgIcon = convertView.findViewById(R.id.pickerIcon);
            ImageView imgFlag = convertView.findViewById(R.id.pickerFlag);
            TextView tvName = convertView.findViewById(R.id.pickerName);
            TextView tvBalance = convertView.findViewById(R.id.pickerBalance);
            TextView tvBalanceEq = convertView.findViewById(R.id.pickerBalanceEq);

            if (row.account.getIcon() != null) {
                imgIcon.setImageBitmap(row.account.getIcon().getBitmap());
            } else {
                imgIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
            }

            AccountOperatingItem wallet = row.account instanceof AccountOperatingItem
                    ? (AccountOperatingItem) row.account : null;
            if (wallet != null && wallet.getCurrency() != null && wallet.getCurrency().getIcon() != null) {
                imgFlag.setImageBitmap(wallet.getCurrency().getIcon().getBitmap());
                imgFlag.setVisibility(View.VISIBLE);
            } else {
                imgFlag.setVisibility(View.GONE);
            }

            boolean archived = Boolean.TRUE.equals(row.account.getIsArchived());
            tvName.setText(archived ? row.account.getName() + " · в архиве" : row.account.getName());

            int nameColor = R.color.colorTextPrimary;
            if (archived) nameColor = R.color.colorTextHint;
            else if (row.account.getId() == mCurrentAccountId) nameColor = R.color.colorPrimary;
            tvName.setTextColor(ContextCompat.getColor(getContext(), nameColor));

            if (!row.hasBalance) {
                tvBalance.setVisibility(View.GONE);
                tvBalanceEq.setVisibility(View.GONE);
                return convertView;
            }

            tvBalance.setVisibility(View.VISIBLE);
            tvBalance.setText(AdapterUtils.getFormat(2).format(row.balance) + " " + row.currencySymbol);

            // Эквивалент показываем только когда он что-то добавляет: у кошелька в валюте
            // эквивалента это была бы та же сумма второй раз.
            if (mCurrencyEq == null || !row.approx) {
                tvBalanceEq.setVisibility(View.GONE);
            } else {
                tvBalanceEq.setVisibility(View.VISIBLE);
                tvBalanceEq.setText(row.hasEq
                        ? "≈ " + AdapterUtils.getFormat(mCurrencyEq.getPrecision()).format(row.balanceEq)
                            + " " + mCurrencyEq.getSymbol()
                        : "~");
            }

            return convertView;
        }
    }
}
