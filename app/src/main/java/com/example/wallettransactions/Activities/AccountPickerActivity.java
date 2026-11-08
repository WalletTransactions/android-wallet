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
import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.Accounts.AccountOperatingItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.PickerSort;
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
 *
 * Порядок строк ({@link PickerSort}) у кошельков и категорий свой: на входе — из
 * «Настроек», кнопка в шапке переключает его по кругу только на этот раз, в
 * настройки не сохраняя. «По частоте» учитывает счёт из
 * второго слота операции ({@link #cPairAccountId}), если это кошелёк: сверху то, что
 * чаще всего шло именно с ним, ниже — по общей частоте.
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
    /** Счёт из второго слота операции (0 — нет): по нему считается частота «в паре». */
    public final static String cPairAccountId = "AccountPicker.PairAccountId";
    /**
     * Счета, которые идут первыми в указанном порядке при любой сортировке
     * (int[] {@code _id}): кандидаты resolver с экрана «ИИ», подсказка категории
     * импорта выписки. Остальные — как обычно.
     */
    public final static String cPriorityIds = "AccountPicker.PriorityIds";
    /**
     * Не показывать системные счета («Перевод», «Комиссия», «Переоценка»): списание остатка
     * при архивации кошелька идёт только на обычную категорию (archive-wallet-writeoff, D8).
     */
    public final static String cHideSystem = "AccountPicker.HideSystem";

    private static final int REQUEST_EDIT_ACCOUNT = 300;
    private static final int REQUEST_ADD_WALLET   = 301;
    private static final int REQUEST_ADD_CATEGORY = 302;

    public static Intent buildIntent(Context ctx, String title, int selectedType, int currentAccountId) {
        return buildIntent(ctx, title, selectedType, currentAccountId, null);
    }

    public static Intent buildIntent(Context ctx, String title, int selectedType, int currentAccountId,
                                     AccountItem pairAccount) {
        return buildIntent(ctx, title, selectedType, currentAccountId, pairAccount, null);
    }

    public static Intent buildIntent(Context ctx, String title, int selectedType, int currentAccountId,
                                     AccountItem pairAccount, int[] priorityIds) {
        Intent intent = new Intent(ctx, AccountPickerActivity.class);
        if (priorityIds != null && priorityIds.length > 0) intent.putExtra(cPriorityIds, priorityIds);
        intent.putExtra(cTitle, title);
        intent.putExtra(cAccountSelectedType, selectedType);
        intent.putExtra(cAccountId, currentAccountId);
        intent.putExtra(cPairAccountId, pairAccount == null ? 0 : pairAccount.getId());
        return intent;
    }

    /** Справочник «Кошельки и категории»: оба списка, переключатель, правка по тапу. */
    public static Intent buildCatalogIntent(Context ctx) {
        Intent intent = new Intent(ctx, AccountPickerActivity.class);
        intent.putExtra(cTitle, ctx.getString(R.string.picker_catalog_title));
        intent.putExtra(cAccountSelectedType, cSelectedTypeALL);
        intent.putExtra(cCatalogMode, true);
        return intent;
    }

    private final List<Row> mWalletRows = new ArrayList<>();
    private final List<Row> mCategoryRows = new ArrayList<>();

    private final List<Row> mVisibleRows = new ArrayList<>();
    /** {@code _id} → место среди приоритетных ({@link #cPriorityIds}). */
    private final Map<Integer, Integer> mPriorityRank = new HashMap<>();
    private AccountPickerAdapter mAdapter;

    private TextView mKindCategories;
    private TextView mKindWallets;
    private TextView mBtnSort;
    private EditText mSearchInput;
    private View mEmptyState;

    private boolean mShowWallets;
    private boolean mCatalogMode;
    private int mCurrentAccountId;
    /** Только кошелёк: с категорией в паре частота ничего не говорит о выборе кошелька. */
    private int mPairWalletId;
    /** Порядок списков: на входе — из «Настроек», кнопка в шапке меняет только здесь. */
    private PickerSort mWalletSort;
    private PickerSort mCategorySort;
    /** Читается из базы при первой сортировке по частоте; null — ещё не читали. */
    private Map<Integer, WalletDbLoader.AccountUsage> mUsage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_picker);

        mKindCategories = findViewById(R.id.kindCategories);
        mKindWallets    = findViewById(R.id.kindWallets);
        mBtnSort        = findViewById(R.id.btnSort);
        mSearchInput    = findViewById(R.id.searchInput);
        mEmptyState     = findViewById(R.id.emptyState);

        String title = getIntent().getStringExtra(cTitle);
        if (title != null) ((TextView) findViewById(R.id.pickerTitle)).setText(title);

        mCatalogMode      = getIntent().getBooleanExtra(cCatalogMode, false);
        mCurrentAccountId = getIntent().getIntExtra(cAccountId, 0);
        int selectedType  = getIntent().getIntExtra(cAccountSelectedType, cSelectedTypeALL);

        int[] priority = getIntent().getIntArrayExtra(cPriorityIds);
        if (priority != null) for (int i = 0; i < priority.length; i++) mPriorityRank.putIfAbsent(priority[i], i);

        int pairId = getIntent().getIntExtra(cPairAccountId, 0);
        AccountItem pair = pairId > 0 ? Singleton.instance.getAccounts(this).getAccountById(pairId) : null;
        mPairWalletId = pair != null && pair.isOperatingAccount() ? pairId : 0;

        mWalletSort   = AppSettings.getPickerSort(this, true);
        mCategorySort = AppSettings.getPickerSort(this, false);

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
        AppSettings.applyPickerKeyboard(this, mSearchInput);
    }

    public void btnUp(View view) {
        finish();
    }

    /** Тот же выбор, что на табе «Кошельки»: справочник — второе место, где счета заводят. */
    public void onAddAccount(View view) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.picker_add_title)
                .setItems(new String[]{getString(R.string.picker_add_wallet), getString(R.string.picker_add_category)}, (d, which) -> {
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
        // У каждого списка свой порядок — кнопка показывает режим видимого.
        paintSortButton();
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
            if (query.isEmpty() || row.account.getDisplayName().toLowerCase().contains(query))
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
        boolean hideSystem = getIntent().getBooleanExtra(cHideSystem, false);

        mWalletRows.clear();
        mCategoryRows.clear();
        mUsage = null;

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
            // «Переоценку» при вводе операции не предлагаем (capital-assets): её проводки
            // пишет диалог переоценки актива. В справочнике она видна.
            if (!mCatalogMode && account.isSystemRevaluation()) continue;
            if (hideSystem && account.isSystem()) continue;

            if (account.isOperatingAccount()) {
                mWalletRows.add(new Row(account, balances.get(account.getId())));
            } else {
                mCategoryRows.add(new Row(account, null));
            }
        }

        sortRows();
    }

    // ── Сортировка ────────────────────────────────────────────────────────────

    /**
     * Тап по кнопке в шапке — следующий режим видимого списка по кругу. Только на этот
     * раз: в настройки не пишется, следующий экран выбора снова откроется с режимом
     * из «Настроек».
     */
    public void onPickSort(View view) {
        PickerSort[] modes = mShowWallets ? PickerSort.WALLET_MODES : PickerSort.CATEGORY_MODES;
        PickerSort next = modes[(PickerSort.indexOf(modes, currentSort()) + 1) % modes.length];
        if (mShowWallets) mWalletSort = next; else mCategorySort = next;

        sortRows();
        paintSortButton();
        applyFilters();
    }

    private PickerSort currentSort() {
        return mShowWallets ? mWalletSort : mCategorySort;
    }

    private void paintSortButton() {
        mBtnSort.setText("⇅ " + currentSort().getTitle(this));
    }

    private void sortRows() {
        // capital-assets: при вводе операции активы — после всех ликвидных кошельков при
        // любой сортировке (стабильное разбиение поверх выбранного порядка). В справочнике
        // активы стоят вместе с кошельками, только с подписью типа.
        Comparator<Row> walletOrder = withPriority(comparator(mWalletSort));
        if (!mCatalogMode) walletOrder = Comparator.<Row>comparingInt(r -> isAsset(r.account) ? 1 : 0).thenComparing(walletOrder);
        mWalletRows.sort(walletOrder);
        mCategoryRows.sort(withPriority(comparator(mCategorySort)));
    }

    static boolean isAsset(AccountItem account) {
        return account instanceof AccountOperatingItem && ((AccountOperatingItem) account).isAsset();
    }

    /** Подпись типа актива: «недвижимость», «авто», «прочее». */
    static String assetTypeTitle(Context ctx, int type) {
        switch (type) {
            case 1: return ctx.getString(R.string.asset_type_real_estate_short);
            case 2: return ctx.getString(R.string.asset_type_vehicle_short);
            default: return ctx.getString(R.string.asset_type_other_short);
        }
    }

    /** Приоритетные счета — первыми в своём порядке, остальные — по {@code rest}. */
    private Comparator<Row> withPriority(Comparator<Row> rest) {
        if (mPriorityRank.isEmpty()) return rest;
        return Comparator.<Row>comparingInt(r -> mPriorityRank.getOrDefault(r.account.getId(), Integer.MAX_VALUE))
                .thenComparing(rest);
    }

    private Comparator<Row> comparator(PickerSort sort) {
        Comparator<Row> byName = (a, b) -> a.account.getDisplayName().compareToIgnoreCase(b.account.getDisplayName());

        switch (sort) {
            case BALANCE:
                // Как в отчёте: крупные остатки сверху, счета без курса к валюте
                // эквивалента опускаются под те, что можно сравнить.
                return Comparator
                        .<Row>comparingInt(r -> r.hasEq ? 1 : 0).reversed()
                        .thenComparing((a, b) -> Double.compare(
                                b.hasEq ? b.balanceEq : b.balance,
                                a.hasEq ? a.balanceEq : a.balance))
                        .thenComparing(byName);

            case USAGE:
                // Сначала то, что шло с кошельком из второго слота, потом общая частота.
                // При равном счёте выше тот, что использовали позже; ни разу не
                // использованные — в конце по алфавиту.
                Map<Integer, WalletDbLoader.AccountUsage> usage = usage();
                WalletDbLoader.AccountUsage none = new WalletDbLoader.AccountUsage();
                Comparator<String> laterFirst = Comparator.nullsLast(Comparator.<String>reverseOrder());
                java.util.function.Function<Row, WalletDbLoader.AccountUsage> of =
                        r -> usage.getOrDefault(r.account.getId(), none);
                return Comparator
                        .<Row>comparingInt(r -> -of.apply(r).pairCount)
                        .thenComparing(r -> of.apply(r).pairLastDate, laterFirst)
                        .thenComparingInt(r -> -of.apply(r).count)
                        .thenComparing(r -> of.apply(r).lastDate, laterFirst)
                        .thenComparing(byName);

            default:
                return byName;
        }
    }

    private Map<Integer, WalletDbLoader.AccountUsage> usage() {
        if (mUsage == null) {
            mUsage = WalletDbLoader.LoadAccountUsage(
                    Singleton.instance.getReadableDatabase(this), mPairWalletId);
        }
        return mUsage;
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
            String name = row.account.getDisplayName();
            if (wallet != null && wallet.isAsset()) {
                name = convertView.getContext().getString(R.string.picker_asset_name, name,
                        assetTypeTitle(convertView.getContext(), wallet.getAsset().type));
            }
            tvName.setText(archived ? convertView.getContext().getString(R.string.picker_archived_name, name) : name);

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
