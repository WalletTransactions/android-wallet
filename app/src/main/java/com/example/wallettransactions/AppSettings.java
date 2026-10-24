package com.example.wallettransactions;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * Persisted app-wide settings shared between {@link com.example.wallettransactions.Activities.SettingsActivity}
 * (writes it when the user picks a currency) and {@link com.example.wallettransactions.Activities.MainActivity}
 * (reads it as the default currencyEq, and keeps it in sync when changed from either screen).
 *
 * Здесь же хранится общая «шапка» корневых экранов — период и фильтр, — чтобы она
 * переживала не только переход между табами, но и перезапуск приложения. Читает и
 * пишет их {@link PeriodFilterState}, остальной код обращается к нему, а не сюда.
 */
public final class AppSettings {

    private static final String PREFS_NAME = "settings";
    private static final String PREF_CURRENCY_EQ_ISO = "currencyEqIso";
    private static final String PREF_PERIOD_FROM = "periodFrom";
    private static final String PREF_PERIOD_TO = "periodTo";
    private static final String PREF_FILTER_ACCOUNTS = "filterAccounts";
    private static final String PREF_FILTER_OPERATION_KIND = "filterOperationKind";
    private static final String PREF_FILTER_SEARCH_TEXT = "filterSearchText";
    private static final String PREF_FILTER_AMOUNT_MIN = "filterAmountMin";
    private static final String PREF_FILTER_AMOUNT_MAX = "filterAmountMax";
    private static final String PREF_FILTER_EQ_MIN = "filterEqMin";
    private static final String PREF_FILTER_EQ_MAX = "filterEqMax";
    private static final String PREF_LAST_WALLET_ID = "lastWalletId";
    private static final String PREF_PICKER_KEYBOARD = "pickerKeyboard";
    private static final String PREF_PICKER_WALLET_SORT = "pickerWalletSort";
    private static final String PREF_PICKER_CATEGORY_SORT = "pickerCategorySort";
    private static final String PREF_THEME_MODE = "themeMode";
    private static final String PREF_SERVER_BASE_URL = "serverBaseUrl";
    private static final String PREF_DEVICE_ID = "deviceId";
    private static final String PREF_LINKED_EMAIL = "linkedEmail";
    private static final String PREF_NEEDS_RELINK = "needsRelink";
    private static final String PREF_AUTO_CONNECT_ENABLED = "autoConnectEnabled";
    private static final String PREF_AUTO_CONNECT_INTERVAL_MINUTES = "autoConnectIntervalMinutes";
    private static final String PREF_NOTIFY_NEW_OPERATIONS = "notifyNewOperations";
    private static final String PREF_REMIND_ON = "remindOn";
    private static final String PREF_REMIND_HOUR = "remindHour";
    private static final String PREF_REMIND_MINUTE = "remindMinute";
    private static final String PREF_LEDGER_SYNC_SEQ = "ledgerSyncSeq";
    private static final String PREF_REFERENCE_SYNC_SEQ = "referenceSyncSeq";
    private static final String PREF_SYNC_LEDGER_ID = "syncLedgerId";
    private static final String PREF_UUID_FORMAT_RESYNC_DONE = "uuidFormatResyncDone";
    private static final String PREF_LAST_SYNC_SUCCESS_AT = "lastSyncSuccessAt";
    private static final String PREF_AI_LAST_WALLET_UUID = "aiLastWalletUuid";

    /** См. {@link #getAutoConnectIntervalMinutes} — минимум, который вообще
     *  умеет {@code androidx.work} для периодической задачи. */
    public static final int DEFAULT_AUTO_CONNECT_INTERVAL_MINUTES = 60;

    /**
     * Гейтвей (wallet-gateway) по умолчанию — LAN-only на этом этапе
     * (org/docs/TODO_mobile_sync.md, п. 1.4): нет ни TLS, ни обнаружения
     * сервера в сети, адрес вводится вручную. {@code localhost} годится,
     * только когда сервер и устройство физически одно и то же (эмулятор
     * при пробросе {@code adb reverse tcp:8090 tcp:8090}, либо запуск
     * тестов на самом сервере) — на реальном телефоне в общей Wi-Fi сети
     * это должен быть LAN-адрес компьютера, см. экран «Настройки».
     */
    public static final String DEFAULT_SERVER_BASE_URL = "http://localhost:8090/";

    /** Значения {@link #getThemeMode}: строки, а не int-константы AppCompat, — их
     *  значения могут поменяться между версиями библиотеки, а в prefs они навсегда. */
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private AppSettings() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** ISO code of the saved equivalent currency, or null if never set. */
    public static String getCurrencyEqIso(Context ctx) {
        return prefs(ctx).getString(PREF_CURRENCY_EQ_ISO, null);
    }

