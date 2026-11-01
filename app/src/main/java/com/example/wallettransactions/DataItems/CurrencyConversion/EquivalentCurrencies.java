package com.example.wallettransactions.DataItems.CurrencyConversion;

import java.util.ArrayList;
import java.util.List;

/**
 * Какие валюты предлагать в пикерах валюты эквивалента (openspec currency-ui-tweaks,
 * spec currency-directory «Пикеры валюты эквивалента учитывают флаг», design D5).
 * Одно правило для шапки корневых экранов и для «Настроек»; без Activity — ради
 * юнит-теста.
 * <p>
 * Текущую валюту сравниваем по id, а не по ссылке: после перечитывания списка
 * (синк, форма валюты) у неё уже другой экземпляр {@link Currency}.
 */
public final class EquivalentCurrencies {

    private EquivalentCurrencies() {}

    /**
     * Валюты с флагом «Показывать в эквиваленте» плюс текущая (даже со снятым флагом),
     * в исходном порядке; текущая, которой среди кандидатов нет, — в конце. Если флаг
     * снят у всех кандидатов — все кандидаты, как до появления флага.
     */
    public static List<Currency> filter(List<Currency> candidates, Currency current) {
        List<Currency> result = new ArrayList<>();
        if (!anyFlagged(candidates)) {
            result.addAll(candidates);
        } else {
            for (Currency c : candidates) {
                if (c.isShowInEquivalent() || sameId(c, current)) result.add(c);
            }
        }
        if (current != null && !containsId(result, current)) result.add(current);
        return result;
    }

    /**
     * Пикер в шапке: валюты живых кошельков с флагом; если среди них с флагом нет ни
     * одной — все валюты справочника с флагом (дальше то же правило, что {@link #filter}).
     */
    public static List<Currency> forHeader(List<Currency> inUse, List<Currency> all, Currency current) {
        return filter(anyFlagged(inUse) ? inUse : all, current);
    }

    private static boolean anyFlagged(List<Currency> currencies) {
        for (Currency c : currencies) if (c.isShowInEquivalent()) return true;
        return false;
    }

    private static boolean containsId(List<Currency> currencies, Currency needle) {
        for (Currency c : currencies) if (sameId(c, needle)) return true;
        return false;
    }

    private static boolean sameId(Currency a, Currency b) {
        return a != null && b != null && a.getId() == b.getId();
    }
}
