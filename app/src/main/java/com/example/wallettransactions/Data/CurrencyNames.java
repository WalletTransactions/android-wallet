package com.example.wallettransactions.Data;

import android.content.Context;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Названия валют по коду ISO и язык встроенного набора (openspec new-user-default-set,
 * design D10). Android знает названия валют на всех языках, поэтому общим валютам и
 * валютам набора переводы на сервере не нужны.
 */
public final class CurrencyNames {
    private CurrencyNames() {}

    /** Язык посева набора: {@code ru} на русском телефоне, иначе {@code en} (spec starter-set, «Язык набора»). */
    public static String seedLanguage(Context context) {
        LocaleList locales = context.getResources().getConfiguration().getLocales();
        Locale first = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        return "ru".equals(first.getLanguage()) ? "ru" : "en";
    }

    /**
     * Название валюты на языке {@code locale} с заглавной буквы («Евро», «Euro»); если
     * Android кода не знает или названия для него нет — {@code fallback}.
     */
    public static String displayName(String iso, String fallback, Locale locale) {
        if (iso == null) return fallback;
        String name;
        try {
            name = java.util.Currency.getInstance(iso.toUpperCase(Locale.ROOT)).getDisplayName(locale);
        } catch (IllegalArgumentException e) {
            return fallback; // не код ISO 4217 (USDT и т.п.)
        }
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(iso)) return fallback;
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    /** Показываемое имя общей валюты — на текущем языке телефона (spec currency-directory). */
    public static String sharedDisplayName(String iso, String storedName) {
        return displayName(iso, storedName, Locale.getDefault());
    }
}
