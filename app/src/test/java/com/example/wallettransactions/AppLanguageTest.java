package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;

import androidx.core.os.LocaleListCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Язык из «Настроек» приложения (AppLanguage): тексты вне экранов на старых Android идут
 * на выбранном языке, на 13+ ресурсы приложения переводит сама система. Телефон — русский
 * ({@code robolectric.properties}).
 */
@RunWith(RobolectricTestRunner.class)
public class AppLanguageTest {

    private final Context app = ApplicationProvider.getApplicationContext();

    @Test
    public void oldAndroid_englishChosen_textsInEnglish() {
        Context c = AppLanguage.localized(app, LocaleListCompat.forLanguageTags("en"), 28);
        assertEquals("Language", c.getString(R.string.settings_language));
        assertEquals("Transfer", c.getString(R.string.system_category_transfer));
    }

    @Test
    public void phoneLanguage_appContextAsIs() {
        Context c = AppLanguage.localized(app, LocaleListCompat.getEmptyLocaleList(), 28);
        assertSame(app, c);
        assertEquals("Язык", c.getString(R.string.settings_language));
    }

    /**
     * spec currency-directory + android-i18n: телефон русский, в «Настройках» выбран English —
     * общая валюта показывается английским названием, своя — хранимым (Huawei P20 Pro,
     * Android 10: до правки в списке «Валюты» было русское «Евро»).
     */
    @Test
    @Config(sdk = 28)
    public void sharedCurrency_oldAndroid_englishChosenOnRussianPhone_shownInEnglish() {
        AppLanguage.set("en");
        try {
            assertEquals("Euro", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    1, "EUR", "Евро", null, "€", 2, true, true).getName());
            assertEquals("Serbian Dinar", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    2, "RSD", "Сербский динар", null, "din.", 2, true, true).getName());
            assertEquals("Доллары нал", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    3, "USD", "Доллары нал", null, "$", 2, true, false).getName());
            assertEquals("Bitcoin", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                    4, "BTC", "Bitcoin", null, "₿", 8, true, true).getName()); // Android кода не знает — запасное имя
        } finally {
            AppLanguage.set("");
        }
    }

    @Test
    @Config(sdk = 28)
    public void sharedCurrency_oldAndroid_phoneLanguageWhenNothingChosen() {
        AppLanguage.set("");
        assertEquals("Евро", new com.example.wallettransactions.DataItems.CurrencyConversion.Currency(
                1, "EUR", "Euro", null, "€", 2, true, true).getName());
    }

    @Test
    public void effectiveLocale_oldAndroid_chosenLanguageWinsOverPhone() {
        assertEquals("en", AppLanguage.effectiveLocale(LocaleListCompat.forLanguageTags("en"), 28).getLanguage());
    }

    @Test
    public void effectiveLocale_phoneLanguage_isDefaultLocale() {
        assertEquals(java.util.Locale.getDefault(),
                AppLanguage.effectiveLocale(LocaleListCompat.getEmptyLocaleList(), 28));
    }

    @Test
    public void effectiveLocale_android13_systemAlreadyAppliedLanguage() {
        // На 13+ язык приложения уже в Locale.getDefault() — список AppCompat не читаем и не применяем.
        assertEquals(java.util.Locale.getDefault(),
                AppLanguage.effectiveLocale(LocaleListCompat.forLanguageTags("en"), 33));
    }

    @Test
    public void android13_systemHandlesLanguage() {
        assertSame(app, AppLanguage.localized(app, LocaleListCompat.forLanguageTags("en"), 33));
    }
}
