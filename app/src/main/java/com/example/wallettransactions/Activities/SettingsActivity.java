package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.Manifest;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.example.wallettransactions.AppLanguage;
import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.BuildConfig;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.DataItems.CurrencyConversion.EquivalentCurrencies;
import com.example.wallettransactions.PickerSort;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.security.AppLock;
import com.example.wallettransactions.security.AppLockStore;
import com.example.wallettransactions.security.BiometricErrors;
import com.example.wallettransactions.security.BiometricKey;
import com.example.wallettransactions.reminder.ReminderWorker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;

/**
 * Экран «Настройки», открывается с таба «Ещё» нижней панели (MainActivity.navMore).
 *
 * Тема оформления, валюта эквивалента и время напоминания реально сохраняются
 * (SharedPreferences) и переживают переоткрытие экрана. «Код доступа» — настоящий
 * (security.AppLock, org/docs/TODO_app_lock.md): включение, выключение и смена идут
 * через {@link LockActivity}. «Вход по отпечатку» — тоже (Фаза 2 плана, п. 3.4).
 * «Напоминание вносить операции» — {@link ReminderWorker}.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_PIN_ENABLE = 1;
    private static final int REQUEST_PIN_DISABLE = 2;
    private static final int REQUEST_PIN_CHANGE_VERIFY = 3;
    private static final int REQUEST_PIN_CHANGE_SET = 4;

    private TextView mainCurrencyValue;
    private TextView languageValue;
    private TextView chipThemeLight;
    private TextView chipThemeDark;
    private TextView chipThemeSystem;
    private FrameLayout switchAccessCode;
    private FrameLayout switchBiometric;
    private TextView labelBiometric;
    private BiometricPrompt biometricPrompt;
    private View rowsPinSet;
    private TextView lockTimeoutValue;
    private FrameLayout switchHideRecents;
    private TextView hideRecentsHint;
    private FrameLayout switchRemind;
    private FrameLayout switchPickerKeyboard;
    private FrameLayout switchCapitalInSummary;
    private TextView walletSortValue;
    private TextView categorySortValue;
    private TextView rowRemindTime;

    /** Тапы по строке «Версия» — {@link #onVersionTap}. */
    private static final int HIDDEN_MENU_TAPS = 5;
    private static final long HIDDEN_MENU_TAP_TIMEOUT_MS = 1500;
    private int versionTapCount = 0;
    private long versionTapLastAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mainCurrencyValue = findViewById(R.id.mainCurrencyValue);
        languageValue = findViewById(R.id.languageValue);
        chipThemeLight = findViewById(R.id.chipThemeLight);
        chipThemeDark = findViewById(R.id.chipThemeDark);
        chipThemeSystem = findViewById(R.id.chipThemeSystem);
        switchAccessCode = findViewById(R.id.switchAccessCode);
        switchBiometric = findViewById(R.id.switchBiometric);
        labelBiometric = findViewById(R.id.labelBiometric);
        // В onCreate: BiometricPrompt должен пережить поворот вместе с колбэком.
        biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), enableBiometricCallback);
        rowsPinSet = findViewById(R.id.rowsPinSet);
        lockTimeoutValue = findViewById(R.id.lockTimeoutValue);
        switchHideRecents = findViewById(R.id.switchHideRecents);
        hideRecentsHint = findViewById(R.id.hideRecentsHint);
        switchRemind = findViewById(R.id.switchRemind);
        switchPickerKeyboard = findViewById(R.id.switchPickerKeyboard);
        switchCapitalInSummary = findViewById(R.id.switchCapitalInSummary);
        walletSortValue = findViewById(R.id.walletSortValue);
        categorySortValue = findViewById(R.id.categorySortValue);
        rowRemindTime = findViewById(R.id.rowRemindTime);

        TextView versionValue = findViewById(R.id.versionValue);
        try {
            versionValue.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
            // Keep the layout's placeholder if the version can't be read.
        }

        // BUILD_TIME — сгенерированное поле BuildConfig (app/build.gradle),
        // дата и время сборки в UTC, растёт вместе с версией на каждой сборке.
        TextView buildDateValue = findViewById(R.id.buildDateValue);
        buildDateValue.setText(BuildConfig.BUILD_TIME);

        applyThemeChips(AppSettings.getThemeMode(this));
        languageValue.setText(languageTitles()[languageIndex()]);

        String savedIso = AppSettings.getCurrencyEqIso(this);
        Currency saved = savedIso != null
                ? Singleton.instance.getCurrencyList(this).getCurrencyByISO(savedIso) : null;
        if (saved != null) mainCurrencyValue.setText(saved.getIso() + " — " + saved.getName());

        paintSecurity();
        applySwitchState(switchRemind, AppSettings.isRemindOn(this));
        applySwitchState(switchPickerKeyboard, AppSettings.isPickerKeyboardOn(this));
        applySwitchState(switchCapitalInSummary, AppSettings.isCapitalInSummaryOn(this));
        paintSortValues();

        paintRemindTime();
    }

    public void btnUp(View view) {
        finish();
    }

    /** Общий обработчик трёх чипов темы — какой нажали, видно по id. */
    public void onPickTheme(View view) {
        String mode;
        int id = view.getId();
        if (id == R.id.chipThemeLight) mode = AppSettings.THEME_LIGHT;
        else if (id == R.id.chipThemeDark) mode = AppSettings.THEME_DARK;
        else mode = AppSettings.THEME_SYSTEM;

        // Повторный тап по уже выбранному варианту ничего не меняет: пересоздавать
        // экран ради той же темы — только моргнуть им на пустом месте.
        if (mode.equals(AppSettings.getThemeMode(this))) return;

        AppSettings.setThemeMode(this, mode);
        // Своей активности смена defaultNightMode не касается — палитру подхватит
        // только новая. Остальной стек пересоздастся сам, когда всплывёт.
        recreate();
    }

    private void applyThemeChips(String mode) {
        paintThemeChip(chipThemeLight, AppSettings.THEME_LIGHT.equals(mode));
        paintThemeChip(chipThemeDark, AppSettings.THEME_DARK.equals(mode));
        paintThemeChip(chipThemeSystem, AppSettings.THEME_SYSTEM.equals(mode));
    }

    private void paintThemeChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected
                ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.colorSurface : R.color.colorTextSecondary));
    }

    /** Язык приложения (AppLanguage): язык телефона, English, Русский. */
    public void onPickLanguage(View view) {
        int checked = languageIndex();
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(languageTitles(), checked, (d, which) -> {
                    d.dismiss();
                    // Экраны AppCompat пересоздаются сами на новом языке.
                    if (which != checked) AppLanguage.set(AppLanguage.TAGS[which]);
                })
                .show();
    }

    private String[] languageTitles() {
        return new String[]{getString(R.string.settings_language_system),
                getString(R.string.settings_language_en), getString(R.string.settings_language_ru)};
    }

    private static int languageIndex() {
        String tag = AppLanguage.current();
        for (int i = 0; i < AppLanguage.TAGS.length; i++) {
            if (AppLanguage.TAGS[i].equals(tag)) return i;
        }
        return 0;
    }

    public void onPickMainCurrency(View view) {
        CurrencyList currencyList = Singleton.instance.getCurrencyList(this);
        String currentIso = AppSettings.getCurrencyEqIso(this);
        Currency current = currentIso != null ? currencyList.getCurrencyByISO(currentIso) : null;
        // Только валюты с флагом «Показывать в эквиваленте» + текущая (currency-ui-tweaks).
        List<Currency> shown = EquivalentCurrencies.filter(new ArrayList<>(currencyList), current);

        String[] items = new String[shown.size()];
        int checked = -1;
        for (int i = 0; i < shown.size(); i++) {
            Currency c = shown.get(i);
            items[i] = c.getIso() + " — " + c.getName();
            if (current != null && c.getId() == current.getId()) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_currency_eq)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Currency chosen = shown.get(which);
                    mainCurrencyValue.setText(chosen.getIso() + " — " + chosen.getName());
                    AppSettings.setCurrencyEqIso(this, chosen.getIso());
                    d.dismiss();
                })
                .show();
    }

    /** Импорт CSV/JSON (счета, операции, курсы валют) — см. {@link ImportActivity}.
     *  До 2026-11-11 пункт стоял в «Ещё». */
    public void onImportFile(View view) {
        startActivity(new Intent(this, ImportActivity.class));
    }

    /** Экспорт базы (файл .db) или JSON — см. {@link ExportActivity}. */
    public void onExportDatabase(View view) {
        startActivity(new Intent(this, ExportActivity.class));
    }

    /**
     * Пять тапов подряд по строке «Версия» (с паузой не больше
     * {@link #HIDDEN_MENU_TAP_TIMEOUT_MS} между тапами) открывают экран
     * «Удалить данные» ({@link ResetDataActivity}) — раньше он был виден
     * прямо в «Ещё», теперь спрятан за этот жест, чтобы не подставлять
     * необратимое действие под случайный тап.
     */
    public void onVersionTap(View view) {
        long now = System.currentTimeMillis();
        if (now - versionTapLastAt > HIDDEN_MENU_TAP_TIMEOUT_MS) versionTapCount = 0;
        versionTapLastAt = now;
        versionTapCount++;

        if (versionTapCount < HIDDEN_MENU_TAPS) {
            int left = HIDDEN_MENU_TAPS - versionTapCount;
            if (versionTapCount >= 3) {
                Toast.makeText(this, getResources().getQuantityString(R.plurals.settings_taps_left, left, left),
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        versionTapCount = 0;
        startActivity(new Intent(this, ResetDataActivity.class));
    }


    // ── Код доступа ───────────────────────────────────────────────────────────

    /** Включение — придумать код; выключение — сначала текущий код. Тумблер меняет только результат. */
    public void onToggleAccessCode(View view) {
        if (AppLockStore.isPinSet(this)) {
            startActivityForResult(LockActivity.verifyIntent(this), REQUEST_PIN_DISABLE);
        } else {
            startActivityForResult(LockActivity.setIntent(this), REQUEST_PIN_ENABLE);
        }
    }

    public void onChangeAccessCode(View view) {
        startActivityForResult(LockActivity.verifyIntent(this), REQUEST_PIN_CHANGE_VERIFY);
    }

    public void onPickLockTimeout(View view) {
        long current = AppLockStore.getTimeoutMs(this);
        int checked = 1;
        for (int i = 0; i < AppLockStore.TIMEOUTS_MS.length; i++) {
            if (AppLockStore.TIMEOUTS_MS[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_lock_after)
                .setSingleChoiceItems(AppLockStore.timeoutTitles(this), checked, (d, which) -> {
                    AppLockStore.setTimeoutMs(this, AppLockStore.TIMEOUTS_MS[which]);
                    paintSecurity();
                    d.dismiss();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        switch (requestCode) {
            case REQUEST_PIN_ENABLE:
                Toast.makeText(this, R.string.settings_pin_on, Toast.LENGTH_SHORT).show();
                break;
            case REQUEST_PIN_DISABLE:
                AppLockStore.clearPin(this);
                AppLock.unlock();
                Toast.makeText(this, R.string.settings_pin_off, Toast.LENGTH_SHORT).show();
                break;
            case REQUEST_PIN_CHANGE_VERIFY:
                // Старый код остаётся, пока новый не подтверждён: отмена на любом шаге ничего не меняет.
                startActivityForResult(LockActivity.setIntent(this), REQUEST_PIN_CHANGE_SET);
                return;
            case REQUEST_PIN_CHANGE_SET:
                Toast.makeText(this, R.string.settings_pin_changed, Toast.LENGTH_SHORT).show();
                break;
            default:
                return;
        }
        paintSecurity();
    }

    /** Отпечатки могли поменять в настройках телефона, пока этот экран был в фоне. */
    @Override
    protected void onResume() {
        super.onResume();
        paintSecurity();
    }

    private void paintSecurity() {
        boolean pinSet = AppLockStore.isPinSet(this);
        applySwitchState(switchAccessCode, pinSet);
        rowsPinSet.setVisibility(pinSet ? View.VISIBLE : View.GONE);
        lockTimeoutValue.setText(AppLockStore.getTimeoutTitle(this));
        applySwitchState(switchHideRecents, AppLockStore.getHideInRecentsSetting(this));
        hideRecentsHint.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? R.string.settings_hide_recents_hint
                : R.string.settings_hide_recents_hint_screenshots);

        // Ключ инвалидирован (отпечатки в телефоне изменились) — тумблер не должен врать.
        if (AppLockStore.isBiometricOn(this) && BiometricKey.cipherOrNull() == null) {
            AppLockStore.setBiometricOn(this, false);
        }
        boolean available = BiometricErrors.unavailableReason(this, pinSet, canAuthenticate()) == null;
        applySwitchState(switchBiometric, AppLockStore.isBiometricOn(this));
        labelBiometric.setTextColor(ContextCompat.getColor(this,
                available ? R.color.colorTextPrimary : R.color.colorTextHint));
    }

    public void onToggleHideRecents(View view) {
        AppLockStore.setHideInRecents(this, toggleSwitch(switchHideRecents));
        // Этому экрану — сразу; остальные подхватят на своём onResume (AppLock).
        AppLock.applyRecentsPrivacy(this);
    }

    private int canAuthenticate() {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
    }

    /**
     * Выключение — сразу и без кода (удобства меньше, защиты не меньше). Включение —
     * новый ключ Keystore и один успешный отпечаток; недоступен — объяснить почему.
     */
    public void onToggleBiometric(View view) {
        if (AppLockStore.isBiometricOn(this)) {
            AppLockStore.setBiometricOn(this, false);
            paintSecurity();
            return;
        }
        boolean pinSet = AppLockStore.isPinSet(this);
        int can = canAuthenticate();
        String reason = BiometricErrors.unavailableReason(this, pinSet, can);
        if (reason != null) {
            if (BiometricErrors.canEnroll(pinSet, can)) {
                new AlertDialog.Builder(this)
                        .setMessage(reason)
                        .setPositiveButton(R.string.settings_open_phone_settings, (d, w) -> openBiometricEnroll())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            } else {
                Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        Cipher cipher;
        try {
            BiometricKey.create();
            cipher = BiometricKey.cipherOrNull();
        } catch (Exception e) {
            cipher = null;
        }
        if (cipher == null) {
            Toast.makeText(this, R.string.settings_biometric_prepare_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_biometric_title))
                .setSubtitle(getString(R.string.settings_biometric_subtitle))
                .setNegativeButtonText(getString(R.string.action_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        biometricPrompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }

    private final BiometricPrompt.AuthenticationCallback enableBiometricCallback =
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
                    if (BiometricKey.confirm(crypto != null ? crypto.getCipher() : null)) {
                        AppLockStore.setBiometricOn(SettingsActivity.this, true);
                        Toast.makeText(SettingsActivity.this, R.string.settings_biometric_on, Toast.LENGTH_SHORT).show();
                    } else {
                        BiometricKey.delete();
                    }
                    paintSecurity();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    BiometricKey.delete();
                    paintSecurity();
                }
            };

    /** Системный экран добавления отпечатка; возврат оттуда не запирает приложение. */
    private void openBiometricEnroll() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent = new Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                    .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                            BiometricManager.Authenticators.BIOMETRIC_STRONG);
        } else {
            intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        AppLock.allowNextLeave();
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    // ── Напоминание ───────────────────────────────────────────────────────────

    /**
     * Само напоминание — reminder.ReminderWorker. Разрешение на уведомления (Android 13+)
     * спрашиваем здесь, когда его включают, — как у «Уведомлять о новых операциях».
     */
    public void onToggleRemind(View view) {
        boolean on = toggleSwitch(switchRemind);
        AppSettings.setRemindOn(this, on);
        ReminderWorker.reschedule(this);

        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    public void onTogglePickerKeyboard(View view) {
        AppSettings.setPickerKeyboardOn(this, toggleSwitch(switchPickerKeyboard));
    }

    /** Табы перечитывают итоги по ревизии состояния — поднимаем её, иначе при
     *  возврате строки «Капитал» остались бы как были. */
    public void onToggleCapitalInSummary(View view) {
        AppSettings.setCapitalInSummaryOn(this, toggleSwitch(switchCapitalInSummary));
        com.example.wallettransactions.PeriodFilterState.get(this).markDataChanged();
    }

    public void onPickWalletSort(View view) {
        pickSort(true, getString(R.string.settings_sort_wallets));
    }

    public void onPickCategorySort(View view) {
        pickSort(false, getString(R.string.settings_sort_categories));
    }

    private void pickSort(boolean wallets, String title) {
        PickerSort[] modes = wallets ? PickerSort.WALLET_MODES : PickerSort.CATEGORY_MODES;
        int checked = PickerSort.indexOf(modes, AppSettings.getPickerSort(this, wallets));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(PickerSort.titles(this, modes), checked, (d, which) -> {
                    AppSettings.setPickerSort(this, wallets, modes[which]);
                    paintSortValues();
                    d.dismiss();
                })
                .show();
    }

    private void paintSortValues() {
        walletSortValue.setText(AppSettings.getPickerSort(this, true).getTitle(this));
        categorySortValue.setText(AppSettings.getPickerSort(this, false).getTitle(this));
    }

    public void onPickRemindTime(View view) {
        new TimePickerDialog(this, (TimePicker tp, int h, int m) -> {
            AppSettings.setRemindTime(this, h, m);
            paintRemindTime();
            // Выключенное напоминание reschedule не заведёт — только запомним время.
            ReminderWorker.reschedule(this);
        }, AppSettings.getRemindHour(this), AppSettings.getRemindMinute(this), true).show();
    }

    private void paintRemindTime() {
        rowRemindTime.setText(getString(R.string.settings_remind_time,
                AppSettings.getRemindHour(this), AppSettings.getRemindMinute(this)));
    }

    public void onRateApp(View view) {
        String pkg = getPackageName();
        AppLock.allowNextLeave();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    /** Flips the pill on/off (bg_switch_on/off) and slides the thumb (start/end), returns the new state. */
    private boolean toggleSwitch(FrameLayout switchView) {
        boolean isOn = switchView.getBackground() != null
                && switchView.getTag() != null && (boolean) switchView.getTag();
        applySwitchState(switchView, !isOn);
        return !isOn;
    }

    private void applySwitchState(FrameLayout switchView, boolean on) {
        switchView.setTag(on);
        switchView.setBackgroundResource(on ? R.drawable.bg_switch_on : R.drawable.bg_switch_off);
        View thumb = switchView.getChildAt(0);
        if (thumb == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.gravity = on ? android.view.Gravity.END : android.view.Gravity.START;
        thumb.setLayoutParams(lp);
    }
}
