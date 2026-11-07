package com.example.wallettransactions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.DataFilter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Единое состояние общей шапки корневых экранов: период, фильтр по счетам и валюта
 * эквивалента.
 *
 * До этого каждый таб («Операции», «Кошельки», «Отчёты») держал свой
 * {@code ChooseDatePeriodFragment}, свой {@link DataFilter} и свой {@code currencyEq},
 * поэтому при переходе между табами период сбрасывался на «месяц назад — сегодня», а
 * фильтр забывался. Теперь экраны только отображают это состояние и пишут в него —
 * см. {@link com.example.wallettransactions.Activities.BaseTabActivity}.
 *
 * Экземпляр один на процесс, значения дублируются в {@link AppSettings}, так что период
 * и фильтр живут и после перезапуска приложения.
 */
public final class PeriodFilterState {

    private static PeriodFilterState instance;

    private Date dateFrom;
    private Date dateTo;

    /** Тот же объект, что уходит в WalletDbLoader; пересобирается из {@link #accountIds}. */
    private final DataFilter dataFilter = new DataFilter();
    private final Set<Integer> accountIds = new LinkedHashSet<>();

    private Currency currencyEq;

    /**
     * Счётчик правок. Экран запоминает ревизию, которую отрисовал, и в onResume видит,
     * что состояние сменили на другом табе, — см. BaseTabActivity.
     */
    private int revision;

    /** Открытые сейчас экраны — см. {@link #addDataChangedListener}. Только главный поток. */
    private final List<Runnable> dataChangedListeners = new ArrayList<>();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PeriodFilterState() {}

    public static synchronized PeriodFilterState get(Context ctx) {
        if (instance == null) {
            instance = new PeriodFilterState();
            instance.restore(ctx);
        }
        return instance;
    }

    public int getRevision() {
        return revision;
    }

