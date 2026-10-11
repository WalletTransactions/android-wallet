package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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

import com.example.wallettransactions.AppSettings;
import com.example.wallettransactions.BuildConfig;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyList;
import com.example.wallettransactions.PickerSort;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;
import com.example.wallettransactions.security.AppLock;
import com.example.wallettransactions.security.AppLockStore;
import com.example.wallettransactions.security.BiometricErrors;
import com.example.wallettransactions.security.BiometricKey;

import java.util.Calendar;
import java.util.Locale;

import javax.crypto.Cipher;

/**
 * Экран «Настройки», открывается с таба «Ещё» нижней панели (MainActivity.navMore).
 *
 * Тема оформления, валюта эквивалента и время напоминания реально сохраняются
 * (SharedPreferences) и переживают переоткрытие экрана. «Код доступа» — настоящий
 * (security.AppLock, org/docs/TODO_app_lock.md): включение, выключение и смена идут
 * через {@link LockActivity}. «Вход по отпечатку» — тоже (Фаза 2 плана, п. 3.4).
 * «Напоминание» пока только визуальное: планировщика напоминаний нет.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "settings";
    private static final int REQUEST_PIN_ENABLE = 1;
    private static final int REQUEST_PIN_DISABLE = 2;
    private static final int REQUEST_PIN_CHANGE_VERIFY = 3;
    private static final int REQUEST_PIN_CHANGE_SET = 4;
    private static final String PREF_REMIND_ON = "remindOn";
    private static final String PREF_REMIND_HOUR = "remindHour";
    private static final String PREF_REMIND_MINUTE = "remindMinute";

    private TextView mainCurrencyValue;
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
    private TextView walletSortValue;
    private TextView categorySortValue;
    private TextView rowRemindTime;

    /** Тапы по строке «Версия» — {@link #onVersionTap}. */
    private static final int HIDDEN_MENU_TAPS = 5;
    private static final long HIDDEN_MENU_TAP_TIMEOUT_MS = 1500;
    private int versionTapCount = 0;
    private long versionTapLastAt = 0;

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mainCurrencyValue = findViewById(R.id.mainCurrencyValue);
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

        SharedPreferences p = prefs();
        String savedIso = AppSettings.getCurrencyEqIso(this);
        Currency saved = savedIso != null
                ? Singleton.instance.getCurrencyList(this).getCurrencyByISO(savedIso) : null;
        if (saved != null) mainCurrencyValue.setText(saved.getIso() + " — " + saved.getName());

        paintSecurity();
        applySwitchState(switchRemind, p.getBoolean(PREF_REMIND_ON, true));
        applySwitchState(switchPickerKeyboard, AppSettings.isPickerKeyboardOn(this));
        paintSortValues();

        int hour = p.getInt(PREF_REMIND_HOUR, 20);
        int minute = p.getInt(PREF_REMIND_MINUTE, 0);
        rowRemindTime.setText(String.format(Locale.getDefault(), "каждый день в %02d:%02d", hour, minute));
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

    public void onPickMainCurrency(View view) {
        CurrencyList currencyList = Singleton.instance.getCurrencyList(this);
        String[] items = new String[currencyList.size()];
        for (int i = 0; i < currencyList.size(); i++) {
            Currency c = currencyList.get(i);
            items[i] = c.getIso() + " — " + c.getName();
        }

        new AlertDialog.Builder(this)
                .setTitle("Валюта эквивалента")
                .setItems(items, (d, which) -> {
                    Currency chosen = currencyList.get(which);
                    mainCurrencyValue.setText(chosen.getIso() + " — " + chosen.getName());
                    AppSettings.setCurrencyEqIso(this, chosen.getIso());
                })
                .show();
    }

    /** Экспорт базы (файл .db) или JSON — см. {@link ExportActivity}. Импорт —
     *  на уровень выше, в «Ещё» ({@link MoreActivity#onMoreImport}). */
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
                Toast.makeText(this, "Ещё " + left + " " + tapsWord(left) + "…", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        versionTapCount = 0;
        startActivity(new Intent(this, ResetDataActivity.class));
    }

    private String tapsWord(int n) {
        if (n == 1) return "тап";
        return "тапа";
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
                .setTitle("Блокировать")
                .setSingleChoiceItems(AppLockStore.TIMEOUT_TITLES, checked, (d, which) -> {
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
                Toast.makeText(this, "Код доступа включён", Toast.LENGTH_SHORT).show();
                break;
            case REQUEST_PIN_DISABLE:
                AppLockStore.clearPin(this);
                AppLock.unlock();
                Toast.makeText(this, "Код доступа выключен", Toast.LENGTH_SHORT).show();
                break;
            case REQUEST_PIN_CHANGE_VERIFY:
                // Старый код остаётся, пока новый не подтверждён: отмена на любом шаге ничего не меняет.
                startActivityForResult(LockActivity.setIntent(this), REQUEST_PIN_CHANGE_SET);
                return;
            case REQUEST_PIN_CHANGE_SET:
                Toast.makeText(this, "Код изменён", Toast.LENGTH_SHORT).show();
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
                ? "превью приложения в списке недавних"
                : "превью в списке недавних; скриншоты в приложении тоже будут запрещены");

        // Ключ инвалидирован (отпечатки в телефоне изменились) — тумблер не должен врать.
        if (AppLockStore.isBiometricOn(this) && BiometricKey.cipherOrNull() == null) {
            AppLockStore.setBiometricOn(this, false);
        }
        boolean available = BiometricErrors.unavailableReason(pinSet, canAuthenticate()) == null;
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
        String reason = BiometricErrors.unavailableReason(pinSet, can);
        if (reason != null) {
            if (BiometricErrors.canEnroll(pinSet, can)) {
                new AlertDialog.Builder(this)
                        .setMessage(reason)
                        .setPositiveButton("Открыть настройки", (d, w) -> openBiometricEnroll())
                        .setNegativeButton("Отмена", null)
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
            Toast.makeText(this, "Не удалось подготовить вход по отпечатку", Toast.LENGTH_SHORT).show();
            return;
        }
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Вход по отпечатку")
                .setSubtitle("Приложите палец, чтобы включить")
                .setNegativeButtonText("Отмена")
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
                        Toast.makeText(SettingsActivity.this, "Вход по отпечатку включён", Toast.LENGTH_SHORT).show();
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

    public void onToggleRemind(View view) {
        boolean on = toggleSwitch(switchRemind);
        prefs().edit().putBoolean(PREF_REMIND_ON, on).apply();
    }

    public void onTogglePickerKeyboard(View view) {
        AppSettings.setPickerKeyboardOn(this, toggleSwitch(switchPickerKeyboard));
    }

    public void onPickWalletSort(View view) {
        pickSort(true, "Сортировка кошельков");
    }

    public void onPickCategorySort(View view) {
        pickSort(false, "Сортировка категорий");
    }

    private void pickSort(boolean wallets, String title) {
        PickerSort[] modes = wallets ? PickerSort.WALLET_MODES : PickerSort.CATEGORY_MODES;
        int checked = PickerSort.indexOf(modes, AppSettings.getPickerSort(this, wallets));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(PickerSort.titles(modes), checked, (d, which) -> {
                    AppSettings.setPickerSort(this, wallets, modes[which]);
                    paintSortValues();
                    d.dismiss();
                })
                .show();
    }

    private void paintSortValues() {
        walletSortValue.setText(AppSettings.getPickerSort(this, true).getTitle());
        categorySortValue.setText(AppSettings.getPickerSort(this, false).getTitle());
    }

    public void onPickRemindTime(View view) {
        SharedPreferences p = prefs();
        int hour = p.getInt(PREF_REMIND_HOUR, 20);
        int minute = p.getInt(PREF_REMIND_MINUTE, 0);

        new TimePickerDialog(this, (TimePicker tp, int h, int m) -> {
            prefs().edit().putInt(PREF_REMIND_HOUR, h).putInt(PREF_REMIND_MINUTE, m).apply();
            rowRemindTime.setText(String.format(Locale.getDefault(), "каждый день в %02d:%02d", h, m));
        }, hour, minute, true).show();
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
