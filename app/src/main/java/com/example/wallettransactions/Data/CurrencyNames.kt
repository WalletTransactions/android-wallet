package com.example.wallettransactions.Data

import android.content.Context
import com.example.wallettransactions.AppLanguage
import java.util.Locale

/**
 * Названия валют по коду ISO и язык встроенного набора (openspec new-user-default-set,
 * design D10). Android знает названия валют на всех языках, поэтому общим валютам и
 * валютам набора переводы на сервере не нужны.
 */
object CurrencyNames {
    /** Язык посева набора: `ru` на русском телефоне, иначе `en` (spec starter-set, «Язык набора»). */
    @JvmStatic
    fun seedLanguage(context: Context): String {
        // Язык из «Настроек» приложения, если выбран (AppLanguage), иначе — телефона.
        val locales = AppLanguage
            .localized(context.applicationContext).resources.configuration.locales
        val first = if (locales.isEmpty) Locale.getDefault() else locales.get(0)
        return if ("ru" == first.language) "ru" else "en"
    }

    /**
     * Название валюты на языке `locale` с заглавной буквы («Евро», «Euro»); если
     * Android кода не знает или названия для него нет — `fallback`.
     */
    @JvmStatic
    fun displayName(iso: String?, fallback: String?, locale: Locale): String? {
        if (iso == null) return fallback
        val name: String? = try {
            java.util.Currency.getInstance(iso.uppercase(Locale.ROOT)).getDisplayName(locale)
        } catch (e: IllegalArgumentException) {
            return fallback // не код ISO 4217 (USDT и т.п.)
        }
        if (name.isNullOrEmpty() || name.equals(iso, ignoreCase = true)) return fallback
        return name.substring(0, 1).uppercase(locale) + name.substring(1)
    }

    /**
     * Показываемое имя общей валюты — на текущем языке приложения (выбранном в «Настройках»,
     * иначе языке телефона; spec currency-directory), а не на языке системы: при English в
     * приложении на русском телефоне иначе осталось бы «Евро».
     */
    @JvmStatic
    fun sharedDisplayName(iso: String?, storedName: String?): String? {
        return displayName(iso, storedName, AppLanguage.effectiveLocale())
    }
}
