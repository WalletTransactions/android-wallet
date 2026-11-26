package com.example.wallettransactions

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import com.example.wallettransactions.DataItems.DataFilter
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashSet

/**
 * Единое состояние общей шапки корневых экранов: период, фильтр по счетам и валюта
 * эквивалента.
 *
 * До этого каждый таб («Операции», «Кошельки», «Отчёты») держал свой
 * `ChooseDatePeriodFragment`, свой [DataFilter] и свой `currencyEq`,
 * поэтому при переходе между табами период сбрасывался на «месяц назад — сегодня», а
 * фильтр забывался. Теперь экраны только отображают это состояние и пишут в него —
 * см. [com.example.wallettransactions.Activities.BaseTabActivity].
 *
 * Экземпляр один на процесс, значения дублируются в [AppSettings], так что период
 * и фильтр живут и после перезапуска приложения.
 */
class PeriodFilterState private constructor() {

    private var dateFrom: Date? = null
    private var dateTo: Date? = null

    /** Тот же объект, что уходит в WalletDbLoader; пересобирается из [accountIds]. */
    private val dataFilter = DataFilter()
    private val accountIds: MutableSet<Int> = LinkedHashSet()

    private var currencyEq: Currency? = null

    /**
     * Счётчик правок. Экран запоминает ревизию, которую отрисовал, и в onResume видит,
     * что состояние сменили на другом табе, — см. BaseTabActivity.
     */
    private var revision = 0

    /** Открытые сейчас экраны — см. [addDataChangedListener]. Только главный поток. */
    private val dataChangedListeners: MutableList<Runnable> = ArrayList()

    fun getRevision(): Int = revision

