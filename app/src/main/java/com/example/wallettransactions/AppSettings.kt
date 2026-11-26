package com.example.wallettransactions

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.util.UUID

/**
 * Persisted app-wide settings shared between [com.example.wallettransactions.Activities.SettingsActivity]
 * (writes it when the user picks a currency) and [com.example.wallettransactions.Activities.MainActivity]
 * (reads it as the default currencyEq, and keeps it in sync when changed from either screen).
 *
 * Здесь же хранится общая «шапка» корневых экранов — период и фильтр, — чтобы она
 * переживала не только переход между табами, но и перезапуск приложения. Читает и
 * пишет их [PeriodFilterState], остальной код обращается к нему, а не сюда.
 */
object AppSettings {

    private const val PREFS_NAME = "settings"
    private const val PREF_CURRENCY_EQ_ISO = "currencyEqIso"
    private const val PREF_PERIOD_FROM = "periodFrom"
    private const val PREF_PERIOD_TO = "periodTo"
    private const val PREF_FILTER_ACCOUNTS = "filterAccounts"
    private const val PREF_FILTER_OPERATION_KIND = "filterOperationKind"
    private const val PREF_FILTER_SEARCH_TEXT = "filterSearchText"
    private const val PREF_FILTER_AMOUNT_MIN = "filterAmountMin"
    private const val PREF_FILTER_AMOUNT_MAX = "filterAmountMax"
    private const val PREF_FILTER_EQ_MIN = "filterEqMin"
    private const val PREF_FILTER_EQ_MAX = "filterEqMax"
    private const val PREF_LAST_WALLET_ID = "lastWalletId"
    private const val PREF_PICKER_KEYBOARD = "pickerKeyboard"
    private const val PREF_SUMMARY_OTHER_EXPANDED = "summaryOtherExpanded"
    private const val PREF_PICKER_WALLET_SORT = "pickerWalletSort"
    private const val PREF_PICKER_CATEGORY_SORT = "pickerCategorySort"
    private const val PREF_THEME_MODE = "themeMode"
    private const val PREF_SERVER_BASE_URL = "serverBaseUrl"
    private const val PREF_DEVICE_ID = "deviceId"
    private const val PREF_LINKED_EMAIL = "linkedEmail"
    private const val PREF_NEEDS_RELINK = "needsRelink"
    private const val PREF_AUTO_CONNECT_ENABLED = "autoConnectEnabled"
    private const val PREF_AUTO_CONNECT_INTERVAL_MINUTES = "autoConnectIntervalMinutes"
    private const val PREF_NOTIFY_NEW_OPERATIONS = "notifyNewOperations"
    private const val PREF_ICON_UPLOAD_ENABLED = "iconUploadEnabled"
    private const val PREF_SERVER_ICON_UPLOAD_ALLOWED = "serverIconUploadAllowed"
    private const val PREF_REMIND_ON = "remindOn"
    private const val PREF_REMIND_HOUR = "remindHour"
    private const val PREF_REMIND_MINUTE = "remindMinute"
    private const val PREF_LEDGER_SYNC_SEQ = "ledgerSyncSeq"
    private const val PREF_REFERENCE_SYNC_SEQ = "referenceSyncSeq"
    private const val PREF_SYNC_LEDGER_ID = "syncLedgerId"
    private const val PREF_UUID_FORMAT_RESYNC_DONE = "uuidFormatResyncDone"
    private const val PREF_DEFERRED_ROWS_RESYNC_DONE = "deferredRowsResyncDone"
    private const val PREF_PAIR_SYMBOL_RESYNC_DONE = "pairSymbolResyncDone"
    private const val PREF_FULL_RELOAD_PENDING = "syncFullReloadPending"
    private const val PREF_LEDGER_REREAD_PENDING = "syncLedgerRereadPending"
    private const val PREF_DEBTS_REDIRTY_PENDING = "debts_redirty_pending"
    private const val PREF_LAST_SYNC_SUCCESS_AT = "lastSyncSuccessAt"
    private const val PREF_LEDGER_CONNECTION_PENDING = "ledgerConnectionPending"
    private const val PREF_LEDGER_CONNECTION_DECISION = "ledgerConnectionDecision"
    private const val PREF_LEDGER_CONNECTION_OFFLINE_OPS = "ledgerConnectionOfflineOps"
    private const val PREF_AI_LAST_WALLET_UUID = "aiLastWalletUuid"

