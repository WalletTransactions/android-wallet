package com.example.wallettransactions.Activities;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
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

import java.util.Calendar;
import java.util.Locale;

/**
 * Экран «Настройки», открывается с таба «Ещё» нижней панели (MainActivity.navMore).
 *
 * Тема оформления, валюта эквивалента и время напоминания реально сохраняются
 * (SharedPreferences) и переживают переоткрытие экрана. Переключатели «Код доступа»/
 * «Вход по отпечатку»/«Напоминание» пока только визуальные — они меняют вид и тоже
 * сохраняют своё состояние, но ничего не включают на самом деле: PIN-экрана,
 * BiometricPrompt и планировщика уведомлений в проекте нет — это отдельная, более
 * крупная задача, если понадобится настоящая защита входа и напоминания.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "settings";
    private static final String PREF_ACCESS_CODE_ON = "accessCodeOn";
    private static final String PREF_BIOMETRIC_ON = "biometricOn";
    private static final String PREF_REMIND_ON = "remindOn";
    private static final String PREF_REMIND_HOUR = "remindHour";
    private static final String PREF_REMIND_MINUTE = "remindMinute";

    private TextView mainCurrencyValue;
    private TextView chipThemeLight;
    private TextView chipThemeDark;
    private TextView chipThemeSystem;
    private FrameLayout switchAccessCode;
    private FrameLayout switchBiometric;
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

        applySwitchState(switchAccessCode, p.getBoolean(PREF_ACCESS_CODE_ON, false));
        applySwitchState(switchBiometric, p.getBoolean(PREF_BIOMETRIC_ON, false));
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

    public void onToggleAccessCode(View view) {
        boolean on = toggleSwitch(switchAccessCode);
        prefs().edit().putBoolean(PREF_ACCESS_CODE_ON, on).apply();
    }

    public void onToggleBiometric(View view) {
        boolean on = toggleSwitch(switchBiometric);
        prefs().edit().putBoolean(PREF_BIOMETRIC_ON, on).apply();
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