    /**
     * Данные в базе поменялись в обход экранов (например, повторяющиеся операции завели
     * новые проводки или синк принёс изменения) — табы в фоне перечитают себя на
     * ближайшем onResume, а открытый сейчас — сразу, через слушатель. Без слушателя
     * экран, на котором застал фоновый синк, показывал старые остатки, пока его не
     * покинешь.
     *
     * Можно звать из любого потока: с фонового ревизия поднимается на главном (там же
     * её читают экраны), с главного — сразу, onResume рассчитывает увидеть её тут же.
     */
    fun markDataChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bumpRevision()
        } else {
            mainHandler.post { bumpRevision() }
        }
    }

    private fun bumpRevision() {
        ++revision
        for (listener in ArrayList(dataChangedListeners)) listener.run()
    }

    /** Экран на переднем плане подписывается в onResume и отписывается в onPause. */
    fun addDataChangedListener(listener: Runnable) {
        if (!dataChangedListeners.contains(listener)) dataChangedListeners.add(listener)
    }

    fun removeDataChangedListener(listener: Runnable) {
        dataChangedListeners.remove(listener)
    }

    // ── Период ────────────────────────────────────────────────────────────────

    fun getDateFrom(): Date? = dateFrom

    fun getDateTo(): Date? = dateTo

    /**
     * Границы округляются до целых суток — так же, как это делает
     * `ChooseDateFragment.dateToSetStartOfDay/EndOfDay`. Иначе сохранённый период
     * приезжал бы обратно со временем «когда его выбрали», и та же дата давала бы другую
     * выборку.
     */
    fun setPeriod(ctx: Context, from: Date, to: Date) {
        val normalizedFrom = startOfDay(from)
        val normalizedTo = endOfDay(to)

        if (normalizedFrom == dateFrom && normalizedTo == dateTo) return

        dateFrom = normalizedFrom
        dateTo = normalizedTo
        ++revision
        AppSettings.setPeriod(ctx, DateUtils.DateToStr(normalizedFrom), DateUtils.DateToStr(normalizedTo))
    }

    // ── Фильтр по счетам ──────────────────────────────────────────────────────

    fun getDataFilter(): DataFilter = dataFilter

    fun getAccountIds(): Set<Int> = LinkedHashSet(accountIds)

    fun hasFilter(): Boolean {
        return accountIds.isNotEmpty() || getOperationKind() != DataFilter.OperationKind.all
                || getSearchText().isNotEmpty()
                || getAmountMin() != null || getAmountMax() != null
                || getEqMin() != null || getEqMax() != null
    }

    // ── Вид операции: только доходы / только расходы ──────────────────────────

    fun getOperationKind(): DataFilter.OperationKind = dataFilter.operationKind

    /** Ставят плитки «Доход» и «Расход» карточки состояния денег — см. BaseTabActivity. */
    fun setOperationKind(ctx: Context, kind: DataFilter.OperationKind) {
        if (getOperationKind() == kind) return

        dataFilter.setOperationKind(kind)
        ++revision
        AppSettings.setFilterOperationKind(ctx, getOperationKind().name)
    }

    /** ID через запятую — формат, в котором фильтр ходит между экранами (см. MainActivity.cFilterAccountList). */
    fun getAccountIdsCsv(): String {
        val csv = StringBuilder()
        for (id in accountIds) {
            if (csv.isNotEmpty()) csv.append(",")
            csv.append(id)
        }
        return csv.toString()
    }

    fun setAccountIds(ctx: Context, ids: Collection<Int>?) {
        val updated: MutableSet<Int> = LinkedHashSet()
        if (ids != null) updated.addAll(ids)
        if (updated == accountIds) return

        accountIds.clear()
        accountIds.addAll(updated)
        rebuildDataFilter(ctx)
        ++revision
        AppSettings.setFilterAccounts(ctx, getAccountIdsCsv())
    }

    fun setAccountIdsCsv(ctx: Context, csv: String?) {
        setAccountIds(ctx, parseIds(csv))
    }

    /** «Сбросить» в шапке — снимает весь фильтр разом: счета, вид операции, текст и суммы. */
    fun clearFilter(ctx: Context) {
        setAccountIds(ctx, null)
        setOperationKind(ctx, DataFilter.OperationKind.all)
        setSearchText(ctx, null)
        setAmountRange(ctx, null, null)
        setEqRange(ctx, null, null)
    }

    /**
     * Готовый фильтр одним действием (openspec similar-search): прежний фильтр снимается
     * целиком — включая текст, суммы и вид, — и ставится только переданное. Так открывают
     * «Операции» из экрана счёта и из «Найти подобные». `kind` и `searchText`
     * могут быть null / пустыми — тогда вид «Все», текста нет.
     */
    fun presetFilter(
        ctx: Context, ids: Collection<Int>?, kind: DataFilter.OperationKind?,
        searchText: String?
    ) {
        // Каждый сеттер сам ничего не делает, если значение то же, — поэтому повтор с теми же
        // данными не поднимает ревизию (в отличие от clearFilter + установка заново).
        setAccountIds(ctx, ids)
        setOperationKind(ctx, kind ?: DataFilter.OperationKind.all)
        setSearchText(ctx, searchText)
        setAmountRange(ctx, null, null)
        setEqRange(ctx, null, null)
    }

    /** Названия выбранных счетов — для строки-резюме фильтра в шапке. */
    fun getAccountNames(ctx: Context): List<String> {
        val accounts = Singleton.instance.getAccounts(ctx)
        val names: MutableList<String> = ArrayList()
        for (id in accountIds) {
            val item = accounts.getAccountById(id)
            if (item != null) names.add(item.displayName)
        }
        return names
    }

    // ── Поиск по тексту ───────────────────────────────────────────────────────

    /** Комментарий или название счёта — см. [DataFilter.getSQLFilter]. Пустая строка — фильтра нет. */
    fun getSearchText(): String = dataFilter.searchText

    fun setSearchText(ctx: Context, text: String?) {
        val normalized = text?.trim() ?: ""
        if (normalized == getSearchText()) return

        dataFilter.setSearchText(normalized)
        ++revision
        AppSettings.setFilterSearchText(ctx, normalized)
    }

    // ── Диапазон суммы ────────────────────────────────────────────────────────

    fun getAmountMin(): Double? = dataFilter.amountMin

    fun getAmountMax(): Double? = dataFilter.amountMax

    /** Сумма в валюте кошелька (`amount`); null — граница не задана. */
    fun setAmountRange(ctx: Context, min: Double?, max: Double?) {
        if (min == getAmountMin() && max == getAmountMax()) return

        dataFilter.setAmountRange(min, max)
        ++revision
        AppSettings.setFilterAmountRange(ctx, min, max)
    }

    fun getEqMin(): Double? = dataFilter.eqMin

    fun getEqMax(): Double? = dataFilter.eqMax

    /** Сумма в валюте эквивалента (`amount_eq`); null — граница не задана. */
    fun setEqRange(ctx: Context, min: Double?, max: Double?) {
        if (min == getEqMin() && max == getEqMax()) return

        dataFilter.setEqRange(min, max)
        ++revision
        AppSettings.setFilterEqRange(ctx, min, max)
    }

    // ── Валюта эквивалента ────────────────────────────────────────────────────

    /**
     * Никогда не null. Пока справочник валют пуст (полная очистка базы без сида, смена
     * сервера — валюты ещё не пришли синком), отдаёт заглушку [isPlaceholder]:
     * экраны и загрузчики зовут `currencyEq.getId()` без проверок, и раньше
     * приложение падало на старте, не дойдя до синка, который заполнил бы справочник.
     * Заглушку при каждом вызове пробуем заменить настоящей валютой.
     */
    fun getCurrencyEq(ctx: Context): Currency {
        val current = currencyEq
        if (current == null || isPlaceholder(current)) {
            val resolved = resolveCurrencyEq(ctx)
            if (resolved != null) currencyEq = resolved
            else if (current == null) currencyEq = placeholderCurrencyEq(ctx)
        }
        return currencyEq!!
    }

    /**
     * Справочник валют перечитан (`Singleton.reloadCurrencyList` — синк или форма
     * валюты). Объекты [Currency] обновлены на месте, так что [currencyEq]
     * уже несёт новые символ и название, — остаётся перерисовать открытые экраны и
     * заменить валюту, если её удалили (android-wallet#8).
     *
     * Можно звать из любого потока, как [markDataChanged]: `currencyEq`
     * меняется на главном, где его читают экраны.
     */
    fun onCurrenciesReloaded(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val apply = Runnable {
            replaceDeletedCurrencyEq(app)
            bumpRevision()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run()
        } else {
            mainHandler.post(apply)
        }
    }

    /**
     * Удалённой валюты (и заглушки) в списке нет (LoadCurrencyList берёт только
     * `deleted_at IS NULL`) — берём ту же, что при запуске ([resolveCurrencyEq]);
     * справочник пуст — заглушку. В [AppSettings] ничего не пишем: если валюту заведут
     * снова, [syncCurrencyEqFromSettings] вернёт её сам.
     */
    private fun replaceDeletedCurrencyEq(ctx: Context) {
        val current = currencyEq ?: return
        if (Singleton.instance.getCurrencyList(ctx).contains(current)) return

        currencyEq = resolveCurrencyEq(ctx) ?: placeholderCurrencyEq(ctx)
    }

    fun setCurrencyEq(ctx: Context, currency: Currency?) {
        if (currency == null || currency == currencyEq) return

        currencyEq = currency
        ++revision
        AppSettings.setCurrencyEqIso(ctx, currency.iso)
    }

    /**
     * Валюту можно сменить и в «Настройках», минуя шапку, — там пишется только
     * [AppSettings]. Экраны зовут это в onResume, чтобы подхватить такую правку.
     */
    fun syncCurrencyEqFromSettings(ctx: Context): Boolean {
        val savedIso = AppSettings.getCurrencyEqIso(ctx)
        if (savedIso == null || (currencyEq != null && savedIso == currencyEq!!.iso)) return false

        val saved = Singleton.instance.getCurrencyList(ctx).getCurrencyByISO(savedIso) ?: return false

        currencyEq = saved
        ++revision
        return true
    }

    // ── Загрузка сохранённого состояния ───────────────────────────────────────

    private fun restore(ctx: Context) {
        val savedFrom = AppSettings.getPeriodFrom(ctx)
        val savedTo = AppSettings.getPeriodTo(ctx)

        if (savedFrom != null && savedTo != null) {
            dateFrom = startOfDay(DateUtils.StrToDate(savedFrom))
            dateTo = endOfDay(DateUtils.StrToDate(savedTo))
        } else {
            // Период по умолчанию — как раньше на «Операциях»: последний месяц.
            val cal = Calendar.getInstance()
            dateTo = endOfDay(cal.time)
            cal.add(Calendar.MONTH, -1)
            dateFrom = startOfDay(cal.time)
        }

        accountIds.addAll(parseIds(AppSettings.getFilterAccounts(ctx)))
        rebuildDataFilter(ctx)
        dataFilter.setOperationKind(parseKind(AppSettings.getFilterOperationKind(ctx)))
        dataFilter.setSearchText(AppSettings.getFilterSearchText(ctx))
        dataFilter.setAmountRange(AppSettings.getFilterAmountMin(ctx), AppSettings.getFilterAmountMax(ctx))
        dataFilter.setEqRange(AppSettings.getFilterEqMin(ctx), AppSettings.getFilterEqMax(ctx))
    }

    /**
     * [DataFilter] хранит сами [com.example.wallettransactions.DataItems.Accounts.AccountItem],
     * а состояние — их ID, поэтому
     * после любой правки списка фильтр собирается заново. ID пропавшего счёта (удалён,
     * пересоздана база) отбрасывается.
     */
    private fun rebuildDataFilter(ctx: Context) {
        val accounts = Singleton.instance.getAccounts(ctx)
        dataFilter.clearAccount()

        for (id in ArrayList(accountIds)) {
            val item = accounts.getAccountById(id)
            if (item != null) dataFilter.addAccount(item)
            else accountIds.remove(id)
        }
    }

    /** Сохранённая в настройках → EUR → первая из справочника; null — только если он пуст. */
    private fun resolveCurrencyEq(ctx: Context): Currency? {
        val currencies = Singleton.instance.getCurrencyList(ctx)
        val savedIso = AppSettings.getCurrencyEqIso(ctx)
        var found: Currency? = if (savedIso != null) currencies.getCurrencyByISO(savedIso) else null
        if (found == null) found = currencies.getCurrencyByISO("EUR")
        if (found == null && !currencies.isEmpty()) found = currencies[0]
        return found
    }

    companion object {
        private var instance: PeriodFilterState? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        @JvmStatic
        @Synchronized
        fun get(ctx: Context): PeriodFilterState {
            var inst = instance
            if (inst == null) {
                inst = PeriodFilterState()
                instance = inst
                inst.restore(ctx)
            }
            return inst
        }

        /** id −1 — такой строки в `currency` нет: курсов к ней нет, эквивалент пустой. */
        @JvmStatic
        fun isPlaceholder(currency: Currency?): Boolean {
            return currency != null && currency.id < 0
        }

        private fun placeholderCurrencyEq(ctx: Context): Currency {
            val iso = AppSettings.getCurrencyEqIso(ctx) ?: "EUR"
            return Currency(-1, iso, iso, null, iso, 2, true, false)
        }

        /** Чужое значение в настройках не должно ронять запуск — это просто «фильтра нет». */
        private fun parseKind(name: String?): DataFilter.OperationKind {
            if (name == null) return DataFilter.OperationKind.all
            return try {
                DataFilter.OperationKind.valueOf(name)
            } catch (ignored: IllegalArgumentException) {
                DataFilter.OperationKind.all
            }
        }

        private fun startOfDay(date: Date): Date {
            val cal = Calendar.getInstance()
            cal.time = date
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.time
        }

        private fun endOfDay(date: Date): Date {
            val cal = Calendar.getInstance()
            cal.time = date
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.time
        }

        @JvmStatic
        fun parseIds(csv: String?): Set<Int> {
            val ids: MutableSet<Int> = LinkedHashSet()
            if (csv == null || csv.trim().isEmpty()) return ids

            for (part in csv.split(",")) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                try {
                    ids.add(trimmed.toInt())
                } catch (ignored: NumberFormatException) {
                    // Чужая строка в extra — молча пропускаем, фильтр не должен ронять экран.
                }
            }
            return ids
        }
    }
}