    /** См. [getAutoConnectIntervalMinutes] — минимум, который вообще
     *  умеет `androidx.work` для периодической задачи. */
    const val DEFAULT_AUTO_CONNECT_INTERVAL_MINUTES = 60

    /**
     * Гейтвей (wallet-gateway) по умолчанию — LAN-only на этом этапе
     * (org/docs/TODO_mobile_sync.md, п. 1.4): нет ни TLS, ни обнаружения
     * сервера в сети, адрес вводится вручную. `localhost` годится,
     * только когда сервер и устройство физически одно и то же (эмулятор
     * при пробросе `adb reverse tcp:8090 tcp:8090`, либо запуск
     * тестов на самом сервере) — на реальном телефоне в общей Wi-Fi сети
     * это должен быть LAN-адрес компьютера, см. экран «Настройки».
     *
     * Порт — от варианта сборки (openspec android-flavors): prod — `:8090`,
     * qa — `:8091` (test-контур), см. `productFlavors` в app/build.gradle.
     */
    const val DEFAULT_SERVER_BASE_URL: String = BuildConfig.DEFAULT_SERVER_URL

    /** Значения [getThemeMode]: строки, а не int-константы AppCompat, — их
     *  значения могут поменяться между версиями библиотеки, а в prefs они навсегда. */
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** ISO code of the saved equivalent currency, or null if never set. */
    @JvmStatic
    fun getCurrencyEqIso(ctx: Context): String? = prefs(ctx).getString(PREF_CURRENCY_EQ_ISO, null)

    @JvmStatic
    fun setCurrencyEqIso(ctx: Context, iso: String?) {
        prefs(ctx).edit().putString(PREF_CURRENCY_EQ_ISO, iso).apply()
    }

    /** Границы периода в формате [DateUtils.DateToStr], или null, если период ещё не выбирали. */
    @JvmStatic
    fun getPeriodFrom(ctx: Context): String? = prefs(ctx).getString(PREF_PERIOD_FROM, null)

    @JvmStatic
    fun getPeriodTo(ctx: Context): String? = prefs(ctx).getString(PREF_PERIOD_TO, null)

    @JvmStatic
    fun setPeriod(ctx: Context, from: String?, to: String?) {
        prefs(ctx).edit()
            .putString(PREF_PERIOD_FROM, from)
            .putString(PREF_PERIOD_TO, to)
            .apply()
    }

    /** ID счетов фильтра через запятую; пустая строка — фильтр не задан. */
    @JvmStatic
    fun getFilterAccounts(ctx: Context): String = prefs(ctx).getString(PREF_FILTER_ACCOUNTS, "")!!

    @JvmStatic
    fun setFilterAccounts(ctx: Context, csvIds: String?) {
        prefs(ctx).edit().putString(PREF_FILTER_ACCOUNTS, csvIds).apply()
    }

    /**
     * Кошелёк последней сохранённой операции — его подставляет окно новой операции, когда
     * фильтр кошелька не подсказывает. 0 — операций ещё не сохраняли.
     */
    @JvmStatic
    fun getLastWalletId(ctx: Context): Int = prefs(ctx).getInt(PREF_LAST_WALLET_ID, 0)

    @JvmStatic
    fun setLastWalletId(ctx: Context, id: Int) {
        prefs(ctx).edit().putInt(PREF_LAST_WALLET_ID, id).apply()
    }

    /**
     * Экраны выбора категории/кошелька (AccountPickerActivity, MultiPickerActivity) открываются
     * с фокусом в поиске и поднятой клавиатурой — можно сразу печатать. По умолчанию выключено.
     */
    @JvmStatic
    fun isPickerKeyboardOn(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_PICKER_KEYBOARD, false)