    /**
     * Данные в базе поменялись в обход экранов (например, повторяющиеся операции завели
     * новые проводки или синк принёс изменения) — табы в фоне перечитают себя на
     * ближайшем onResume, а открытый сейчас — сразу, через слушатель. Без слушателя
     * экран, на котором застал фоновый синк, показывал старые остатки, пока его не
     * покинешь.
     * <p>
     * Можно звать из любого потока: с фонового ревизия поднимается на главном (там же
     * её читают экраны), с главного — сразу, onResume рассчитывает увидеть её тут же.
     */
    public void markDataChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bumpRevision();
        } else {
            mainHandler.post(this::bumpRevision);
        }
    }

    private void bumpRevision() {
        ++revision;
        for (Runnable listener : new ArrayList<>(dataChangedListeners)) listener.run();
    }

    /** Экран на переднем плане подписывается в onResume и отписывается в onPause. */
    public void addDataChangedListener(Runnable listener) {
        if (!dataChangedListeners.contains(listener)) dataChangedListeners.add(listener);
    }

    public void removeDataChangedListener(Runnable listener) {
        dataChangedListeners.remove(listener);
    }

    // ── Период ────────────────────────────────────────────────────────────────

    public Date getDateFrom() {
        return dateFrom;
    }

    public Date getDateTo() {
        return dateTo;
    }

    /**
     * Границы округляются до целых суток — так же, как это делает
     * {@code ChooseDateFragment.dateToSetStartOfDay/EndOfDay}. Иначе сохранённый период
     * приезжал бы обратно со временем «когда его выбрали», и та же дата давала бы другую
     * выборку.
     */
    public void setPeriod(Context ctx, Date from, Date to) {
        Date normalizedFrom = startOfDay(from);
        Date normalizedTo = endOfDay(to);

        if (normalizedFrom.equals(dateFrom) && normalizedTo.equals(dateTo)) return;

        dateFrom = normalizedFrom;
        dateTo = normalizedTo;
        ++revision;
        AppSettings.setPeriod(ctx, DateUtils.DateToStr(dateFrom), DateUtils.DateToStr(dateTo));
    }

    // ── Фильтр по счетам ──────────────────────────────────────────────────────

    public DataFilter getDataFilter() {
        return dataFilter;
    }

    public Set<Integer> getAccountIds() {
        return new LinkedHashSet<>(accountIds);
    }

    public boolean hasFilter() {
        return !accountIds.isEmpty() || getOperationKind() != DataFilter.OperationKind.all
                || !getSearchText().isEmpty()
                || getAmountMin() != null || getAmountMax() != null
                || getEqMin() != null || getEqMax() != null;
    }

    // ── Вид операции: только доходы / только расходы ──────────────────────────

    public DataFilter.OperationKind getOperationKind() {
        return dataFilter.getOperationKind();
    }

    /** Ставят плитки «Доход» и «Расход» карточки состояния денег — см. BaseTabActivity. */
    public void setOperationKind(Context ctx, DataFilter.OperationKind kind) {
        if (getOperationKind() == kind) return;

        dataFilter.setOperationKind(kind);
        ++revision;
        AppSettings.setFilterOperationKind(ctx, getOperationKind().name());
    }

    /** ID через запятую — формат, в котором фильтр ходит между экранами (см. MainActivity.cFilterAccountList). */
    public String getAccountIdsCsv() {
        StringBuilder csv = new StringBuilder();
        for (Integer id : accountIds) {
            if (csv.length() > 0) csv.append(",");
            csv.append(id);
        }
        return csv.toString();
    }

    public void setAccountIds(Context ctx, Collection<Integer> ids) {
        Set<Integer> updated = new LinkedHashSet<>();
        if (ids != null) updated.addAll(ids);
        if (updated.equals(accountIds)) return;

        accountIds.clear();
        accountIds.addAll(updated);
        rebuildDataFilter(ctx);
        ++revision;
        AppSettings.setFilterAccounts(ctx, getAccountIdsCsv());
    }

    public void setAccountIdsCsv(Context ctx, String csv) {
        setAccountIds(ctx, parseIds(csv));
    }

    /** «Сбросить» в шапке — снимает весь фильтр разом: счета, вид операции, текст и суммы. */
    public void clearFilter(Context ctx) {
        setAccountIds(ctx, null);
        setOperationKind(ctx, DataFilter.OperationKind.all);
        setSearchText(ctx, null);
        setAmountRange(ctx, null, null);
        setEqRange(ctx, null, null);
    }

    /** Названия выбранных счетов — для строки-резюме фильтра в шапке. */
    public List<String> getAccountNames(Context ctx) {
        AccountList accounts = Singleton.instance.getAccounts(ctx);
        List<String> names = new ArrayList<>();
        for (Integer id : accountIds) {
            AccountItem item = accounts.getAccountById(id);
            if (item != null) names.add(item.getDisplayName());
        }
        return names;
    }

    // ── Поиск по тексту ───────────────────────────────────────────────────────

    /** Комментарий или название счёта — см. {@link DataFilter#getSQLFilter}. Пустая строка — фильтра нет. */
    public String getSearchText() {
        return dataFilter.getSearchText();
    }

    public void setSearchText(Context ctx, String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.equals(getSearchText())) return;

        dataFilter.setSearchText(normalized);
        ++revision;
        AppSettings.setFilterSearchText(ctx, normalized);
    }

    // ── Диапазон суммы ────────────────────────────────────────────────────────

    public Double getAmountMin() {
        return dataFilter.getAmountMin();
    }

    public Double getAmountMax() {
        return dataFilter.getAmountMax();
    }

    /** Сумма в валюте кошелька ({@code amount}); null — граница не задана. */
    public void setAmountRange(Context ctx, Double min, Double max) {
        if (Objects.equals(min, getAmountMin()) && Objects.equals(max, getAmountMax())) return;

        dataFilter.setAmountRange(min, max);
        ++revision;
        AppSettings.setFilterAmountRange(ctx, min, max);
    }

    public Double getEqMin() {
        return dataFilter.getEqMin();
    }

    public Double getEqMax() {
        return dataFilter.getEqMax();
    }

    /** Сумма в валюте эквивалента ({@code amount_eq}); null — граница не задана. */
    public void setEqRange(Context ctx, Double min, Double max) {
        if (Objects.equals(min, getEqMin()) && Objects.equals(max, getEqMax())) return;

        dataFilter.setEqRange(min, max);
        ++revision;
        AppSettings.setFilterEqRange(ctx, min, max);
    }

    // ── Валюта эквивалента ────────────────────────────────────────────────────

    /**
     * Никогда не null. Пока справочник валют пуст (полная очистка базы без сида, смена
     * сервера — валюты ещё не пришли синком), отдаёт заглушку {@link #isPlaceholder}:
     * экраны и загрузчики зовут {@code currencyEq.getId()} без проверок, и раньше
     * приложение падало на старте, не дойдя до синка, который заполнил бы справочник.
     * Заглушку при каждом вызове пробуем заменить настоящей валютой.
     */
    public Currency getCurrencyEq(Context ctx) {
        if (currencyEq == null || isPlaceholder(currencyEq)) {
            Currency resolved = resolveCurrencyEq(ctx);
            if (resolved != null) currencyEq = resolved;
            else if (currencyEq == null) currencyEq = placeholderCurrencyEq(ctx);
        }
        return currencyEq;
    }

    /** id −1 — такой строки в {@code currency} нет: курсов к ней нет, эквивалент пустой. */
    public static boolean isPlaceholder(Currency currency) {
        return currency != null && currency.getId() < 0;
    }

    private static Currency placeholderCurrencyEq(Context ctx) {
        String iso = AppSettings.getCurrencyEqIso(ctx);
        if (iso == null) iso = "EUR";
        return new Currency(-1, iso, iso, null, iso, 2, true, false);
    }

    /**
     * Справочник валют перечитан ({@code Singleton.reloadCurrencyList} — синк или форма
     * валюты). Объекты {@link Currency} обновлены на месте, так что {@link #currencyEq}
     * уже несёт новые символ и название, — остаётся перерисовать открытые экраны и
     * заменить валюту, если её удалили (android-wallet#8).
     * <p>
     * Можно звать из любого потока, как {@link #markDataChanged}: {@code currencyEq}
     * меняется на главном, где его читают экраны.
     */
    public void onCurrenciesReloaded(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Runnable apply = () -> {
            replaceDeletedCurrencyEq(app);
            bumpRevision();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            mainHandler.post(apply);
        }
    }

    /**
     * Удалённой валюты (и заглушки) в списке нет (LoadCurrencyList берёт только
     * {@code deleted_at IS NULL}) — берём ту же, что при запуске ({@link #resolveCurrencyEq});
     * справочник пуст — заглушку. В {@link AppSettings} ничего не пишем: если валюту заведут
     * снова, {@link #syncCurrencyEqFromSettings} вернёт её сам.
     */
    private void replaceDeletedCurrencyEq(Context ctx) {
        if (currencyEq == null) return;
        if (Singleton.instance.getCurrencyList(ctx).contains(currencyEq)) return;

        Currency next = resolveCurrencyEq(ctx);
        currencyEq = next != null ? next : placeholderCurrencyEq(ctx);
    }

    public void setCurrencyEq(Context ctx, Currency currency) {
        if (currency == null || currency.equals(currencyEq)) return;

        currencyEq = currency;
        ++revision;
        AppSettings.setCurrencyEqIso(ctx, currency.getIso());
    }

    /**
     * Валюту можно сменить и в «Настройках», минуя шапку, — там пишется только
     * {@link AppSettings}. Экраны зовут это в onResume, чтобы подхватить такую правку.
     */
    public boolean syncCurrencyEqFromSettings(Context ctx) {
        String savedIso = AppSettings.getCurrencyEqIso(ctx);
        if (savedIso == null || (currencyEq != null && savedIso.equals(currencyEq.getIso()))) return false;

        Currency saved = Singleton.instance.getCurrencyList(ctx).getCurrencyByISO(savedIso);
        if (saved == null) return false;

        currencyEq = saved;
        ++revision;
        return true;
    }

    // ── Загрузка сохранённого состояния ───────────────────────────────────────

    private void restore(Context ctx) {
        String savedFrom = AppSettings.getPeriodFrom(ctx);
        String savedTo = AppSettings.getPeriodTo(ctx);

        if (savedFrom != null && savedTo != null) {
            dateFrom = startOfDay(DateUtils.StrToDate(savedFrom));
            dateTo = endOfDay(DateUtils.StrToDate(savedTo));
        } else {
            // Период по умолчанию — как раньше на «Операциях»: последний месяц.
            Calendar cal = Calendar.getInstance();
            dateTo = endOfDay(cal.getTime());
            cal.add(Calendar.MONTH, -1);
            dateFrom = startOfDay(cal.getTime());
        }

        accountIds.addAll(parseIds(AppSettings.getFilterAccounts(ctx)));
        rebuildDataFilter(ctx);
        dataFilter.setOperationKind(parseKind(AppSettings.getFilterOperationKind(ctx)));
        dataFilter.setSearchText(AppSettings.getFilterSearchText(ctx));
        dataFilter.setAmountRange(AppSettings.getFilterAmountMin(ctx), AppSettings.getFilterAmountMax(ctx));
        dataFilter.setEqRange(AppSettings.getFilterEqMin(ctx), AppSettings.getFilterEqMax(ctx));
    }

    /** Чужое значение в настройках не должно ронять запуск — это просто «фильтра нет». */
    private static DataFilter.OperationKind parseKind(String name) {
        if (name == null) return DataFilter.OperationKind.all;
        try {
            return DataFilter.OperationKind.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return DataFilter.OperationKind.all;
        }
    }

    /**
     * {@link DataFilter} хранит сами {@link AccountItem}, а состояние — их ID, поэтому
     * после любой правки списка фильтр собирается заново. ID пропавшего счёта (удалён,
     * пересоздана база) отбрасывается.
     */
    private void rebuildDataFilter(Context ctx) {
        AccountList accounts = Singleton.instance.getAccounts(ctx);
        dataFilter.clearAccount();

        for (Integer id : new ArrayList<>(accountIds)) {
            AccountItem item = accounts.getAccountById(id);
            if (item != null) dataFilter.addAccount(item);
            else accountIds.remove(id);
        }
    }

    /** Сохранённая в настройках → EUR → первая из справочника; null — только если он пуст. */
    private Currency resolveCurrencyEq(Context ctx) {
        CurrencyList currencies = Singleton.instance.getCurrencyList(ctx);
        String savedIso = AppSettings.getCurrencyEqIso(ctx);
        Currency found = savedIso != null ? currencies.getCurrencyByISO(savedIso) : null;
        if (found == null) found = currencies.getCurrencyByISO("EUR");
        if (found == null && !currencies.isEmpty()) found = currencies.get(0);
        return found;
    }

    private static Date startOfDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Date endOfDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    public static Set<Integer> parseIds(String csv) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (csv == null || csv.trim().isEmpty()) return ids;

        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                ids.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // Чужая строка в extra — молча пропускаем, фильтр не должен ронять экран.
            }
        }
        return ids;
    }
}
