package com.example.wallettransactions.Activities;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.example.wallettransactions.Activities.Fragments.ChooseDatePeriodFragment;
import com.example.wallettransactions.AdapterUtils;
import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.Data.RecurringOperationsDb;
import com.example.wallettransactions.Data.WalletDbLoader;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.EquivalentCurrencies;
import com.example.wallettransactions.DataItems.DataFilter;
import com.example.wallettransactions.DataItems.Operations.EquivalentResult;
import com.example.wallettransactions.DataItems.ReportItems.ReportByAccount;
import com.example.wallettransactions.DateUtils;
import com.example.wallettransactions.PeriodFilterState;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Общая часть четырёх корневых экранов нижней панели: «Операции», «Кошельки»,
 * «Отчёты», «Ещё».
 *
 * Раньше каждый экран сам подсвечивал таб, сам заводил период, сам считал карточку
 * остатков и сам обрабатывал фильтр — четыре почти одинаковые копии, которые к тому
 * же не знали друг о друге, поэтому при переходе на другой таб период и фильтр
 * сбрасывались. Здесь это в одном месте:
 *
 * <ul>
 *   <li>период, фильтр и валюта эквивалента живут в {@link PeriodFilterState} —
 *   один объект на все табы, переживает и переход, и перезапуск приложения;</li>
 *   <li>вёрстка шапки — {@code view_tab_title.xml} + {@code view_tab_actions.xml} +
 *   {@code view_period_summary.xml}, экраны их только подключают;</li>
 *   <li>цифры карточки («на начало», «на конец», доход, расход, итог) считает
 *   {@link #refreshSummaryCard()} — одна реализация, поэтому на всех табах они
 *   совпадают;</li>
 *   <li>переходы между табами, кнопка «+», фильтр и выбор валюты — обработчики
 *   {@code android:onClick} из общих layout'ов.</li>
 * </ul>
 *
 * Экран наследника: {@code setContentView(...)} → свои {@code findViewById} →
 * {@link #initTabScreen(int, String)} → {@link #reloadSharedAndTabData()}.
 * Перезагрузку своего списка он делает в {@link #reloadTabData()}.
 */
public abstract class BaseTabActivity extends AppCompatActivity {

    /** Коды запросов общей шапки; наследники нумеруют свои ниже 900. */
    protected static final int requestCodeSharedFilter = 900;
    protected static final int requestCodeSharedOperationAdd = 901;

    protected ChooseDatePeriodFragment fragmentDataPeriod;

    /**
     * false, пока {@link #initTabScreen(int, String)} не вызван. Экран-наследник может
     * работать и без общей шапки — {@link AccountActivity} в режиме пикера вообще не
     * ставит layout, — и тогда обновлять нечего.
     */
    private boolean tabScreenInitialized;

    /** Ревизия состояния, которую экран уже отрисовал — см. {@link #onResume()}. */
    private int renderedRevision = -1;

    /** Пока true, слушатель периода молчит: даты ставит сам экран, а не пользователь. */
    private boolean applyingSharedState;

    // ── Общее состояние ───────────────────────────────────────────────────────

    protected PeriodFilterState state() {
        return PeriodFilterState.get(this);
    }

    protected Date getDateFrom() {
        return state().getDateFrom();
    }

    protected Date getDateTo() {
        return state().getDateTo();
    }

    protected DataFilter getDataFilter() {
        return state().getDataFilter();
    }

    protected Currency getCurrencyEq() {
        return state().getCurrencyEq(this);
    }

    /** Одно соединение с базой на приложение — закрывать его экраны не должны. */
    protected SQLiteDatabase getDb() {
        return Singleton.instance.getReadableDatabase(this);
    }

    // ── Инициализация ─────────────────────────────────────────────────────────

    /**
     * @param activeTabId один из R.id.navOps / navWallets / navReport / navMore
     * @param title       текст для {@code view_tab_title}; null — если у экрана свой заголовок
     */
    protected void initTabScreen(int activeTabId, String title) {
        BottomNav.setActiveTab(this, activeTabId);

        TextView screenTitle = findViewById(R.id.screenTitle);
        if (screenTitle != null && title != null) screenTitle.setText(title);

        updateCurrencyButton();

        fragmentDataPeriod = (ChooseDatePeriodFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragmentDataPeriod);

        if (fragmentDataPeriod != null) {
            // Сначала даты, потом слушатель: setDateFrom/To сами дёргают onDateChanged,
            // и с уже подключённым слушателем экран грузился бы дважды.
            applySharedPeriod();

            fragmentDataPeriod.setOnDatePeriodChangedListener((parent, from, to) -> {
                if (applyingSharedState) return;
                state().setPeriod(this, from, to);
                reloadSharedAndTabData();
            });
        }

        // Первую отрисовку наследник делает сам (например, «Операции» — только после
        // диалога аварийного восстановления), поэтому ревизию считаем актуальной.
        renderedRevision = state().getRevision();
        tabScreenInitialized = true;
    }

    private void applySharedPeriod() {
        applyingSharedState = true;
        try {
            fragmentDataPeriod.setDateFrom(getDateFrom());
            fragmentDataPeriod.setDateTo(getDateTo());
        } finally {
            applyingSharedState = false;
        }
    }

    /**
     * Период, фильтр или валюту могли сменить на другом табе (или в «Настройках») —
     * тогда экран, поднятый через FLAG_ACTIVITY_REORDER_TO_FRONT, показывал бы старое.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (!tabScreenInitialized) return;

        state().syncCurrencyEqFromSettings(this);
        applyRecurringOperations();
        reloadIfStale();
        state().addDataChangedListener(onDataChanged);
    }

    @Override
    protected void onPause() {
        state().removeDataChangedListener(onDataChanged);
        super.onPause();
    }

    /** Данные сменились, пока экран открыт (фоновый синк) — см. PeriodFilterState.markDataChanged. */
    private final Runnable onDataChanged = this::reloadIfStale;

    private void reloadIfStale() {
        if (state().getRevision() == renderedRevision) return;

        updateCurrencyButton();
        if (fragmentDataPeriod != null) applySharedPeriod();
        reloadSharedAndTabData();
    }

    /**
     * Заводит операции, которые по правилам «Повторяющихся операций» уже наступили. В фоне
     * их к сроку заводит {@link com.example.wallettransactions.sync.RecurringWorker}, но
     * Doze может его придержать — открытый таб не ждёт. Создали что-то — поднимаем
     * ревизию, и проверка ниже перечитает экран.
     * <p>
     * После аварийного завершения не трогаем базу, пока пользователь не выбрал действие в
     * диалоге восстановления ({@link MainActivity}).
     */
    private void applyRecurringOperations() {
        if (com.example.wallettransactions.WalletApplication.hadCrash(this)) return;
        int created = RecurringOperationsDb.generateDue(
                Singleton.instance.getWritableDatabase(this), java.time.LocalDateTime.now());
        if (created > 0) state().markDataChanged();
    }

    // ── Перерисовка ───────────────────────────────────────────────────────────

    /** Обновить общую шапку и данные экрана. */
    protected void reloadSharedAndTabData() {
        refreshSummaryCard();
        refreshFilterBanner();
        reloadTabData();
        renderedRevision = state().getRevision();
    }

    /** Перезагрузка того, что специфично для экрана: лента операций, кошельки, категории. */
    protected abstract void reloadTabData();

    /**
     * Цифры карточки состояния денег за текущий период. Одна реализация на все табы —
     * до этого «Операции» и «Кошельки» считали их своими копиями этого кода.
     */
    protected void refreshSummaryCard() {
        TextView openingBalance = findViewById(R.id.openingBalance);
        if (openingBalance == null) return; // экран без карточки («Ещё»)

        SQLiteDatabase db = getDb();
        Currency currencyEq = getCurrencyEq();

        EquivalentResult opBalance = WalletDbLoader.GenerateReportGetBalanceToDate(
                db, getDateFrom(), currencyEq, getDataFilter());
        EquivalentResult clBalance = WalletDbLoader.GenerateReportGetBalanceToDate(
                db, getDateTo(), currencyEq, getDataFilter());

        AdapterUtils.setAmountText(openingBalance, opBalance, currencyEq, AdapterUtils.Operation.transfer);
        AdapterUtils.setAmountText(findViewById(R.id.endingBalance), clBalance, currencyEq,
                AdapterUtils.Operation.transfer);
        AdapterUtils.setAmountText(findViewById(R.id.totalProfitBalance),
                clBalance.getTotalAmountEq() - opBalance.getTotalAmountEq(),
                currencyEq,
                clBalance.getApproxEq() || opBalance.getApproxEq(),
                clBalance.isIncomplete() || opBalance.isIncomplete());

        // Доход и расход — те же запросы, что строит отчёт за период. Суммы приходят
        // положительными: доход или расход задаёт направление операции, а не знак, —
        // поэтому операция указана явно, иначе расход отрисовался бы зелёным плюсом.
        // Фильтр сюда не идёт (null): карточка — итог по всем деньгам, а плитки «Доход» и
        // «Расход» сами ставят фильтр по виду — с ним вторая плитка обнулялась бы.
        ReportByAccount reportIncome = WalletDbLoader.GenerateIncomeReportByAccount(db,
                getDateFrom(), getDateTo(), Singleton.instance.getAccounts(this),
                Singleton.instance.getCurrencyList(this), currencyEq, null);
        ReportByAccount reportExpanse = WalletDbLoader.GenerateExpanseReportByAccount(db,
                getDateFrom(), getDateTo(), Singleton.instance.getAccounts(this),
                Singleton.instance.getCurrencyList(this), currencyEq, null);

        AdapterUtils.setAmountText(findViewById(R.id.incomeSum), reportIncome.getTotal(),
                currencyEq, AdapterUtils.Operation.deposit);
        AdapterUtils.setAmountText(findViewById(R.id.expenseSum), reportExpanse.getTotal(),
                currencyEq, AdapterUtils.Operation.expanse);

        refreshCapitalRow(db, currencyEq);
    }

    /**
     * Строки «Капитал» и «Всего с капиталом» на конец периода (openspec capital-assets): итоги
     * выше считают только ликвидные кошельки, капитал — отдельно, тем же запросом балансов.
     * Без активов строк нет; видимость у двух строк всегда общая.
     */
    private void refreshCapitalRow(SQLiteDatabase db, Currency currencyEq) {
        View capitalRow = findViewById(R.id.capitalRow);
        View netWorthRow = findViewById(R.id.netWorthRow);
        if (capitalRow == null || netWorthRow == null) return;
        // Помеченный актив без операций тоже показывает строки (с нулём).
        boolean hasAssets = com.example.wallettransactions.Data.WalletDbCRUD.HasAssets(db);
        int visibility = hasAssets ? View.VISIBLE : View.GONE;
        capitalRow.setVisibility(visibility);
        netWorthRow.setVisibility(visibility);
        if (!hasAssets) return;
        WalletDbLoader.CapitalResult capital = WalletDbLoader.GenerateCapitalToDate(db, getDateTo(), currencyEq);
        AdapterUtils.setAmountText(findViewById(R.id.capitalSum), capital.capital, currencyEq,
                AdapterUtils.Operation.transfer);
        AdapterUtils.setAmountText(findViewById(R.id.netWorthSum), capital.netWorth, currencyEq,
                AdapterUtils.Operation.transfer);
    }

    protected void refreshFilterBanner() {
        View filterBanner = findViewById(R.id.filterBanner);
        if (filterBanner == null) return;

        // Вид операции идёт первым: его ставят плиткой карточки, а не экраном «Фильтр»,
        // поэтому без строки-резюме он остался бы включённым незаметно для пользователя.
        List<String> parts = new ArrayList<>();
        String kindTitle = operationKindTitle(state().getOperationKind());
        if (kindTitle != null) parts.add(kindTitle);
        parts.addAll(state().getAccountNames(this));

        if (!state().getSearchText().isEmpty()) parts.add("«" + state().getSearchText() + "»");

        String amountRange = rangeSummary(state().getAmountMin(), state().getAmountMax());
        if (amountRange != null) parts.add(amountRange);

        String eqRange = rangeSummary(state().getEqMin(), state().getEqMax());
        if (eqRange != null) {
            Currency currencyEq = getCurrencyEq();
            parts.add(eqRange + (currencyEq != null ? " " + currencyEq.getIso() : ""));
        }

        if (parts.isEmpty()) {
            filterBanner.setVisibility(View.GONE);
            return;
        }

        filterBanner.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.filterSummary)).setText(getString(R.string.filter_banner, join(parts)));
    }

    /** null — фильтра по виду нет, показывать в резюме нечего. */
    private String operationKindTitle(DataFilter.OperationKind kind) {
        switch (kind) {
            case income:   return getString(R.string.filter_only_income);
            case expense:  return getString(R.string.filter_only_expense);
            case transfer: return getString(R.string.filter_only_transfers);
            default:       return null;
        }
    }

    /** null — обе границы не заданы, показывать в резюме нечего. */
    private String rangeSummary(Double min, Double max) {
        if (min == null && max == null) return null;
        if (min != null && max != null) return formatCompact(min) + "–" + formatCompact(max);
        if (min != null) return getString(R.string.filter_range_from, formatCompact(min));
        return getString(R.string.filter_range_to, formatCompact(max));
    }

    /** Без лишних нулей после запятой: 100.0 → «100», 100.5 → «100.5». */
    private static String formatCompact(double value) {
        return com.example.wallettransactions.AdapterUtils.plainNumber(value);
    }

    protected void updateCurrencyButton() {
        TextView btnCurrencyEq = findViewById(R.id.btnCurrencyEq);
        if (btnCurrencyEq == null) return;

        Currency currencyEq = getCurrencyEq();
        btnCurrencyEq.setText(currencyEq != null ? currencyEq.getIso() : "—");
    }

    // ── Обработчики общих layout'ов ───────────────────────────────────────────

    public void btnChangeFilter(View view) {
        Intent intent = new Intent(this, FilterActivity.class);
        intent.putExtra(MainActivity.cFilterAccountList, state().getAccountIdsCsv());
        startActivityForResult(intent, requestCodeSharedFilter);
    }

    public void onClearFilter(View view) {
        state().clearFilter(this);
        reloadSharedAndTabData();
    }

    /** Валюта эквивалента: выбор общий для всех табов, поэтому уходит в PeriodFilterState. */
    public void onPickCurrencyEq(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        Currency currencyEq = getCurrencyEq();
        // Заглушка пустого справочника — не валюта, в списке ей не место.
        if (PeriodFilterState.isPlaceholder(currencyEq)) currencyEq = null;

        // Все валюты с флагом «Показывать в эквиваленте» + текущая — как в «Настройках»;
        // кошелёк в валюте не нужен (equivalent-picker-by-flag).
        List<Currency> allCurrencies = new ArrayList<>(Singleton.instance.getCurrencyList(this));
        int idx = 0;
        for (Currency currency : EquivalentCurrencies.filter(allCurrencies, currencyEq)) {
            MenuItem menuItem = popup.getMenu().add(R.id.checkgroup, currency.getId(), idx,
                    currency.getIso() + " (" + currency.getSymbol() + ")");
            menuItem.setCheckable(true);
            if (currencyEq != null && currencyEq.getId() == currency.getId()) menuItem.setChecked(true);
            ++idx;
        }
        popup.getMenu().setGroupCheckable(R.id.checkgroup, true, true);

        popup.setOnMenuItemClickListener(item -> {
            state().setCurrencyEq(this,
                    Singleton.instance.getCurrencyList(this).getCurrencyById(item.getItemId()));
            updateCurrencyButton();
            reloadSharedAndTabData();
            return true;
        });

        popup.show();
    }

    public void btnOperationAdd(View view) {
        Intent intent = new Intent(this, TransactionActivity.class);
        intent.putExtra(TransactionActivity.cDate, DateUtils.DateToStr(new Date()));
        intent.putExtra(TransactionActivity.cComment, "");
        intent.putExtra(TransactionActivity.cAmount, 0.0);
        intent.putExtra(TransactionActivity.cIsEdit, false);
        putFilterPrefill(intent);
        startActivityForResult(intent, requestCodeSharedOperationAdd);
    }

    /**
     * Лента отфильтрована по счетам — новая операция почти наверняка про них же: подставляем
     * первый выбранный кошелёк и первую категорию фильтра. Тип берём из фильтра по виду
     * операции; без него — расход, как и при пустом окне. У перевода кошелёк фильтра
     * встаёт в «Откуда», второй выбранный кошелёк (если есть) — в «Куда».
     * Кошелька в фильтре нет — подставляем последний использованный.
     */
    private void putFilterPrefill(Intent intent) {
        DataFilter.OperationKind kind = state().getOperationKind();
        if (kind == DataFilter.OperationKind.all) kind = DataFilter.OperationKind.expense;

        AccountList accounts = Singleton.instance.getAccounts(this);
        List<AccountItem> wallets = new ArrayList<>();
        AccountItem category = null;

        // Порядок id в фильтре — порядок выбора, поэтому «первый» здесь и есть первый выбранный.
        for (Integer id : state().getAccountIds()) {
            AccountItem item = accounts.getAccountById(id);
            if (item == null || item.getIsArchived() || item.isSystem()) continue;

            if (item.isOperatingAccount()) wallets.add(item);
            else if (category == null) category = item;
        }

        AccountItem wallet = wallets.isEmpty() ? lastWallet(accounts) : wallets.get(0);
        AccountItem from;
        AccountItem to;
        switch (kind) {
            case income:
                from = category;
                to = wallet;
                break;
            case transfer:
                from = wallet;
                to = wallets.size() > 1 ? wallets.get(1) : null;
                break;
            default:
                from = wallet;
                to = category;
                break;
        }

        intent.putExtra(TransactionActivity.cKind, kind.name());
        if (from != null) intent.putExtra(TransactionActivity.cFrom, from.getId().intValue());
        if (to != null) intent.putExtra(TransactionActivity.cTo, to.getId().intValue());
    }

    /** Кошелёк последней сохранённой операции, если его с тех пор не удалили и не убрали в архив. */
    private AccountItem lastWallet(AccountList accounts) {
        int id = AppSettings.getLastWalletId(this);
        AccountItem item = id > 0 ? accounts.getAccountById(id) : null;
        if (item == null || !item.isOperatingAccount() || item.getIsArchived()) return null;
        return item;
    }

    // ── Плитки «Доход» и «Расход» карточки (android:onClick в view_period_summary.xml) ──

    public void onFilterIncome(View view) {
        openOperationsFilteredByKind(DataFilter.OperationKind.income);
    }

    public void onFilterExpense(View view) {
        openOperationsFilteredByKind(DataFilter.OperationKind.expense);
    }

    /**
     * Цифра в карточке — свёрнутый итог, за ним всегда стоят конкретные операции: тап
     * разворачивает его в ленту «Операций», где остаются только операции этого вида.
     * Фильтр общий, поэтому он виден в строке-резюме шапки и снимается тем же «Сбросить».
     */
    private void openOperationsFilteredByKind(DataFilter.OperationKind kind) {
        state().setOperationKind(this, kind);

        // Уже на «Операциях» — переходить некуда, но ленту надо перечитать: onResume,
        // который обычно ловит смену фильтра, здесь не сработает.
        if (getClass() == MainActivity.class) reloadSharedAndTabData();
        else openTab(MainActivity.class);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case requestCodeSharedFilter:
                if (resultCode == RESULT_OK) {
                    state().setAccountIdsCsv(this,
                            data != null ? data.getStringExtra(MainActivity.cFilterAccountList) : null);
                    // Период на «Фильтре» тоже могли сменить. reloadSharedAndTabData
                    // запоминает ревизию, поэтому onResume сюда уже не вернётся и
                    // пилюлю периода надо обновить прямо здесь.
                    if (fragmentDataPeriod != null) applySharedPeriod();
                    reloadSharedAndTabData();
                }
                break;

            case requestCodeSharedOperationAdd:
                if (resultCode == RESULT_OK) reloadSharedAndTabData();
                break;
        }
    }

    // ── Нижняя панель (android:onClick в view_bottom_nav.xml) ──────────────────

    public void navOperations(View view) {
        openTab(MainActivity.class);
    }

    public void navWallets(View view) {
        openTab(AccountActivity.class);
    }

    public void navReport(View view) {
        openTab(ReportActivity.class);
    }

    public void navMore(View view) {
        openTab(MoreActivity.class);
    }

    /**
     * REORDER_TO_FRONT: уже открытый таб поднимается, а не создаётся заново, поэтому
     * стек не растёт от прыжков по панели, а его список не грузится с нуля. Актуальность
     * периода и фильтра при этом проверяет {@link #onResume()}.
     */
    private void openTab(Class<?> target) {
        if (getClass() == target) return;

        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        overridePendingTransition(0, 0);
    }

    private static String join(List<String> parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(part);
        }
        return joined.toString();
    }
}
