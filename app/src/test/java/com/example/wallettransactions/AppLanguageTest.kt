package com.example.wallettransactions

import android.content.Context
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Язык из «Настроек» приложения (AppLanguage): тексты вне экранов на старых Android идут
 * на выбранном языке, на 13+ ресурсы приложения переводит сама система. Телефон — русский
 * (`robolectric.properties`).
 */
@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun oldAndroid_englishChosen_textsInEnglish() {
        val c = AppLanguage.localized(app, LocaleListCompat.forLanguageTags("en"), 28)
        assertEquals("Language", c.getString(R.string.settings_language))
        assertEquals("Transfer", c.getString(R.string.system_category_transfer))
    }

    @Test
    fun phoneLanguage_appContextAsIs() {
        val c = AppLanguage.localized(app, LocaleListCompat.getEmptyLocaleList(), 28)
        assertSame(app, c)
        assertEquals("Язык", c.getString(R.string.settings_language))
    }

    /**
     * spec currency-directory + android-i18n: телефон русский, в «Настройках» выбран English —
     * общая валюта показывается английским названием, своя — хранимым (Huawei P20 Pro,
     * Android 10: до правки в списке «Валюты» было русское «Евро»).
     */
    @Test
    @Config(sdk = [28])
    fun sharedCurrency_oldAndroid_englishChosenOnRussianPhone_shownInEnglish() {
        AppLanguage.set("en")
        try {
            assertEquals("Euro", Currency(1, "EUR", "Евро", null, "€", 2, true, true).name)
            assertEquals("Serbian Dinar", Currency(2, "RSD", "Сербский динар", null, "din.", 2, true, true).name)
            assertEquals("Доллары нал", Currency(3, "USD", "Доллары нал", null, "$", 2, true, false).name)
            assertEquals("Bitcoin", Currency(4, "BTC", "Bitcoin", null, "₿", 8, true, true).name) // Android кода не знает — запасное имя
        } finally {
            AppLanguage.set("")
        }
    }

    @Test
    @Config(sdk = [28])
    fun sharedCurrency_oldAndroid_phoneLanguageWhenNothingChosen() {
        AppLanguage.set("")
        assertEquals("Евро", Currency(1, "EUR", "Euro", null, "€", 2, true, true).name)
    }

    @Test
    fun effectiveLocale_oldAndroid_chosenLanguageWinsOverPhone() {
        assertEquals("en", AppLanguage.effectiveLocale(LocaleListCompat.forLanguageTags("en"), 28).language)
    }

    @Test
    fun effectiveLocale_phoneLanguage_isDefaultLocale() {
        assertEquals(
            Locale.getDefault(),
            AppLanguage.effectiveLocale(LocaleListCompat.getEmptyLocaleList(), 28)
        )
    }

    @Test
    fun effectiveLocale_android13_systemAlreadyAppliedLanguage() {
        // На 13+ язык приложения уже в Locale.getDefault() — список AppCompat не читаем и не применяем.
        assertEquals(
            Locale.getDefault(),
            AppLanguage.effectiveLocale(LocaleListCompat.forLanguageTags("en"), 33)
        )
    }

    @Test
    fun android13_systemHandlesLanguage() {
        assertSame(app, AppLanguage.localized(app, LocaleListCompat.forLanguageTags("en"), 33))
    }
}
