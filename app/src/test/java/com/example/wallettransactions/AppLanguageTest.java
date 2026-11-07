package com.example.wallettransactions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;

import androidx.core.os.LocaleListCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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

    @Test
    public void android13_systemHandlesLanguage() {
        assertSame(app, AppLanguage.localized(app, LocaleListCompat.forLanguageTags("en"), 33));
    }
}
