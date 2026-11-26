package com.example.wallettransactions

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Язык приложения, выбранный в «Настройках» (openspec android-i18n, design D7).
 *
 * Хранит выбор AppCompat ([AppCompatDelegate.setApplicationLocales]): на Android 13+
 * это тот же язык, что в «Настройки → Система → Языки → Языки приложений», на старых —
 * запись AppCompat (`autoStoreLocales` в манифесте). Пустой тег — язык телефона.
 */
object AppLanguage {

    /** Варианты в порядке пунктов диалога: язык телефона, English, Русский. */
    @JvmField
    val TAGS = arrayOf("", "en", "ru")

    @Volatile
    private var cachedTag: String? = null

    @Volatile
    private var cachedContext: Context? = null

    /** Выбранный язык ("en", "ru") или "" — язык телефона. */
    @JvmStatic
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else locales.get(0)!!.language
    }

    /**
     * Локаль для текстов, которые Android переводит сам по данным (название валюты по коду
     * ISO), а не из ресурсов приложения: язык из «Настроек» приложения, иначе язык телефона.
     *
     * На 13+ система сама применяет язык приложения к процессу, и [Locale.getDefault]
     * уже он; на старых AppCompat меняет только ресурсы экранов, а `Locale.getDefault()`
     * остаётся языком телефона — без этого при English в приложении общая валюта показывалась
     * бы русским «Евро» (spec currency-directory). [AppCompatDelegate.getApplicationLocales]
     * на 13+ — вызов в system_server (см. [localized]), поэтому там его не зовём.
     */
    @JvmStatic
    fun effectiveLocale(): Locale {
        if (Build.VERSION.SDK_INT >= 33) return Locale.getDefault()
        return effectiveLocale(AppCompatDelegate.getApplicationLocales(), Build.VERSION.SDK_INT)
    }

    @JvmStatic
    fun effectiveLocale(locales: LocaleListCompat, sdkInt: Int): Locale {
        if (sdkInt >= 33 || locales.isEmpty) return Locale.getDefault()
        val chosen = locales.get(0)
        return chosen ?: Locale.getDefault()
    }

    /** Ставит язык; открытые экраны AppCompat пересоздаются сами. */
    @JvmStatic
    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /**
     * Контекст для текстов вне экранов ([Texts], имена системных категорий). На
     * Android 13+ система сама переводит ресурсы приложения на выбранный язык; на старых
     * AppCompat меняет язык только экранам, и контекст приложения остался бы на языке телефона.
     *
     * Версию проверяем до [AppCompatDelegate.getApplicationLocales]: на 13+ это вызов
     * system_server (~10 мс), а метод зовётся на каждую строку списка — старт висел минутами.
     */
    @JvmStatic
    fun localized(app: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return app
        return localized(app, AppCompatDelegate.getApplicationLocales(), Build.VERSION.SDK_INT)
    }

    @JvmStatic
    fun localized(app: Context, locales: LocaleListCompat, sdkInt: Int): Context {
        if (sdkInt >= 33 || locales.isEmpty) return app
        val tag = locales.toLanguageTags()
        val cached = cachedContext
        if (cached != null && tag == cachedTag) return cached
        val config = Configuration(app.resources.configuration)
        config.setLocale(locales.get(0))
        val ctx = app.createConfigurationContext(config)
        cachedContext = ctx
        cachedTag = tag
        return ctx
    }
}