    public static void setCurrencyEqIso(Context ctx, String iso) {
        prefs(ctx).edit().putString(PREF_CURRENCY_EQ_ISO, iso).apply();
    }

    /** Границы периода в формате {@link DateUtils#DateToStr}, или null, если период ещё не выбирали. */
    public static String getPeriodFrom(Context ctx) {
        return prefs(ctx).getString(PREF_PERIOD_FROM, null);
    }

    public static String getPeriodTo(Context ctx) {
        return prefs(ctx).getString(PREF_PERIOD_TO, null);
    }

    public static void setPeriod(Context ctx, String from, String to) {
        prefs(ctx).edit()
                .putString(PREF_PERIOD_FROM, from)
                .putString(PREF_PERIOD_TO, to)
                .apply();
    }

    /** ID счетов фильтра через запятую; пустая строка — фильтр не задан. */
    public static String getFilterAccounts(Context ctx) {
        return prefs(ctx).getString(PREF_FILTER_ACCOUNTS, "");
    }

    public static void setFilterAccounts(Context ctx, String csvIds) {
        prefs(ctx).edit().putString(PREF_FILTER_ACCOUNTS, csvIds).apply();
    }

    /**
     * Кошелёк последней сохранённой операции — его подставляет окно новой операции, когда
     * фильтр кошелька не подсказывает. 0 — операций ещё не сохраняли.
     */
    public static int getLastWalletId(Context ctx) {
        return prefs(ctx).getInt(PREF_LAST_WALLET_ID, 0);
    }

    public static void setLastWalletId(Context ctx, int id) {
        prefs(ctx).edit().putInt(PREF_LAST_WALLET_ID, id).apply();
    }

    /**
     * Экраны выбора категории/кошелька (AccountPickerActivity, MultiPickerActivity) открываются
     * с фокусом в поиске и поднятой клавиатурой — можно сразу печатать. По умолчанию выключено.
     */
    public static boolean isPickerKeyboardOn(Context ctx) {
        return prefs(ctx).getBoolean(PREF_PICKER_KEYBOARD, false);
    }