    @JvmStatic
    fun setPickerKeyboardOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_PICKER_KEYBOARD, on).apply()
    }

    /**
     * Открывать ли строку «Прочее» в карточке итогов сразу в раскрытом виде
     * (openspec summary-other-section). По умолчанию нет — видна только сумма.
     * Читается при открытии экрана; кнопка ▾ на экране настройку не меняет.
     */
    @JvmStatic
    fun isSummaryOtherExpanded(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_SUMMARY_OTHER_EXPANDED, false)

    @JvmStatic
    fun setSummaryOtherExpanded(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_SUMMARY_OTHER_EXPANDED, on).apply()
    }

    /**
     * Порядок строк на экране выбора: для кошельков (`wallets`) и для категорий
     * свой. Задаётся только в «Настройках»: кнопка на экране выбора меняет порядок
     * лишь до его закрытия.
     * По умолчанию — то, как список сортировался до появления настройки.
     */
    @JvmStatic
    fun getPickerSort(ctx: Context, wallets: Boolean): PickerSort {
        val modes = if (wallets) PickerSort.WALLET_MODES else PickerSort.CATEGORY_MODES
        val fallback = if (wallets) PickerSort.BALANCE else PickerSort.NAME
        val sort = PickerSort.fromName(
            prefs(ctx).getString(
                if (wallets) PREF_PICKER_WALLET_SORT else PREF_PICKER_CATEGORY_SORT, null
            ), fallback
        )
        // «По остатку», сохранённый для категорий, — не их режим.
        for (mode in modes) if (mode == sort) return sort
        return fallback
    }

    @JvmStatic
    fun setPickerSort(ctx: Context, wallets: Boolean, sort: PickerSort) {
        prefs(ctx).edit().putString(
            if (wallets) PREF_PICKER_WALLET_SORT else PREF_PICKER_CATEGORY_SORT, sort.name
        ).apply()
    }

    /**
     * Ставит фокус в поле поиска и поднимает клавиатуру, если включено
     * [isPickerKeyboardOn]. Вызывать из onCreate экрана выбора.
     *
     * `setSoftInputMode` заменяет режим целиком, вместе с битами adjust — без
     * [pickerSoftInputMode] `adjustResize` из манифеста затирался бы, и
     * клавиатура снова закрывала бы «Готово» (openspec currency-ui-tweaks, design D11).
     */
    @JvmStatic
    fun applyPickerKeyboard(activity: Activity, search: EditText) {
        if (!isPickerKeyboardOn(activity)) return
        search.requestFocus()
        activity.window.setSoftInputMode(
            pickerSoftInputMode(activity.window.attributes.softInputMode)
        )
    }

    /** Текущий режим с состоянием `ALWAYS_VISIBLE`: биты adjust сохраняются, меняются только биты state. */
    @JvmStatic
    fun pickerSoftInputMode(current: Int): Int {
        val adjust = current and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        return adjust or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    }

    /** Имя константы `DataFilter.OperationKind`; null — фильтра по виду ещё не было. */
    @JvmStatic
    fun getFilterOperationKind(ctx: Context): String? =
        prefs(ctx).getString(PREF_FILTER_OPERATION_KIND, null)

    @JvmStatic
    fun setFilterOperationKind(ctx: Context, kindName: String?) {
        prefs(ctx).edit().putString(PREF_FILTER_OPERATION_KIND, kindName).apply()
    }

    /** Строка поиска фильтра операций; пустая строка — фильтра нет. */
    @JvmStatic
    fun getFilterSearchText(ctx: Context): String = prefs(ctx).getString(PREF_FILTER_SEARCH_TEXT, "")!!

    @JvmStatic
    fun setFilterSearchText(ctx: Context, text: String?) {
        prefs(ctx).edit().putString(PREF_FILTER_SEARCH_TEXT, text).apply()
    }

    /** Границы суммы фильтра операций (валюта кошелька); null — граница не задана. */
    @JvmStatic
    fun getFilterAmountMin(ctx: Context): Double? = getNullableDouble(ctx, PREF_FILTER_AMOUNT_MIN)

    @JvmStatic
    fun getFilterAmountMax(ctx: Context): Double? = getNullableDouble(ctx, PREF_FILTER_AMOUNT_MAX)

    @JvmStatic
    fun setFilterAmountRange(ctx: Context, min: Double?, max: Double?) {
        setNullableDouble(ctx, PREF_FILTER_AMOUNT_MIN, min)
        setNullableDouble(ctx, PREF_FILTER_AMOUNT_MAX, max)
    }

    /** Границы суммы фильтра операций (валюта эквивалента); null — граница не задана. */
    @JvmStatic
    fun getFilterEqMin(ctx: Context): Double? = getNullableDouble(ctx, PREF_FILTER_EQ_MIN)

    @JvmStatic
    fun getFilterEqMax(ctx: Context): Double? = getNullableDouble(ctx, PREF_FILTER_EQ_MAX)

    @JvmStatic
    fun setFilterEqRange(ctx: Context, min: Double?, max: Double?) {
        setNullableDouble(ctx, PREF_FILTER_EQ_MIN, min)
        setNullableDouble(ctx, PREF_FILTER_EQ_MAX, max)
    }

    /** SharedPreferences не хранит nullable Double — используем строку и её отсутствие. */
    private fun getNullableDouble(ctx: Context, key: String): Double? {
        val raw = prefs(ctx).getString(key, null) ?: return null
        return try {
            raw.toDouble()
        } catch (ignored: NumberFormatException) {
            null
        }
    }

    private fun setNullableDouble(ctx: Context, key: String, value: Double?) {
        prefs(ctx).edit().putString(key, value?.toString()).apply()
    }

    // ── Синхронизация с сервером ───────────────────────────────────────────────
    //
    // org/docs/TODO_mobile_sync.md, Фаза 3: адрес wallet-gateway на LAN, ввод
    // вручную (обнаружение сервера в сети — отдельная задача). device_id и
    // токены сюда не попадают — это секреты, им место в EncryptedSharedPreferences,
    // не в этом файле (см. план, п. 5.1).

    /** Адрес wallet-gateway с завершающим `/`, например `http://192.168.1.42:8090/`. */
    @JvmStatic
    fun getServerBaseUrl(ctx: Context): String =
        prefs(ctx).getString(PREF_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL)!!

    /** Дописывает завершающий `/`, если его не ввели — иначе конкатенация путей ломается. */
    @JvmStatic
    fun setServerBaseUrl(ctx: Context, url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        prefs(ctx).edit().putString(PREF_SERVER_BASE_URL, normalized).apply()
    }

    /**
     * Стабильный id этой установки приложения — не секрет (в отличие от
     * токенов в [com.example.wallettransactions.TokenStore], поэтому
     * живёт в обычных, не зашифрованных prefs), просто то, чем wallet-api
     * различает устройства одного пользователя (план, п. 1.2 — сервер сам
     * резолвит `(sub, device_id)` в свою запись `device`, в JWT это поле не
     * попадает). Генерируется один раз при первом обращении и остаётся тем
     * же до переустановки приложения.
     */
    @JvmStatic
    fun getOrCreateDeviceId(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString(PREF_DEVICE_ID, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        p.edit().putString(PREF_DEVICE_ID, generated).apply()
        return generated
    }

    /** Email аккаунта, которым связались (см. `SyncLinkActivity`) — только
     *  для строки статуса на экране «Настройки», null пока связки не было. */
    @JvmStatic
    fun getLinkedEmail(ctx: Context): String? = prefs(ctx).getString(PREF_LINKED_EMAIL, null)

    @JvmStatic
    fun setLinkedEmail(ctx: Context, email: String?) {
        prefs(ctx).edit().putString(PREF_LINKED_EMAIL, email).apply()
    }

    /**
     * Выставляется `SyncWorker`, когда очередная попытка
     * `POST /auth/refresh` вернула 401 — refresh-токен истёк, отозван
     * или уже был использован (см. README google-auth, "Обновление
     * сессии"). Означает: нужно заново пройти `SyncLinkActivity` —
     * тихо повторить вход через Google из фоновой задачи нельзя, Credential
     * Manager требует показать UI поверх Activity.
     */
    @JvmStatic
    fun isNeedsRelink(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_NEEDS_RELINK, false)

    @JvmStatic
    fun setNeedsRelink(ctx: Context, needsRelink: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_NEEDS_RELINK, needsRelink).apply()
    }

    /** Экран «Ещё» → «Интеграции» → «Автоматическая синхронизация». */
    @JvmStatic
    fun isAutoConnectEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_AUTO_CONNECT_ENABLED, false)

    @JvmStatic
    fun setAutoConnectEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_AUTO_CONNECT_ENABLED, enabled).apply()
    }

    /** Раз в сколько минут `SyncWorker` пробует refresh + push/pull. */
    @JvmStatic
    fun getAutoConnectIntervalMinutes(ctx: Context): Int =
        prefs(ctx).getInt(PREF_AUTO_CONNECT_INTERVAL_MINUTES, DEFAULT_AUTO_CONNECT_INTERVAL_MINUTES)

    @JvmStatic
    fun setAutoConnectIntervalMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(PREF_AUTO_CONNECT_INTERVAL_MINUTES, minutes).apply()
    }

    /** Экран «Ещё» → «Интеграции» → «Уведомлять о новых операциях» (sync.NewOperationsNotifier). */
    @JvmStatic
    fun isNotifyNewOperationsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_NOTIFY_NEW_OPERATIONS, true)

    @JvmStatic
    fun setNotifyNewOperationsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_NOTIFY_NEW_OPERATIONS, enabled).apply()
    }

    /**
     * «Настройки» → «Данные» → «Отправлять свои иконки на сервер» (openspec optional-icon-upload).
     * Выбор пользователя, по умолчанию выключено; только на этом телефоне, не синкается.
     * Выключается и сам — отказом сервера (`icon_upload_disabled`).
     */
    @JvmStatic
    fun isIconUploadEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_ICON_UPLOAD_ENABLED, false)

    @JvmStatic
    fun setIconUploadEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_ICON_UPLOAD_ENABLED, enabled).apply()
    }

    /**
     * Последнее известное значение флага сервера `capabilities.icon_upload` из
     * `GET /sync/pull`. До первого pull и у старого сервера без блока — `false`:
     * приём считается запрещённым.
     */
    @JvmStatic
    fun isServerIconUploadAllowed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_SERVER_ICON_UPLOAD_ALLOWED, false)

    @JvmStatic
    fun setServerIconUploadAllowed(ctx: Context, allowed: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_SERVER_ICON_UPLOAD_ALLOWED, allowed).apply()
    }

    /** Проход синка отправляет свои иконки: пользователь включил и сервер разрешает. */
    @JvmStatic
    fun shouldUploadOwnIcons(ctx: Context): Boolean =
        isIconUploadEnabled(ctx) && isServerIconUploadAllowed(ctx)

    /**
     * «Настройки» → «Уведомления» → «Напоминание вносить операции» (reminder.ReminderWorker).
     * Ключи — те же, что писал переключатель, пока был заглушкой. По умолчанию выключено:
     * включение спрашивает разрешение на уведомления, а без спроса слать их каждый вечер
     * тем, кто видел заглушку «включённой», не стоит.
     */
    @JvmStatic
    fun isRemindOn(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_REMIND_ON, false)

    @JvmStatic
    fun setRemindOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_REMIND_ON, on).apply()
    }

    @JvmStatic
    fun getRemindHour(ctx: Context): Int = prefs(ctx).getInt(PREF_REMIND_HOUR, 20)

    @JvmStatic
    fun getRemindMinute(ctx: Context): Int = prefs(ctx).getInt(PREF_REMIND_MINUTE, 0)

    @JvmStatic
    fun setRemindTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit().putInt(PREF_REMIND_HOUR, hour).putInt(PREF_REMIND_MINUTE, minute).apply()
    }

    /**
     * Курсоры для `GET /sync/pull` (org/docs/TODO_mobile_sync.md, Фаза 3,
     * п. 1) — позиция в каждом из двух независимых потоков изменений (см.
     * README wallet-api, "Синхронизация": `ledger` — личные данные
     * пользователя, `reference` — общие справочники). `0` — синк
     * по этому потоку ещё ни разу не происходил, следующий pull должен
     * начать с самого начала. Значение приходит от сервера как
     * `next_seq` в ответе pull — сохранять сюда нужно только ПОСЛЕ того, как
     * все входящие изменения со страницы реально применены локально, иначе
     * обрыв соединения посреди применения молча потеряет страницу целиком.
     */
    @JvmStatic
    fun getLedgerSyncSeq(ctx: Context): Long = prefs(ctx).getLong(PREF_LEDGER_SYNC_SEQ, 0L)

    @JvmStatic
    fun setLedgerSyncSeq(ctx: Context, seq: Long) {
        prefs(ctx).edit().putLong(PREF_LEDGER_SYNC_SEQ, seq).apply()
    }

    @JvmStatic
    fun getReferenceSyncSeq(ctx: Context): Long = prefs(ctx).getLong(PREF_REFERENCE_SYNC_SEQ, 0L)

    @JvmStatic
    fun setReferenceSyncSeq(ctx: Context, seq: Long) {
        prefs(ctx).edit().putLong(PREF_REFERENCE_SYNC_SEQ, seq).apply()
    }

    /**
     * Сбрасывает оба курсора в начало — вызывается при успешной связке
     * (см. `SyncLinkActivity.onLinked`), если связанный email
     * изменился: другой Google-аккаунт почти наверняка ведёт на другой
     * леджер сервера, и продолжать со старой позиции означало бы либо
     * пропустить его историю целиком, либо (хуже) молча подмешать данные
     * одного аккаунта в другой. Для того же аккаунта (истёк и обновлён
     * refresh-токен) сброс не нужен — курсоры остаются в силе.
     */
    @JvmStatic
    fun resetSyncCursors(ctx: Context) {
        prefs(ctx).edit()
            .putLong(PREF_LEDGER_SYNC_SEQ, 0L)
            .putLong(PREF_REFERENCE_SYNC_SEQ, 0L)
            .remove(PREF_SYNC_LEDGER_ID)
            .apply()
        // Отложенные строки pull (sync.PullDeferredRows) ждут родителей из
        // старого леджера — для нового они чужие.
        File(ctx.noBackupFilesDir, SYNC_PULL_DEFERRED_FILE).delete()
    }

    /** Файл отложенных строк pull в `noBackupFilesDir` — см. `sync.PullDeferredRows`. */
    const val SYNC_PULL_DEFERRED_FILE = "sync_pull_deferred.json"

    /**
     * Леджер сервера, к которому относится [getLedgerSyncSeq] — из
     * `ledger.ledger_id` ответа pull. Смена при том же email (аккаунт
     * пересоздан на сервере, переход в чужой леджер по инвайту) — курсор от
     * старого леджера не годится, `SyncEngine.pullAll` начинает поток
     * заново. `null` — ещё не известен (первый синк или старая версия).
     */
    @JvmStatic
    fun getSyncLedgerId(ctx: Context): String? = prefs(ctx).getString(PREF_SYNC_LEDGER_ID, null)

    @JvmStatic
    fun setSyncLedgerId(ctx: Context, ledgerId: String?) {
        prefs(ctx).edit().putString(PREF_SYNC_LEDGER_ID, ledgerId).apply()
    }

    /**
     * Одноразовая перекачка потоков с нуля после исправления поиска по uuid
     * (SyncEngine.localUuid): до него строки со ссылкой на счёт, чей локальный
     * uuid записан без дефисов, молча пропускались, а курсор уходил дальше.
     */
    @JvmStatic
    fun isUuidFormatResyncDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_UUID_FORMAT_RESYNC_DONE, false)

    @JvmStatic
    fun setUuidFormatResyncDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(PREF_UUID_FORMAT_RESYNC_DONE, true).apply()
    }

    /**
     * Одноразовая перекачка потоков с нуля после того, как pull научился
     * откладывать строки с ещё не пришедшим родителем (SyncEngine,
     * PullDeferredRows): до этого такие строки терялись — кошелёк оставался
     * без account_operating, то есть без валюты.
     */
    @JvmStatic
    fun isDeferredRowsResyncDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_DEFERRED_ROWS_RESYNC_DONE, false)

    @JvmStatic
    fun setDeferredRowsResyncDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(PREF_DEFERRED_ROWS_RESYNC_DONE, true).apply()
    }

    /**
     * Однократная перезагрузка потоков после исправления конфликта символа пары (openspec
     * sync-pair-symbol-adopt): прежняя версия молча отбрасывала пару кошелька, символ которой
     * занят общей парой, а позиция потока уходила дальше — без перезагрузки пара не вернулась бы.
     */
    @JvmStatic
    fun isPairSymbolResyncDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_PAIR_SYMBOL_RESYNC_DONE, false)

    @JvmStatic
    fun setPairSymbolResyncDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(PREF_PAIR_SYMBOL_RESYNC_DONE, true).apply()
    }

    /**
     * Идёт загрузка синка с нуля при уже синкнутых локальных строках (openspec
     * purge-synced-tombstones, design п. 4): `SyncEngine.pullAll` отмечает
     * uuid пришедших строк в `sync_reload_seen` и, когда оба потока
     * догнаны, удаляет синкнутые строки без отметки. Флаг живёт между проходами:
     * прерванная загрузка продолжается с сохранённого курсора, вычистка — в
     * проходе, который догнал потоки. Пишется синхронно (`commit`), вместе
     * с обнулением курсоров: курсоры 0 без флага означали бы загрузку без вычистки.
     */
    @JvmStatic
    fun isFullReloadPending(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_FULL_RELOAD_PENDING, false)

    /** Начать загрузку с нуля: флаг и оба курсора 0 — одной записью. */
    @JvmStatic
    fun startFullReload(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(PREF_FULL_RELOAD_PENDING, true)
            .putLong(PREF_LEDGER_SYNC_SEQ, 0L)
            .putLong(PREF_REFERENCE_SYNC_SEQ, 0L)
            .commit()
    }

    @JvmStatic
    fun clearFullReloadPending(ctx: Context) {
        prefs(ctx).edit().putBoolean(PREF_FULL_RELOAD_PENDING, false).commit()
    }

    /**
     * Перечитать поток ledger с начала (openspec capital-assets, design п. 2): поднимает
     * шаг БД v89 — прежняя версия молча пропускала в pull строки `account_asset`, а
     * курсор ledger уходил дальше. `SyncEngine.pullAll` при флаге начинает ledger с 0
     * (reference и `ledger_id` не трогает) и снимает флаг после первой применённой
     * страницы — обрыв дальше продолжает с сохранённого курсора. Свежая установка флага не
     * получает: курсор и так 0. Пишется синхронно (`commit`).
     */
    @JvmStatic
    fun isLedgerRereadPending(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_LEDGER_REREAD_PENDING, false)

    @JvmStatic
    fun setLedgerRereadPending(ctx: Context, pending: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_LEDGER_REREAD_PENDING, pending).commit()
    }

    /**
     * Повторно отправить все живые `counterparty`/`account_debt` (openspec debts,
     * design D2, spec sync-run): поднимает шаг БД v91. Если APK вышел раньше сервера, push
     * молча пропускал незнакомые серверу таблицы, а клиент снимал dirty — строки не доехали
     * бы никогда. `SyncEngine.pushDirty` при флаге помечает их dirty и снимает флаг.
     * Пишется синхронно (`commit`).
     */
    @JvmStatic
    fun isDebtsRedirtyPending(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_DEBTS_REDIRTY_PENDING, false)

    @JvmStatic
    fun setDebtsRedirtyPending(ctx: Context, pending: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_DEBTS_REDIRTY_PENDING, pending).commit()
    }

    /**
     * Идёт подключение телефона к кошельку на сервере (openspec new-user-default-set,
     * spec ledger-connection, design D4): ставит `SyncRunner` в начале прохода, если
     * есть вход и на телефоне нет строк, бывших на сервере; снимается после прохода, где
     * отправка прошла, или после «Удалить с телефона». Отдельный флаг, а не
     * `SyncEngine.hasSyncedRows`: после прерванной загрузки часть строк уже принята,
     * а подключение ещё не закончено. Пишется синхронно — от него зависит порядок прохода.
     */
    @JvmStatic
    fun isLedgerConnectionPending(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_LEDGER_CONNECTION_PENDING, false)

    @JvmStatic
    fun setLedgerConnectionPending(ctx: Context, pending: Boolean) {
        val e = prefs(ctx).edit().putBoolean(PREF_LEDGER_CONNECTION_PENDING, pending)
        if (!pending) {
            e.remove(PREF_LEDGER_CONNECTION_DECISION).remove(PREF_LEDGER_CONNECTION_OFFLINE_OPS)
        }
        e.commit()
    }

    /** Решение по операциям без входа (spec ledger-connection, «Вопрос об операциях без входа»). */
    enum class ConnectionDecision {
        /** Вопрос не задавался. */
        NONE,

        /** Вопрос задан, ответа нет — главный экран показывает диалог, push не идёт. */
        ASKED,

        /** «Перенести» — следующий проход подключения отправляет операции. */
        TRANSFER
    }

    @JvmStatic
    fun getConnectionDecision(ctx: Context): ConnectionDecision {
        val v = prefs(ctx).getString(PREF_LEDGER_CONNECTION_DECISION, null) ?: return ConnectionDecision.NONE
        return try {
            ConnectionDecision.valueOf(v)
        } catch (e: IllegalArgumentException) {
            ConnectionDecision.NONE
        }
    }

    /** `offlineOps` — число операций для текста диалога (только для [ConnectionDecision.ASKED]). */
    @JvmStatic
    fun setConnectionDecision(ctx: Context, decision: ConnectionDecision, offlineOps: Int) {
        prefs(ctx).edit()
            .putString(PREF_LEDGER_CONNECTION_DECISION, decision.name)
            .putInt(PREF_LEDGER_CONNECTION_OFFLINE_OPS, offlineOps)
            .commit()
    }

    @JvmStatic
    fun getConnectionOfflineOps(ctx: Context): Int =
        prefs(ctx).getInt(PREF_LEDGER_CONNECTION_OFFLINE_OPS, 0)

    /**
     * Время (epoch ms) последнего прохода синка с исходом OK/PARTIAL — пишет
     * `SyncRunner`, показывает блок ошибки экрана «ИИ». 0 — не было.
     */
    @JvmStatic
    fun getLastSyncSuccessAt(ctx: Context): Long = prefs(ctx).getLong(PREF_LAST_SYNC_SUCCESS_AT, 0L)

    @JvmStatic
    fun setLastSyncSuccessAt(ctx: Context, epochMillis: Long) {
        prefs(ctx).edit().putLong(PREF_LAST_SYNC_SUCCESS_AT, epochMillis).apply()
    }

    /**
     * uuid кошелька последней траты, записанной с экрана «ИИ» — уходит в задание
     * `parse_expense` как `default_wallet_uuid`. uuid, а не _id: это
     * значение resolver сравнивает со справочником сервера.
     */
    @JvmStatic
    fun getAiLastWalletUuid(ctx: Context): String? = prefs(ctx).getString(PREF_AI_LAST_WALLET_UUID, null)

    @JvmStatic
    fun setAiLastWalletUuid(ctx: Context, uuid: String?) {
        prefs(ctx).edit().putString(PREF_AI_LAST_WALLET_UUID, uuid).apply()
    }

    // ── Тема оформления ────────────────────────────────────────────────────────
    //
    // Сама палитра живёт в ресурсах: values/colors.xml против values-night/colors.xml.
    // Отсюда решается только, какой из двух наборов подставит система, — за это
    // отвечает AppCompatDelegate, а -night включён тем, что AppTheme унаследован от
    // Theme.AppCompat.DayNight (res/values/styles.xml).

    /** [THEME_SYSTEM] (по умолчанию), [THEME_LIGHT] или [THEME_DARK]. */
    @JvmStatic
    fun getThemeMode(ctx: Context): String = prefs(ctx).getString(PREF_THEME_MODE, THEME_SYSTEM)!!

    /**
     * Сохраняет выбор и применяет его. Экран, с которого вызвали, обновится не сам:
     * ему нужно `recreate()`. Остальные активности переживут смену темы в стеке
     * и перерисуются, когда всплывут — AppCompatDelegate пересоздаёт их в onStart().
     */
    @JvmStatic
    fun setThemeMode(ctx: Context, mode: String?) {
        prefs(ctx).edit().putString(PREF_THEME_MODE, mode).apply()
        applyThemeMode(ctx)
    }

    /** Вызывается на старте процесса ([WalletApplication]) и после смены выбора. */
    @JvmStatic
    fun applyThemeMode(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getThemeMode(ctx)))
    }

    private fun toNightMode(mode: String?): Int {
        if (THEME_LIGHT == mode) return AppCompatDelegate.MODE_NIGHT_NO
        if (THEME_DARK == mode) return AppCompatDelegate.MODE_NIGHT_YES
        // «Как в системе» умеет только Android 10+: до него системного переключателя
        // тёмной темы нет и конфигурация всегда светлая — режим просто не сработает.
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
