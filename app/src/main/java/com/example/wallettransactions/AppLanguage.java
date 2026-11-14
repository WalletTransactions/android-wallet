package com.example.wallettransactions;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/**
 * Язык приложения, выбранный в «Настройках» (openspec android-i18n, design D7).
 * <p>
 * Хранит выбор AppCompat ({@link AppCompatDelegate#setApplicationLocales}): на Android 13+
 * это тот же язык, что в «Настройки → Система → Языки → Языки приложений», на старых —
 * запись AppCompat ({@code autoStoreLocales} в манифесте). Пустой тег — язык телефона.
 */
public final class AppLanguage {

    /** Варианты в порядке пунктов диалога: язык телефона, English, Русский. */
    public static final String[] TAGS = {"", "en", "ru"};

    private static volatile String cachedTag;
    private static volatile Context cachedContext;

    private AppLanguage() {}

    /** Выбранный язык ("en", "ru") или "" — язык телефона. */
    public static String current() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        return locales.isEmpty() ? "" : locales.get(0).getLanguage();
    }

    /**
     * Локаль для текстов, которые Android переводит сам по данным (название валюты по коду
     * ISO), а не из ресурсов приложения: язык из «Настроек» приложения, иначе язык телефона.
     * <p>
     * На 13+ система сама применяет язык приложения к процессу, и {@link Locale#getDefault}
     * уже он; на старых AppCompat меняет только ресурсы экранов, а {@code Locale.getDefault()}
     * остаётся языком телефона — без этого при English в приложении общая валюта показывалась
     * бы русским «Евро» (spec currency-directory). {@link AppCompatDelegate#getApplicationLocales}
     * на 13+ — вызов в system_server (см. {@link #localized}), поэтому там его не зовём.
     */
    public static Locale effectiveLocale() {
        if (Build.VERSION.SDK_INT >= 33) return Locale.getDefault();
        return effectiveLocale(AppCompatDelegate.getApplicationLocales(), Build.VERSION.SDK_INT);
    }

    static Locale effectiveLocale(LocaleListCompat locales, int sdkInt) {
        if (sdkInt >= 33 || locales.isEmpty()) return Locale.getDefault();
        Locale chosen = locales.get(0);
        return chosen != null ? chosen : Locale.getDefault();
    }

    /** Ставит язык; открытые экраны AppCompat пересоздаются сами. */
    public static void set(String tag) {
        AppCompatDelegate.setApplicationLocales(tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }

    /**
     * Контекст для текстов вне экранов ({@link Texts}, имена системных категорий). На
     * Android 13+ система сама переводит ресурсы приложения на выбранный язык; на старых
     * AppCompat меняет язык только экранам, и контекст приложения остался бы на языке телефона.
     * <p>
     * Версию проверяем до {@link AppCompatDelegate#getApplicationLocales}: на 13+ это вызов
     * system_server (~10 мс), а метод зовётся на каждую строку списка — старт висел минутами.
     */
    public static Context localized(Context app) {
        if (Build.VERSION.SDK_INT >= 33) return app;
        return localized(app, AppCompatDelegate.getApplicationLocales(), Build.VERSION.SDK_INT);
    }

    static Context localized(Context app, LocaleListCompat locales, int sdkInt) {
        if (sdkInt >= 33 || locales.isEmpty()) return app;
        String tag = locales.toLanguageTags();
        Context cached = cachedContext;
        if (cached != null && tag.equals(cachedTag)) return cached;
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocale(locales.get(0));
        Context ctx = app.createConfigurationContext(config);
        cachedContext = ctx;
        cachedTag = tag;
        return ctx;
    }
}