    public static void setPickerKeyboardOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(PREF_PICKER_KEYBOARD, on).apply();
    }

    /**
     * Порядок строк на экране выбора: для кошельков ({@code wallets}) и для категорий
     * свой. Задаётся только в «Настройках»: кнопка на экране выбора меняет порядок
     * лишь до его закрытия.
     * По умолчанию — то, как список сортировался до появления настройки.
     */
    public static PickerSort getPickerSort(Context ctx, boolean wallets) {
        PickerSort[] modes = wallets ? PickerSort.WALLET_MODES : PickerSort.CATEGORY_MODES;
        PickerSort fallback = wallets ? PickerSort.BALANCE : PickerSort.NAME;
        PickerSort sort = PickerSort.fromName(prefs(ctx).getString(
                wallets ? PREF_PICKER_WALLET_SORT : PREF_PICKER_CATEGORY_SORT, null), fallback);
        // «По остатку», сохранённый для категорий, — не их режим.
        for (PickerSort mode : modes) if (mode == sort) return sort;
        return fallback;
    }

    public static void setPickerSort(Context ctx, boolean wallets, PickerSort sort) {
        prefs(ctx).edit().putString(
                wallets ? PREF_PICKER_WALLET_SORT : PREF_PICKER_CATEGORY_SORT, sort.name()).apply();
    }

    /**
     * Ставит фокус в поле поиска и поднимает клавиатуру, если включено
     * {@link #isPickerKeyboardOn}. Вызывать из onCreate экрана выбора.
     */
    public static void applyPickerKeyboard(android.app.Activity activity, android.widget.EditText search) {
        if (!isPickerKeyboardOn(activity)) return;
        search.requestFocus();
        activity.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    /** Имя константы {@code DataFilter.OperationKind}; null — фильтра по виду ещё не было. */
    public static String getFilterOperationKind(Context ctx) {
        return prefs(ctx).getString(PREF_FILTER_OPERATION_KIND, null);
    }

    public static void setFilterOperationKind(Context ctx, String kindName) {
        prefs(ctx).edit().putString(PREF_FILTER_OPERATION_KIND, kindName).apply();
    }

    /** Строка поиска фильтра операций; пустая строка — фильтра нет. */
    public static String getFilterSearchText(Context ctx) {
        return prefs(ctx).getString(PREF_FILTER_SEARCH_TEXT, "");
    }

    public static void setFilterSearchText(Context ctx, String text) {
        prefs(ctx).edit().putString(PREF_FILTER_SEARCH_TEXT, text).apply();
    }

    /** Границы суммы фильтра операций (валюта кошелька); null — граница не задана. */
    public static Double getFilterAmountMin(Context ctx) {
        return getNullableDouble(ctx, PREF_FILTER_AMOUNT_MIN);
    }

    public static Double getFilterAmountMax(Context ctx) {
        return getNullableDouble(ctx, PREF_FILTER_AMOUNT_MAX);
    }

    public static void setFilterAmountRange(Context ctx, Double min, Double max) {
        setNullableDouble(ctx, PREF_FILTER_AMOUNT_MIN, min);
        setNullableDouble(ctx, PREF_FILTER_AMOUNT_MAX, max);
    }

    /** Границы суммы фильтра операций (валюта эквивалента); null — граница не задана. */
    public static Double getFilterEqMin(Context ctx) {
        return getNullableDouble(ctx, PREF_FILTER_EQ_MIN);
    }

    public static Double getFilterEqMax(Context ctx) {
        return getNullableDouble(ctx, PREF_FILTER_EQ_MAX);
    }

    public static void setFilterEqRange(Context ctx, Double min, Double max) {
        setNullableDouble(ctx, PREF_FILTER_EQ_MIN, min);
        setNullableDouble(ctx, PREF_FILTER_EQ_MAX, max);
    }

    /** SharedPreferences не хранит nullable Double — используем строку и её отсутствие. */
    private static Double getNullableDouble(Context ctx, String key) {
        String raw = prefs(ctx).getString(key, null);
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void setNullableDouble(Context ctx, String key, Double value) {
        prefs(ctx).edit().putString(key, value == null ? null : Double.toString(value)).apply();
    }

    // ── Синхронизация с сервером ───────────────────────────────────────────────
    //
    // org/docs/TODO_mobile_sync.md, Фаза 3: адрес wallet-gateway на LAN, ввод
    // вручную (обнаружение сервера в сети — отдельная задача). device_id и
    // токены сюда не попадают — это секреты, им место в EncryptedSharedPreferences,
    // не в этом файле (см. план, п. 5.1).

    /** Адрес wallet-gateway с завершающим {@code /}, например {@code http://192.168.1.42:8090/}. */
    public static String getServerBaseUrl(Context ctx) {
        return prefs(ctx).getString(PREF_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL);
    }

    /** Дописывает завершающий {@code /}, если его не ввели — иначе конкатенация путей ломается. */
    public static void setServerBaseUrl(Context ctx, String url) {
        String normalized = url.endsWith("/") ? url : url + "/";
        prefs(ctx).edit().putString(PREF_SERVER_BASE_URL, normalized).apply();
    }

    /**
     * Стабильный id этой установки приложения — не секрет (в отличие от
     * токенов в {@link com.example.wallettransactions.TokenStore}, поэтому
     * живёт в обычных, не зашифрованных prefs), просто то, чем wallet-api
     * различает устройства одного пользователя (план, п. 1.2 — сервер сам
     * резолвит `(sub, device_id)` в свою запись `device`, в JWT это поле не
     * попадает). Генерируется один раз при первом обращении и остаётся тем
     * же до переустановки приложения.
     */
    public static String getOrCreateDeviceId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String existing = p.getString(PREF_DEVICE_ID, null);
        if (existing != null) return existing;
        String generated = java.util.UUID.randomUUID().toString();
        p.edit().putString(PREF_DEVICE_ID, generated).apply();
        return generated;
    }

    /** Email аккаунта, которым связались (см. {@code SyncLinkActivity}) — только
     *  для строки статуса на экране «Настройки», null пока связки не было. */
    public static String getLinkedEmail(Context ctx) {
        return prefs(ctx).getString(PREF_LINKED_EMAIL, null);
    }

    public static void setLinkedEmail(Context ctx, String email) {
        prefs(ctx).edit().putString(PREF_LINKED_EMAIL, email).apply();
    }

    /**
     * Выставляется {@code SyncWorker}, когда очередная попытка
     * {@code POST /auth/refresh} вернула 401 — refresh-токен истёк, отозван
     * или уже был использован (см. README google-auth, "Обновление
     * сессии"). Означает: нужно заново пройти {@code SyncLinkActivity} —
     * тихо повторить вход через Google из фоновой задачи нельзя, Credential
     * Manager требует показать UI поверх Activity.
     */
    public static boolean isNeedsRelink(Context ctx) {
        return prefs(ctx).getBoolean(PREF_NEEDS_RELINK, false);
    }

    public static void setNeedsRelink(Context ctx, boolean needsRelink) {
        prefs(ctx).edit().putBoolean(PREF_NEEDS_RELINK, needsRelink).apply();
    }

    /** Экран «Ещё» → «Интеграции» → «Автоматическая синхронизация». */
    public static boolean isAutoConnectEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_AUTO_CONNECT_ENABLED, false);
    }

    public static void setAutoConnectEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_AUTO_CONNECT_ENABLED, enabled).apply();
    }

    /** Раз в сколько минут {@code SyncWorker} пробует refresh + push/pull. */
    public static int getAutoConnectIntervalMinutes(Context ctx) {
        return prefs(ctx).getInt(PREF_AUTO_CONNECT_INTERVAL_MINUTES, DEFAULT_AUTO_CONNECT_INTERVAL_MINUTES);
    }

    public static void setAutoConnectIntervalMinutes(Context ctx, int minutes) {
        prefs(ctx).edit().putInt(PREF_AUTO_CONNECT_INTERVAL_MINUTES, minutes).apply();
    }

    /** Экран «Ещё» → «Интеграции» → «Уведомлять о новых операциях» (sync.NewOperationsNotifier). */
    public static boolean isNotifyNewOperationsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_NOTIFY_NEW_OPERATIONS, true);
    }

    public static void setNotifyNewOperationsEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_NOTIFY_NEW_OPERATIONS, enabled).apply();
    }

    /**
     * «Настройки» → «Уведомления» → «Напоминание вносить операции» (reminder.ReminderWorker).
     * Ключи — те же, что писал переключатель, пока был заглушкой. По умолчанию выключено:
     * включение спрашивает разрешение на уведомления, а без спроса слать их каждый вечер
     * тем, кто видел заглушку «включённой», не стоит.
     */
    public static boolean isRemindOn(Context ctx) {
        return prefs(ctx).getBoolean(PREF_REMIND_ON, false);
    }

    public static void setRemindOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(PREF_REMIND_ON, on).apply();
    }

    public static int getRemindHour(Context ctx) {
        return prefs(ctx).getInt(PREF_REMIND_HOUR, 20);
    }

    public static int getRemindMinute(Context ctx) {
        return prefs(ctx).getInt(PREF_REMIND_MINUTE, 0);
    }

    public static void setRemindTime(Context ctx, int hour, int minute) {
        prefs(ctx).edit().putInt(PREF_REMIND_HOUR, hour).putInt(PREF_REMIND_MINUTE, minute).apply();
    }

    /**
     * Курсоры для {@code GET /sync/pull} (org/docs/TODO_mobile_sync.md, Фаза 3,
     * п. 1) — позиция в каждом из двух независимых потоков изменений (см.
     * README wallet-api, "Синхронизация": {@code ledger} — личные данные
     * пользователя, {@code reference} — общие справочники). {@code 0} — синк
     * по этому потоку ещё ни разу не происходил, следующий pull должен
     * начать с самого начала. Значение приходит от сервера как {@code
     * next_seq} в ответе pull — сохранять сюда нужно только ПОСЛЕ того, как
     * все входящие изменения со страницы реально применены локально, иначе
     * обрыв соединения посреди применения молча потеряет страницу целиком.
     */
    public static long getLedgerSyncSeq(Context ctx) {
        return prefs(ctx).getLong(PREF_LEDGER_SYNC_SEQ, 0L);
    }

    public static void setLedgerSyncSeq(Context ctx, long seq) {
        prefs(ctx).edit().putLong(PREF_LEDGER_SYNC_SEQ, seq).apply();
    }

    public static long getReferenceSyncSeq(Context ctx) {
        return prefs(ctx).getLong(PREF_REFERENCE_SYNC_SEQ, 0L);
    }

    public static void setReferenceSyncSeq(Context ctx, long seq) {
        prefs(ctx).edit().putLong(PREF_REFERENCE_SYNC_SEQ, seq).apply();
    }

    /**
     * Сбрасывает оба курсора в начало — вызывается при успешной связке
     * (см. {@code SyncLinkActivity.onLinked}), если связанный email
     * изменился: другой Google-аккаунт почти наверняка ведёт на другой
     * леджер сервера, и продолжать со старой позиции означало бы либо
     * пропустить его историю целиком, либо (хуже) молча подмешать данные
     * одного аккаунта в другой. Для того же аккаунта (истёк и обновлён
     * refresh-токен) сброс не нужен — курсоры остаются в силе.
     */
    public static void resetSyncCursors(Context ctx) {
        prefs(ctx).edit()
                .putLong(PREF_LEDGER_SYNC_SEQ, 0L)
                .putLong(PREF_REFERENCE_SYNC_SEQ, 0L)
                .remove(PREF_SYNC_LEDGER_ID)
                .apply();
    }

    /**
     * Леджер сервера, к которому относится {@link #getLedgerSyncSeq} — из
     * {@code ledger.ledger_id} ответа pull. Смена при том же email (аккаунт
     * пересоздан на сервере, переход в чужой леджер по инвайту) — курсор от
     * старого леджера не годится, {@code SyncEngine.pullAll} начинает поток
     * заново. {@code null} — ещё не известен (первый синк или старая версия).
     */
    public static String getSyncLedgerId(Context ctx) {
        return prefs(ctx).getString(PREF_SYNC_LEDGER_ID, null);
    }

    public static void setSyncLedgerId(Context ctx, String ledgerId) {
        prefs(ctx).edit().putString(PREF_SYNC_LEDGER_ID, ledgerId).apply();
    }

    /**
     * Одноразовая перекачка потоков с нуля после исправления поиска по uuid
     * (SyncEngine.localUuid): до него строки со ссылкой на счёт, чей локальный
     * uuid записан без дефисов, молча пропускались, а курсор уходил дальше.
     */
    public static boolean isUuidFormatResyncDone(Context ctx) {
        return prefs(ctx).getBoolean(PREF_UUID_FORMAT_RESYNC_DONE, false);
    }

    public static void setUuidFormatResyncDone(Context ctx) {
        prefs(ctx).edit().putBoolean(PREF_UUID_FORMAT_RESYNC_DONE, true).apply();
    }

    /**
     * Время (epoch ms) последнего прохода синка с исходом OK/PARTIAL — пишет
     * {@code SyncRunner}, показывает блок ошибки экрана «ИИ». 0 — не было.
     */
    public static long getLastSyncSuccessAt(Context ctx) {
        return prefs(ctx).getLong(PREF_LAST_SYNC_SUCCESS_AT, 0L);
    }

    public static void setLastSyncSuccessAt(Context ctx, long epochMillis) {
        prefs(ctx).edit().putLong(PREF_LAST_SYNC_SUCCESS_AT, epochMillis).apply();
    }

    /**
     * uuid кошелька последней траты, записанной с экрана «ИИ» — уходит в задание
     * {@code parse_expense} как {@code default_wallet_uuid}. uuid, а не _id: это
     * значение resolver сравнивает со справочником сервера.
     */
    public static String getAiLastWalletUuid(Context ctx) {
        return prefs(ctx).getString(PREF_AI_LAST_WALLET_UUID, null);
    }

    public static void setAiLastWalletUuid(Context ctx, String uuid) {
        prefs(ctx).edit().putString(PREF_AI_LAST_WALLET_UUID, uuid).apply();
    }

    // ── Тема оформления ────────────────────────────────────────────────────────
    //
    // Сама палитра живёт в ресурсах: values/colors.xml против values-night/colors.xml.
    // Отсюда решается только, какой из двух наборов подставит система, — за это
    // отвечает AppCompatDelegate, а -night включён тем, что AppTheme унаследован от
    // Theme.AppCompat.DayNight (res/values/styles.xml).

    /** {@link #THEME_SYSTEM} (по умолчанию), {@link #THEME_LIGHT} или {@link #THEME_DARK}. */
    public static String getThemeMode(Context ctx) {
        return prefs(ctx).getString(PREF_THEME_MODE, THEME_SYSTEM);
    }

    /**
     * Сохраняет выбор и применяет его. Экран, с которого вызвали, обновится не сам:
     * ему нужно {@code recreate()}. Остальные активности переживут смену темы в стеке
     * и перерисуются, когда всплывут — AppCompatDelegate пересоздаёт их в onStart().
     */
    public static void setThemeMode(Context ctx, String mode) {
        prefs(ctx).edit().putString(PREF_THEME_MODE, mode).apply();
        applyThemeMode(ctx);
    }

    /** Вызывается на старте процесса ({@link WalletApplication}) и после смены выбора. */
    public static void applyThemeMode(Context ctx) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getThemeMode(ctx)));
    }

    private static int toNightMode(String mode) {
        if (THEME_LIGHT.equals(mode)) return AppCompatDelegate.MODE_NIGHT_NO;
        if (THEME_DARK.equals(mode)) return AppCompatDelegate.MODE_NIGHT_YES;
        // «Как в системе» умеет только Android 10+: до него системного переключателя
        // тёмной темы нет и конфигурация всегда светлая — режим просто не сработает.
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
